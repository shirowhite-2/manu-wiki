#!/usr/bin/env python3
"""Expose the attachment upload limit in XWiki's administration UI.

XWiki renders admin sections from XWiki.ConfigurableClass objects. This script
adds one such object in the XWiki application (the same pattern used by
XWiki.RegistrationConfig). It points at XWiki.XWikiPreferences and shows only
the upload_maxsize property, so the admin form in
  /bin/admin/XWiki/XWikiPreferences?section=attachmentsrestriction
edits the wiki-wide upload limit (bytes) and nothing else.

Reuses apply_sprint1.XWiki. Idempotent. Default is a dry run; pass --apply.
"""
import argparse
import importlib.util
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from interface_assets import I18N_JS
# The admin form reads its object from the configurable's own document, and
# upload_maxsize is the wiki-wide value on XWiki.XWikiPreferences (used by all
# spaces such as Drafts and Knowledge). Registration therefore lives there.
CONFIG_SPACE = "XWiki"
CONFIG_PAGE = "XWikiPreferences"
WRONG_CONFIG_PAGE = ("XWiki", "WebPreferences")  # earlier placement, removed on upgrade
REMOVE = [("XWiki", "LuxPediaUploadLimit"), ("LuxPedia/Admin", "UploadLimit")]
# Script page that shows the limit in MB and converts it to bytes on submit.
JS_SPACE = "LuxPedia/Code"
JS_PAGE = "UploadLimitMbScript"

CONFIG_PROPERTIES = {
    "categoryIcon": "file-text",
    "configurationClass": "XWiki.XWikiPreferences",
    "displayBeforeCategory": "",
    "displayInCategory": "content",
    "displayInSection": "attachmentsrestriction",
    "heading": "Attachment upload limit (MB)",
    "iconAttachment": "",
    "linkPrefix": "",
    "propertiesToShow": "upload_maxsize",
    "scope": "WIKI",
}


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


UPLOAD_LIMIT_JS = I18N_JS + """(function () {
  var t = window.LuxPediaI18n.t;
  var FIELD = 'XWiki.XWikiPreferences_0_upload_maxsize';
  var MB = 1048576;
  function init() {
    var input = document.querySelector('input[name="' + FIELD + '"]');
    if (!input || input.getAttribute('data-luxpedia-mb') === '1') { return; }
    input.setAttribute('data-luxpedia-mb', '1');
    var form = input.form;
    var current = parseInt(input.value, 10);
    var mbInput = document.createElement('input');
    mbInput.type = 'number';
    mbInput.min = '1';
    mbInput.max = '99999';
    mbInput.step = '1';
    mbInput.className = 'form-control';
    mbInput.style.width = '8em';
    mbInput.style.display = 'inline-block';
    mbInput.value = current > 0 ? String(Math.round(current / MB)) : '32';
    var unit = document.createElement('span');
    unit.textContent = t('uploadUnit');
    unit.dataset.luxpediaI18n = 'uploadUnit';
    input.type = 'hidden';
    input.parentNode.insertBefore(mbInput, input);
    input.parentNode.insertBefore(unit, input.nextSibling);
    function sync() {
      var v = parseInt(mbInput.value, 10);
      if (v >= 1 && v <= 99999) { input.value = String(v * MB); return true; }
      return false;
    }
    mbInput.addEventListener('input', sync);
    if (form) {
      form.addEventListener('submit', function (event) {
        if (!sync()) {
          event.preventDefault();
          window.alert(t('uploadInvalid'));
        }
      });
    }
  }
  document.addEventListener('xwiki:dom:loaded', init);
  document.addEventListener('DOMContentLoaded', init);
})();
"""

JS_PROPERTIES = {
    "name": "luxpedia.uploadLimitMb",
    "code": UPLOAD_LIMIT_JS,
    "use": "always",
    "parse": "0",
    "cache": "long",
}


def remove_superseded(module, wiki):
    for space, page in REMOVE:
        if not wiki.page_exists(space, page):
            continue
        status, body = wiki.request("DELETE", wiki.page_path(space, page))
        if status not in (200, 204):
            raise RuntimeError(f"删除旧页面失败 {space}.{page}: {status} {body[:200]}")
        module.log(f"removed superseded page: {space}.{page}")


def has_config_object(wiki):
    space, page = CONFIG_SPACE, CONFIG_PAGE
    for number in wiki.objects(space, page, "XWiki.ConfigurableClass"):
        values = wiki.object_values(space, page, "XWiki.ConfigurableClass", number)
        if values.get("displayInSection") == CONFIG_PROPERTIES["displayInSection"]:
            return True
    return False


def remove_misplaced_config(module, wiki):
    space, page = WRONG_CONFIG_PAGE
    for number in wiki.objects(space, page, "XWiki.ConfigurableClass"):
        values = wiki.object_values(space, page, "XWiki.ConfigurableClass", number)
        if values.get("displayInSection") == CONFIG_PROPERTIES["displayInSection"]:
            status, body = wiki.request(
                "DELETE", wiki.page_path(space, page) + f"/objects/XWiki.ConfigurableClass/{number}",
                token=wiki.form_token(),
            )
            if status not in (200, 204):
                raise RuntimeError(f"删除错误位置的配置对象失败: {status} {body[:200]}")
            module.log(f"removed misplaced config object: {space}.{page}")


def apply(module, wiki):
    module.log("== upload limit configuration registration")
    remove_superseded(module, wiki)
    remove_misplaced_config(module, wiki)
    if not has_config_object(wiki):
        wiki.add_object(CONFIG_SPACE, CONFIG_PAGE, "XWiki.ConfigurableClass", CONFIG_PROPERTIES)
        module.log(f"object created: {CONFIG_SPACE}.{CONFIG_PAGE} XWiki.ConfigurableClass")
    else:
        module.log("config object present (unchanged)")
    module.log("== MB input script")
    wiki.ensure_page(JS_SPACE, JS_PAGE, "Upload limit MB script", "Client-side MB conversion for the upload limit form.", hidden=True)
    wiki.ensure_object_values(JS_SPACE, JS_PAGE, "XWiki.JavaScriptExtension", JS_PROPERTIES)


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
