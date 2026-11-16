#!/usr/bin/env python3
"""Bootstrap the Sprint 1 wiki structure: roles, categories, classes and pages."""

import argparse
import base64
import mimetypes
import os
import re
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path
from xml.sax.saxutils import escape

ROOT = Path(__file__).resolve().parents[1]
SECRETS = ROOT / ".secrets"
NS = "{http://www.xwiki.org}"

GROUPS = {
    "Managers": ("XWiki.LuxManagers", "管理"),
    "Editors": ("XWiki.LuxEditors", "编辑者"),
    "Reviewers": ("XWiki.LuxReviewers", "审核人"),
    "Readers": ("XWiki.LuxReaders", "读者"),
}

TEST_USERS = [
    ("poceditor", "POC", "Editor", "Editors"),
    ("pocreviewer", "POC", "Reviewer", "Reviewers"),
    ("pocreader", "POC", "Reader", "Readers"),
]

CATEGORIES = [
    ("Technical", "技术文档", "系统架构、接口、技术规范"),
    ("Operations", "操作手册", "日常维护、操作步骤、检查清单"),
    ("Faults", "故障处理", "故障现象、排查步骤、恢复方法"),
    ("Archive", "归档", "历史资料与过期文档"),
    ("BOP-ACTIVE", "BOP-ACTIVE", "BOP-ACTIVE 分类，说明待管理员补充"),
    ("BOP-PASSIVE", "BOP-PASSIVE", "BOP-PASSIVE 分类，说明待管理员补充"),
]

ARTICLE_TABLE = """{{velocity}}
#set($q = "from doc.object(LuxPedia.Code.ArticleClass) as article where doc.space = '__SPACE__' order by doc.date desc")
#set($results = $services.query.xwql($q).execute())
#if($results.size() == 0)
暂无文章。
#else
|=标题|=状态|=负责人|=标签
#foreach($item in $results)
  #set($d = $xwiki.getDocument($item))
  #set($o = $d.getObject("LuxPedia.Code.ArticleClass"))
  #if($o)
    |=[[$d.title>>$d.fullName]]|$o.getProperty("status").value|$o.getProperty("owner").value|$o.getProperty("tagsText").value
  #end
#end
#end
{{/velocity}}"""


ARTICLE_META = """{{velocity}}
#set($o = $doc.getObject("LuxPedia.Code.ArticleClass"))
#if($o)
|=字段|=值
|主分类|$!o.getProperty("primaryCategory").value
|状态|$!o.getProperty("status").value
|负责人|$!o.getProperty("owner").value
|适用版本|$!o.getProperty("applicableVersion").value
|标签|$!o.getProperty("tagsText").value
|摘要|$!o.getProperty("summary").value
#end
{{/velocity}}

"""

SOURCE_EDIT_UIX = """{{velocity}}
{{html clean="false"}}
#if ($doc.hasAccessLevel('edit'))
  <li class="btn-group" id="tmSourceEdit">
    <a class="btn btn-default" href="$doc.getURL('edit', 'editor=wiki')" title="源码编辑" rel="nofollow">
      <span class="btn-label">源码编辑</span>
    </a>
  </li>
#end
{{/html}}
{{/velocity}}"""

KNOWLEDGE_HOME = """= 知识库 =

按主分类浏览已发布知识。主分类是空间，标签用于交叉分类。

* [[技术文档>>Knowledge.Technical.WebHome]]
* [[操作手册>>Knowledge.Operations.WebHome]]
* [[故障处理>>Knowledge.Faults.WebHome]]
* [[归档>>Knowledge.Archive.WebHome]]
* [[BOP-ACTIVE>>Knowledge.BOP-ACTIVE.WebHome]]
* [[BOP-PASSIVE>>Knowledge.BOP-PASSIVE.WebHome]]
* [[文档库>>Knowledge.DocumentLibrary]]
* [[分类索引>>Knowledge.CategoryIndex]]
* [[分类说明>>Knowledge.CategoryGuide]]

{{info}}
当前一篇文章使用一个主分类 + 多个标签。`list.md` 的 CM-03 要求“一篇文章可以属于一个或多个分类”，多主分类尚未实现。
{{/info}}
"""

CATEGORY_GUIDE = """= 分类说明 =

`list.md` 的“分类与知识结构管理”定义的是分类能力（多级、标签、导航、负责人、模板、权限、迁移、统计），没有规定具体的业务分类名称。当前使用以下示例分类树：

|=主分类|=用途|=示例|=负责人
|技术文档|系统架构、接口、技术规范|架构说明、接口文档|XWiki.Admin
|操作手册|日常维护、操作步骤、检查清单|点检步骤、巡检清单|XWiki.Admin
|故障处理|故障现象、排查步骤、恢复方法|告警处理、恢复手册|XWiki.Admin
|归档|历史资料与过期文档|旧版本、历史通知|XWiki.Admin

分类规则：

* 主分类 = `Knowledge` 下的空间，承载权限。
* 标签 = 交叉分类，例如“安全”“SOP”“设备”“历史”。
* 文章页面顶部的元数据表显示主分类、状态、负责人、版本、标签和摘要。
* 分类首页按 `ArticleClass.primaryCategory` 和页面空间自动列出文章。
* 新增主分类：管理员在 `Knowledge` 下创建子空间，并复制分类首页结构。
* 分类级权限：在对应分类空间的 `WebPreferences` 中配置组权限。
* 分类负责人：`CategoryClass.owner`，当前默认 `XWiki.Admin`。
* 分类模板：`CategoryClass.template`，当前默认 `LuxPedia.Templates.Article`。

当前限制：

* 一篇文章只有一个主分类；多主分类/多分类对象尚未实现。
* 分类统计目前是文章数量，不含最近更新时间趋势。
* 分类负责人尚未接入审批分配 UI。
"""

CATEGORY_INDEX = """= 分类索引 =

本页自动列出所有已创建的分类（带 `LuxPedia.Code.CategoryClass` 的分类首页），新增分类后无需修改本页。

{{velocity}}
#set($cats = $services.query.xwql("from doc.object(LuxPedia.Code.CategoryClass) as c where doc.name = 'WebHome' and doc.space like 'Knowledge.%' order by doc.title").execute())
#if($cats.size() == 0)
暂无分类。
#else
|=分类|=文章数|=说明
#foreach($item in $cats)
  #set($cd = $xwiki.getDocument($item))
  #set($co = $cd.getObject("LuxPedia.Code.CategoryClass"))
  #set($catSpace = $cd.fullName.replace('.WebHome', ''))
  #set($arts = $services.query.xwql("from doc.object(LuxPedia.Code.ArticleClass) as a where doc.space = '$catSpace'").execute())
  |=[[$cd.title>>$cd.fullName]]|$arts.size()|$!co.getProperty("description").value
#end
#end
{{/velocity}}
"""

CATEGORY_HOME_TEMPLATE = """{{velocity}}
#set($catSpace = $doc.fullName.replace('.WebHome', ''))
#set($results = $services.query.xwql("from doc.object(LuxPedia.Code.ArticleClass) as article where doc.space = '${catSpace}' order by doc.date desc").execute())
#if($results.size() == 0)
暂无文章。
#else
|=标题|=状态|=负责人|=标签
#foreach($item in $results)
  #set($d = $xwiki.getDocument($item))
  #set($o = $d.getObject("LuxPedia.Code.ArticleClass"))
  #if($o)
    |=[[$d.title>>$d.fullName]]|$o.getProperty("status").value|$o.getProperty("owner").value|$o.getProperty("tagsText").value
  #end
#end
#end
{{/velocity}}
"""

CATEGORY_ADMIN = """= 分类管理 =

仅管理员可用。创建分类会在 `Knowledge` 下新建空间和分类首页，并写入 `LuxPedia.Code.CategoryClass`。分类代号只允许字母、数字和连字符，创建后可在分类首页与分类索引中看到。

{{velocity}}
#set($isAdmin = $xwiki.hasAdminRights())
#set($msg = "")
#if($isAdmin && "$!request.action" == "create")
  #if($services.csrf.isTokenValid($request.form_token))
    #set($key = "$!request.key")
    #set($key = $key.trim())
    #set($label = "$!request.label")
    #set($label = $label.trim())
    #set($desc = "$!request.description")
    #set($desc = $desc.trim())
    #set($owner = "$!request.owner")
    #set($owner = $owner.trim())
    #if(!$key.matches('[A-Za-z0-9][A-Za-z0-9-]{0,39}') || $label == "")
      #set($msg = "分类代号或名称不合法：代号为 1-40 位字母、数字或连字符，名称不能为空。")
    #else
      #set($cdoc = $xwiki.getDocument("Knowledge.${key}.WebHome"))
      #if($cdoc.isNew())
        #define($body)= $label =

$desc

{{include document="LuxPedia.Code.CategoryHomeTemplate"/}}#end
        #set($discard = $cdoc.setTitle($label))
        #set($discard = $cdoc.setContent($body))
        #set($obj = $cdoc.newObject("LuxPedia.Code.CategoryClass"))
        #set($discard = $obj.set("name", $label))
        #set($discard = $obj.set("parent", "Knowledge"))
        #set($discard = $obj.set("owner", $owner))
        #set($discard = $obj.set("template", "LuxPedia.Templates.Article"))
        #set($discard = $obj.set("description", $desc))
        #set($discard = $cdoc.save("Create category from admin page"))
        #set($msg = "已创建分类 ${label}（Knowledge.${key}）。")
      #else
        #set($msg = "分类代号 ${key} 已存在，未修改。")
      #end
    #end
  #else
    #set($msg = "表单令牌无效，请刷新页面后重试。")
  #end
#elseif(!$isAdmin)
  #set($msg = "仅管理员可以创建分类。")
#end
#if($msg != "")
{{info}}$msg{{/info}}
#end
#if($isAdmin)
{{html clean="false"}}
<form method="post" action="$doc.getURL('view')">
<input type="hidden" name="action" value="create"/>
<input type="hidden" name="form_token" value="$services.csrf.getToken()"/>
<p><label>分类代号 <input type="text" name="key" maxlength="40" placeholder="如 BOP-ACTIVE"/></label></p>
<p><label>显示名称 <input type="text" name="label" maxlength="60"/></label></p>
<p><label>分类说明 <input type="text" name="description" maxlength="200"/></label></p>
<p><label>分类负责人 <input type="text" name="owner" maxlength="60" value="XWiki.Admin"/></label></p>
<p><button type="submit" class="btn btn-primary">创建分类</button></p>
</form>
{{/html}}
#end
{{/velocity}}

== 当前分类 ==

{{velocity}}
#set($cats = $services.query.xwql("from doc.object(LuxPedia.Code.CategoryClass) as c where doc.name = 'WebHome' and doc.space like 'Knowledge.%' order by doc.title").execute())
#foreach($item in $cats)
* [[$xwiki.getDocument($item).title>>$item]]
#end
{{/velocity}}
"""
MAIN_HOME = """= LuxPedia 企业知识库 =

欢迎使用 LuxPedia。当前已启用文章分类、四类角色权限、富文本/源码编辑、附件、图片、视频和图表。

* [[Knowledge 知识库>>Knowledge.WebHome]]
* 主分类：[[BOP-ACTIVE>>Knowledge.BOP-ACTIVE.WebHome]] · [[BOP-PASSIVE>>Knowledge.BOP-PASSIVE.WebHome]] · [[技术文档>>Knowledge.Technical.WebHome]] · [[操作手册>>Knowledge.Operations.WebHome]] · [[故障处理>>Knowledge.Faults.WebHome]] · [[归档>>Knowledge.Archive.WebHome]]
* [[分类索引>>Knowledge.CategoryIndex]]
* [[Drafts 草稿区>>Drafts.WebHome]]
* [[文档库>>Knowledge.DocumentLibrary]]
* [[多媒体与图表示例>>Knowledge.Technical.MacroShowcase]]
"""

SAMPLE_ARTICLE = ARTICLE_META + """= 示例技术文档 =

{{toc/}}

这是一篇 Sprint 1 示例文章，用于验证分类、元数据、历史版本和权限。

== 图片 ==

[[image:architecture.svg]]

== 视频 ==

{{embed attachment="sample.mp4" /}}

== 图表 ==

{{chartjs type="bar"}}
{"labels":["Q1","Q2","Q3","Q4"],"datasets":[{"label":"示例数据","data":[12,19,8,15],"backgroundColor":"#2f6fed"}]}
{{/chartjs}}

== 说明 ==

正文支持 CKEditor 富文本编辑和 Wiki 源码编辑。附件、图片、视频和图表均在查看模式直接显示。
"""

MACRO_SHOWCASE = ARTICLE_META + """= 多媒体与图表示例 =

本页演示正文中的图片、视频和图表。

== 图片 ==

[[image:architecture.svg]]

== 视频 ==

{{embed attachment="sample.mp4" /}}

== 图表 ==

{{chartjs type="line"}}
{"labels":["1月","2月","3月","4月"],"datasets":[{"label":"访问量","data":[120,190,80,150],"borderColor":"#2f6fed","fill":false}]}
{{/chartjs}}
"""


class XWiki:
    def __init__(self, base_url, username, password):
        self.base_url = base_url.rstrip("/")
        self.auth = base64.b64encode(f"{username}:{password}".encode()).decode()
        self.token = None

    def request(self, method, path, data=None, content_type=None, token=None, auth=None):
        headers = {"Authorization": auth or ("Basic " + self.auth)}
        if content_type:
            headers["Content-Type"] = content_type
        if token:
            headers["XWiki-Form-Token"] = token
        if isinstance(data, str):
            data = data.encode("utf-8")
        request = urllib.request.Request(self.base_url + path, data=data, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            return error.code, error.read()

    def form_token(self):
        if self.token:
            return self.token
        status, body = self.request("GET", "/bin/view/Main/")
        match = re.search(rb'data-xwiki-form-token="([^"]+)"', body)
        if not match:
            raise RuntimeError("无法获取 XWiki 表单令牌，请确认管理员账号可登录")
        self.token = match.group(1).decode()
        return self.token

    @staticmethod
    def page_path(space, page):
        space_path = "/spaces/".join(part for part in space.split("/") if part)
        return f"/rest/wikis/xwiki/spaces/{space_path}/pages/{page}"

    def page_exists(self, space, page):
        status, _ = self.request("GET", self.page_path(space, page))
        return status == 200

    def ensure_page(self, space, page, title, content, hidden=False, auth=None, update=False):
        if self.page_exists(space, page) and not update:
            log(f"page exists: {space}.{page}")
            return False
        xml = (
            '<page xmlns="http://www.xwiki.org">'
            f"<title>{escape(title)}</title><syntax>xwiki/2.1</syntax>"
            f"<hidden>{'true' if hidden else 'false'}</hidden>"
            f"<content>{escape(content)}</content></page>"
        )
        status, body = self.request(
            "PUT", self.page_path(space, page), xml, "application/xml", auth=auth
        )
        if status not in (201, 202):
            raise RuntimeError(f"创建页面失败 {space}.{page}: {status} {body[:300]}")
        log(f"page {'updated' if update else 'created'}: {space}.{page}")
        return True

    def objects(self, space, page, class_name):
        status, body = self.request("GET", self.page_path(space, page) + "/objects")
        if status != 200:
            return []
        root = ET.fromstring(body)
        result = []
        for summary in root.findall(NS + "objectSummary"):
            if summary.findtext(NS + "className") == class_name:
                number = int(summary.findtext(NS + "number"))
                result.append(number)
        return sorted(result)

    def object_values(self, space, page, class_name, number):
        path = self.page_path(space, page) + f"/objects/{class_name}/{number}/properties"
        status, body = self.request("GET", path)
        if status != 200:
            return {}
        root = ET.fromstring(body)
        values = {}
        for prop in root.findall(NS + "property"):
            name = prop.get("name")
            value = prop.findtext(NS + "value")
            if value is None:
                value = ""
            values[name] = value
        return values

    def has_object(self, space, page, class_name):
        return bool(self.objects(space, page, class_name))

    def add_object(self, space, page, class_name, properties):
        fields = {"className": class_name}
        for name, value in properties.items():
            fields["property#" + name] = value
        data = urllib.parse.urlencode(fields)
        status, body = self.request(
            "POST",
            self.page_path(space, page) + "/objects",
            data,
            "application/x-www-form-urlencoded",
            token=self.form_token(),
        )
        if status != 201:
            raise RuntimeError(f"创建对象失败 {space}.{page} {class_name}: {status} {body[:300]}")
        log(f"object created: {space}.{page} {class_name}")
        return True

    def set_object_property(self, space, page, class_name, number, name, value):
        path = self.page_path(space, page) + f"/objects/{class_name}/{number}/properties/{name}"
        status, body = self.request(
            "PUT", path, value, "text/plain", token=self.form_token()
        )
        if status not in (200, 202, 204):
            raise RuntimeError(
                f"更新对象属性失败 {space}.{page} {class_name}.{name}: {status} {body[:300]}"
            )

    def ensure_object_values(self, space, page, class_name, properties):
        numbers = self.objects(space, page, class_name)
        if not numbers:
            return self.add_object(space, page, class_name, properties)
        number = numbers[0]
        current = self.object_values(space, page, class_name, number)
        changed = False
        for name, value in properties.items():
            if current.get(name, "") != value:
                self.set_object_property(space, page, class_name, number, name, value)
                changed = True
        if changed:
            log(f"object updated: {space}.{page} {class_name}")
        return changed

    def ensure_group_member(self, group, member):
        group_page = group.split(".")[-1]
        for number in self.objects("XWiki", group_page, "XWiki.XWikiGroups"):
            if self.object_values("XWiki", group_page, "XWiki.XWikiGroups", number).get("member") == member:
                return False
        return self.add_object("XWiki", group_page, "XWiki.XWikiGroups", {"member": member})

    def attachment_exists(self, space, page, name):
        path = self.page_path(space, page) + "/attachments/" + urllib.parse.quote(name)
        status, _ = self.request("GET", path)
        return status == 200

    def ensure_attachment(self, space, page, name):
        if self.attachment_exists(space, page, name):
            log(f"attachment exists: {space}.{page}@{name}")
            return False
        source = ROOT / "examples" / "attachments" / name
        if not source.is_file():
            raise RuntimeError(f"附件源文件不存在: {source}")
        content_type = mimetypes.guess_type(name)[0] or "application/octet-stream"
        path = self.page_path(space, page) + "/attachments/" + urllib.parse.quote(name)
        status, body = self.request("PUT", path, source.read_bytes(), content_type)
        if status not in (201, 202):
            raise RuntimeError(f"上传附件失败 {space}.{page}@{name}: {status} {body[:300]}")
        log(f"attachment uploaded: {space}.{page}@{name}")
        return True

    def ensure_right(self, space, page, properties):
        for number in self.objects(space, page, "XWiki.XWikiGlobalRights"):
            values = self.object_values(space, page, "XWiki.XWikiGlobalRights", number)
            if all(values.get(key, "") == value for key, value in properties.items()):
                return False
        return self.add_object(space, page, "XWiki.XWikiGlobalRights", properties)

    def class_exists(self, class_name):
        status, body = self.request("GET", f"/rest/wikis/xwiki/classes/{class_name}/properties")
        return status == 200 and b"<property " in body

    def ensure_class_bootstrap(self, superadmin):
        if self.class_exists("LuxPedia.Code.ArticleClass") and self.class_exists("LuxPedia.Code.CategoryClass"):
            log("classes exist: ArticleClass, CategoryClass")
            return
        if not superadmin:
            raise RuntimeError(
                "缺少 ArticleClass/CategoryClass，且未提供 XWIKI_SUPERADMIN_USER/PASSWORD 用于创建类"
            )
        username, password = superadmin
        code = r'''{{groovy}}
def addClass = { String name, Closure body ->
  def doc = xwiki.getDocument(name)
  def xdoc = doc.getDocument()
  def cls = xdoc.getXClass()
  body(cls)
  doc.save("Bootstrap Sprint 1 classes", true)
}
addClass("LuxPedia.Code.ArticleClass") { cls ->
  if (cls.get("title") == null) cls.addTextField("title", "标题", 120)
  if (cls.get("primaryCategory") == null) cls.addTextField("primaryCategory", "主分类", 60)
  if (cls.get("tagsText") == null) cls.addTextField("tagsText", "标签", 120)
  if (cls.get("owner") == null) cls.addTextField("owner", "负责人", 60)
  if (cls.get("status") == null) cls.addStaticListField("status", "状态", "draft|submitted|approved|rejected|published")
  if (cls.get("summary") == null) cls.addTextAreaField("summary", "摘要", 80, 5)
  if (cls.get("applicableVersion") == null) cls.addTextField("applicableVersion", "适用版本", 60)
  if (cls.get("effectiveDate") == null) cls.addDateField("effectiveDate", "生效日期")
  if (cls.get("relatedArticles") == null) cls.addTextAreaField("relatedArticles", "相关文章", 80, 3)
  if (cls.get("approvedRevision") == null) cls.addTextField("approvedRevision", "已批准版本", 20)
  if (cls.get("approvedBy") == null) cls.addTextField("approvedBy", "批准人", 60)
  if (cls.get("approvedAt") == null) cls.addDateField("approvedAt", "批准时间")
}
addClass("LuxPedia.Code.CategoryClass") { cls ->
  if (cls.get("name") == null) cls.addTextField("name", "分类名称", 60)
  if (cls.get("parent") == null) cls.addTextField("parent", "上级分类", 60)
  if (cls.get("owner") == null) cls.addTextField("owner", "分类负责人", 60)
  if (cls.get("template") == null) cls.addTextField("template", "默认模板", 120)
  if (cls.get("description") == null) cls.addTextAreaField("description", "分类描述", 80, 5)
  if (cls.get("sortOrder") == null) cls.addTextField("sortOrder", "排序", 20)
}
println "classes ready"
{{/groovy}}
'''
        super_auth = "Basic " + base64.b64encode(f"{username}:{password}".encode()).decode()
        self.ensure_page(
            "LuxPedia/Code",
            "BootstrapSprint1",
            "Sprint 1 Bootstrap",
            code,
            hidden=True,
            auth=super_auth,
            update=True,
        )
        status, body = self.request(
            "GET", "/bin/view/LuxPedia/Code/BootstrapSprint1?xpage=plain", auth=super_auth
        )
        if status != 200 or b"classes ready" not in body:
            raise RuntimeError(f"执行类初始化失败: {status} {body[:500]}")
        log("classes created via Groovy bootstrap")


def log(message):
    print(message, flush=True)


def load_admin():
    username_file = SECRETS / "admin_username"
    password_file = SECRETS / "admin_password"
    if not username_file.exists() or not password_file.exists():
        raise RuntimeError("缺少 .secrets/admin_username 或 .secrets/admin_password")
    return username_file.read_text().strip(), password_file.read_text().strip()


def load_or_create_password(username):
    path = SECRETS / f"{username}_password"
    if path.exists():
        return path.read_text().strip(), False
    password = secrets.token_urlsafe(24)
    path.write_text(password + "\n")
    path.chmod(0o600)
    return password, True


def ensure_user(wiki, username, first_name, last_name, group):
    space, page = "XWiki", username
    wiki.ensure_page(space, page, username, '{{include document="XWiki.XWikiUserSheet"/}}')
    created = False
    if not wiki.has_object(space, page, "XWiki.XWikiUsers"):
        password, created = load_or_create_password(username)
        wiki.add_object(
            space,
            page,
            "XWiki.XWikiUsers",
            {
                "first_name": first_name,
                "last_name": last_name,
                "password": password,
                "email": f"{username}@example.invalid",
            },
        )
        log(f"user created: {username}")
    wiki.ensure_group_member("XWikiAllGroup", f"XWiki.{username}")
    wiki.ensure_group_member(group, f"XWiki.{username}")


def ensure_group(wiki, key):
    group, label = GROUPS[key]
    wiki.ensure_page("XWiki", group.split(".")[-1], label, "")
    return group


def ensure_category(wiki, key, label, description):
    space = "Knowledge/" + key
    content = "= " + label + " =\n\n" + description + "\n\n" + ARTICLE_TABLE.replace("__SPACE__", "Knowledge." + key)
    wiki.ensure_page(space, "WebHome", label, content)
    wiki.ensure_object_values(
        space,
        "WebHome",
        "LuxPedia.Code.CategoryClass",
        {
            "name": label,
            "parent": "Knowledge",
            "owner": "XWiki.Admin",
            "template": "LuxPedia.Templates.Article",
            "description": description,
            "sortOrder": str(len(description)),
        },
    )


def ensure_article(wiki, space, page, title, category, status, content):
    wiki.ensure_page(space, page, title, content)
    wiki.ensure_object_values(
        space,
        page,
        "LuxPedia.Code.ArticleClass",
        {
            "title": title,
            "primaryCategory": category,
            "tagsText": category + ", 示例",
            "owner": "XWiki.Admin",
            "status": status,
            "summary": f"{title}（Sprint 1 示例）",
            "applicableVersion": "1.0",
        },
    )
    wiki.ensure_object_values(space, page, "XWiki.TagClass", {"tags": category + "|示例"})


def apply(wiki, superadmin):
    log("== groups")
    for key in GROUPS:
        ensure_group(wiki, key)
    wiki.ensure_group_member("XWikiAllGroup", "XWiki.Admin")
    wiki.ensure_group_member("XWiki.LuxManagers", "XWiki.Admin")
    if wiki.page_exists("XWiki", "whiteshiro"):
        wiki.ensure_group_member("XWiki.LuxManagers", "XWiki.whiteshiro")

    log("== users")
    for username, first_name, last_name, group_key in TEST_USERS:
        ensure_user(wiki, username, first_name, last_name, GROUPS[group_key][0])

    log("== classes")
    wiki.ensure_class_bootstrap(superadmin)

    log("== pages")
    wiki.ensure_page("Main", "WebHome", "LuxPedia", MAIN_HOME)
    wiki.ensure_page(
        "LuxPedia/Code",
        "SourceEditUIX",
        "Source Edit Menu",
        "Source edit menu UI extension.",
        hidden=True,
    )
    wiki.ensure_object_values(
        "LuxPedia/Code",
        "SourceEditUIX",
        "XWiki.UIExtensionClass",
        {
            "extensionPointId": "org.xwiki.plaftorm.menu.content",
            "name": "luxpedia.source.edit",
            "scope": "wiki",
            "parameters": "order=15000",
            "content": SOURCE_EDIT_UIX,
        },
    )
    wiki.ensure_page("Knowledge", "WebHome", "知识库", KNOWLEDGE_HOME)
    wiki.ensure_page("Knowledge", "CategoryGuide", "分类说明", CATEGORY_GUIDE)
    wiki.ensure_page("Knowledge", "CategoryIndex", "分类索引", CATEGORY_INDEX)
    wiki.ensure_page("LuxPedia/Code", "CategoryHomeTemplate", "分类首页模板", CATEGORY_HOME_TEMPLATE, hidden=True)
    wiki.ensure_page("Knowledge", "CategoryAdmin", "分类管理", CATEGORY_ADMIN)
    for key, label, description in CATEGORIES:
        ensure_category(wiki, key, label, description)

    wiki.ensure_page(
        "Knowledge",
        "DocumentLibrary",
        "文档库",
        "= 文档库 =\n\n"
        "{{velocity}}\n"
        '#set($q = "from doc.object(LuxPedia.Code.ArticleClass) as article '
        "where doc.space like 'Knowledge.%' order by doc.date desc\")\n"
        "#set($results = $services.query.xwql($q).execute())\n"
        "|=标题|=主分类|=状态|=负责人\n"
        "#foreach($item in $results)\n"
        '  #set($d = $xwiki.getDocument($item))\n'
        '  #set($o = $d.getObject("LuxPedia.Code.ArticleClass"))\n'
        "  #if($o)\n"
        '    |=[[$d.title>>$d.fullName]]|$o.getProperty("primaryCategory").value'
        '|$o.getProperty("status").value|$o.getProperty("owner").value\n'
        "  #end\n"
        "#end\n"
        "{{/velocity}}\n",
    )

    wiki.ensure_page(
        "Drafts",
        "WebHome",
        "Drafts",
        "= Drafts 草稿区 =\n\n"
        "编辑者在此创建和维护草稿，提交审核后由审核人处理。\n\n"
        + ARTICLE_TABLE.replace("__SPACE__", "Drafts")
        + "\n",
    )

    wiki.ensure_page(
        "LuxPedia/Templates",
        "Article",
        "文章模板",
        ARTICLE_META
        + "= 文章标题 =\n\n"
        "**主分类：** 技术文档\n"
        "**负责人：** 填写负责人\n"
        "**状态：** draft\n"
        "**适用版本：** 填写版本\n\n"
        "== 目的与范围 ==\n\n"
        "== 前置条件 ==\n\n"
        "== 操作步骤 ==\n\n"
        "== 验证与回退 ==\n\n"
        "== 参考与附件 ==\n",
    )

    log("== sample articles")
    ensure_article(
        wiki,
        "Knowledge/Technical",
        "SampleArticle",
        "示例技术文档",
        "技术文档",
        "published",
        SAMPLE_ARTICLE,
    )
    ensure_article(
        wiki,
        "Knowledge/Technical",
        "MacroShowcase",
        "多媒体与图表示例",
        "技术文档",
        "published",
        MACRO_SHOWCASE,
    )
    ensure_article(
        wiki,
        "Drafts",
        "SampleArticle",
        "示例草稿",
        "技术文档",
        "draft",
        "= 示例草稿 =\n\n"
        "这是一篇 Drafts 示例草稿，编辑者可修改并提交审核。\n",
    )
    for name in ("architecture.svg", "sample.mp4"):
        wiki.ensure_attachment("Knowledge/Technical", "SampleArticle", name)
        wiki.ensure_attachment("Knowledge/Technical", "MacroShowcase", name)

    log("== editor and global guest rights")
    wiki.ensure_object_values("XWiki", "XWikiPreferences", "XWiki.XWikiPreferences", {"editor": "Wysiwyg"})
    wiki.ensure_right(
        "XWiki",
        "XWikiPreferences",
        {"users": "XWiki.XWikiGuest", "levels": "edit,comment,delete,register", "allow": "0"},
    )

    log("== space rights")
    wiki.ensure_page("Knowledge", "WebPreferences", "WebPreferences", "", hidden=True)
    wiki.ensure_page("Drafts", "WebPreferences", "WebPreferences", "", hidden=True)

    knowledge = [
        ({"groups": "XWiki.LuxManagers", "levels": "view,comment,edit,delete", "allow": "1"}),
        ({"groups": "XWiki.LuxEditors", "levels": "view,comment", "allow": "1"}),
        ({"groups": "XWiki.LuxEditors", "levels": "edit,delete", "allow": "0"}),
        ({"groups": "XWiki.LuxReviewers", "levels": "view,comment", "allow": "1"}),
        ({"groups": "XWiki.LuxReviewers", "levels": "edit,delete", "allow": "0"}),
        ({"groups": "XWiki.LuxReaders", "levels": "view,comment", "allow": "1"}),
        ({"groups": "XWiki.LuxReaders", "levels": "edit,delete", "allow": "0"}),
        ({"users": "XWiki.XWikiGuest", "levels": "view,comment,edit,delete", "allow": "0"}),
    ]
    drafts = [
        ({"groups": "XWiki.LuxManagers", "levels": "view,comment,edit,delete", "allow": "1"}),
        ({"groups": "XWiki.LuxEditors", "levels": "view,comment,edit", "allow": "1"}),
        ({"groups": "XWiki.LuxEditors", "levels": "delete", "allow": "0"}),
        ({"groups": "XWiki.LuxReviewers", "levels": "view,comment", "allow": "1"}),
        ({"groups": "XWiki.LuxReviewers", "levels": "edit,delete", "allow": "0"}),
        ({"groups": "XWiki.LuxReaders", "levels": "view,comment,edit,delete", "allow": "0"}),
        ({"users": "XWiki.XWikiGuest", "levels": "view,comment,edit,delete", "allow": "0"}),
    ]
    for rights in knowledge:
        wiki.ensure_right("Knowledge", "WebPreferences", rights)
    for rights in drafts:
        wiki.ensure_right("Drafts", "WebPreferences", rights)

    wiki.ensure_right(
        "XWiki",
        "XWikiPreferences",
        {"groups": "XWiki.LuxManagers", "levels": "admin", "allow": "1"},
    )

    log("== done")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="实际写入 XWiki；默认只检查")
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    args = parser.parse_args()

    username, password = load_admin()
    wiki = XWiki(args.base_url, username, password)
    status, _ = wiki.request("GET", "/rest/wikis/xwiki")
    if status != 200:
        raise SystemExit(f"无法连接 XWiki REST: {args.base_url} ({status})")

    if not args.apply:
        log("只检查模式。加 --apply 才会写入。")
        log(f"目标: {args.base_url}")
        return

    superadmin = None
    super_user = os.environ.get("XWIKI_SUPERADMIN_USER")
    super_password = os.environ.get("XWIKI_SUPERADMIN_PASSWORD")
    if super_user and super_password:
        superadmin = (super_user, super_password)

    apply(wiki, superadmin)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
