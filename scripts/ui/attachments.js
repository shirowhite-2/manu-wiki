(function () {
  'use strict';
  if (window.LuxPediaAttachments) { return; }
  window.LuxPediaAttachments = true;
  var t = window.LuxPediaI18n.t;
  var BAR = 'luxpedia-attachments-bar';

  function kind(file) {
    if (/\.(png|jpe?g|gif|webp|svg|bmp|avif|apng|ico)$/i.test(file.name)) { return 'images'; }
    if (/\.(mp4|webm|ogv|mov|m4v|avi)$/i.test(file.name)) { return 'videos'; }
    return 'files';
  }

  function labelled(tag, key) {
    var node = document.createElement(tag);
    node.dataset.luxpediaI18n = key; node.textContent = t(key);
    return node;
  }

  function attachmentReference(name) {
    // Serialize an attachment-only entity reference, not its draft document. Core escapes @, :, and backslashes.
    return { type: 'attach', typed: true,
      reference: XWiki.Model.serialize(new XWiki.EntityReference(name, XWiki.EntityType.ATTACHMENT)) };
  }

  function link(editor, reference, text) {
    var resource = CKEDITOR.plugins.xwikiResource;
    var anchor = document.createElement('a');
    anchor.textContent = text;
    anchor.href = resource.getResourceURL(reference, editor);
    anchor.dataset.reference = resource.serializeResourceReference(reference);
    anchor.dataset.linktype = reference.type === 'attach' ? 'attachment' : '';
    anchor.target = '_blank'; anchor.rel = 'noopener noreferrer';
    return anchor;
  }

  function insert(editor, file) {
    // The marker protocol uses this delimiter. Never silently corrupt such a filename.
    if (file.name.indexOf('|-|') >= 0) { throw new Error('reference delimiter'); }
    var reference = attachmentReference(file.name);
    var type = kind(file);
    editor.focus();
    if (type === 'images') {
      var image = editor.widgets && editor.widgets.registered.image;
      if (!image || typeof image.insert !== 'function') { throw new Error('image widget unavailable'); }
      reference.typed = false;
      // Use the same insertion API as the native XWiki image picker, including its rendering markers.
      image.insert({ setImageData: { resourceReference: reference,
        src: CKEDITOR.plugins.xwikiResource.getResourceURL(reference, editor), alt: file.name } });
      return;
    }
    if (type === 'videos') {
      if (!editor.getCommand('xwiki-macro-insert') ||
          editor.execCommand('xwiki-macro-insert', { name: 'embed', parameters: { attachment: file.name }, inline: false }) === false) {
        throw new Error('macro insertion unavailable');
      }
      return;
    }
    var paragraph = document.createElement('p');
    var attachments = { type: 'doc', typed: true, reference: '', parameters: { queryString: 'viewer=attachments' } };
    // PDF uses the browser's own viewer when available; Office opens this article's attachment page.
    paragraph.appendChild(link(editor, /\.pdf$/i.test(file.name) ? reference : attachments, file.name));
    paragraph.appendChild(document.createTextNode(' · '));
    var download = attachmentReference(file.name);
    download.parameters = { queryString: 'force-download=1' };
    paragraph.appendChild(link(editor, download, t('attachmentDownload')));
    paragraph.appendChild(document.createTextNode(' · '));
    paragraph.appendChild(link(editor, attachments, t('attachmentPage')));
    var after = document.createElement('p'); after.appendChild(document.createElement('br'));
    editor.insertHtml(paragraph.outerHTML + after.outerHTML);
  }

  function build(editor) {
    if (document.getElementById(BAR) || editor.readOnly || !editor.container) { return; }
    var source = editor.config && editor.config.sourceDocument;
    if (!source || typeof source.getRestURL !== 'function' || !window.XWiki || !XWiki.Model ||
        !CKEDITOR.plugins.xwikiResource) { return; }
    var bar = document.createElement('div'); bar.id = BAR; bar.className = 'form-inline';
    bar.style.cssText = 'display:flex;flex-wrap:wrap;align-items:center;gap:6px;margin:8px 0;';
    var label = labelled('label', 'attachmentInsert'); label.htmlFor = 'luxpedia-attachment-select';
    var filter = document.createElement('select'); filter.className = 'form-control';
    filter.id = 'luxpedia-attachment-filter';
    filter.setAttribute('aria-label', t('attachmentFilter'));
    ['all', 'images', 'videos', 'files'].forEach(function (name) {
      var option = labelled('option', 'attachmentType.' + name); option.value = name; filter.appendChild(option);
    });
    var select = document.createElement('select'); select.className = 'form-control';
    select.id = 'luxpedia-attachment-select'; select.style.cssText = 'max-width:100%;min-width:160px;flex:1;';
    var button = labelled('button', 'insertIntoArticle'); button.type = 'button'; button.disabled = true;
    button.className = 'btn btn-default'; button.id = 'luxpedia-attachment-insert';
    var refresh = labelled('button', 'attachmentRefresh'); refresh.type = 'button'; refresh.className = 'btn btn-default';
    refresh.id = 'luxpedia-attachment-refresh';
    var manage = labelled('a', 'attachmentManage'); manage.className = 'btn btn-default';
    manage.href = source.getURL('view', 'viewer=attachments'); manage.target = '_blank'; manage.rel = 'noopener noreferrer';
    var hint = labelled('small', 'attachmentInsertHint'); hint.style.flexBasis = '100%';
    var status = document.createElement('span'); status.setAttribute('role', 'status'); status.style.flexBasis = '100%';
    [label, filter, select, button, refresh, manage, hint, status].forEach(function (node) { bar.appendChild(node); });
    editor.container.$.parentNode.insertBefore(bar, editor.container.$);
    var files = [], loaded = false, generation = 0;

    function statusMessage(key) { status.dataset.luxpediaI18n = key; status.textContent = key ? t(key) : ''; }
    function placeholder(key) {
      select.replaceChildren(labelled('option', key)); select.firstChild.value = '';
      select.disabled = true; button.disabled = true;
    }
    function render() {
      if (!loaded) { return; }
      var selected = select.value;
      select.replaceChildren();
      ['images', 'videos', 'files'].forEach(function (type) {
        if (filter.value !== 'all' && filter.value !== type) { return; }
        var group = document.createElement('optgroup'); group.label = t('attachmentType.' + type);
        files.filter(function (file) { return kind(file) === type; }).forEach(function (file) {
          var option = document.createElement('option'); option.value = file.name; option.textContent = file.name;
          group.appendChild(option);
        });
        if (group.children.length) { select.appendChild(group); }
      });
      if (!select.children.length) { placeholder('attachmentEmpty'); return; }
      if (Array.from(select.options).some(function (option) { return option.value === selected; })) { select.value = selected; }
      select.disabled = false; button.disabled = !!editor.readOnly;
    }
    function load() {
      var request = ++generation;
      loaded = false; placeholder('attachmentLoading'); statusMessage(''); refresh.disabled = true;
      // Only list this document's attachments. No cross-page lookup or write permission is added.
      var rest = new URL(source.getRestURL('attachments'), location.href);
      if (rest.origin !== location.origin) { placeholder('attachmentError'); refresh.disabled = false; return; }
      fetch(rest.href, { credentials: 'same-origin', headers: { Accept: 'application/json' }, cache: 'no-store' })
        .then(function (response) {
          if (request !== generation) { return { attachments: [] }; }
          if (response.status === 404) { statusMessage('attachmentSaveFirst'); return { attachments: [] }; }
          if (!response.ok) { throw new Error('HTTP ' + response.status); }
          return response.json();
        }).then(function (data) {
          if (request !== generation) { return; }
          if (!Array.isArray(data.attachments)) { throw new Error('invalid attachment response'); }
          var names = new Set();
          files = data.attachments.filter(function (file) {
            if (!file || typeof file.name !== 'string' || !file.name || names.has(file.name)) { return false; }
            names.add(file.name); return true;
          }).sort(function (a, b) { return a.name.localeCompare(b.name); });
          loaded = true; render(); refresh.disabled = false;
        }).catch(function () {
          if (request !== generation) { return; }
          files = []; placeholder('attachmentError'); statusMessage('attachmentError'); refresh.disabled = false;
        });
    }
    filter.addEventListener('change', render);
    refresh.addEventListener('click', load);
    button.addEventListener('click', function () {
      if (editor.readOnly || !loaded) { return; }
      var file = files.find(function (item) { return item.name === select.value; });
      if (!file) { return; }
      try { insert(editor, file); statusMessage('attachmentInserted'); }
      catch (error) { statusMessage('attachmentInsertError'); }
    });
    editor.on('fileUploadResponse', function () { setTimeout(load, 0); });
    document.addEventListener('luxpedia:languagechange', function () {
      filter.setAttribute('aria-label', t('attachmentFilter')); render();
    });
    load();
  }

  function boot() {
    if (!/\/bin\/(?:edit|inline)\//.test(location.pathname)) { return; }
    var attempts = 0;
    function bind() {
      if (!window.CKEDITOR) { return; }
      Object.keys(CKEDITOR.instances).some(function (key) {
        var editor = CKEDITOR.instances[key];
        if (editor.status === 'ready') { build(editor); }
        return !!document.getElementById(BAR);
      });
    }
    bind();
    var timer = setInterval(function () {
      bind();
      if (document.getElementById(BAR) || ++attempts >= 60) { clearInterval(timer); }
    }, 500);
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); }
  else { boot(); }
})();
