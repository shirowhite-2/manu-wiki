#!/usr/bin/env python3
"""Add the LuxPedia editing guide and a content-menu entry to reach it.

Reuses the REST helper from apply_sprint1.py. Idempotent: existing pages and
UI extension objects are only updated when their content differs.
Default is a dry run; pass --apply to write to XWiki.
"""
import argparse
import importlib.util
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GUIDE_SPACE = "LuxPedia/Help"
GUIDE_PAGE = "EditGuide"
MENU_SPACE = "LuxPedia/Code"
MENU_PAGE = "EditGuideUIX"

GUIDE_CONTENT = """= 编辑指南 =

本页说明编辑、附件、视频和保存的操作。不同角色看到的按钮不同；按钮位置以页面实际显示为准。

== 1. 谁可以编辑 ==

* **编辑者**：可以在草稿区（Drafts）创建和修改页面，并使用源码编辑。
* **审核人**：可以查看草稿并审核，不能修改正文。
* **读者**：只能阅读正式内容，不能编辑。

如果看不到编辑入口，通常是权限不足，请联系管理员，刷新页面无法解决。

== 2. 编辑与保存 ==

1. 打开草稿页面，点击页面上的编辑按钮。
2. 在富文本编辑器中修改内容。
3. 点击 **Done** 保存。每次保存都会生成新版本，可在页面历史中查看或恢复。
4. 需要直接编写 XWiki 语法时，在内容菜单中选择“源码编辑”（只有编辑者可见）。

== 3. 上传附件 ==

附件用于存放图片、PDF、Word 和视频。

1. 在编辑页面找到附件面板（Attachments）。
2. 选择文件并上传。
3. 目前单个文件上限为 **32 MB**，超过上限时上传会被拦截。管理员可在管理界面的“附件限制”分区修改上传上限（单位为字节）。

== 4. 插入视频 ==

视频必须先作为附件上传，然后在正文中嵌入。

1. 在需要显示视频的位置插入 Embed 宏，或在源码编辑中写入：

{{code language="none"}}
{{embed attachment="文件名.mp4" /}}
{{/code}}

2. 文件名必须与附件名完全一致。
3. 保存后切换到阅读模式，确认视频可以播放。

== 5. 审核与发布 ==

编辑完成后由编辑者提交审核，审核人检查后发布。提交和发布的按钮位置以页面实际显示为准。当前审核流程还没有审批意见、撤回和邮件通知。

== 6. 常见问题 ==

* **保存失败**：检查网络和登录状态；保存前先复制正文，避免丢失。
* **视频不播放**：检查附件名是否一致、格式是否为 MP4，并换一个浏览器试试。
* **上传失败**：检查文件是否超过 32 MB。

反馈问题时请提供页面地址、操作步骤和错误信息，不要发送密码或密钥。

[[返回 LuxPedia 首页>>Main.WebHome]]
"""

MENU_CONTENT = """{{velocity}}
{{html clean="false"}}
#if ($doc.hasAccessLevel('view'))
  <li class="btn-group" id="tmEditGuide">
    <a class="btn btn-default" href="$xwiki.getURL('LuxPedia.Help.EditGuide')" title="编辑指南" rel="nofollow">
      <span class="btn-label">编辑指南</span>
    </a>
  </li>
#end
{{/html}}
{{/velocity}}"""


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def current_guide_content(module, wiki):
    status, body = wiki.request("GET", wiki.page_path(GUIDE_SPACE, GUIDE_PAGE))
    if status != 200:
        return None
    root = ET.fromstring(body)
    node = root.find("{http://www.xwiki.org}content")
    return node.text if node is not None else None


def apply(module, wiki):
    module.log("== editing guide page")
    if current_guide_content(module, wiki) != GUIDE_CONTENT:
        wiki.ensure_page(GUIDE_SPACE, GUIDE_PAGE, "编辑指南", GUIDE_CONTENT, update=True)
    else:
        module.log("page unchanged: LuxPedia.Help.EditGuide")
    module.log("== content menu entry")
    wiki.ensure_page(MENU_SPACE, MENU_PAGE, "Edit Guide Menu", "Edit guide menu UI extension.", hidden=True)
    wiki.ensure_object_values(
        MENU_SPACE,
        MENU_PAGE,
        "XWiki.UIExtensionClass",
        {
            "extensionPointId": "org.xwiki.plaftorm.menu.content",
            "name": "luxpedia.edit.guide",
            "scope": "wiki",
            "parameters": "order=15001",
            "content": MENU_CONTENT,
        },
    )


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write to XWiki; default is a dry run")
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    args = parser.parse_args()

    module = load_apply_module()
    username, password = module.load_admin()
    wiki = module.XWiki(args.base_url, username, password)
    status, _ = wiki.request("GET", "/rest/wikis/xwiki")
    if status != 200:
        raise SystemExit(f"无法连接 XWiki REST: {args.base_url} ({status})")

    if not args.apply:
        module.log("dry run: pass --apply to write")
        module.log(f"target: {args.base_url}")
        return

    apply(module, wiki)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
