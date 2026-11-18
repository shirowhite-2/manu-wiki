#!/usr/bin/env python3
"""Add a "insert video" bar above the CKEditor on XWiki edit pages.

The bar lists the video attachments of the page being edited and inserts the
same markup XWiki's own macro wizard produces for the Embed macro:
    <!--startmacro:embed|-|attachment="NAME"--><!--stopmacro-->
which becomes {{embed attachment="NAME" /}} when the page is saved.

The script is delivered as an XWiki.JavaScriptExtension (use=always). It only
acts on /bin/edit/... pages that contain a CKEditor instance.
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
SPACE = "LuxPedia/Code"
PAGE = "VideoInsertScript"

VIDEO_INSERT_JS = I18N_JS + r"""(function () {
  var t = window.LuxPediaI18n.t;
  var BAR_ID = 'luxpedia-video-bar';
  var VIDEO = /\.(mp4|mov|avi|webm|ogv|m4v)$/i;

  function currentPage() {
    var m = window.location.pathname.match(/\/bin\/edit\/(.+)$/);
    if (!m) { return null; }
    var parts = m[1].split('/').filter(function (p) { return p.length > 0; });
    if (parts.length < 2) { return null; }
    return { page: parts.pop(), spaces: parts };
  }

  function restPath(info) {
    var segs = [];
    info.spaces.forEach(function (s) { segs.push('spaces/' + encodeURIComponent(s)); });
    return '/rest/wikis/xwiki/' + segs.join('/') + '/pages/' + encodeURIComponent(info.page) + '/attachments';
  }

  function build(editor, info) {
    var bar = document.createElement('div');
    bar.id = BAR_ID;
    bar.setAttribute('style', 'margin:8px 0;padding:8px 10px;background:#f5f7fa;border:1px solid #d9dee7;border-radius:4px;font-size:14px;line-height:2;');

    var label = document.createElement('strong');
    label.textContent = t('insertVideo');
    label.dataset.luxpediaI18n = 'insertVideo';

    var select = document.createElement('select');
    select.id = 'luxpedia-video-select';
    select.setAttribute('style', 'min-width:16em;margin:0 6px;');
    var placeholder = document.createElement('option');
    placeholder.value = '';
    placeholder.textContent = t('loadingVideos');
    placeholder.dataset.luxpediaI18n = 'loadingVideos';
    select.appendChild(placeholder);

    var button = document.createElement('button');
    button.type = 'button';
    button.id = 'luxpedia-video-insert';
    button.className = 'btn btn-primary';
    button.textContent = t('insertIntoArticle');
    button.dataset.luxpediaI18n = 'insertIntoArticle';
    button.disabled = true;

    var hint = document.createElement('span');
    hint.setAttribute('style', 'color:#666;margin-left:8px;');
    hint.textContent = t('videoHint');
    hint.dataset.luxpediaI18n = 'videoHint';

    bar.appendChild(label);
    bar.appendChild(select);
    bar.appendChild(button);
    bar.appendChild(hint);
    editor.container.$.parentNode.insertBefore(bar, editor.container.$);

    fetch(restPath(info), { credentials: 'same-origin', headers: { 'Accept': 'application/json' } })
      .then(function (r) { return r.ok ? r.json() : Promise.reject(r.status); })
      .then(function (data) {
        var names = (data.attachments || []).map(function (a) { return a.name; })
          .filter(function (n) { return VIDEO.test(n); });
        select.innerHTML = '';
        if (names.length === 0) {
          var none = document.createElement('option');
          none.value = '';
          none.textContent = t('noVideos');
    none.dataset.luxpediaI18n = 'noVideos';
          select.appendChild(none);
          return;
        }
        names.forEach(function (n) {
          var o = document.createElement('option');
          o.value = n;
          o.textContent = n;
          select.appendChild(o);
        });
        button.disabled = false;
      })
      .catch(function () {
        select.innerHTML = '';
        var err = document.createElement('option');
        err.value = '';
        err.textContent = t('attachmentError');
    err.dataset.luxpediaI18n = 'attachmentError';
        select.appendChild(err);
      });

    button.addEventListener('click', function () {
      var name = select.value;
      if (!name) { return; }
      var safe = name.replace(/["<>]/g, '');
      editor.focus();
      // Embed is a standalone macro. Insert it in a fresh empty paragraph (never inside text),
      // followed by a visible caption with the file name.
      var empty = new CKEDITOR.dom.element('p', editor.document);
      empty.setHtml('<br>');
      var selection = editor.getSelection();
      var start = selection && selection.getStartElement();
      var block = start && start.getAscendant ? start.getAscendant({ p: 1, h1: 1, h2: 1, h3: 1, h4: 1, h5: 1, h6: 1, li: 1, div: 1 }, true) : null;
      if (block && block.getName && block.getName() !== 'body') {
        empty.insertAfter(block);
      } else {
        editor.insertElement(empty);
      }
      var range = editor.createRange();
      range.moveToElementEditStart(empty);
      range.select();
      editor.insertHtml('<p><!--startmacro:embed|-|attachment="' + safe + '"--><!--stopmacro--></p><p>（' + name + '）</p>');
    });
  }

  var attempts = 0;
  var timer = setInterval(function () {
    attempts += 1;
    if (attempts > 60) { clearInterval(timer); return; }
    if (typeof CKEDITOR === 'undefined' || document.getElementById(BAR_ID)) { return; }
    var names = Object.keys(CKEDITOR.instances);
    var info = currentPage();
    if (names.length === 0 || !info) { return; }
    var editor = CKEDITOR.instances[names[0]];
    clearInterval(timer);
    if (editor.status === 'ready') {
      build(editor, info);
    } else {
      editor.on('instanceReady', function () { build(editor, info); });
    }
  }, 500);
})();
"""

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
    module.log("== video insert script")
    wiki.ensure_page(SPACE, PAGE, "Video insert script", "Client-side video insert bar for the CKEditor.", hidden=True)
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
