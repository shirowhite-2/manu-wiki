(function () {
  'use strict';
  if (window.LuxPediaRagSync) { return; } window.LuxPediaRagSync = true;
  var t = window.LuxPediaI18n.t, prefix = location.pathname.split('/bin/')[0] || '';
  var endpoint = prefix + '/bin/get/LuxPedia/Workflow/AdminTools?outputSyntax=plain', token = '';
  function node(tag, text, className) { var n = document.createElement(tag); if (text !== undefined) { n.textContent = text; } if (className) { n.className = className; } return n; }
  function labelled(tag, key, className) { var n = node(tag, t(key), className); n.dataset.luxpediaI18n = key; return n; }
  function format(key, values) { return Object.keys(values).reduce(function (text, name) { return text.split('{' + name + '}').join(values[name]); }, t(key)); }
  function button(parent, key, handler) { var n = labelled('button', key, 'btn btn-default'); n.type = 'button'; n.style.marginRight = '.4em'; n.addEventListener('click', handler); parent.appendChild(n); return n; }
  function request(operation, data, write) {
    var options = { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } };
    var fields = new URLSearchParams(Object.assign({ operation: operation }, data || {})), url = endpoint;
    if (write) { fields.set('form_token', token); options.method = 'POST'; options.headers['Content-Type'] = 'application/x-www-form-urlencoded'; options.body = fields.toString(); }
    else { url += '&' + fields.toString(); }
    return fetch(url, options).then(function (r) { if (!r.ok) { throw new Error('unavailable'); } return r.json(); }).then(function (result) {
      if (result.token) { token = result.token; }
      if (result.status !== 'ok') { var error = new Error(result.status || 'unavailable'); error.reason = result.reason || ''; throw error; }
      return result;
    });
  }
  function table(parent, headings) {
    var wrapper = node('div'); wrapper.style.overflowX = 'auto'; var n = node('table', undefined, 'table table-striped luxpedia-system-list');
    var row = n.createTHead().insertRow(); headings.forEach(function (key) { var th = labelled('th', key); th.scope = 'col'; row.appendChild(th); });
    wrapper.appendChild(n); parent.appendChild(wrapper); return n.createTBody();
  }
  function field(parent, key, type, value) {
    var group = node('div', undefined, 'form-group'); group.style.cssText = 'margin:.5em .5em .5em 0;max-width:40em;';
    var label = labelled('label', key), input = document.createElement('input');
    input.id = 'luxpedia-rag-' + key; input.dataset.ragField = key; input.type = type; label.htmlFor = input.id;
    if (type === 'checkbox') { input.checked = !!value; } else { input.className = 'form-control'; input.value = value === undefined || value === null ? '' : value; }
    group.appendChild(label); group.appendChild(document.createTextNode(' ')); group.appendChild(input); parent.appendChild(group); return input;
  }
  function say(notice, text, kind) {
    delete notice.dataset.luxpediaI18n; notice.textContent = text;
    notice.className = text && kind ? 'alert alert-' + kind : ''; notice.style.margin = text && kind ? '.5em 0' : '';
    if (text && kind && notice.scrollIntoView) { notice.scrollIntoView({ block: 'nearest' }); }
  }
  function failure(notice, error) {
    say(notice, error && error.message === 'refused' ? format('ragRefused', { reason: error.reason }) : t('adminFailed'), 'danger');
  }
  function status() { var n = node('div'); n.setAttribute('role', 'status'); return n; }
  function summary(counts) {
    return format('ragFinished', { inserted: counts.inserted || 0, updated: counts.updated || 0, moved: counts.moved || 0,
      deleted: counts.deleted || 0, refreshed: counts.refreshed || 0, failed: counts.failed || 0 });
  }
  function mount(root) {
    var content = root.querySelector('#luxpedia-rag-content'), notice = root.querySelector('#luxpedia-rag-notice'), data, dirty = false;
    function load(keepNotice) {
      if (!keepNotice) { notice.dataset.luxpediaI18n = 'adminLoading'; notice.textContent = t('adminLoading'); }
      return request('rag-overview').then(function (result) { data = result; dirty = false; if (!keepNotice) { say(notice, ''); } render(); })
        .catch(function (error) { failure(notice, error); });
    }
    function render() {
      content.replaceChildren(); var settings = data.settings, selection = Object.assign({}, settings.categories || {});
      var steps = node('ol', undefined, 'xHint'); ['ragStep1', 'ragStep2', 'ragStep3'].forEach(function (key) { steps.appendChild(labelled('li', key)); });
      content.appendChild(steps);
      content.appendChild(labelled('h3', 'ragConnection'));
      var form = node('div'); content.appendChild(form);
      var baseUrl = field(form, 'ragBaseUrl', 'url', settings.baseUrl); form.appendChild(labelled('p', 'ragBaseUrlHint', 'xHint'));
      var workspacePrefix = field(form, 'ragPrefix', 'text', settings.prefix); workspacePrefix.maxLength = 16;
      var apiKey = field(form, 'ragApiKey', 'password', ''); apiKey.autocomplete = 'new-password';
      var clearKey = null;
      if (settings.apiKeySet) { form.appendChild(labelled('p', 'ragApiKeySet', 'xHint')); clearKey = field(form, 'ragClearApiKey', 'checkbox', false); }
      var maxChars = field(form, 'ragMaxChars', 'number', settings.maxChars), timeout = field(form, 'ragTimeout', 'number', settings.timeoutSeconds);
      var testControls = node('p'); form.appendChild(testControls);
      var test = button(testControls, 'ragTest', function () {
        if (!baseUrl.value.trim()) { say(testStatus, t('ragEnterAddress'), 'warning'); baseUrl.focus(); return; }
        test.disabled = true; say(testStatus, t('ragTesting'), 'info');
        var connection = { baseUrl: baseUrl.value.trim(), prefix: workspacePrefix.value.trim(), apiKey: apiKey.value,
          clearApiKey: !!(clearKey && clearKey.checked), timeoutSeconds: Number(timeout.value) };
        request('rag-test', { payload: JSON.stringify(connection) }, true).then(function (result) {
          var problems = [];
          if (result.conflicts && result.conflicts.length) { problems.push(format('ragConflicts', { names: result.conflicts.join(', ') })); }
          if (result.missing && result.missing.length) { problems.push(format('ragMissing', { names: result.missing.join(', ') })); }
          if (!result.reachable) {
            say(testStatus, format(result.error ? 'ragTestUnreachable' : 'ragTestHttp', { url: result.baseUrl, error: result.error || '', status: result.httpStatus }), 'danger');
          } else {
            say(testStatus, [format('ragTestOk', { url: result.baseUrl, version: result.core_version || result.httpStatus })].concat(problems)
              .concat(dirty ? [t('ragTestUnsaved')] : []).join(' '), problems.length ? 'warning' : 'success');
          }
        }).catch(function (error) { failure(testStatus, error); }).finally(function () { test.disabled = false; });
      });
      var testStatus = status(); form.appendChild(testStatus);

      content.appendChild(labelled('h3', 'ragCategories')); content.appendChild(labelled('p', 'ragCategoriesHint', 'xHint'));
      var categories = table(content, ['ragCategory', 'ragOwnWorkspace', 'ragWorkspace', 'ragArticles']);
      data.categories.forEach(function (category) {
        var row = categories.insertRow(), title = row.insertCell();
        title.textContent = category.title; title.style.paddingLeft = (category.depth * 1.2) + 'em'; title.appendChild(node('small', ' · ' + category.reference));
        var check = document.createElement('input'); check.type = 'checkbox'; check.checked = category.enabled; check.setAttribute('aria-label', category.title);
        check.addEventListener('change', function () { selection[category.reference] = check.checked; changed(); });
        row.insertCell().appendChild(check);
        row.insertCell().textContent = category.workspace || t('ragNotSynced'); row.insertCell().textContent = category.articles;
      });
      var controls = node('p'); content.appendChild(controls);
      var save = button(controls, 'ragSaveSettings', function () {
        save.disabled = true;
        var desired = { baseUrl: baseUrl.value.trim(), prefix: workspacePrefix.value.trim(), apiKey: apiKey.value, clearApiKey: !!(clearKey && clearKey.checked),
          maxChars: Number(maxChars.value), timeoutSeconds: Number(timeout.value), categories: selection };
        request('rag-save-settings', { payload: JSON.stringify({ version: data.version, settings: desired }) }, true)
          .then(function () { return load(true); }).then(function () { say(content.querySelector('[data-rag-save-status]'), t('ragSaved'), 'success'); })
          .catch(function (error) { save.disabled = false; failure(saveStatus, error); });
      });
      save.className = 'btn btn-primary';
      var saveStatus = status(); saveStatus.dataset.ragSaveStatus = '1'; content.appendChild(saveStatus);
      function changed() {
        if (dirty) { return; } dirty = true; say(saveStatus, t('ragUnsaved'), 'warning');
        preview.disabled = true; say(previewStatus, t('ragSaveFirst'), 'warning');
      }
      [baseUrl, workspacePrefix, apiKey, maxChars, timeout].concat(clearKey ? [clearKey] : []).forEach(function (input) { input.addEventListener('input', changed); input.addEventListener('change', changed); });

      content.appendChild(labelled('h3', 'ragPending')); content.appendChild(labelled('p', 'ragPendingHint', 'xHint'));
      var counts = data.plan.counts, excluded = data.excluded || {};
      content.appendChild(node('p', ['insert', 'update', 'move', 'delete', 'refresh'].map(function (type) { return t('ragAction.' + type) + ': ' + counts[type]; }).join(' · ')));
      content.appendChild(node('p', format('ragExcluded', { uncategorized: excluded.uncategorized || 0, disabled: excluded.disabled || 0, large: excluded.too_large || 0 }), 'xHint'));
      if (data.plan.items.length) {
        var pending = table(content, ['ragType', 'ragPage', 'ragWorkspace', 'ragFile']);
        data.plan.items.forEach(function (item) {
          var row = pending.insertRow(); row.insertCell().textContent = t('ragAction.' + item.type);
          row.insertCell().textContent = (item.title || item.reference) + ' · ' + item.reference;
          row.insertCell().textContent = item.type === 'move' ? item.oldWorkspace + ' → ' + item.workspace : item.workspace || item.oldWorkspace;
          row.insertCell().textContent = item.fileSource || '—';
        });
      } else { content.appendChild(labelled('p', 'ragNothing')); }
      var actions = node('p'); content.appendChild(actions);
      var preview = button(actions, 'ragPreview', function () {
        preview.disabled = true;
        request('rag-preview', {}, true).then(function (result) {
          return result.lost.length || result.recreated.length ? load(true).then(function () { confirm(result); }) : confirm(result);
        })
          .catch(function (error) { failure(previewStatus, error); }).finally(function () { preview.disabled = false; });
      });
      preview.className = 'btn btn-primary';
      var running = !!(data.run && data.run.id && !data.run.done);
      preview.disabled = !settings.baseUrl || running;
      button(actions, 'ragReload', function () { if (!dirty || window.confirm(t('ragDiscard'))) { load(); } });
      var previewStatus = status(); content.appendChild(previewStatus);
      if (!settings.baseUrl) { say(previewStatus, t('ragNeedAddress'), 'info'); }
      if (running) { say(notice, format('ragRunning', { done: data.run.processed, total: data.run.total }), 'info'); }

      content.appendChild(labelled('h3', 'ragOwned'));
      if (data.workspaces.length) {
        var owned = table(content, ['ragWorkspace', 'ragCategory', 'ragDocuments', 'ragCreated', 'ragStatus']);
        data.workspaces.forEach(function (workspace) {
          var row = owned.insertRow(); [workspace.name, workspace.category, workspace.documents, workspace.createdAt ? new Date(workspace.createdAt).toLocaleString() : '',
            workspace.lost ? format('ragLost', { time: new Date(workspace.lost).toLocaleString() }) : t('ragActive')].forEach(function (value) { row.insertCell().textContent = value; });
        });
      } else { content.appendChild(labelled('p', 'ragNone')); }
      content.appendChild(labelled('h3', 'ragRuns'));
      if (data.runs.length) {
        var runs = table(content, ['ragStarted', 'ragBy', 'ragResult']);
        data.runs.forEach(function (run) {
          var row = runs.insertRow(); row.insertCell().textContent = new Date(run.startedAt).toLocaleString(); row.insertCell().textContent = run.actor;
          row.insertCell().textContent = summary(run.counts) + (run.aborted ? ' ' + t('ragAborted') : '');
        });
      } else { content.appendChild(labelled('p', 'ragNone')); }
      if (data.failures.length) {
        content.appendChild(labelled('h3', 'ragFailures'));
        var failures = table(content, ['ragPage', 'ragType', 'ragReason']);
        data.failures.forEach(function (item) { var row = failures.insertRow(); [item.reference, item.step, item.reason].forEach(function (value) { row.insertCell().textContent = value; }); });
      }
    }
    function confirm(result) {
      var dialog = node('dialog', undefined, 'modal-content'); dialog.id = 'luxpedia-rag-preview'; dialog.style.cssText = 'max-width:calc(100vw - 24px);width:48em;padding:1.5em;';
      dialog.appendChild(labelled('h3', 'ragPreviewTitle')); dialog.appendChild(labelled('p', 'ragPreviewHint'));
      if (result.lost.length) { dialog.appendChild(node('p', format('ragLostNotice', { names: result.lost.join(', ') }), 'alert alert-warning')); }
      if (result.recreated.length) { dialog.appendChild(node('p', format('ragRecreatedNotice', { names: result.recreated.join(', ') }), 'alert alert-danger')); }
      if (result.total === 0) { dialog.appendChild(labelled('p', 'ragNothing')); }
      dialog.appendChild(node('p', ['insert', 'update', 'move', 'delete', 'refresh'].map(function (type) { return t('ragAction.' + type) + ': ' + result.counts[type]; }).join(' · ')));
      var list = node('ul'); result.items.slice(0, 50).forEach(function (item) { list.appendChild(node('li', t('ragAction.' + item.type) + ' · ' + item.reference)); }); dialog.appendChild(list);
      var controls = node('div'); dialog.appendChild(controls);
      var start = button(controls, 'ragStart', function () {
        start.disabled = cancel.disabled = true; dialog.close(); dialog.remove();
        request('rag-run', { payload: result.ticket }, true).then(follow).catch(function (error) { failure(notice, error); load(true); });
      });
      var cancel = button(controls, 'cancel', function () { dialog.close(); dialog.remove(); });
      start.disabled = result.total === 0;
      document.body.appendChild(dialog); dialog.showModal(); dialog.addEventListener('close', function () { if (!start.disabled) { dialog.remove(); } });
    }
    function follow(result) {
      var run = result.run;
      say(notice, format('ragRunning', { done: run.processed, total: run.total }), 'info');
      if (!run.done) { return request('rag-continue', { payload: run.id }, true).then(follow).catch(function (error) { failure(notice, error); load(true); }); }
      return load(true).then(function () {
        say(notice, summary(run.counts) + (run.aborted ? ' ' + t('ragAborted') : ''), run.aborted || run.counts.failed ? 'warning' : 'success');
      });
    }
    load();
  }
  function boot() { var root = document.getElementById('luxpedia-rag-sync'); if (root && !root.dataset.bound) { root.dataset.bound = '1'; mount(root); } }
  document.addEventListener('xwiki:dom:updated', boot);
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();
