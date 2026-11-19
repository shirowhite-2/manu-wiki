(function () {
  'use strict';
  if (window.LuxPediaPresentation) { return; } window.LuxPediaPresentation = true;
  var t = window.LuxPediaI18n.t, prefix = location.pathname.split('/bin/')[0] || '', snapshot, reference = '', requested = false;
  function text(tag, value, className) { var n = document.createElement(tag); n.textContent = value; if (className) { n.className = className; } return n; }
  function label(tag, key, className) { var n = text(tag, t(key), className); n.dataset.luxpediaI18n = key; return n; }
  function pageReference() {
    var value = document.documentElement.getAttribute('data-xwiki-reference') || document.querySelector('meta[name="document"]')?.content;
    if (value) { return value; }
    try { return XWiki.Model.serialize(XWiki.currentDocument.getDocumentReference()); } catch (ignored) { return ''; }
  }
  function safeURL(value, external) {
    try { var url = new URL(value, location.href); return /^https?:$/.test(url.protocol) && !url.username && !url.password && (external || url.origin === location.origin) ? url : null; }
    catch (ignored) { return null; }
  }
  function localized(value, en, zh) { return value[window.LuxPediaI18n.language() === 'zh' ? zh : en] || value[en] || value[zh] || ''; }
  function menuList(items, parent, depth) {
    var ul = document.createElement('ul'); ul.style.paddingLeft = depth ? '1.4em' : '1em';
    items.filter(function (item) { return item.parent === parent; }).forEach(function (item) {
      var li = document.createElement('li'), href = item.href && safeURL(item.href, true), name = localized(item, 'en', 'zh');
      if (href) { var a = text('a', name); a.href = href.href; if (href.origin !== location.origin) { a.target = '_blank'; a.rel = 'noopener noreferrer'; } li.appendChild(a); }
      else { li.appendChild(text('span', name)); }
      if (items.some(function (child) { return child.parent === item.id; })) { li.appendChild(menuList(items, item.id, depth + 1)); }
      ul.appendChild(li);
    }); return ul;
  }
  function render() {
    if (!snapshot) { return; }
    var signature = JSON.stringify(snapshot.menus) + window.LuxPediaI18n.language();
    ['top', 'sidebar'].forEach(function (locationName) {
      var items = snapshot.menus.filter(function (item) { return item.location === locationName; });
      var old = document.getElementById('luxpedia-managed-' + locationName);
      if (old && items.length && old.dataset.signature === signature) { return; }
      if (old) { old.remove(); } if (!items.length) { return; }
      if (locationName === 'top') {
        var nav = document.querySelector('#menuview .navbar-left, #headerglobal .navbar-left');
        if (!nav) { nav = document.querySelector('#menuview .navbar-right, #headerglobal .navbar-right'); }
        if (!nav) { return; }
        var holder = document.createElement(nav.tagName === 'UL' ? 'li' : 'div'); holder.id = 'luxpedia-managed-top'; holder.className = 'dropdown'; holder.dataset.signature = signature;
        var button = label('button', 'navManagedMenu', 'btn btn-link navbar-btn'); button.type = 'button'; button.setAttribute('aria-expanded', 'false');
        var list = menuList(items, '', 0); list.className = 'dropdown-menu'; list.style.cssText = 'min-width:15em;max-height:70vh;overflow:auto;display:none;';
        button.addEventListener('click', function () { var open = list.style.display === 'none'; list.style.display = open ? 'block' : 'none'; button.setAttribute('aria-expanded', String(open)); });
        holder.appendChild(button); holder.appendChild(list); nav.appendChild(holder);
      } else {
        var sidebar = document.getElementById('leftPanels') || document.querySelector('.panels.left'); if (!sidebar) { return; }
        var panel = document.createElement('section'); panel.id = 'luxpedia-managed-sidebar'; panel.className = 'panel panel-default'; panel.dataset.signature = signature;
        panel.appendChild(label('h3', 'navManagedMenu', 'panel-heading')); var body = document.createElement('div'); body.className = 'panel-body'; body.appendChild(menuList(items, '', 0)); panel.appendChild(body); sidebar.appendChild(panel);
      }
    });
    var content = document.getElementById('xwikicontent');
    if (/\/bin\/view\//.test(location.pathname) && content) {
      [['header', 'headerEn', 'headerZh'], ['footer', 'footerEn', 'footerZh']].forEach(function (entry) {
        var id = 'luxpedia-category-' + entry[0], value = localized(snapshot.banner || {}, entry[1], entry[2]); var box = document.getElementById(id);
        if (!value) { if (box) { box.remove(); } return; }
        if (!box) { box = document.createElement(entry[0]); box.id = id; box.className = entry[0] === 'header' ? 'alert alert-info' : 'well well-sm'; box.style.whiteSpace = 'pre-wrap'; content.insertAdjacentElement(entry[0] === 'header' ? 'beforebegin' : 'afterend', box); }
        if (box.textContent !== value) { box.textContent = value; }
      });
    }
    var existing = document.getElementById('luxpedia-page-qr');
    if (!snapshot.qrURL || !/\/bin\/view\//.test(location.pathname)) { if (existing) { existing.remove(); } return; }
    if (!existing) {
      var title = document.querySelector('#document-title h1, #title h1, h1.document-title') || document.getElementById('document-title');
      if (!title) { return; }
      existing = label('button', 'qrTitle', 'btn btn-default btn-sm'); existing.id = 'luxpedia-page-qr'; existing.type = 'button'; existing.style.marginLeft = '.5em'; existing.addEventListener('click', showQR); title.insertAdjacentElement('afterend', existing);
    }
  }
  function showQR() {
    var url = safeURL(snapshot.qrURL, false); if (!url) { return; } url.search = ''; url.hash = '';
    var dialog = document.createElement('dialog'); dialog.id = 'luxpedia-qr-dialog'; dialog.className = 'modal-content'; dialog.style.cssText = 'max-width:calc(100vw - 24px);width:30em;padding:1.5em;text-align:center;';
    dialog.appendChild(label('h3', 'qrTitle')); var status = label('p', 'qrPermissionHint'); dialog.appendChild(status);
    try {
      var qr = window.LuxPediaQRCode(0, 'M'); qr.addData(url.href, 'Byte'); qr.make();
      var count = qr.getModuleCount(), cell = 6, margin = 4, size = (count + margin * 2) * cell;
      var canvas = document.createElement('canvas'); canvas.width = canvas.height = size; canvas.style.cssText = 'max-width:100%;width:256px;height:auto;image-rendering:pixelated;';
      canvas.setAttribute('role', 'img'); canvas.setAttribute('aria-label', t('qrTitle'));
      var context = canvas.getContext('2d'); context.fillStyle = '#fff'; context.fillRect(0, 0, size, size); context.fillStyle = '#000';
      for (var r = 0; r < count; r++) { for (var c = 0; c < count; c++) { if (qr.isDark(r, c)) { context.fillRect((c + margin) * cell, (r + margin) * cell, cell, cell); } } }
      dialog.appendChild(canvas); var download = label('a', 'qrDownload', 'btn btn-default'); download.href = canvas.toDataURL('image/png'); download.download = 'page-qr.png'; dialog.appendChild(download);
    } catch (error) { status.dataset.luxpediaI18n = 'qrUnavailable'; status.textContent = t('qrUnavailable'); }
    var link = text('a', url.href); link.href = url.href; link.style.cssText = 'display:block;overflow-wrap:anywhere;margin:1em 0;'; dialog.appendChild(link);
    if (['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) { dialog.appendChild(label('p', 'qrLocalHint', 'xHint')); }
    var close = label('button', 'cancel', 'btn btn-default'); close.type = 'button'; close.addEventListener('click', function () { dialog.close(); }); dialog.appendChild(close);
    dialog.addEventListener('close', function () { dialog.remove(); }); document.body.appendChild(dialog); dialog.showModal();
  }
  function load() {
    var ref = pageReference(); if (!ref || requested) { return; } reference = ref; requested = true;
    fetch(prefix + '/bin/get/LuxPedia/Workflow/Presentation?outputSyntax=plain&reference=' + encodeURIComponent(ref), { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
      .then(function (response) { if (!response.ok) { throw new Error('Unavailable'); } return response.json(); }).then(function (data) {
        if (data.status === 'ok' && reference === ref) { snapshot = data; render(); }
      }).catch(function () { /* Native navigation and page content remain usable if configuration is unavailable. */ });
  }
  document.addEventListener('luxpedia:languagechange', render);
  document.addEventListener('luxpedia:presentationrefresh', function () { requested = false; load(); });
  document.addEventListener('xwiki:dom:updated', function () { render(); if (!requested) { load(); } });
  function boot() { load(); var attempts = 0, timer = setInterval(function () { if (!requested) { load(); } else { render(); } if (++attempts >= 20) { clearInterval(timer); } }, 500); }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();
