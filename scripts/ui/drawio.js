(function () {
  'use strict';
  if (window.LuxPediaDrawio) { return; }
  window.LuxPediaDrawio = true;
  var t = window.LuxPediaI18n.t;
  var hosts = ['app.diagrams.net', 'viewer.diagrams.net', 'app.draw.io', 'viewer.draw.io', 'draw.io', 'www.draw.io'];
  var nextPanel = 0;

  function diagramLink(value) {
    if (!value || value.length > 200000 || /[\u0000-\u0020\u007f]/.test(value)) { return null; }
    var url;
    try { url = new URL(value); } catch (error) { return null; }
    if (url.protocol !== 'https:' || url.username || url.password || url.port ||
        hosts.indexOf(url.hostname) < 0 || url.pathname !== '/') { return null; }
    return url;
  }

  function viewerLink(url) {
    // Rebuild from fixed viewer options; never carry editor/plugin/config parameters into the iframe.
    var hash = url.hash;
    var source = url.searchParams.get('url');
    if (!hash && source) { hash = '#U' + encodeURIComponent(source); }
    if (/^#U./.test(hash)) {
      try {
        var raw = hash.slice(2);
        source = new URL(/^https:\/\//i.test(raw) ? raw : decodeURIComponent(raw));
        if (source.protocol !== 'https:' || source.username || source.password) { return null; }
      } catch (error) { return null; }
      hash = '#U' + encodeURIComponent(source.href);
    } else if (!/^#R./.test(hash)) { return null; }
    var viewer = new URL('https://viewer.diagrams.net/');
    viewer.search = new URLSearchParams({ lightbox: '1', nav: '1', layers: '1',
      target: 'blank', lang: window.LuxPediaI18n.language() }).toString();
    // Keep harmless presentation choices from the published link.
    ['page', 'page-id', 'tags', 'layer-ids'].forEach(function (key) {
      if (url.searchParams.has(key)) { viewer.searchParams.set(key, url.searchParams.get(key)); }
    });
    viewer.hash = hash;
    return viewer.href;
  }

  function message(tag, key) {
    var node = document.createElement(tag);
    node.dataset.luxpediaI18n = key;
    node.textContent = t(key);
    return node;
  }

  function enhanceLinks() {
    if (!/\/bin\/view\//.test(location.pathname)) { return; }
    var content = document.getElementById('xwikicontent');
    if (!content) { return; }
    content.querySelectorAll('a[href]').forEach(function (link) {
      if (link.dataset.luxpediaDrawio || link.closest('pre, code, [contenteditable="true"]')) { return; }
      var url = diagramLink(link.getAttribute('href'));
      if (!url) { return; }
      link.dataset.luxpediaDrawio = '1';
      link.target = '_blank';
      link.rel = 'noopener noreferrer';
      link.referrerPolicy = 'no-referrer';
      var box = document.createElement('span');
      box.className = 'luxpedia-drawio-preview';
      box.style.cssText = 'display:block;margin:8px 0;max-width:100%;';
      link.insertAdjacentElement('afterend', box);
      var preview = viewerLink(url);
      if (!preview) {
        box.appendChild(message('small', 'drawioLinkOnly'));
        return;
      }
      var button = message('button', 'drawioPreview');
      button.type = 'button';
      button.className = 'btn btn-default btn-sm';
      button.setAttribute('aria-expanded', 'false');
      box.appendChild(button);
      var panel = document.createElement('span');
      panel.hidden = true;
      panel.id = 'luxpedia-drawio-panel-' + (++nextPanel);
      button.setAttribute('aria-controls', panel.id);
      box.appendChild(panel);
      var frame;
      button.addEventListener('click', function () {
        panel.hidden = !panel.hidden;
        button.setAttribute('aria-expanded', String(!panel.hidden));
        button.dataset.luxpediaI18n = panel.hidden ? 'drawioPreview' : 'drawioHide';
        button.textContent = t(button.dataset.luxpediaI18n);
        if (!panel.hidden && !frame) {
          panel.appendChild(message('small', 'drawioExternalHint'));
          frame = document.createElement('iframe');
          frame.title = t('drawioFrameTitle');
          frame.dataset.luxpediaDrawioFrame = '1';
          frame.referrerPolicy = 'no-referrer';
          frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-popups allow-downloads');
          frame.setAttribute('loading', 'lazy');
          frame.style.cssText = 'display:block;box-sizing:border-box;width:100%;height:480px;max-height:75vh;border:1px solid currentColor;margin-top:8px;';
          // No third-party request is made before the reader chooses Preview.
          frame.src = viewerLink(url);
          panel.appendChild(frame);
        }
      });
    });
  }

  function insertBar(editor) {
    if (document.getElementById('luxpedia-drawio-bar') || !editor.container || editor.readOnly) { return; }
    var bar = document.createElement('div');
    bar.id = 'luxpedia-drawio-bar';
    bar.className = 'form-inline';
    bar.style.cssText = 'margin:8px 0;display:flex;flex-wrap:wrap;gap:6px;align-items:center;';
    var label = message('label', 'drawioLink');
    label.htmlFor = 'luxpedia-drawio-url';
    var input = document.createElement('input');
    // A partly typed link must not trigger native form validation when saving the article.
    input.id = 'luxpedia-drawio-url'; input.type = 'text'; input.inputMode = 'url';
    input.className = 'form-control'; input.maxLength = 200000;
    input.placeholder = 'https://app.diagrams.net/…';
    input.style.cssText = 'flex:1;min-width:180px;';
    var button = message('button', 'drawioInsert');
    button.type = 'button'; button.className = 'btn btn-default';
    var status = document.createElement('span');
    status.setAttribute('role', 'status');
    status.style.flexBasis = '100%';
    [label, input, button, status].forEach(function (node) { bar.appendChild(node); });
    editor.container.$.parentNode.insertBefore(bar, editor.container.$);
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter') { event.preventDefault(); button.click(); }
    });
    button.addEventListener('click', function () {
      if (editor.readOnly) { return; }
      var url = diagramLink(input.value.trim());
      if (!url) {
        status.dataset.luxpediaI18n = 'drawioInvalid'; status.textContent = t('drawioInvalid');
        return;
      }
      var anchor = document.createElement('a');
      anchor.href = url.href; anchor.textContent = t('drawioOpen');
      anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
      editor.focus();
      // Save an ordinary link: native rich-text conversion, review and publication remain unchanged.
      editor.insertHtml('<p>' + anchor.outerHTML + '</p><p><br></p>');
      input.value = '';
      status.dataset.luxpediaI18n = viewerLink(url) ? 'drawioInserted' : 'drawioLinkOnly';
      status.textContent = t(status.dataset.luxpediaI18n);
    });
  }

  function bindEditors() {
    if (!window.CKEDITOR) { return; }
    Object.keys(window.CKEDITOR.instances).forEach(function (key) {
      var editor = window.CKEDITOR.instances[key];
      if (editor.status === 'ready') { insertBar(editor); }
    });
  }

  function boot() {
    enhanceLinks();
    if (/\/bin\/(?:edit|inline)\//.test(location.pathname)) {
      bindEditors();
      var attempts = 0;
      var timer = setInterval(function () {
        bindEditors();
        if (document.getElementById('luxpedia-drawio-bar') || ++attempts >= 60) { clearInterval(timer); }
      }, 500);
    }
  }
  document.addEventListener('xwiki:dom:updated', enhanceLinks);
  document.addEventListener('luxpedia:languagechange', function () {
    document.querySelectorAll('[data-luxpedia-drawio-frame]').forEach(function (frame) {
      frame.title = t('drawioFrameTitle');
      var url = new URL(frame.src);
      url.searchParams.set('lang', window.LuxPediaI18n.language());
      frame.src = url.href;
    });
  });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); }
  else { boot(); }
})();
