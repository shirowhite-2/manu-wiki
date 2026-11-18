"""Admin directory with virtual role folders; never rename canonical user accounts."""
import re
from pathlib import Path

UI = Path(__file__).resolve().parent / 'ui'
ROLES = ('managers', 'editors', 'reviewers', 'readers', 'unassigned')
ADMIN_NAVIGATION = """## LUXPEDIA_ADMIN_DIRECTORY_START
#if($services.security.authorization.hasAccess('admin', $services.model.resolveDocument('XWiki.XWikiPreferences')))
* [[$services.localization.render('luxpedia.xwikiAdmin')>>XWiki.WebHome]]
** [[$services.localization.render('luxpedia.users')>>XWiki.Users.WebHome]]
** [[$services.localization.render('luxpedia.role.managers')>>XWiki.Users.Managers.WebHome]]
** [[$services.localization.render('luxpedia.role.editors')>>XWiki.Users.Editors.WebHome]]
** [[$services.localization.render('luxpedia.role.reviewers')>>XWiki.Users.Reviewers.WebHome]]
** [[$services.localization.render('luxpedia.role.readers')>>XWiki.Users.Readers.WebHome]]
** [[$services.localization.render('luxpedia.role.unassigned')>>XWiki.Users.Unassigned.WebHome]]
#end
## LUXPEDIA_ADMIN_DIRECTORY_END
"""


def apply(module, wiki):
    content = (UI / 'pages/users.xwiki').read_text()
    # VIEW is enforced by policy on save; the script service also checks the actor.
    wiki.ensure_page('XWiki', 'WebHome', 'XWiki administration',
                     (UI / 'pages/admin_home.xwiki').read_text(), update=True)
    wiki.ensure_page('XWiki/Users', 'WebHome', 'Users', content.replace('__ROLE__', 'all'), update=True)
    for role in ROLES:
        wiki.ensure_page('XWiki/Users/' + role.capitalize(), 'WebHome', role.capitalize(),
                         content.replace('__ROLE__', role), update=True)
    # Native navigation configuration excludes the raw namespace, including its
    # hundreds of terminal profiles. The navigation panel exposes an admin-only index.
    wiki.ensure_page('PanelsCode', 'NavigationConfiguration', 'Navigation configuration', '', hidden=True)
    cls = 'PanelsCode.NavigationConfigurationClass'
    numbers = wiki.objects('PanelsCode', 'NavigationConfiguration', cls)
    current = wiki.object_values('PanelsCode', 'NavigationConfiguration', cls, numbers[0]) if numbers else {}
    # XWiki REST serializes list properties with '|'; preserve spaces in page names.
    exclusions = current.get('exclusions', '').split('|')
    exclusions = [value for value in exclusions if value]
    if 'XWiki.WebHome' not in exclusions:
        exclusions.append('XWiki.WebHome')
    wiki.ensure_object_values('PanelsCode', 'NavigationConfiguration', cls, {'exclusions': '|'.join(exclusions)})
    panel_class = 'Panels.PanelClass'
    panels = wiki.objects('Panels', 'Navigation', panel_class)
    if not panels:
        raise RuntimeError('The native Panels.Navigation panel must be installed before the user directory.')
    panel = wiki.object_values('Panels', 'Navigation', panel_class, panels[0])
    source = re.sub(r'## LUXPEDIA_ADMIN_DIRECTORY_START.*?## LUXPEDIA_ADMIN_DIRECTORY_END\n?', '',
                    panel.get('content', ''), flags=re.S)
    if '#panelfooter()' not in source:
        raise RuntimeError('Panels.Navigation has no panel footer; restore the native panel before applying the directory.')
    source = source.replace('#panelfooter()', ADMIN_NAVIGATION + '#panelfooter()', 1)
    wiki.ensure_object_values('Panels', 'Navigation', panel_class, {
        'content': source, 'async_cached': '0', 'async_enabled': '0'})
    module.log('Private XWiki entry and role-based user directory installed; account references preserved')
