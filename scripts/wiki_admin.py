#!/usr/bin/env python3
"""Administrator CLI for LuxPedia: add categories and user accounts.

Reads the admin account from .secrets/admin_username and .secrets/admin_password.
Every command is idempotent: existing categories and users are reported, not changed.

Examples:
  python3 scripts/wiki_admin.py list-categories
  python3 scripts/wiki_admin.py add-category BOP-NEW "BOP-NEW" --description "说明" --owner XWiki.Admin
  python3 scripts/wiki_admin.py add-admin zhangsan --first 三 --last 张
  python3 scripts/wiki_admin.py add-user lisi --group Editors --first 四 --last 李
"""

import argparse
import importlib.util
import os
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CATEGORY_KEY = re.compile(r"^[A-Za-z0-9][A-Za-z0-9-]{0,39}$")


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def connect(module):
    username, password = module.load_admin()
    wiki = module.XWiki(os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"), username, password)
    status, _ = wiki.request("GET", "/rest/wikis/xwiki")
    if status != 200:
        raise SystemExit(f"无法连接 XWiki: {wiki.base_url} ({status})")
    return wiki


def cmd_list_categories(module, wiki, args):
    status, body = wiki.request("GET", "/bin/view/Knowledge/CategoryIndex?xpage=plain")
    if status != 200:
        raise SystemExit(f"读取分类索引失败: {status}")
    keys = []
    for key in re.findall(rb"/bin/view/Knowledge/([A-Za-z0-9-]+)/", body):
        name = key.decode()
        if name not in keys:
            keys.append(name)
    if not keys:
        print("(无分类)")
    for name in keys:
        print(name)


def cmd_add_category(module, wiki, args):
    key = args.key.strip()
    label = args.label.strip()
    if not CATEGORY_KEY.match(key):
        raise SystemExit("分类代号只允许 1-40 位字母、数字或连字符")
    if not label:
        raise SystemExit("分类名称不能为空")
    space = f"Knowledge/{key}"
    content = (
        "= " + label + " =\n\n" + (args.description or "") + "\n\n"
        '{{include document="LuxPedia.Code.CategoryHomeTemplate"/}}\n'
    )
    created = wiki.ensure_page(space, "WebHome", label, content)
    wiki.ensure_object_values(
        space,
        "WebHome",
        "LuxPedia.Code.CategoryClass",
        {
            "name": label,
            "parent": "Knowledge",
            "owner": args.owner,
            "template": "LuxPedia.Templates.Article",
            "description": args.description or "",
            "sortOrder": "0",
        },
    )
    print(("已创建" if created else "已存在，仅同步属性") + f": Knowledge.{key}")


def cmd_add_user(module, wiki, args):
    group_key = {"Managers": "Managers", "Editors": "Editors", "Reviewers": "Reviewers", "Readers": "Readers"}[args.group]
    if not re.match(r"^[A-Za-z][A-Za-z0-9_.-]{1,39}$", args.username):
        raise SystemExit("用户名需以字母开头，2-40 位，可含字母、数字、下划线、点和连字符")
    existed = wiki.page_exists("XWiki", args.username)
    module.ensure_user(wiki, args.username, args.first, args.last, module.GROUPS[group_key][0])
    secret = ROOT / ".secrets" / f"{args.username}_password"
    if existed:
        print(f"用户 {args.username} 已存在，已确认组：{module.GROUPS[group_key][1]}")
    else:
        print(f"已创建用户 {args.username}，组：{module.GROUPS[group_key][1]}")
        print(f"初始密码保存在 {secret}（权限 600），请通过安全渠道交给用户并要求首次登录后修改。")


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list-categories", help="列出当前主分类")

    add_cat = sub.add_parser("add-category", help="新增主分类（空间 + 分类首页 + CategoryClass）")
    add_cat.add_argument("key", help="分类代号，如 BOP-NEW")
    add_cat.add_argument("label", help="显示名称")
    add_cat.add_argument("--description", default="", help="分类说明")
    add_cat.add_argument("--owner", default="XWiki.Admin", help="分类负责人")

    for name, help_text in (
        ("add-admin", "新增管理员账号（加入 LuxManagers 组，拥有全局管理权限）"),
        ("add-user", "新增普通账号并加入指定角色组"),
    ):
        p = sub.add_parser(name, help=help_text)
        p.add_argument("username")
        p.add_argument("--first", required=True, help="名")
        p.add_argument("--last", required=True, help="姓")
        if name == "add-user":
            p.add_argument("--group", required=True, choices=["Editors", "Reviewers", "Readers", "Managers"])
    return parser


def main():
    args = build_parser().parse_args()
    module = load_apply_module()
    wiki = connect(module)
    if args.command == "list-categories":
        cmd_list_categories(module, wiki, args)
    elif args.command == "add-category":
        cmd_add_category(module, wiki, args)
    elif args.command == "add-admin":
        args.group = "Managers"
        cmd_add_user(module, wiki, args)
    elif args.command == "add-user":
        cmd_add_user(module, wiki, args)


if __name__ == "__main__":
    try:
        main()
    except SystemExit:
        raise
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
