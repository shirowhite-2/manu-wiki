(function () {
  'use strict';
  if (window.LuxPediaDrawio) { return; }
  window.LuxPediaDrawio = true;
  var t = window.LuxPediaI18n.t;
  var hosts = ['app.diagrams.net', 'viewer.diagrams.net', 'app.draw.io', 'viewer.draw.io', 'draw.io', 'www.draw.io'];
  var nextPanel = 0;
  var links = new WeakMap(), boxes = new Set(), observer, observedContent, scheduled = false;

  function webLink(value) {
    if (!value || value.length > 200000 || /[\u0000-\u0020\u007f]/.test(value) || !/^(?:https?:)?\/\//i.test(value)) { return null; }
    try {
      var url = new URL(value, location.href);
      return /^(https?:)$/.test(url.protocol) && !url.username && !url.password ? url : null;
    } catch (error) { return null; }
  }

  function classify(value) {
    var url = webLink(value); if (!url) { return null; }
    var candidate = value.startsWith('//') ? new URL('https:' + value) : url;
    var diagram = diagramLink(candidate.href);
    var type = diagram ? 'drawio' : ['github.com', 'www.github.com', 'gist.github.com', 'raw.githubusercontent.com', 'gist.githubusercontent.com'].includes(url.hostname) ? 'github' :
      ['bitbucket.org', 'www.bitbucket.org'].includes(url.hostname) ? 'bitbucket' : 'link';
    return { url: url, type: type, diagram: diagram };
  }


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

  function excluded(node) {
    return node.isContentEditable || node.closest('pre, code, script, style, textarea, input, select, button, label, .luxpedia-drawio-preview');
  }

  function linkPlainText(content) {
    var walker = document.createTreeWalker(content, NodeFilter.SHOW_TEXT), pending = [], node;
    while ((node = walker.nextNode())) {
      var parent = node.parentElement;
      if (!parent || parent.closest('a') || excluded(parent)) { continue; }
      var value = node.nodeValue.trim(), item = classify(value);
      if (item) { pending.push({ node: node, value: value, item: item }); }
    }
    pending.forEach(function (entry) {
      var text = entry.node.nodeValue, start = text.indexOf(entry.value), fragment = document.createDocumentFragment();
      fragment.appendChild(document.createTextNode(text.slice(0, start)));
      var link = document.createElement('a'); link.href = entry.item.url.href; link.textContent = entry.value;
      fragment.appendChild(link); fragment.appendChild(document.createTextNode(text.slice(start + entry.value.length)));
      entry.node.replaceWith(fragment);
    });
  }

  function restoreLink(link, state) {
    if (!state || !state.box) { return; }
    state.box.remove(); boxes.delete(state.box);
    Object.keys(state.original).forEach(function (name) {
      if (state.original[name] === null) { link.removeAttribute(name); } else { link.setAttribute(name, state.original[name]); }
    });
    delete link.dataset.luxpediaDrawio;
  }

  function enhanceLinks() {
    scheduled = false;
    if (!/\/bin\/view\//.test(location.pathname)) { return; }
    var content = document.getElementById('xwikicontent');
    if (!content) { return; }
    if (content !== observedContent) {
      if (observer) { observer.disconnect(); }
      observedContent = content;
      observer = new MutationObserver(function () {
        if (!scheduled) { scheduled = true; setTimeout(enhanceLinks, 0); }
      });
      observer.observe(content, { childList: true, subtree: true, attributes: true, attributeFilter: ['href'] });
    }
    linkPlainText(content);
    boxes.forEach(function (box) {
      if (!box.sourceLink.isConnected || !content.contains(box.sourceLink) || box.parentNode !== box.sourceLink.parentNode) {
        box.remove(); boxes.delete(box);
      }
    });
    content.querySelectorAll('a').forEach(function (link) {
      if (excluded(link)) { return; }
      var href = link.getAttribute('href'), state = links.get(link);
      if (state && state.href === href && (!state.box || state.box.isConnected)) { return; }
      restoreLink(link, state); links.delete(link); delete link.dataset.luxpediaLinkType;
      var item = classify(href); if (!item) { return; }
      state = { href: href, box: null }; links.set(link, state); link.dataset.luxpediaLinkType = item.type;
      if (item.type !== 'drawio') { return; }
      state.original = { target: link.getAttribute('target'), rel: link.getAttribute('rel'), referrerpolicy: link.getAttribute('referrerpolicy') };
      link.dataset.luxpediaDrawio = '1';
      link.target = '_blank';
      link.rel = Array.from(new Set((link.rel || '').split(/\s+/).filter(Boolean).concat(['noopener', 'noreferrer']))).join(' ');
      link.referrerPolicy = 'no-referrer';
      var box = document.createElement('span');
      state.box = box; box.sourceLink = link; boxes.add(box);
      box.className = 'luxpedia-drawio-preview';
      box.style.cssText = 'display:block;margin:8px 0;max-width:100%;';
      link.insertAdjacentElement('afterend', box);
      var preview = viewerLink(item.diagram);
      if (!preview) { box.appendChild(message('small', 'drawioLinkOnly')); return; }
      var button = message('button', 'drawioPreview'); button.type = 'button'; button.className = 'btn btn-default btn-sm';
      button.setAttribute('aria-expanded', 'false'); box.appendChild(button);
      var panel = document.createElement('span'); panel.hidden = true; panel.id = 'luxpedia-drawio-panel-' + (++nextPanel);
      button.setAttribute('aria-controls', panel.id); box.appendChild(panel);
      var frame;
      button.addEventListener('click', function () {
        panel.hidden = !panel.hidden; button.setAttribute('aria-expanded', String(!panel.hidden));
        button.dataset.luxpediaI18n = panel.hidden ? 'drawioPreview' : 'drawioHide'; button.textContent = t(button.dataset.luxpediaI18n);
        if (!panel.hidden && !frame) {
          panel.appendChild(message('small', 'drawioExternalHint'));
          frame = document.createElement('iframe'); frame.title = t('drawioFrameTitle'); frame.dataset.luxpediaDrawioFrame = '1';
          frame.referrerPolicy = 'no-referrer';
          frame.setAttribute('sandbox', 'allow-scripts allow-same-origin allow-popups allow-downloads'); frame.setAttribute('loading', 'lazy');
          frame.style.cssText = 'display:block;box-sizing:border-box;width:100%;height:480px;max-height:75vh;border:1px solid currentColor;margin-top:8px;';
          frame.src = viewerLink(item.diagram); panel.appendChild(frame);
        }
      });
    });
    if (window.LuxPediaTables && typeof window.LuxPediaTables.scan === 'function') { window.LuxPediaTables.scan(); }
  }

  function insertBar(editor) {
    if (document.getElementById('luxpedia-drawio-bar') || !editor.container || editor.readOnly) { return; }
    var bar = document.createElement('div');
    bar.id = 'luxpedia-drawio-bar';
    bar.className = 'form-inline';
    bar.style.cssText = 'margin:8px 0;display:flex;flex-wrap:wrap;gap:6px;align-items:center;';
    var label = message('label', 'externalLink');
    label.htmlFor = 'luxpedia-drawio-url';
    var input = document.createElement('input');
    // A partly typed link must not trigger native form validation when saving the article.
    input.id = 'luxpedia-drawio-url'; input.type = 'text'; input.inputMode = 'url';
    input.className = 'form-control'; input.maxLength = 200000;
    input.placeholder = 'https://github.com/…';
    input.style.cssText = 'flex:1;min-width:180px;';
    var button = message('button', 'externalInsert');
    button.type = 'button'; button.className = 'btn btn-default';
    var status = document.createElement('span');
    status.setAttribute('role', 'status');
    status.style.flexBasis = '100%';
    [label, input, button, status].forEach(function (node) { bar.appendChild(node); });
    editor.container.$.parentNode.insertBefore(bar, editor.container.$);
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter') { event.preventDefault(); button.click(); }
    });
    input.addEventListener('input', function () {
      var item = classify(input.value.trim());
      var key = item ? item.type === 'drawio' ? viewerLink(item.diagram) ? 'externalDetectedDrawio' : 'externalDetectedDiagramLink' :
        item.type === 'github' ? 'externalDetectedGithub' : item.type === 'bitbucket' ? 'externalDetectedBitbucket' : 'externalDetectedLink' : '';
      if (key) { status.dataset.luxpediaI18n = key; } else { delete status.dataset.luxpediaI18n; }
      status.textContent = key ? t(key) : '';
    });
    button.addEventListener('click', function () {
      if (editor.readOnly) { return; }
      var item = classify(input.value.trim());
      if (!item) {
        status.dataset.luxpediaI18n = 'externalInvalid'; status.textContent = t('externalInvalid');
        return;
      }
      var anchor = document.createElement('a');
      anchor.href = item.url.href; anchor.textContent = item.type === 'drawio' ? t('drawioOpen') : item.url.href;
      anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
      editor.focus();
      // Save an ordinary link: native rich-text conversion, review and publication remain unchanged.
      editor.insertHtml('<p>' + anchor.outerHTML + '</p><p><br></p>');
      input.value = '';
      status.dataset.luxpediaI18n = item.type === 'drawio' ? viewerLink(item.diagram) ? 'drawioInserted' : 'drawioLinkOnly' : 'externalInserted';
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
    if (/\/bin\/(?:edit|inline|create)\//.test(location.pathname)) {
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
    boxes.forEach(function (box) {
      var frame = box.querySelector('[data-luxpedia-drawio-frame]'); if (!frame) { return; }
      frame.title = t('drawioFrameTitle');
      var url = new URL(frame.src);
      url.searchParams.set('lang', window.LuxPediaI18n.language());
      frame.src = url.href;
    });
  });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); }
  else { boot(); }
})();
