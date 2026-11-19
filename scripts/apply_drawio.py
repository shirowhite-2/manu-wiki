#!/usr/bin/env python3
"""Install external link insertion and on-demand draw.io previews through the full workflow installer."""
from interface_assets import I18N_JS, UI

SPACE = 'LuxPedia/Code'
PAGE = 'DrawioScript'
DRAWIO_JS = I18N_JS + (UI / 'drawio.js').read_text()


def apply(module, wiki):
    module.log('== external links and draw.io previews')
    wiki.ensure_page(SPACE, PAGE, 'External links and draw.io previews',
                     'Recognize imported links, preserve navigation and preview draw.io diagrams.', hidden=True)
    wiki.ensure_object_values(SPACE, PAGE, 'XWiki.JavaScriptExtension', {
        'name': 'luxpedia.drawio', 'code': DRAWIO_JS,
        'use': 'always', 'parse': '0', 'cache': 'long'})
