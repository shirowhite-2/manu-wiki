"""Expose personal favorites using native storage and a fixed current-actor service."""
import re
from interface_assets import I18N_JS, UI

ENDPOINT = '''{{velocity wiki="false"}}
#set($discard = $response.setContentType('application/json'))
#set($discard = $response.setHeader('Cache-Control', 'private, no-store'))
#set($discard = $response.setHeader('Vary', 'Cookie, Authorization'))
#if($request.getMethod() == 'POST')
#set($name = $request.getParameter('reference'))
#if(!$name)#set($name = $request.getParameter('docRef'))#end
#set($operation = $request.getParameter('operation'))
#if(!$operation)#set($operation = $request.getParameter('action'))#end
#set($result = $services.luxpediaWorkflowPolicy.manageFavorite($name, $operation, $request.getParameter('version'), $request.getParameter('form_token')))
#else
#set($result = $services.luxpediaWorkflowPolicy.favorites($request.getParameter('reference'), $request.getParameter('q'), $request.getParameter('page')))
#end
$jsontool.serialize($result)
{{/velocity}}'''
NAVIGATION = '''## LUXPEDIA_PERSONAL_FAVORITES_START
{{html clean="false"}}
<section id="luxpedia-favorites-sidebar" hidden="hidden" style="margin-top:1em">
<h3 data-luxpedia-i18n="myFavorites"></h3>
<div class="luxpedia-favorites-recent"></div>
<a class="luxpedia-favorites-all" data-luxpedia-i18n="favoritesViewAll"></a>
</section>
{{/html}}
## LUXPEDIA_PERSONAL_FAVORITES_END
'''
FAVORITES_JS = I18N_JS + (UI / 'favorites.js').read_text()
PAGE = '''<div id="luxpedia-favorites-page">
<form class="form-inline" id="luxpedia-favorites-search" style="margin:1em 0">
<label for="luxpedia-favorites-query" data-luxpedia-i18n="favoritesSearch"></label>
<input id="luxpedia-favorites-query" type="search" maxlength="100" class="form-control" />
<button type="submit" class="btn btn-default" data-luxpedia-i18n="search"></button>
</form>
<div class="luxpedia-favorites-results" aria-live="polite"></div>
</div>'''
PAGE = '{{html clean="false"}}\n' + PAGE + '\n{{/html}}'


def apply(module, wiki):
    if not wiki.page_exists('Favorites/Code', 'FavoritesClass'):
        raise RuntimeError('Install Favorites Application 1.4.4 before configuring personal favorites.')
    wiki.ensure_page('LuxPedia/Workflow', 'Favorites', 'Personal favorites service', ENDPOINT, hidden=True, update=True)
    # The original endpoint must not keep a second GET-based mutation path.
    wiki.ensure_page('Favorites/Code', 'AddRemoveFavoriteService', 'Personal favorites service', ENDPOINT, hidden=True, update=True)
    wiki.ensure_page('LuxPedia/Code', 'FavoritesScript', 'Personal favorites interface', '', hidden=True)
    wiki.ensure_object_values('LuxPedia/Code', 'FavoritesScript', 'XWiki.JavaScriptExtension', {
        'name': 'luxpedia.personalFavorites', 'code': FAVORITES_JS, 'use': 'always', 'parse': '0', 'cache': 'long'})
    cls = 'Panels.PanelClass'
    numbers = wiki.objects('Panels', 'Navigation', cls)
    if not numbers:
        raise RuntimeError('Panels.Navigation is required for the personal favorites entry.')
    source = wiki.object_values('Panels', 'Navigation', cls, numbers[0]).get('content', '')
    source = re.sub(r'## LUXPEDIA_PERSONAL_FAVORITES_START.*?## LUXPEDIA_PERSONAL_FAVORITES_END\n?', '', source, flags=re.S)
    if '#panelfooter()' not in source:
        raise RuntimeError('Panels.Navigation has no panel footer; personal favorites was not appended.')
    wiki.ensure_object_values('Panels', 'Navigation', cls, {
        'content': source.replace('#panelfooter()', NAVIGATION + '#panelfooter()', 1),
        'async_cached': '0', 'async_enabled': '0'})
    # The upstream panel renders titles without its own per-document VIEW check.
    # Retain a safe entry if it is configured in a custom layout; the new list applies VIEW on the server.
    if wiki.objects('Favorites/Code', 'FavoritesPanel', cls):
        wiki.ensure_object_values('Favorites/Code', 'FavoritesPanel', cls, {
            'content': "{{velocity}}\n#panelheader($services.localization.render('luxpedia.myFavorites'))\n"
                       "[[$services.localization.render('luxpedia.favoritesViewAll')>>LuxPedia.Favorites.WebHome]]\n"
                       "#panelfooter()\n{{/velocity}}",
            'async_cached': '0', 'async_enabled': '0'})
    wiki.ensure_page('Favorites', 'WebPreferences', 'WebPreferences', '', hidden=True)
    for group in ('XWiki.LuxEditors', 'XWiki.LuxReviewers', 'XWiki.LuxReaders'):
        wiki.ensure_right('Favorites', 'WebPreferences', {'groups': group, 'levels': 'view', 'allow': '1'})
        wiki.ensure_right('Favorites', 'WebPreferences', {'groups': group, 'levels': 'edit,delete,admin', 'allow': '0'})
    module.log('Personal favorites installed using native profile storage')
