"""Offline deployment checks; never connect to or mutate a live wiki."""
import importlib.util
from pathlib import Path
import sys
import unittest
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
import apply_interface as interface
import apply_workflow_security as security
import apply_workflow as workflow
from interface_assets import MESSAGES


class FakeWiki:
    def __init__(self, policy=True):
        self.policy = policy
        self.pages = {}
        self.existing_pages = {('XWiki', 'XWikiPreferences')}
        self.object_data = {}
        self.objects_written = []
        self.rights = []
        self.requests = []
        self.article_template = None

    @staticmethod
    def page_path(space, page):
        return '/rest/wikis/xwiki/spaces/' + space.replace('/', '/spaces/') + '/pages/' + page

    def ensure_page(self, space, page, title, content, **kwargs):
        if self.page_exists(space, page) and not kwargs.get('update', False):
            return False
        self.pages[(space, page)] = (title, content)
        return True

    def page_exists(self, space, page):
        return (space, page) in self.pages or (space, page) in self.existing_pages

    def has_object(self, space, page, class_name):
        return (space, page, class_name) in self.object_data

    def objects(self, space, page, class_name):
        return [0] if self.has_object(space, page, class_name) else []

    def object_values(self, space, page, class_name, number):
        return dict(self.object_data[(space, page, class_name)])

    def ensure_object_values(self, space, page, class_name, values):
        if not self.page_exists(space, page):
            raise RuntimeError(f'Object parent page missing (HTTP 404): {space}.{page}')
        self.objects_written.append((space, page, class_name, values))
        self.object_data.setdefault((space, page, class_name), {}).update(values)

    def ensure_right(self, space, page, values):
        self.rights.append((space, page, values))

    def form_token(self):
        return 'test-token'

    def request(self, method, path, data=None, content_type=None, **kwargs):
        self.requests.append((method, path, data))
        if 'PolicyStatus?' in path:
            return 200, b'luxpedia-workflow-policy-2' if self.policy else b'POLICY_NOT_INSTALLED'
        if 'PolicySetup?' in path:
            return 200, b'AUDIT_CLASS_READY'
        if '/translations/' in path:
            return (201, b'') if method == 'PUT' else (404, b'')
        if path.endswith('/PublicationWorkflow/pages/Translations'):
            return 200, b'<page xmlns="http://www.xwiki.org"><content>custom.key=preserved\nworkflow.title=Workflow</content></page>'
        if path.endswith('/Templates/pages/Article'):
            if self.article_template is not None:
                root = ET.Element('page', xmlns='http://www.xwiki.org')
                ET.SubElement(root, 'content').text = self.article_template
                return 200, ET.tostring(root)
            return 404, b''
        raise AssertionError((method, path))


class Module:
    ARTICLE_META = workflow.load_apply_module().ARTICLE_META
    CATEGORY_ADMIN = (ROOT / 'scripts/apply_sprint1.py').read_text().split('CATEGORY_ADMIN = """', 1)[1].split('"""', 1)[0]
    SOURCE_EDIT_UIX = '{{velocity}}源码编辑{{/velocity}}'
    @staticmethod
    def log(message):
        pass


class DraftHomeRightsWiki:
    page_path = staticmethod(FakeWiki.page_path)

    def __init__(self, rules, status=204):
        self.rules = {key: dict(values) for key, values in rules.items()}
        self.status = status
        self.deleted = []

    def objects(self, space, page, class_name):
        assert (space, page) == ('Drafts', 'WebHome')
        return [number for cls, number in self.rules if cls == class_name]

    def object_values(self, space, page, class_name, number):
        assert (space, page) == ('Drafts', 'WebHome')
        return self.rules[(class_name, number)]

    def form_token(self):
        return 'test-token'

    def request(self, method, path, **kwargs):
        assert method == 'DELETE' and kwargs['token'] == 'test-token'
        assert path.startswith(self.page_path('Drafts', 'WebHome') + '/objects/')
        class_name, number = path.split('/objects/', 1)[1].rsplit('/', 1)
        key = (class_name, int(number))
        if self.status in (200, 204):
            self.deleted.append(key)
            del self.rules[key]
        return self.status, b''


class InterfaceConfigTest(unittest.TestCase):
    def test_native_workflow_and_field_catalogs_have_both_languages(self):
        import re
        for stem in ('workflow', 'fields'):
            catalogs = [(interface.UI / f'{stem}_{language}.properties').read_text() for language in ('en', 'zh')]
            keys = [set(line.split('=', 1)[0] for line in content.splitlines() if '=' in line) for content in catalogs]
            self.assertEqual(keys[0], keys[1])
            self.assertIsNone(re.search(r'[\u3400-\u9fff]', catalogs[0]))
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        self.assertIn('LuxPedia.Code.ArticleClass_primaryCategory=Primary category',
                      wiki.pages[('LuxPedia/Code', 'InterfaceTranslations')][1])
        native = wiki.pages[('PublicationWorkflow', 'Translations')][1]
        self.assertIn('workflow.start.advancedoptions.button=Advanced options', native)
        self.assertIn('workflow.panel.draftVersion=Open draft', native)

    def test_only_known_branding_strings_are_migrated_without_changing_theme(self):
        for known in (True, False):
            wiki = FakeWiki()
            key = ('XWiki', 'XWikiPreferences', 'XWiki.XWikiPreferences')
            wiki.object_data[key] = {'title': interface.LEGACY_BRAND_TITLE if known else 'Custom title',
                                     'version': interface.LEGACY_BRAND_FOOTER if known else 'Custom footer',
                                     'colorTheme': 'MyTheme', 'skin': 'MySkin'}
            interface.apply(Module, wiki)
            values = wiki.object_data[key]
            self.assertEqual(values['title'], interface.BRAND_TITLE if known else 'Custom title')
            self.assertEqual(values['version'], interface.BRAND_FOOTER if known else 'Custom footer')
            self.assertEqual((values['colorTheme'], values['skin']), ('MyTheme', 'MySkin'))

    def test_generated_article_template_gets_english_and_chinese_without_touching_custom_body(self):
        legacy = (interface.UI / 'pages/article_zh.xwiki').read_text()
        for content in (legacy, Module.ARTICLE_META + legacy, 'My customized template'):
            wiki = FakeWiki()
            wiki.article_template = content
            interface.apply(Module, wiki)
            if content == 'My customized template':
                self.assertNotIn(('LuxPedia/Templates', 'Article'), wiki.pages)
            else:
                self.assertEqual(wiki.pages[('LuxPedia/Templates', 'Article')][1],
                                 (interface.UI / 'pages/article_en.xwiki').read_text())
                self.assertTrue(any(path.endswith('/Templates/pages/Article/translations/zh')
                                    for method, path, data in wiki.requests if method == 'PUT'))

    def test_draft_creation_removes_only_obsolete_home_denials_and_is_repeatable(self):
        obsolete = {'groups': 'XWiki.LuxEditors', 'levels': 'edit', 'allow': '0'}
        retained = {
            ('XWiki.XWikiRights', 1): dict(obsolete, users='XWiki.specificUser'),
            ('XWiki.XWikiRights', 2): dict(obsolete, groups='XWiki.LuxReviewers'),
            ('XWiki.XWikiGlobalRights', 3): dict(obsolete, levels='edit,delete'),
            ('XWiki.XWikiRights', 4): dict(obsolete, allow='1')}
        wiki = DraftHomeRightsWiki(retained)
        wiki.rules.update({(cls, 0): dict(obsolete)
                           for cls in ('XWiki.XWikiRights', 'XWiki.XWikiGlobalRights')})
        workflow.restore_draft_creation(Module, wiki)
        workflow.restore_draft_creation(Module, wiki)
        self.assertEqual(wiki.rules, retained)
        self.assertEqual(len(wiki.deleted), 2)

    def test_failed_creation_acl_migration_stops_instead_of_claiming_success(self):
        wiki = DraftHomeRightsWiki({('XWiki.XWikiRights', 0): {
            'groups': 'XWiki.LuxEditors', 'levels': 'edit', 'allow': '0'}}, status=403)
        with self.assertRaisesRegex(RuntimeError, 'Drafts create entry: HTTP 403'):
            workflow.restore_draft_creation(Module, wiki)
        self.assertEqual(len(wiki.rules), 1)

    def test_new_article_entry_uses_native_form_and_article_template(self):
        content = interface.PAGES[('Drafts', 'WebHome')][1]
        self.assertIn("#if($doc.hasAccessLevel('edit'))", content)
        self.assertIn("$xwiki.getURL('Drafts.WebHome', 'create',", content)
        self.assertIn('spaceReference=Drafts&parent=Drafts.WebHome&template=LuxPedia.Templates.Article', content)
        self.assertIn("#lp('newArticle')", content)
        self.assertEqual(MESSAGES['en']['newArticle'], 'New article')
        self.assertEqual(MESSAGES['zh']['newArticle'], '新建文章')

    def test_language_catalogs_match_and_default_is_english(self):
        self.assertEqual(MESSAGES['en'].keys(), MESSAGES['zh'].keys())
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        preferences = next(values for space, page, cls, values in wiki.objects_written if page == 'XWikiPreferences')
        self.assertEqual(preferences['default_language'], 'en')
        self.assertEqual(preferences['languages'], 'en,zh')

    def test_translation_and_system_pages_do_not_rewrite_articles(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        allowed = set(interface.PAGES) | {('PublicationWorkflow', 'Translations'), ('Knowledge', 'CategoryAdmin'),
            ('LuxPedia/Code', 'CategoryHomeTemplate'), ('LuxPedia/Code', 'InterfaceTranslations'),
            ('LuxPedia', 'HelpPanel'), ('LuxPedia/Code', 'SourceEditUIX'), ('LuxPedia/Code', 'EditGuideUIX')}
        self.assertLessEqual(wiki.pages.keys(), allowed)
        puts = [path for method, path, data in wiki.requests if method == 'PUT']
        self.assertTrue(puts)
        self.assertTrue(all(path.endswith('/translations/zh') for path in puts))
        self.assertEqual(wiki.pages[('Main', 'WebHome')][0], 'LuxPedia knowledge base')

    def test_missing_optional_panel_and_menus_are_created_with_complete_registration(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        panel = wiki.object_data[('LuxPedia', 'HelpPanel', 'Panels.PanelClass')]
        self.assertIn('LuxPedia.Help.WebHome', panel['content'])
        for page, name, order in [('SourceEditUIX', 'luxpedia.source.edit', 15000),
                                  ('EditGuideUIX', 'luxpedia.edit.guide', 15001)]:
            menu = wiki.object_data[('LuxPedia/Code', page, 'XWiki.UIExtensionClass')]
            self.assertEqual(menu['extensionPointId'], 'org.xwiki.plaftorm.menu.content')
            self.assertEqual(menu['name'], name)
            self.assertEqual(menu['scope'], 'wiki')
            self.assertEqual(menu['parameters'], f'order={order}')
            self.assertIn('$services.localization.render', menu['content'])

    def test_existing_panel_documents_and_menu_registration_are_preserved(self):
        wiki = FakeWiki()
        existing = {('LuxPedia', 'HelpPanel'): ('Custom panel', 'Existing panel body'),
                    ('LuxPedia/Code', 'SourceEditUIX'): ('Custom menu', 'Existing menu body')}
        wiki.pages.update(existing)
        menu_key = ('LuxPedia/Code', 'SourceEditUIX', 'XWiki.UIExtensionClass')
        registration = {'name': 'custom.source.menu', 'parameters': 'order=123',
                        'scope': 'wiki', 'extensionPointId': 'custom.menu.point'}
        wiki.object_data[menu_key] = dict(registration, content='Old localized label')
        interface.apply(Module, wiki)
        for key, value in existing.items():
            self.assertEqual(wiki.pages[key], value)
        for key, value in registration.items():
            self.assertEqual(wiki.object_data[menu_key][key], value)
        self.assertIn("luxpedia.sourceEdit", wiki.object_data[menu_key]['content'])

    def test_optional_panel_and_menu_setup_can_be_repeated(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        pages = dict(wiki.pages)
        objects = {key: dict(values) for key, values in wiki.object_data.items()}
        interface.apply(Module, wiki)
        self.assertEqual(wiki.pages, pages)
        self.assertEqual(wiki.object_data, objects)

    def test_native_translation_keeps_unrelated_keys(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        content = wiki.pages[('PublicationWorkflow', 'Translations')][1]
        self.assertIn('custom.key=preserved', content)
        self.assertIn('workflow.title=Publication', content)

    def test_active_draft_list_excludes_published_copies_in_both_languages(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        english = wiki.pages[('Drafts', 'WebHome')][1]
        translations = [ET.fromstring(data).findtext('{http://www.xwiki.org}content')
                        for method, path, data in wiki.requests
                        if method == 'PUT' and path.endswith('/Drafts/pages/WebHome/translations/zh')]
        self.assertEqual(len(translations), 1)
        for content in [english, *translations]:
            self.assertIn("$state != 'published' && $state != 'archived'", content)
            self.assertIn('luxpedia-system-list', content)
            self.assertNotIn('{{documents ', content)

    def test_old_policy_stops_before_new_workflow_features_are_deployed(self):
        wiki = FakeWiki()
        original = wiki.request
        def request(method, path, *args, **kwargs):
            if 'PolicyStatus?' in path:
                return 200, b'luxpedia-workflow-policy-1'
            return original(method, path, *args, **kwargs)
        wiki.request = request
        with self.assertRaisesRegex(RuntimeError, 'Install or update workflow-policy'):
            security.apply(Module, wiki)
        self.assertEqual(wiki.rights, [])
        self.assertNotIn(('LuxPedia/Workflow', 'Actions'), wiki.pages)

    def test_security_setup_stops_when_component_is_missing(self):
        wiki = FakeWiki(policy=False)
        with self.assertRaisesRegex(RuntimeError, 'Install or update workflow-policy'):
            security.apply(Module, wiki)
        self.assertEqual(wiki.rights, [])
        self.assertNotIn(('LuxPedia/Code', 'ReviewAuditClass'), wiki.pages)

    def test_role_security_disables_scripting_and_reader_comments(self):
        wiki = FakeWiki()
        security.apply(Module, wiki)
        for group in ('LuxEditors', 'LuxReviewers', 'LuxReaders'):
            self.assertIn(('XWiki', 'XWikiPreferences', {
                'groups': 'XWiki.' + group, 'levels': 'script', 'allow': '0'}), wiki.rights)
        self.assertIn(('Knowledge', 'WebPreferences', {
            'groups': 'XWiki.LuxReaders', 'levels': 'comment', 'allow': '0'}), wiki.rights)
        setup = next(request for request in wiki.requests if 'PolicySetup?' in request[1])
        self.assertEqual(setup[0], 'POST')
        self.assertEqual(setup[2], 'form_token=test-token')
        self.assertNotIn('{{groovy}}', security.HEALTH + security.SETUP)

    def test_delegated_admin_never_changes_protected_programming_rules(self):
        class DelegatedAdminWiki(FakeWiki):
            def ensure_right(self, space, page, values):
                if 'programming' in values.get('levels', '').split(','):
                    raise PermissionError('XWiki removes programming ACLs written without programming rights')
                super().ensure_right(space, page, values)
        wiki = DelegatedAdminWiki()
        existing = ('XWiki', 'XWikiPreferences', {
            'groups': 'XWiki.XWikiAdminGroup', 'levels': 'programming', 'allow': '1'})
        wiki.rights.append(existing)
        security.apply(Module, wiki)
        self.assertEqual([rule for rule in wiki.rights if 'programming' in rule[2]['levels'].split(',')], [existing])

    def test_script_denial_is_not_reported_as_a_missing_jar(self):
        body = b'Failed to execute the [groovy] macro. The execution of the [groovy] script macro is not allowed.'
        with self.assertRaisesRegex(RuntimeError, 'page author cannot execute'):
            security.verify_response(200, body, 'luxpedia-workflow-policy-2', 'PolicyStatus')

    def test_policy_marker_accepts_plain_or_rendered_output(self):
        for body in (b'luxpedia-workflow-policy-2\n', b'<p>luxpedia-workflow-policy-2</p>'):
            security.verify_response(200, body, 'luxpedia-workflow-policy-2', 'PolicyStatus')

    def test_admin_and_token_errors_are_distinct(self):
        with self.assertRaisesRegex(RuntimeError, 'wiki-level administrator'):
            security.verify_response(200, b'POLICY_FORBIDDEN', 'AUDIT_CLASS_READY', 'PolicySetup')
        with self.assertRaisesRegex(RuntimeError, 'invalid form token'):
            security.verify_response(200, b'POLICY_BAD_TOKEN', 'AUDIT_CLASS_READY', 'PolicySetup')

    def test_http_200_reports_final_java_cause_without_token_or_stack(self):
        body = b'<p>Failed to execute velocity</p> Caused by: java.lang.RuntimeException: wrapper\nCaused by: java.lang.IllegalArgumentException: bad token secret-token\n at org.example.Setup.run(Setup.java:1)'
        with self.assertRaises(RuntimeError) as raised:
            security.verify_response(200, body, 'AUDIT_CLASS_READY', 'PolicySetup', redactions=('secret-token',))
        self.assertIn('IllegalArgumentException: bad token [redacted]', str(raised.exception))
        self.assertNotIn('secret-token', str(raised.exception))
        self.assertNotIn('Setup.java', str(raised.exception))

    def test_translation_error_is_reported(self):
        wiki = FakeWiki()
        wiki.request = lambda *args, **kwargs: (403, b'forbidden')
        with self.assertRaisesRegex(RuntimeError, 'translation zh: 403'):
            interface.ensure_translation(wiki, 'Main', 'WebHome', 'zh', '首页', '正文')


if __name__ == '__main__':
    unittest.main()
