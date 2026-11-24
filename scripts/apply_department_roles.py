"""Register department roles in the existing native administrator interface."""
from interface_assets import UI, I18N_JS
import xml.etree.ElementTree as ET


def apply(module, wiki):
    wiki.ensure_page('LuxPedia/Code', 'DepartmentRoles', 'Department roles',
                     (UI / 'pages/department_roles.xwiki').read_text(), hidden=True, update=True)
    wiki.ensure_page('LuxPedia/Code', 'DepartmentSettings', 'Department access settings',
                     'Server-managed department scope. Configure in Wiki Administration > Content > Department roles.', hidden=True)
    for page in ('DepartmentRoles', 'DepartmentSettings'):
        wiki.ensure_object_values('LuxPedia/Code', page, 'XWiki.XWikiRights', {
            'groups': 'XWiki.LuxManagers,XWiki.XWikiAdminGroup', 'levels': 'view', 'allow': '1'})
    wiki.ensure_object_values('LuxPedia/Code', 'DepartmentRoles', 'XWiki.ConfigurableClass', {
        'displayInCategory': 'usersgroups', 'displayInSection': 'LuxPediaDepartments', 'scope': 'WIKI', 'sectionOrder': '350',
        'heading': '', 'configurationClass': '', 'propertiesToShow': '',
        'codeToExecute': '{{include reference="LuxPedia.Code.DepartmentRoles" context="new"/}}',
        'displayBeforeCategory': '', 'categoryIcon': 'users', 'iconAttachment': '', 'linkPrefix': ''})
    wiki.ensure_page('LuxPedia/Code', 'DepartmentRolesScript', 'Department roles controls', '', hidden=True)
    wiki.ensure_object_values('LuxPedia/Code', 'DepartmentRolesScript', 'XWiki.JavaScriptExtension', {
        'name': 'luxpedia.DepartmentRoles', 'code': I18N_JS + (UI / 'department_roles.js').read_text(),
        'use': 'always', 'parse': '0', 'cache': 'long'})
    status, body = wiki.request('GET', wiki.page_path('Main', 'SolrSearchMacros'))
    if status == 200:
        page = ET.fromstring(body)
        source = page.findtext('{http://www.xwiki.org}content') or ''
        protected = protect_solr_macros(source)
        if protected != source:
            wiki.ensure_page('Main', 'SolrSearchMacros', page.findtext('{http://www.xwiki.org}title') or 'Solr search macros', protected, hidden=True, update=True)
    elif status != 404:
        raise RuntimeError('Could not inspect native Solr search protection: HTTP ' + str(status))
    module.log('Department role administration installed; enable category restrictions after configuring public categories and members')


def protect_solr_macros(content):
    """Native Solr filters result rows, but its global facets/highlights may describe hidden rows."""
    marker = '## LUXPEDIA_DEPARTMENT_SEARCH'
    if marker in content:
        return content
    execute = '#set ($searchResponse = $query.execute()[0])'
    pagination = '#pagination($paginationParameters)'
    if content.count(execute) != 1 or content.count(pagination) != 2 or content.count('#macro (displaySearchResults)') != 1:
        raise RuntimeError('Native Solr macros differ from the supported layout. Integrate department search protection before enabling department restrictions.')
    content = content.replace(execute, '''#if($services.luxpediaWorkflowPolicy.departmentSearchRestricted())
    #set($discard = $query.bindValue('facet', false))
    #set($discard = $query.bindValue('hl', false))
    #set($discard = $query.bindValue('debugQuery', 'off'))
  #end
  ''' + execute)
    content = content.replace(pagination, '''#if($services.luxpediaWorkflowPolicy.departmentSearchRestricted())
#luxpediaDepartmentSearchNavigation()
#else
''' + pagination + '\n#end')
    navigation = (UI / 'pages/department_search_navigation.vm').read_text()


    return content.replace('#macro (displaySearchResults)', navigation + '\n#macro (displaySearchResults)', 1)
