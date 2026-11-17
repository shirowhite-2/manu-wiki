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
from interface_assets import MESSAGES


class FakeWiki:
    def __init__(self, policy=True):
        self.policy = policy
        self.pages = {}
        self.objects_written = []
        self.rights = []
        self.requests = []

    @staticmethod
    def page_path(space, page):
        return '/rest/wikis/xwiki/spaces/' + space.replace('/', '/spaces/') + '/pages/' + page

    def ensure_page(self, space, page, title, content, **kwargs):
        self.pages[(space, page)] = (title, content)

    def page_exists(self, space, page):
        return (space, page) in self.pages

    def ensure_object_values(self, space, page, class_name, values):
        self.objects_written.append((space, page, class_name, values))

    def ensure_right(self, space, page, values):
        self.rights.append((space, page, values))

    def form_token(self):
        return 'test-token'

    def request(self, method, path, data=None, content_type=None, **kwargs):
        self.requests.append((method, path, data))
        if 'PolicyStatus?' in path:
            return 200, b'luxpedia-workflow-policy-1' if self.policy else b'POLICY_NOT_INSTALLED'
        if 'PolicySetup?' in path:
            return 200, b'AUDIT_CLASS_READY'
        if '/translations/' in path:
            return (201, b'') if method == 'PUT' else (404, b'')
        if path.endswith('/PublicationWorkflow/pages/Translations'):
            return 200, b'<page xmlns="http://www.xwiki.org"><content>custom.key=preserved\nworkflow.title=Workflow</content></page>'
        if path.endswith('/Templates/pages/Article'):
            return 404, b''
        raise AssertionError((method, path))


class Module:
    CATEGORY_ADMIN = (ROOT / 'scripts/apply_sprint1.py').read_text().split('CATEGORY_ADMIN = """', 1)[1].split('"""', 1)[0]
    SOURCE_EDIT_UIX = '{{velocity}}源码编辑{{/velocity}}'
    @staticmethod
    def log(message):
        pass


class InterfaceConfigTest(unittest.TestCase):
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
            ('LuxPedia/Code', 'CategoryHomeTemplate'), ('LuxPedia/Code', 'InterfaceTranslations')}
        self.assertLessEqual(wiki.pages.keys(), allowed)
        puts = [path for method, path, data in wiki.requests if method == 'PUT']
        self.assertTrue(puts)
        self.assertTrue(all(path.endswith('/translations/zh') for path in puts))
        self.assertEqual(wiki.pages[('Main', 'WebHome')][0], 'LuxPedia knowledge base')

    def test_native_translation_keeps_unrelated_keys(self):
        wiki = FakeWiki()
        interface.apply(Module, wiki)
        content = wiki.pages[('PublicationWorkflow', 'Translations')][1]
        self.assertIn('custom.key=preserved', content)
        self.assertIn('workflow.title=Publication', content)

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
            security.verify_response(200, body, 'luxpedia-workflow-policy-1', 'PolicyStatus')

    def test_policy_marker_accepts_plain_or_rendered_output(self):
        for body in (b'luxpedia-workflow-policy-1\n', b'<p>luxpedia-workflow-policy-1</p>'):
            security.verify_response(200, body, 'luxpedia-workflow-policy-1', 'PolicyStatus')

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
