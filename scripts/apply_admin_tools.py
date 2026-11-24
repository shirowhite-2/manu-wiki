"""Extend the existing XWiki administration sections and private administrator index."""
from interface_assets import UI, I18N_JS

ENDPOINT = '''{{velocity wiki="false"}}
#set($discard = $response.setContentType('application/json'))
#set($discard = $response.setHeader('Cache-Control', 'private, no-store'))
#set($discard = $response.setHeader('Vary', 'Cookie, Authorization'))
#if($request.getMethod() == 'POST')
#set($result = $services.luxpediaWorkflowPolicy.adminWrite($request.getParameter('operation'), $request.getParameter('payload'), $request.getParameter('form_token')))
#else
#set($result = $services.luxpediaWorkflowPolicy.adminRead($request.getParameter('operation'), $request.getParameter('q'), $request.getParameter('area'), $request.getParameter('page'), $request.getParameter('days')))
#end
$jsontool.serialize($result)
{{/velocity}}'''
PRESENTATION = '''{{velocity wiki="false"}}
#set($discard = $response.setContentType('application/json'))
#set($discard = $response.setHeader('Cache-Control', 'private, no-store'))
#set($discard = $response.setHeader('Vary', 'Cookie, Authorization'))
$jsontool.serialize($services.luxpediaWorkflowPolicy.presentation($request.getParameter('reference')))
{{/velocity}}'''
ADMIN_JS = I18N_JS + (UI / 'admin_tools.js').read_text() + (UI / 'rag_sync.js').read_text()
SECTIONS = [('AdminOperations', 'admin_operations', 'LuxPediaOperations', '120'),
            ('NavigationEditor', 'admin_navigation', 'LuxPediaNavigation', '130'),
            ('RagSyncEditor', 'admin_rag', 'LuxPediaRag', '140')]
RESTRICTED = {'groups': 'XWiki.LuxManagers,XWiki.XWikiAdminGroup', 'levels': 'view', 'allow': '1'}
QR_JS = '(function(define,module,exports){\n' + (UI / 'vendor/qrcode-generator-1.4.4.js').read_text() + '\nwindow.LuxPediaQRCode=qrcode;\n})();\n'
PRESENTATION_JS = I18N_JS + QR_JS + (UI / 'navigation_presentation.js').read_text()


def apply(module, wiki):
    module.log('== existing administrator interface: operations and navigation')
    for page, source, section, order in SECTIONS:
        wiki.ensure_page('LuxPedia/Code', page, page, (UI / 'pages' / (source + '.xwiki')).read_text(), hidden=True, update=True)
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.XWikiRights', RESTRICTED)
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.ConfigurableClass', {
            'displayInCategory': 'content', 'displayInSection': section, 'scope': 'WIKI', 'sectionOrder': order,
            'heading': '', 'configurationClass': '', 'propertiesToShow': '',
            'codeToExecute': '{{include reference="LuxPedia.Code.' + page + '" context="new"/}}',
            'displayBeforeCategory': '', 'categoryIcon': 'cog', 'iconAttachment': '', 'linkPrefix': ''})
    wiki.ensure_page('LuxPedia/Workflow', 'AdminTools', 'Administrator operations', ENDPOINT, hidden=True, update=True)
    wiki.ensure_object_values('LuxPedia/Workflow', 'AdminTools', 'XWiki.XWikiRights', {
        'groups': 'XWiki.LuxManagers,XWiki.XWikiAdminGroup', 'levels': 'view', 'allow': '1'})
    wiki.ensure_page('LuxPedia/Workflow', 'Presentation', 'Current page navigation', PRESENTATION, hidden=True, update=True)
    wiki.ensure_page('LuxPedia/Code', 'NavigationSettings', 'Administrator navigation settings',
                     'Server-managed menus and category banners. Use the existing wiki administration interface.', hidden=True)
    wiki.ensure_object_values('LuxPedia/Code', 'NavigationSettings', 'XWiki.XWikiRights', RESTRICTED)
    # The sync ledger pages (LuxPedia.Code.RagLedger_*) are created by the policy with the same restriction.
    for page, title in [('RagSettings', 'LightRAG sync settings'), ('RagSyncState', 'LightRAG sync state')]:
        wiki.ensure_page('LuxPedia/Code', page, title, 'Server-managed LightRAG sync data. Use the LightRAG sync administration section.', hidden=True)
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.XWikiRights', RESTRICTED)
    for page, code in [('AdminToolsScript', ADMIN_JS), ('NavigationPresentation', PRESENTATION_JS)]:
        wiki.ensure_page('LuxPedia/Code', page, page, '', hidden=True)
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.JavaScriptExtension', {
            'name': 'luxpedia.' + page, 'code': code, 'use': 'always', 'parse': '0', 'cache': 'long'})
