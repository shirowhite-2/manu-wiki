#!/usr/bin/env python3
"""Install the unified image, video and file insertion bar for CKEditor.

Keeps the legacy VideoInsertScript extension identity for idempotent upgrades.
Uses native relative image/link references and the native Embed command so publication can
copy attachments without leaving readers dependent on Drafts download URLs.
Reuses apply_sprint1.XWiki. Default is a dry run; pass --apply.
"""
import argparse
import importlib.util
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from interface_assets import I18N_JS
SPACE = "LuxPedia/Code"
PAGE = "VideoInsertScript"

# Keep the installed extension identity so upgrades replace the old video-only bar.
VIDEO_INSERT_JS = I18N_JS + (ROOT / 'scripts/ui/attachments.js').read_text()


VIDEO_PROPERTIES = {
    "name": "luxpedia.videoInsert",
    "code": VIDEO_INSERT_JS,
    "use": "always",
    "parse": "0",
    "cache": "long",
}


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def apply(module, wiki):
    module.log("== image, video and file insertion script")
    wiki.ensure_page(SPACE, PAGE, "Video insert script", "Insert images, videos and attachment links using native XWiki references.", hidden=True)
    wiki.ensure_object_values(SPACE, PAGE, "XWiki.JavaScriptExtension", VIDEO_PROPERTIES)


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
