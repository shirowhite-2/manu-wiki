#!/usr/bin/env python3
"""Install draw.io link insertion and on-demand previews through the full workflow installer."""
from interface_assets import I18N_JS, UI

SPACE = 'LuxPedia/Code'
PAGE = 'DrawioScript'
DRAWIO_JS = I18N_JS + (UI / 'drawio.js').read_text()


def apply(module, wiki):
    module.log('== draw.io links and previews')
    wiki.ensure_page(SPACE, PAGE, 'draw.io links and previews',
                     'Insert diagram links and preview published diagrams.', hidden=True)
    wiki.ensure_object_values(SPACE, PAGE, 'XWiki.JavaScriptExtension', {
        'name': 'luxpedia.drawio', 'code': DRAWIO_JS,
        'use': 'always', 'parse': '0', 'cache': 'long'})
