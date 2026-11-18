#!/usr/bin/env python3
"""Add short guidance boxes to the main LuxPedia pages.

Each box is prepended once, guarded by a marker comment, so repeated runs do not
duplicate it. Existing page body text is kept. Reuses apply_sprint1.XWiki.
Default is a dry run; pass --apply to write.
"""
import argparse
import importlib.util
import os
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NS = "{http://www.xwiki.org}"
MARKER = '{{velocity output="false"}}## luxpedia-guidance{{/velocity}}'
OLD_MARKER = "<!-- luxpedia-guidance -->"  # first version; rendered as visible text, removed on upgrade

GUIDANCE = {
    ("Drafts", "WebHome"): """{{info}}
**草稿区**：这里是编辑中的内容，只有编辑者可以创建和修改，审核人可以查看和审核。读者看不到草稿。

* 编辑完成后用 **Done** 保存，再按页面上的工作流提交审核（按钮位置以页面实际显示为准）。
* 看不到编辑入口，通常是账号权限不足，请联系管理员。
* 操作步骤见 [[编辑指南>>LuxPedia.Help.EditGuide]]。
{{/info}}""",
    ("Knowledge", "WebHome"): """{{info}}
**知识库**：这里是已发布的正式内容。要修改已发布文章，请先在草稿区创建修改稿，审核通过后再发布。
{{/info}}""",
    ("Knowledge", "DocumentLibrary"): """{{info}}
**文档库**：汇总所有已创建的文章，列表只显示你有阅读权限的内容。要修改文章，请到对应的草稿区操作。
{{/info}}""",
    ("Knowledge", "CategoryIndex"): """{{info}}
**分类索引**：显示每个主分类下的文章数量。新增分类只能由管理员完成，入口在分类管理页面。
{{/info}}""",
    ("Knowledge", "CategoryAdmin"): """{{info}}
**仅管理员可见**：创建分类、设置负责人。

附件上传上限（默认 32 MB）可在管理界面的“附件限制”分区修改，地址为 /bin/admin/XWiki/XWikiPreferences?section=attachmentsrestriction ，单位为字节。修改后请用接近上限的文件测试上传。
{{/info}}""",
}


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def read_page(module, wiki, space, page):
    status, body = wiki.request("GET", wiki.page_path(space, page))
    if status != 200:
        return None, None
    root = ET.fromstring(body)
    title = root.findtext(NS + "title") or page
    content = root.findtext(NS + "content") or ""
    return title, content


def apply(module, wiki):
    for (space, page), box in GUIDANCE.items():
        title, content = read_page(module, wiki, space, page)
        if title is None:
            module.log(f"skip (page missing): {space}.{page}")
            continue
        if content.startswith(MARKER + "\n" + box + "\n\n"):
            module.log(f"unchanged: {space}.{page}")
            continue
        body = content
        if body.startswith(MARKER):
            # replace the previously written box (everything up to its closing {{/info}})
            end = body.index("{{/info}}") + len("{{/info}}")
            body = body[end:].lstrip("\n")
        elif body.startswith(OLD_MARKER):
            body = body[len(OLD_MARKER):].lstrip("\n")
        wiki.ensure_page(space, page, title, f"{MARKER}\n{box}\n\n{body}", update=True)


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
        for space, page in GUIDANCE:
            module.log(f"would check: {space}.{page}")
        return

    apply(module, wiki)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
