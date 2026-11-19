#!/usr/bin/env python3
"""Install read-only table tools and native-editor content components; preserve articles."""
from interface_assets import I18N_JS, UI

CONTENT_TOOLS_JS = I18N_JS + '\n'.join((UI / name).read_text() for name in (
    'table_export.js', 'table_tools.js', 'content_components.js'))


def apply(module, wiki):
    module.log('== metadata browsing, content components and spreadsheet export')
    wiki.ensure_page('LuxPedia/Code', 'ContentTools', 'Content tools',
                     'Permission-filtered browsing, table export and native editor snippets.', hidden=True)
    wiki.ensure_object_values('LuxPedia/Code', 'ContentTools', 'XWiki.JavaScriptExtension', {
        'name': 'luxpedia.contentTools', 'code': CONTENT_TOOLS_JS, 'use': 'always', 'parse': '0', 'cache': 'long'})
