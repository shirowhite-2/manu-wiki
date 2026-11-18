#!/usr/bin/env python3
"""Install English-default LuxPedia interface pages and Chinese translations (no article rewrites)."""
import argparse
import importlib.util
import hashlib
import json
import os
import re
import sys
from pathlib import Path
from xml.sax.saxutils import escape
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from interface_assets import MESSAGES, UI

V = '''{{velocity}}
#macro(lp $key)$services.localization.render("luxpedia.$key")#end
'''
END = '\n{{/velocity}}'
LEGACY_BRAND_TITLE = "#if($xcontext.action == 'login')登录 - LuxPedia#elseif($tdoc.fullName == 'Main.WebHome')LuxPedia - 企业知识库#else$tdoc.getRenderedTitle('plain/1.0') - LuxPedia#end"
LEGACY_BRAND_FOOTER = 'LuxPedia · 企业知识库 · <a href="$xwiki.getURL(\'LuxPedia.About.WebHome\')">关于与开源声明</a>'
BRAND_TITLE = "#if($xcontext.action == 'login')$services.localization.render('luxpedia.login') - LuxPedia#elseif($tdoc.fullName == 'Main.WebHome')$services.localization.render('luxpedia.home')#else$tdoc.getRenderedTitle('plain/1.0') - LuxPedia#end"
BRAND_FOOTER = 'LuxPedia · $services.localization.render(\'luxpedia.brandTagline\') · <a href="$xwiki.getURL(\'LuxPedia.About.WebHome\')">$services.localization.render(\'luxpedia.about\')</a>'
CATEGORY_LIST = (UI / 'pages/categories.xwiki').read_text()
ARTICLE_LIST = (UI / 'pages/articles.xwiki').read_text()
REVIEW_LIST = (UI / 'pages/review_queue.xwiki').read_text()
PAGES = {
    ("LuxPedia/Workflow", "ReviewQueue"): ("queue", V + "= #lp('queue') =\n\n#lp('nextReview')\n\n" + REVIEW_LIST + END),
    ("Main", "WebHome"): ("home", V + '''= #lp('home') =

#lp('homeIntro')

* [[#lp('knowledge')>>Knowledge.WebHome]]
* [[#lp('drafts')>>Drafts.WebHome]]
* [[#lp('queue')>>PublicationWorkflow.Overview]]
* [[#lp('library')>>Knowledge.DocumentLibrary]]
* [[#lp('categories')>>Knowledge.CategoryIndex]]
* [[#lp('editGuide')>>LuxPedia.Help.EditGuide]]
''' + CATEGORY_LIST + END),
    ("Knowledge", "WebHome"): ("knowledge", ""),
    ("Knowledge", "CategoryIndex"): ("categories", ""),
    ("Knowledge", "CategoryGuide"): ("categoryGuide", ""),
    ("Knowledge", "DocumentLibrary"): ("library", ""),
    ("Drafts", "WebHome"): ("drafts", ""),
    ("LuxPedia/Help", "EditGuide"): ("editGuide", V + "= #lp('editGuide') =\n\n#lp('guideContent')\n" + END),
    ("LuxPedia/Help", "WebHome"): ("help", V + "= #lp('help') =\n\n#lp('guideContent')\n" + END),
    ("LuxPedia/About", "WebHome"): ("about", V + "= #lp('about') =\n\n#lp('aboutContent')\n" + END),
    ("LuxPedia", "WebHome"): ("helpCenter", V + "= #lp('helpCenter') =\n\n[[#lp('help')>>LuxPedia.Help.WebHome]] · [[#lp('about')>>LuxPedia.About.WebHome]]\n" + END),
}


EDITABLE_PAGES = {
    ('Knowledge', 'WebHome'): ('knowledge', 'knowledgeIntro', ['CategoryList']),
    ('Knowledge', 'CategoryIndex'): ('categories', 'categoryIntro', ['CategoryList']),
    ('Knowledge', 'CategoryGuide'): ('categoryGuide', 'categoryIntro', []),
    ('Knowledge', 'DocumentLibrary'): ('library', 'libraryIntro', ['ArticleList']),
    ('Drafts', 'WebHome'): ('drafts', 'draftsIntro', ['DraftTools', 'DraftList']),
}
MANAGED_BASELINES = json.loads((UI / 'managed_page_baselines.json').read_text())
DRAFT_TOOLS = V + (UI / 'pages/draft_tools.xwiki').read_text() + END


def editable_body(space, page, language):
    key, intro, renderers = EDITABLE_PAGES[(space, page)]
    content = '= ' + MESSAGES[language][key] + ' =\n\n' + MESSAGES[language][intro] + '\n\n'
    return content + '\n\n'.join('{{include reference="LuxPedia.Code.' + name + '" author="target"/}}' for name in renderers)


def ensure_editable_page(wiki, space, page):
    key = EDITABLE_PAGES[(space, page)][0]
    baseline = MANAGED_BASELINES[space + '/' + page]
    content = editable_body(space, page, 'en')
    status, body = wiki.request('GET', wiki.page_path(space, page))
    if status == 200:
        root = ET.fromstring(body)
        existing = root.findtext('{http://www.xwiki.org}content') or ''
        title = root.findtext('{http://www.xwiki.org}title') or ''
        known = hashlib.sha256(existing.encode()).hexdigest() in baseline['hashes'] or existing == content
        if not known or title not in baseline['titles']:
            return
    elif status != 404:
        raise RuntimeError(f'Could not inspect editable page {space}.{page}: HTTP {status}')
    wiki.ensure_page(space, page, MESSAGES['en'][key], content, update=True)
    ensure_translation(wiki, space, page, 'zh', MESSAGES['zh'][key], editable_body(space, page, 'zh'),
                       preserve_hashes=baseline['hashes'], preserve_titles=baseline['titles'])


for _space, _page in EDITABLE_PAGES:
    PAGES[(_space, _page)] = (EDITABLE_PAGES[(_space, _page)][0], editable_body(_space, _page, 'en'))


def ensure_translation(wiki, space, page, language, title, content, preserve_hashes=None, preserve_titles=None):
    path = wiki.page_path(space, page) + '/translations/' + language
    status, body = wiki.request('GET', path)
    if status == 200:
        root = ET.fromstring(body)
        if root.findtext('{http://www.xwiki.org}title') == title and root.findtext('{http://www.xwiki.org}content') == content:
            return
        if preserve_hashes is not None:
            old = root.findtext('{http://www.xwiki.org}content') or ''
            old_title = root.findtext('{http://www.xwiki.org}title') or ''
            if ((hashlib.sha256(old.encode()).hexdigest() not in preserve_hashes and old != content)
                    or old_title not in preserve_titles):
                return
    xml = '<page xmlns="http://www.xwiki.org"><title>' + escape(title) + '</title><syntax>xwiki/2.1</syntax><content>' + escape(content) + '</content></page>'
    status, body = wiki.request('PUT', path, xml, 'application/xml', token=wiki.form_token())
    if status not in (200, 201, 202):
        raise RuntimeError(f'Could not update {space}.{page} translation {language}: {status}')


def apply(module, wiki):
    wiki.ensure_object_values('XWiki', 'XWikiPreferences', 'XWiki.XWikiPreferences', {
        'default_language': 'en', 'multilingual': '1', 'languages': 'en,zh'})
    def translations(language):
        return '\n'.join('luxpedia.' + key + '=' + value.replace('\\', '\\\\').replace('\n', '\\n')
                         for key, value in MESSAGES[language].items()) + '\n' + (UI / f'fields_{language}.properties').read_text()
    wiki.ensure_page('LuxPedia/Code', 'InterfaceTranslations', 'LuxPedia interface translations', translations('en'), hidden=True, update=True)
    wiki.ensure_object_values('LuxPedia/Code', 'InterfaceTranslations', 'XWiki.TranslationDocumentClass', {'scope': 'WIKI'})
    ensure_translation(wiki, 'LuxPedia/Code', 'InterfaceTranslations', 'zh', 'LuxPedia 界面翻译', translations('zh'))
    native_path = wiki.page_path('PublicationWorkflow', 'Translations')
    status, body = wiki.request('GET', native_path)
    if status != 200:
        raise RuntimeError('Publication Workflow must be installed before applying its interface translations')
    original = ET.fromstring(body)
    native = original.findtext('{http://www.xwiki.org}content') or ''
    overrides = dict(line.split('=', 1) for line in (UI / 'workflow_en.properties').read_text().splitlines()
                     if line and not line.startswith('#'))
    for key, value in overrides.items():
        native, count = re.subn(r'(?m)^' + re.escape(key) + r'\s*=.*$', key + '=' + value, native)
        if not count: native += '\n' + key + '=' + value
    wiki.ensure_page('PublicationWorkflow', 'Translations', 'Publication translations', native, hidden=True, update=True)
    ensure_translation(wiki, 'PublicationWorkflow', 'Translations', 'zh', '文章发布翻译', (UI / 'workflow_zh.properties').read_text())
    # Migrate only the exact branding strings installed by our branding script.
    for number in wiki.objects('XWiki', 'XWikiPreferences', 'XWiki.XWikiPreferences'):
        current = wiki.object_values('XWiki', 'XWikiPreferences', 'XWiki.XWikiPreferences', number)
        changes = {}
        if current.get('title') == LEGACY_BRAND_TITLE:
            changes['title'] = BRAND_TITLE
        if current.get('version') == LEGACY_BRAND_FOOTER:
            changes['version'] = BRAND_FOOTER
        if changes:
            wiki.ensure_object_values('XWiki', 'XWikiPreferences', 'XWiki.XWikiPreferences', changes)
        break
    for renderer, content in [('CategoryList', V + CATEGORY_LIST + END), ('ArticleList', V + ARTICLE_LIST + END),
                              ('DraftList', V + (UI / 'pages/drafts.xwiki').read_text() + END), ('DraftTools', DRAFT_TOOLS)]:
        wiki.ensure_page('LuxPedia/Code', renderer, renderer, content, hidden=True, update=True)
    for (space, page), (key, content) in PAGES.items():
        if (space, page) in EDITABLE_PAGES:
            ensure_editable_page(wiki, space, page)
            continue
        content = content.replace('PublicationWorkflow.Overview', 'LuxPedia.Workflow.ReviewQueue')
        wiki.ensure_page(space, page, MESSAGES['en'][key], content, update=True)
        ensure_translation(wiki, space, page, 'zh', MESSAGES['zh'][key], content)
    # Preserve the category-creation implementation while localizing its UI strings.
    content = module.CATEGORY_ADMIN
    replacements = {
        '= 分类管理 =': "= $services.localization.render('luxpedia.categoryAdmin') =",
        '仅管理员可用。创建分类会在 `Knowledge` 下新建空间和分类首页，并写入 `LuxPedia.Code.CategoryClass`。分类代号只允许字母、数字和连字符，创建后可在分类首页与分类索引中看到。': "$services.localization.render('luxpedia.categoryIntro')",
        '分类代号或名称不合法：代号为 1-40 位字母、数字或连字符，名称不能为空。': "$services.localization.render('luxpedia.categoryInvalid')",
        '已创建分类 ${label}（Knowledge.${key}）。': "$services.localization.render('luxpedia.categoryCreated')",
        '分类代号 ${key} 已存在，未修改。': "$services.localization.render('luxpedia.categoryExists')",
        '表单令牌无效，请刷新页面后重试。': "$services.localization.render('luxpedia.invalidToken')",
        '仅管理员可以创建分类。': "$services.localization.render('luxpedia.adminOnly')",
        '分类代号 ': "$services.localization.render('luxpedia.categoryKey') ",
        '如 BOP-ACTIVE': 'BOP-ACTIVE',
        '显示名称 ': "$services.localization.render('luxpedia.categoryName') ",
        '分类说明 ': "$services.localization.render('luxpedia.description') ",
        '分类负责人 ': "$services.localization.render('luxpedia.owner') ",
        '>创建分类<': ">$services.localization.render('luxpedia.createCategory')<",
        '== 当前分类 ==': "== $services.localization.render('luxpedia.categories') ==",
    }
    # All localization calls must be inside the Velocity macro, including the intro/footer.
    content = content.replace('{{velocity}}', '').replace('{{/velocity}}', '')
    for old, new in replacements.items():
        content = content.replace(old, new)
    content = '{{velocity}}\n' + content + '\n{{/velocity}}'
    wiki.ensure_page('Knowledge', 'CategoryAdmin', 'Manage categories', content, update=True)
    ensure_translation(wiki, 'Knowledge', 'CategoryAdmin', 'zh', '分类管理', content)
    category_template = V + ARTICLE_LIST.replace("(doc.space = 'Knowledge' or doc.space like 'Knowledge.%')", "doc.space = :categorySpace")
    category_template = category_template.replace(').execute()', ").bindValue('categorySpace', $catSpace).execute()", 1)
    category_context = "#set($categoryRef = $doc.fullName)\n#set($flow = $doc.getObject('PublicationWorkflow.PublicationWorkflowClass'))\n#if($categoryRef.startsWith('Drafts.') && $flow)#set($categoryRef = $flow.getValue('target'))#end\n#set($catSpace = $categoryRef.replace('.WebHome', ''))\n"
    category_template = category_template.replace('#set($articles', category_context + '#set($articles', 1) + END
    wiki.ensure_page('LuxPedia/Code', 'CategoryHomeTemplate', 'Category article list', category_template, hidden=True, update=True)
    # Branding is optional: object creation requires an existing parent page.
    # Keep an existing panel document intact and localize only its panel object.
    wiki.ensure_page('LuxPedia', 'HelpPanel', 'LuxPedia help', '', hidden=True)
    wiki.ensure_object_values('LuxPedia', 'HelpPanel', 'Panels.PanelClass', {
        'name': 'LuxPedia help', 'type': 'view', 'category': 'Information',
        'content': V + '#panelheader("LuxPedia")\n* [[#lp(\'help\')>>LuxPedia.Help.WebHome]]\n* [[#lp(\'about\')>>LuxPedia.About.WebHome]]\n#panelfooter()' + END,
        'async_cached': '0', 'async_enabled': '0'})
    if wiki.page_exists('FlamingoThemes', 'LuxPedia'):
        wiki.ensure_object_values('FlamingoThemes', 'LuxPedia', 'FlamingoThemesCode.ThemeClass', {'logo-description': 'LuxPedia knowledge base'})
    # Update only the known system prefix in the trusted creation template.
    # User articles are converted during their own authenticated save by the policy.
    status, body = wiki.request('GET', wiki.page_path('LuxPedia/Templates', 'Article'))
    if status == 200:
        template = ET.fromstring(body)
        content = template.findtext('{http://www.xwiki.org}content') or ''
        legacy_body = (UI / 'pages/article_zh.xwiki').read_text()
        if content in (legacy_body, module.ARTICLE_META + legacy_body, (UI / 'pages/article_en.xwiki').read_text()):
            wiki.ensure_page('LuxPedia/Templates', 'Article', 'Article template',
                             (UI / 'pages/article_en.xwiki').read_text(), hidden=True, update=True)
            ensure_translation(wiki, 'LuxPedia/Templates', 'Article', 'zh', '文章模板', legacy_body)
        elif content.startswith(module.ARTICLE_META):
            wiki.ensure_page('LuxPedia/Templates', 'Article', 'Article template',
                             content[len(module.ARTICLE_META):], hidden=True, update=True)
    # These menu extensions render on every page and use XWiki's selected locale.
    for page, old, key, name, order in [
            ('SourceEditUIX', '源码编辑', 'sourceEdit', 'luxpedia.source.edit', 15000),
            ('EditGuideUIX', '编辑指南', 'editGuide', 'luxpedia.edit.guide', 15001)]:
        template = module.SOURCE_EDIT_UIX if page == 'SourceEditUIX' else None
        if template is None:
            spec = importlib.util.spec_from_file_location('apply_help', ROOT / 'scripts/apply_help.py')
            help_module = importlib.util.module_from_spec(spec); spec.loader.exec_module(help_module)
            template = help_module.MENU_CONTENT
        content = template.replace(old, "$services.localization.render('luxpedia." + key + "')")
        wiki.ensure_page('LuxPedia/Code', page, 'LuxPedia menu extension',
                         'Localized content menu UI extension.', hidden=True)
        properties = {'content': content}
        if not wiki.has_object('LuxPedia/Code', page, 'XWiki.UIExtensionClass'):
            properties.update({'extensionPointId': 'org.xwiki.plaftorm.menu.content',
                               'name': name, 'scope': 'wiki', 'parameters': f'order={order}'})
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.UIExtensionClass', properties)
    module.log('English and Chinese interface pages installed; article content and category names preserved')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--base-url', default=os.environ.get('XWIKI_URL', 'http://127.0.0.1:8080'))
    args = parser.parse_args()
    if not args.apply:
        print('Dry run: configure English/Chinese locales and update system interface pages. Article bodies are preserved.')
        return
    spec = importlib.util.spec_from_file_location('apply_sprint1', ROOT / 'scripts/apply_sprint1.py')
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    apply(module, module.XWiki(args.base_url, *module.load_admin()))


if __name__ == '__main__':
    main()
