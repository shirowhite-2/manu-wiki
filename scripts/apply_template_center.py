"""Install the admin template index; creation/editing use native XWiki forms."""
from interface_assets import UI, MESSAGES


def apply(module, wiki):
    from apply_interface import ensure_translation
    content = (UI / 'pages/template_center.xwiki').read_text()
    wiki.ensure_page('LuxPedia/Templates', 'WebHome', MESSAGES['en']['templateCenter'], content, update=True)
    ensure_translation(wiki, 'LuxPedia/Templates', 'WebHome', 'zh', MESSAGES['zh']['templateCenter'], content)
    # Only the index is private. Users still need VIEW on template bodies and providers.
    wiki.ensure_object_values('LuxPedia/Templates', 'WebHome', 'XWiki.XWikiRights', {
        'groups': 'XWiki.LuxManagers,XWiki.XWikiAdminGroup', 'levels': 'view', 'allow': '1'})
    # A custom admin section must explicitly include its display page; an empty
    # codeToExecute would register a menu entry with a blank content area.
    wiki.ensure_object_values('LuxPedia/Templates', 'WebHome', 'XWiki.ConfigurableClass', {
        'displayInCategory': 'content', 'displayInSection': 'LuxPediaTemplates',
        'scope': 'WIKI', 'sectionOrder': '110', 'heading': '',
        'configurationClass': '', 'propertiesToShow': '',
        'codeToExecute': '{{include reference="LuxPedia.Templates.WebHome" context="new"/}}',
        'displayBeforeCategory': '', 'categoryIcon': 'page_white_text',
        'iconAttachment': '', 'linkPrefix': ''})
    wiki.ensure_page('LuxPedia/Code', 'ArticleTemplateSeed', 'Article template starter',
                     (UI / 'pages/article_en.xwiki').read_text(), hidden=True)
    ensure_translation(wiki, 'LuxPedia/Code', 'ArticleTemplateSeed', 'zh', '文章模板起始内容',
                       (UI / 'pages/article_zh.xwiki').read_text())
    module.log('Admin template center installed; existing template bodies and provider references preserved')
