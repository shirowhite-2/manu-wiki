(function () {
  'use strict';
  if (window.LuxPediaComponents) { return; } window.LuxPediaComponents = true;
  var t = window.LuxPediaI18n.t, BAR = 'luxpedia-components-bar';
  function labelled(tag, key) { var node = document.createElement(tag); node.dataset.luxpediaI18n = key; node.textContent = t(key); return node; }
  function message(node, key) { if (key) { node.dataset.luxpediaI18n = key; } else { delete node.dataset.luxpediaI18n; } node.textContent = key ? t(key) : ''; }
  function today() { var date = new Date(); return date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0'); }
  function build(editor) {
    if (editor.readOnly || !editor.container || document.getElementById(BAR) || !editor.config || !editor.config.sourceDocument) { return; }
    var bar = document.createElement('div'); bar.id = BAR; bar.className = 'form-inline';
    bar.style.cssText = 'display:flex;flex-wrap:wrap;gap:8px;align-items:center;margin:8px 0;';
    var caption = labelled('label', 'contentComponents'); caption.htmlFor = 'luxpedia-component-type';
    var select = document.createElement('select'); select.id = caption.htmlFor; select.className = 'form-control';
    ['date', 'checklist', 'decision'].forEach(function (kind) { var option = labelled('option', 'component.' + kind); option.value = kind; select.appendChild(option); });
    function button(key, id) { var node = labelled('button', key); node.type = 'button'; node.id = id; node.className = 'btn btn-default'; return node; }
    var insert = button('componentInsert', 'luxpedia-component-insert'), toggle = button('componentToggleTask', 'luxpedia-task-toggle');
    var help = labelled('a', 'componentGuide'); help.className = 'btn btn-link';
    help.href = location.pathname.replace(/\/bin\/.*$/, '/bin/view/LuxPedia/Help/ContentComponents'); help.target = '_blank'; help.rel = 'noopener noreferrer';
    var status = document.createElement('span'); status.setAttribute('role', 'status'); status.style.flexBasis = '100%';
    [caption, select, insert, toggle, help, status].forEach(function (node) { bar.appendChild(node); });
    editor.container.$.parentNode.insertBefore(bar, editor.container.$);
    var dialog = document.createElement('dialog'); dialog.className = 'modal-content'; dialog.id = 'luxpedia-component-dialog';
    dialog.style.cssText = 'max-width:calc(100vw - 24px);width:40em;padding:1.5em;';
    var title = labelled('h3', 'contentComponents'); title.id = 'luxpedia-component-heading'; dialog.setAttribute('aria-labelledby', title.id);
    var form = document.createElement('form'); dialog.appendChild(title); dialog.appendChild(form); document.body.appendChild(dialog);
    var fields = {}, groups = {};
    function field(name, key, type, options) {
      var group = document.createElement('div'); group.className = 'form-group';
      var label = labelled('label', key); label.htmlFor = 'luxpedia-component-' + name;
      var input = document.createElement(type === 'textarea' || type === 'select' ? type : 'input'); input.id = label.htmlFor; input.className = 'form-control';
      if (!['textarea', 'select'].includes(type)) { input.type = type; }
      if (type === 'textarea') { input.rows = 4; input.maxLength = 16000; } else if (type === 'text') { input.maxLength = 300; }
      (options || []).forEach(function (key) { var option = labelled('option', 'decision.' + key); option.value = key; input.appendChild(option); });
      group.appendChild(label); group.appendChild(input); form.appendChild(group); fields[name] = input; groups[name] = group; return input;
    }
    field('label', 'componentDateLabel', 'text'); field('date', 'componentDateValue', 'date');
    field('tasks', 'componentTasks', 'textarea'); field('decision', 'componentDecisionText', 'textarea');
    field('owner', 'owner', 'text'); field('state', 'componentDecisionState', 'select', ['proposed', 'accepted', 'rejected']);
    var hint = labelled('p', 'componentSaveHint'); hint.className = 'xHint'; form.appendChild(hint);
    var error = document.createElement('p'); error.setAttribute('role', 'alert'); form.appendChild(error);
    var confirm = button('insertIntoArticle', 'luxpedia-component-confirm'); confirm.type = 'submit';
    var cancel = button('cancel', 'luxpedia-component-cancel'); form.appendChild(confirm); form.appendChild(cancel); cancel.style.marginLeft = '.5em';
    var bookmarks;
    insert.addEventListener('click', function () {
      if (editor.readOnly) { return; }
      var selection = editor.getSelection && editor.getSelection();
      bookmarks = selection && selection.createBookmarks2 ? selection.createBookmarks2(true) : null;
      var kind = select.value; message(error, '');
      Object.keys(groups).forEach(function (name) {
        var show = name === 'date' || kind === 'date' && name === 'label' || kind === 'checklist' && ['tasks', 'owner'].includes(name) || kind === 'decision' && ['decision', 'owner', 'state'].includes(name);
        groups[name].style.display = show ? '' : 'none'; fields[name].required = show && ['label', 'tasks', 'decision'].includes(name);
      });
      fields.date.required = kind !== 'checklist'; fields.date.value = today(); fields.label.value = t('componentDefaultDateLabel');
      title.dataset.luxpediaI18n = 'component.' + kind; title.textContent = t(title.dataset.luxpediaI18n);
      if (dialog.showModal) { dialog.showModal(); } else { dialog.setAttribute('open', ''); }
    });
    function close() { if (dialog.close) { dialog.close(); } else { dialog.removeAttribute('open'); } editor.focus(); }
    cancel.addEventListener('click', close);
    function paragraph(key, value) {
      var node = document.createElement('p'), strong = document.createElement('strong'); strong.textContent = key + ': ';
      node.appendChild(strong); node.appendChild(document.createTextNode(value)); return node;
    }
    form.addEventListener('submit', function (event) {
      event.preventDefault(); if (editor.readOnly) { return; }
      var kind = select.value, fragment = document.createElement('div'), date = fields.date.value;
      if (!form.checkValidity()) { form.reportValidity(); return; }
      if (kind === 'date' && !fields.label.value.trim() || kind === 'decision' && !fields.decision.value.trim()) { message(error, 'componentRequired'); return; }
      if (kind === 'date') {
        fragment.appendChild(paragraph(fields.label.value.trim(), date));
      } else if (kind === 'checklist') {
        var tasks = fields.tasks.value.split(/\r?\n/).map(function (line) { return line.trim(); }).filter(Boolean);
        if (!tasks.length || tasks.length > 100) { message(error, 'componentTaskLimit'); return; }
        var list = document.createElement('ul');
        tasks.forEach(function (task) {
          var item = document.createElement('li'); item.textContent = '☐ ' + task +
            (fields.owner.value.trim() ? ' — ' + fields.owner.value.trim() : '') + (date ? ' — ' + date : ''); list.appendChild(item);
        }); fragment.appendChild(list);
      } else if (kind === 'decision') {
        var table = document.createElement('table'); table.className = 'table';
        var heading = table.createTHead().insertRow();
        ['componentDateValue', 'component.decision', 'owner', 'status'].forEach(function (key) {
          var cell = document.createElement('th'); cell.scope = 'col'; cell.textContent = t(key); heading.appendChild(cell);
        });
        var row = table.createTBody().insertRow();
        [date, fields.decision.value.trim(), fields.owner.value.trim(), t('decision.' + fields.state.value)].forEach(function (value) { row.insertCell().textContent = value; });
        fragment.appendChild(table);
      } else { return; }
      fragment.appendChild(document.createElement('p')).appendChild(document.createElement('br'));
      try {
        editor.focus(); if (bookmarks && editor.getSelection && editor.getSelection().selectBookmarks) { editor.getSelection().selectBookmarks(bookmarks); }
        editor.insertHtml(fragment.innerHTML); close(); message(status, 'componentInserted');
      } catch (failure) { message(error, 'componentInsertFailed'); }
    });
    toggle.addEventListener('mousedown', function (event) { event.preventDefault(); });
    toggle.addEventListener('click', function () {
      if (editor.readOnly) { return; }
      var selection = editor.getSelection && editor.getSelection(), start = selection && selection.getStartElement && selection.getStartElement();
      var item = start && start.$ && start.$.closest('li');
      var editable = editor.editable && editor.editable();
      if (!item || !editable || !editable.$.contains(item)) { message(status, 'componentSelectTask'); return; }
      var walker = item.ownerDocument.createTreeWalker(item, NodeFilter.SHOW_TEXT), text = walker.nextNode();
      while (text && !text.nodeValue.trim()) { text = walker.nextNode(); }
      if (!text || !/^\s*[☐☑]/.test(text.nodeValue)) { message(status, 'componentSelectTask'); return; }
      if (editor.fire) { editor.fire('saveSnapshot'); }
      text.nodeValue = text.nodeValue.replace(/[☐☑]/, function (mark) { return mark === '☐' ? '☑' : '☐'; });
      if (editor.fire) { editor.fire('saveSnapshot'); editor.fire('change'); } message(status, 'componentTaskChanged');
    });
    if (editor.on) { editor.on('readOnly', function () { insert.disabled = toggle.disabled = !!editor.readOnly; if (editor.readOnly && dialog.open) { close(); } }); }
  }
  function boot() {
    if (!/\/bin\/(?:edit|inline|create)\/(?:Drafts|Knowledge)(?:\/|$)/.test(location.pathname)) { return; }
    var attempts = 0;
    function bind() {
      if (!window.CKEDITOR || !CKEDITOR.instances) { return; }
      Object.keys(CKEDITOR.instances).some(function (key) { var editor = CKEDITOR.instances[key]; if (editor.status === 'ready') { build(editor); } return !!document.getElementById(BAR); });
    }
    bind(); var timer = setInterval(function () { bind(); if (document.getElementById(BAR) || ++attempts >= 60) { clearInterval(timer); } }, 500);
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();
