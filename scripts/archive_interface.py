"""Attach the protected archive index to the existing category without moving pages."""
import re
import xml.etree.ElementTree as ET
from interface_assets import MESSAGES

INCLUDE = '{{include reference="LuxPedia.Code.ArchiveList" author="target"/}}'
TEMPLATE_INCLUDE = re.compile(r'\{\{include\s+(?:document|reference)="LuxPedia\.Code\.CategoryHomeTemplate"(?:\s+author="target")?\s*/\}\}')
ARCHIVE_INCLUDE = re.compile(r'''\{\{include\b[^}]*\b(?:reference|document)=["']LuxPedia\.Code\.ArchiveList["'][^}]*\}\}''')
NS = '{http://www.xwiki.org}'
LEGACY_LABEL = '归档'
LEGACY_DESCRIPTION = '历史资料与过期文档'


def default_body(language):
    return '= ' + MESSAGES[language]['archiveCenter'] + ' =\n\n' + MESSAGES[language]['archiveIntro'] + '\n\n' + INCLUDE + '\n'


def migrate_body(module, title, body, language):
    category = next((item for item in module.CATEGORIES if item[0] == 'Archive'),
                    ('Archive', '归档', '历史资料与过期文档'))
    old_table = module.ARTICLE_TABLE.replace('__SPACE__', 'Knowledge.Archive')
    old_body = '= ' + LEGACY_LABEL + ' =\n\n' + LEGACY_DESCRIPTION + '\n\n' + old_table
    generated = body in (old_body, default_body('en'), default_body('zh')) and title in (
        LEGACY_LABEL, category[1], MESSAGES['en']['archiveCenter'], MESSAGES['zh']['archiveCenter'])
    if generated:
        return MESSAGES[language]['archiveCenter'], default_body(language), True
    body = body.replace(old_table, '')
    body = TEMPLATE_INCLUDE.sub(INCLUDE, body)
    if not ARCHIVE_INCLUDE.search(body):
        body = body.rstrip() + '\n\n' + INCLUDE + '\n'
    if title == LEGACY_LABEL:
        title = MESSAGES[language]['archiveCenter']
    return title, body, False


def apply(module, wiki, ensure_translation):
    status, response = wiki.request('GET', wiki.page_path('Knowledge/Archive', 'WebHome'))
    if status == 404:
        title, body, generated = MESSAGES['en']['archiveCenter'], default_body('en'), True
    elif status == 200:
        page = ET.fromstring(response)
        title, body, generated = migrate_body(module, page.findtext(NS + 'title') or 'Archive',
                                              page.findtext(NS + 'content') or '', 'en')
    else:
        raise RuntimeError(f'Could not inspect the Archive category: HTTP {status}')
    wiki.ensure_page('Knowledge/Archive', 'WebHome', title, body, update=True)
    category_class = 'LuxPedia.Code.CategoryClass'
    if not wiki.has_object('Knowledge/Archive', 'WebHome', category_class):
        wiki.ensure_object_values('Knowledge/Archive', 'WebHome', 'LuxPedia.Code.CategoryClass', {
            'name': MESSAGES['en']['archiveCenter'], 'parent': 'Knowledge', 'owner': 'XWiki.Admin',
            'template': 'LuxPedia.Templates.Article', 'description': MESSAGES['en']['archiveIntro'], 'sortOrder': '100'})
    else:
        for number in wiki.objects('Knowledge/Archive', 'WebHome', category_class):
            current = wiki.object_values('Knowledge/Archive', 'WebHome', category_class, number)
            changes = {}
            if current.get('name') == LEGACY_LABEL:
                changes['name'] = MESSAGES['en']['archiveCenter']
            if current.get('description') == LEGACY_DESCRIPTION:
                changes['description'] = MESSAGES['en']['archiveIntro']
            if changes:
                wiki.ensure_object_values('Knowledge/Archive', 'WebHome', category_class, changes)
    path = wiki.page_path('Knowledge/Archive', 'WebHome') + '/translations/zh'
    status, response = wiki.request('GET', path)
    if status == 200:
        page = ET.fromstring(response)
        title, body, _ = migrate_body(module, page.findtext(NS + 'title') or '归档',
                                      page.findtext(NS + 'content') or '', 'zh')
        ensure_translation(wiki, 'Knowledge/Archive', 'WebHome', 'zh', title, body)
    elif status == 404:
        if generated:
            ensure_translation(wiki, 'Knowledge/Archive', 'WebHome', 'zh', MESSAGES['zh']['archiveCenter'], default_body('zh'))
    else:
        raise RuntimeError(f'Could not inspect the Archive category translation: HTTP {status}')
