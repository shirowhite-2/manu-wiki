(function () {
  'use strict';
  if (window.LuxPediaAdminTools) { return; } window.LuxPediaAdminTools = true;
  var t = window.LuxPediaI18n.t, prefix = location.pathname.split('/bin/')[0] || '';
  var endpoint = prefix + '/bin/get/LuxPedia/Workflow/AdminTools?outputSyntax=plain';
  var token = '', generation = 0, tab = 'dashboard', days = '30', area = 'Knowledge', search = '';
  function node(tag, text, className) { var n = document.createElement(tag); if (text !== undefined) { n.textContent = text; } if (className) { n.className = className; } return n; }
  function labelled(tag, key, className) { var n = node(tag, t(key), className); n.dataset.luxpediaI18n = key; return n; }
  function message(target, key) { target.dataset.luxpediaI18n = key; target.textContent = t(key); }
  function button(parent, key, handler) { var n = labelled('button', key, 'btn btn-default'); n.type = 'button'; n.style.marginRight = '.4em'; n.addEventListener('click', handler); parent.appendChild(n); return n; }
  function request(operation, data, write) {
    var options = { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } };
    var fields = new URLSearchParams(Object.assign({ operation: operation }, data || {})), url = endpoint;
    if (write) { fields.set('form_token', token); options.method = 'POST'; options.headers['Content-Type'] = 'application/x-www-form-urlencoded'; options.body = fields.toString(); }
    else { url += '&' + fields.toString(); }
    return fetch(url, options).then(function (r) { if (!r.ok) { throw new Error('unavailable'); } return r.json(); }).then(function (result) {
      if (result.status !== 'ok') { throw new Error(result.status || 'unavailable'); } if (result.token) { token = result.token; } return result;
    });
  }
  function table(parent, headings) {
    var wrapper = node('div'); wrapper.style.overflowX = 'auto'; var n = node('table', undefined, 'table table-striped luxpedia-system-list');
    var row = n.createTHead().insertRow(); headings.forEach(function (key) { var th = labelled('th', key); th.scope = 'col'; row.appendChild(th); });
    wrapper.appendChild(n); parent.appendChild(wrapper); return n.createTBody();
  }
  function field(parent, key, type, value, options) {
    var group = node('div', undefined, 'form-group'); group.style.cssText = 'margin:.5em .5em .5em 0;max-width:100%;';
    var label = labelled('label', key); var input = document.createElement(type === 'select' || type === 'textarea' ? type : 'input');
    input.id = 'luxpedia-field-' + (++field.next); input.dataset.adminField = key; input.className = 'form-control'; input.style.maxWidth = '100%'; label.htmlFor = input.id;
    if (!['select', 'textarea'].includes(type)) { input.type = type; }
    (options || []).forEach(function (entry) { var option = node('option', entry[1]); option.value = entry[0]; if (entry[2]) { option.dataset.luxpediaI18n = entry[2]; } input.appendChild(option); });
    if (type === 'checkbox') { input.checked = !!value; input.className = ''; } else { input.value = value || ''; }
    if (type === 'textarea') { input.rows = 3; input.maxLength = 2000; } else if (type !== 'select') { input.maxLength = 1024; }
    group.appendChild(label); group.appendChild(document.createTextNode(' ')); group.appendChild(input); parent.appendChild(group); return input;
  }
  field.next = 0;
  function nativeAdmin(section) { return prefix + '/bin/admin/XWiki/XWikiPreferences?section=' + encodeURIComponent(section); }
  function overview(parent, data, reload) {
    var controls = node('div', undefined, 'form-inline'); parent.appendChild(controls);
    var range = field(controls, 'adminPeriod', 'select', days, ['7', '30', '90'].map(function (d) { return [d, t('adminDays').replace('{count}', d)]; }));
    range.addEventListener('change', function () { days = range.value; reload(); }); button(controls, 'adminRefresh', reload);
    var native = node('p'); parent.appendChild(native);
    [['users', 'Users'], ['adminGroups', 'Groups'], ['adminRights', 'Rights']].forEach(function (entry) { var a = labelled('a', entry[0], 'btn btn-link'); a.href = nativeAdmin(entry[1]); native.appendChild(a); });
    parent.appendChild(labelled('h3', 'adminRoles'));
    var body = table(parent, ['adminRole', 'adminCount', 'adminOpen']);
    Object.keys(data.roles).forEach(function (role) {
      var row = body.insertRow(); row.insertCell().textContent = t(role === 'all' ? 'users' : 'role.' + role); row.insertCell().textContent = data.roles[role];
      var link = labelled('a', 'adminOpen'); link.href = prefix + '/bin/view/XWiki/Users/' + (role === 'all' ? '' : role.charAt(0).toUpperCase() + role.slice(1) + '/'); row.insertCell().appendChild(link);
    });
    parent.appendChild(labelled('p', 'adminRoleHint', 'xHint')); parent.appendChild(labelled('h3', 'adminActivity'));
    var activity = data.activity;
    if (!activity.available) { parent.appendChild(labelled('p', 'adminActivityUnavailable')); }
    else {
      parent.appendChild(node('p', t('adminActivitySummary').replace('{edits}', activity.edits).replace('{users}', activity.users).replace('{days}', activity.days)));
      parent.appendChild(labelled('p', 'adminActivityBasis', 'xHint'));
      var leaders = table(parent, ['adminActor', 'adminMajorRevisions']);
      activity.leaders.forEach(function (entry) { var row = leaders.insertRow(); row.insertCell().textContent = entry.user; row.insertCell().textContent = entry.count; });
    }
    parent.appendChild(labelled('h3', 'adminRuntime'));
    var info = table(parent, ['adminMetric', 'adminValue']);
    [['adminWikiVersion', data.runtime.wiki], ['adminPolicy', data.runtime.policy], ['adminJava', data.runtime.java],
      ['adminUptime', Math.floor(data.runtime.uptime / 60000) + ' ' + t('adminMinutes')],
      ['adminHeap', Math.round(data.runtime.heapUsed / 1048576) + ' / ' + Math.round(data.runtime.heapMax / 1048576) + ' MiB'],
      ['adminDisk', data.runtime.diskTotal ? (data.runtime.diskFree / 1073741824).toFixed(1) + ' / ' + (data.runtime.diskTotal / 1073741824).toFixed(1) + ' GiB' : t('adminState.unavailable')]].forEach(function (entry) { var row = info.insertRow(); row.insertCell().textContent = t(entry[0]); row.insertCell().textContent = entry[1]; });
    data.runtime.checks.forEach(function (entry) { var row = info.insertRow(); row.insertCell().textContent = t('adminHealth.' + entry.name); row.insertCell().textContent = t('adminState.' + entry.state); });
    parent.appendChild(labelled('p', 'adminRuntimeHint', 'xHint'));
  }
  function operations(root) {
    var content = root.querySelector('#luxpedia-admin-content'), notice = root.querySelector('#luxpedia-admin-notice');
    function load(page) {
      root.querySelectorAll('[data-admin-tab]').forEach(function (button) { button.classList.toggle('active', button.dataset.adminTab === tab); button.setAttribute('aria-pressed', String(button.dataset.adminTab === tab)); });
      var id = ++generation; message(notice, 'adminLoading');
      request(tab, { q: search, area: area, page: String(page || 1), days: days }).then(function (data) {
        if (id !== generation) { return; } notice.textContent = ''; delete notice.dataset.luxpediaI18n; content.replaceChildren();
        if (tab === 'dashboard') { overview(content, data, function () { load(1); }); return; }
        var restoring = tab === 'trash', selected = new Set();
        var tools = node('div', undefined, 'form-inline'); content.appendChild(tools);
        if (!restoring) {
          var scope = field(tools, 'browserArea', 'select', area, [['Knowledge', t('knowledge')], ['Drafts', t('drafts')]]);
          scope.addEventListener('change', function () { area = scope.value; load(1); });
          var query = field(tools, 'search', 'search', search); query.maxLength = 100;
          button(tools, 'search', function () { search = query.value; load(1); }); query.addEventListener('keydown', function (e) { if (e.key === 'Enter') { e.preventDefault(); search = query.value; load(1); } });
        }
        button(tools, 'adminRefresh', function () { load(data.page); });
        var preview = button(tools, restoring ? 'adminPreviewRestore' : 'adminPreviewDelete', function () {
          preview.disabled = true;
          request(restoring ? 'preview-restore' : 'preview-delete', { payload: JSON.stringify(Array.from(selected)) }, true).then(function (data) {
            showPreview(data, notice, function () { load(1); });
          }).catch(function (e) { message(notice, e.message === 'conflict' ? 'adminConflict' : 'adminFailed'); }).finally(function () { preview.disabled = selected.size === 0; });
        }); preview.disabled = true;
        content.appendChild(labelled('p', 'adminBatchHint', 'xHint'));
        var body = table(content, restoring ? ['selectDraft', 'title', 'adminLocale', 'adminDeletedAt', 'adminActor'] : ['selectDraft', 'title', 'adminVersion', 'status']);
        var checkboxes = [];
        data.items.forEach(function (item) {
          var row = body.insertRow(), checkbox = document.createElement('input'); checkbox.type = 'checkbox'; checkbox.setAttribute('aria-label', item.title || item.reference);
          var value = restoring ? item.id : item.reference; checkbox.disabled = !restoring && item.state !== 'ready';
          checkbox.addEventListener('change', function () {
            if (checkbox.checked && selected.size >= 20) { checkbox.checked = false; message(notice, 'adminSelectLimit'); return; }
            checkbox.checked ? selected.add(value) : selected.delete(value); preview.disabled = selected.size === 0;
          }); checkboxes.push(checkbox); row.insertCell().appendChild(checkbox);
          var title = row.insertCell();
          if (!restoring && item.url) { var a = node('a', item.title || item.reference); a.href = item.url; title.appendChild(a); title.appendChild(node('small', ' · ' + item.reference)); }
          else { title.textContent = item.reference; }
          row.insertCell().textContent = restoring ? item.locale || t('adminDefaultLanguage') : item.version;
          row.insertCell().textContent = restoring ? new Date(item.date).toLocaleString() : t('adminPageState.' + item.state);
          if (restoring) { row.insertCell().textContent = item.actor; }
        });
        button(tools, 'adminSelectVisible', function () {
          selected.clear(); checkboxes.forEach(function (check) { check.checked = !check.disabled && selected.size < 20; if (check.checked) { selected.add(restoring ? data.items[checkboxes.indexOf(check)].id : data.items[checkboxes.indexOf(check)].reference); } });
          preview.disabled = selected.size === 0;
        });
        var pager = node('p'); content.appendChild(pager);
        var previous = button(pager, 'tablePrevious', function () { load(data.page - 1); }); previous.disabled = data.page <= 1;
        pager.appendChild(node('span', ' ' + data.page + ' / ' + data.pages + ' · ' + data.total + ' '));
        var next = button(pager, 'tableNext', function () { load(data.page + 1); }); next.disabled = data.page >= data.pages;
      }).catch(function () { if (id === generation) { message(notice, 'adminFailed'); } });
    }
    root.querySelectorAll('[data-admin-tab]').forEach(function (button) { button.addEventListener('click', function () { tab = button.dataset.adminTab; load(1); }); });
    root.luxpediaReload = function () { var input = root.querySelector('[data-admin-field="search"]'); if (input) { search = input.value; } load(1); };
    load(1);
  }
  function showPreview(data, notice, reload) {
    var dialog = node('dialog', undefined, 'modal-content'); dialog.id = 'luxpedia-admin-preview'; dialog.style.cssText = 'max-width:calc(100vw - 24px);width:48em;padding:1.5em;';
    dialog.appendChild(labelled('h3', data.operation === 'delete' ? 'adminPreviewDelete' : 'adminPreviewRestore'));
    dialog.appendChild(labelled('p', 'adminPreviewHint'));
    var list = node('ul'); data.items.forEach(function (item) { list.appendChild(node('li', item.reference + (item.version ? ' · ' + item.version + ' · ' + item.attachments + ' ' + t('adminAttachments') + ' · ' + item.translations + ' ' + t('adminTranslations') : ' · ' + (item.locale || t('adminDefaultLanguage'))))); }); dialog.appendChild(list);
    var controls = node('div'); dialog.appendChild(controls);
    var confirm = button(controls, data.operation === 'delete' ? 'adminConfirmDelete' : 'adminConfirmRestore', function () {
      confirm.disabled = cancel.disabled = true;
      request('execute', { payload: data.ticket }, true).then(function (result) {
        dialog.close(); dialog.remove(); var succeeded = result.items.filter(function (item) { return ['deleted', 'restored'].includes(item.result); }).length;
        reload();
        var previous = document.getElementById('luxpedia-admin-results'); if (previous) { previous.remove(); }
        var output = node('section', undefined, 'alert alert-info'); output.id = 'luxpedia-admin-results';
        output.appendChild(node('p', t('adminResultSummary').replace('{count}', succeeded).replace('{total}', result.items.length)));
        result.items.forEach(function (item) { output.appendChild(node('p', item.reference + ' · ' + t('adminResult.' + item.result))); }); notice.parentNode.insertBefore(output, notice.nextSibling);
      }).catch(function (error) { confirm.disabled = cancel.disabled = false; message(notice, error.message === 'conflict' ? 'adminConflict' : 'adminFailed'); });
    });
    var cancel = button(controls, 'cancel', function () { dialog.close(); dialog.remove(); });
    document.body.appendChild(dialog); dialog.showModal(); dialog.addEventListener('close', function () { if (!confirm.disabled) { dialog.remove(); } });
  }
  function navigation(root) {
    var parent = root.querySelector('#luxpedia-navigation-editor'), notice = root.querySelector('#luxpedia-navigation-notice'), snapshot, categories = [], editMenu = -1, editBanner = -1;
    function load() {
      message(notice, 'adminLoading');
      Promise.all([request('settings'), request('categories')]).then(function (values) { snapshot = values[0]; categories = values[1].items; notice.textContent = ''; delete notice.dataset.luxpediaI18n; render(); })
        .catch(function () { message(notice, 'adminFailed'); });
    }
    function render() {
      parent.replaceChildren(); var settings = snapshot.settings;
      var qr = field(parent, 'navQrEnabled', 'checkbox', settings.qrEnabled); qr.addEventListener('change', function () { settings.qrEnabled = qr.checked; });
      parent.appendChild(labelled('h3', 'navMenus')); parent.appendChild(labelled('p', 'navMenuHint', 'xHint'));
      var menus = table(parent, ['navLabelEn', 'navLabelZh', 'navDestination', 'navParent', 'navLocation', 'adminActions']);
      settings.menus.forEach(function (item, index) {
        var row = menus.insertRow(); [item.en, item.zh, item.destination, (settings.menus.find(function (candidate) { return candidate.id === item.parent; }) || {}).en || (settings.menus.find(function (candidate) { return candidate.id === item.parent; }) || {}).zh || '—', t('navLocation.' + item.location)].forEach(function (value) { row.insertCell().textContent = value; });
        var actions = row.insertCell(); button(actions, 'navEdit', function () { editMenu = index; render(); });
        button(actions, 'navUp', function () { var sibling = settings.menus.slice(0, index).map(function (row, i) { return row.parent === item.parent && row.location === item.location ? i : -1; }).filter(function (i) { return i >= 0; }).pop(); if (sibling !== undefined) { [settings.menus[sibling], settings.menus[index]] = [item, settings.menus[sibling]]; } render(); });
        button(actions, 'navDown', function () { var sibling = settings.menus.findIndex(function (row, i) { return i > index && row.parent === item.parent && row.location === item.location; }); if (sibling >= 0) { [settings.menus[sibling], settings.menus[index]] = [item, settings.menus[sibling]]; } render(); });
        button(actions, 'navRemove', function () { if (settings.menus.some(function (candidate) { return candidate.parent === item.id; })) { message(notice, 'navRemoveChildren'); return; } settings.menus.splice(index, 1); editMenu = -1; render(); });
      });
      button(parent, 'navAddMenu', function () { editMenu = settings.menus.length; render(); });
      if (editMenu >= 0) {
        var existing = settings.menus[editMenu], form = node('section', undefined, 'well'); form.id = 'luxpedia-menu-form'; parent.appendChild(form);
        var en = field(form, 'navLabelEn', 'text', existing && existing.en), zh = field(form, 'navLabelZh', 'text', existing && existing.zh);
        en.maxLength = zh.maxLength = 120;
        var destination = field(form, 'navDestination', 'text', existing && existing.destination); form.appendChild(labelled('p', 'navDestinationHint', 'xHint'));
        var location = field(form, 'navLocation', 'select', existing ? existing.location : 'sidebar', [['sidebar', t('navLocation.sidebar'), 'navLocation.sidebar'], ['top', t('navLocation.top'), 'navLocation.top']]);
        var descendants = new Set(existing ? [existing.id] : []); for (var level = 0; level < 4; level++) { settings.menus.forEach(function (item) { if (descendants.has(item.parent)) { descendants.add(item.id); } }); }
        var menuParent = field(form, 'navParent', 'select', existing && existing.parent, [['', t('navNoParent')]].concat(settings.menus.filter(function (item) { return !descendants.has(item.id); }).map(function (item) { return [item.id, item.en || item.zh]; })));
        function chooseParent() { var parent = settings.menus.find(function (item) { return item.id === menuParent.value; }); if (parent) { location.value = parent.location; } location.disabled = !!parent; }
        menuParent.addEventListener('change', chooseParent); chooseParent();
        var enabled = field(form, 'navEnabled', 'checkbox', !existing || existing.enabled), roles = [];
        form.appendChild(labelled('h4', 'navVisibleRoles'));
        ['all', 'authenticated', 'managers', 'editors', 'reviewers', 'readers'].forEach(function (role) {
          var group = node('label', undefined, 'checkbox-inline'), check = document.createElement('input'); check.type = 'checkbox'; check.value = role;
          check.checked = existing ? existing.roles.includes(role) : role === 'all'; group.appendChild(check); group.appendChild(labelled('span', 'navRole.' + role)); form.appendChild(group); roles.push(check);
        });
        button(form, 'navApplyEntry', function () {
          if (!en.value.trim() && !zh.value.trim() || !roles.some(function (r) { return r.checked; })) { message(notice, 'navEntryInvalid'); return; }
          var value = { id: existing ? existing.id : 'menu_' + Math.random().toString(36).slice(2, 12), parent: menuParent.value, en: en.value, zh: zh.value, destination: destination.value, location: location.value, enabled: enabled.checked, roles: roles.filter(function (r) { return r.checked; }).map(function (r) { return r.value; }) };
          if (existing) { settings.menus.forEach(function (child) { if (descendants.has(child.id)) { child.location = value.location; } }); }
          settings.menus[editMenu] = value; editMenu = -1; render(); message(notice, 'navUnsaved');
        }); button(form, 'cancel', function () { editMenu = -1; render(); });
      }
      parent.appendChild(labelled('h3', 'navBanners')); var banners = table(parent, ['browserCategory', 'navHeader', 'navFooter', 'adminActions']);
      settings.banners.forEach(function (item, index) {
        var row = banners.insertRow(); [item.category, item.headerEn || item.headerZh, item.footerEn || item.footerZh].forEach(function (value) { row.insertCell().textContent = value; });
        var actions = row.insertCell(); button(actions, 'navEdit', function () { editBanner = index; render(); }); button(actions, 'navRemove', function () { settings.banners.splice(index, 1); editBanner = -1; render(); });
      });
      button(parent, 'navAddBanner', function () { editBanner = settings.banners.length; render(); });
      if (editBanner >= 0) {
        var banner = settings.banners[editBanner], box = node('section', undefined, 'well'); box.id = 'luxpedia-banner-form'; parent.appendChild(box);
        var category = field(box, 'browserCategory', 'select', banner && banner.category, categories.map(function (item) { return [item.reference, (item.title || item.reference) + ' · ' + item.reference]; }));
        var fields = {};
        [['headerEn', 'navHeaderEn'], ['headerZh', 'navHeaderZh'], ['footerEn', 'navFooterEn'], ['footerZh', 'navFooterZh']].forEach(function (entry) { fields[entry[0]] = field(box, entry[1], 'textarea', banner && banner[entry[0]]); });
        var inherit = field(box, 'navInherit', 'checkbox', !banner || banner.inherit), active = field(box, 'navEnabled', 'checkbox', !banner || banner.enabled);
        button(box, 'navApplyEntry', function () {
          var value = { category: category.value, inherit: inherit.checked, enabled: active.checked }; Object.keys(fields).forEach(function (key) { value[key] = fields[key].value; });
          settings.banners[editBanner] = value; editBanner = -1; render(); message(notice, 'navUnsaved');
        }); button(box, 'cancel', function () { editBanner = -1; render(); });
      }
      parent.appendChild(labelled('p', 'navPublishHint', 'xHint'));
      var save = button(parent, 'navSave', function () {
        if (editMenu >= 0 || editBanner >= 0) { message(notice, 'navFinishEntry'); return; }
        save.disabled = true;
        request('save-settings', { payload: JSON.stringify({ version: snapshot.version, settings: settings }) }, true).then(function (data) { snapshot = data; render(); message(notice, 'navSaved'); document.dispatchEvent(new CustomEvent('luxpedia:presentationrefresh')); })
          .catch(function (error) { save.disabled = false; message(notice, error.message === 'conflict' ? 'navSaveConflict' : 'adminFailed'); });
      }); button(parent, 'adminRefresh', load);
    }
    load();
  }
  function boot() {
    var tools = document.getElementById('luxpedia-admin-tools'), settings = document.getElementById('luxpedia-navigation-settings');
    if (tools && !tools.dataset.bound) { tools.dataset.bound = '1'; operations(tools); }
    if (settings && !settings.dataset.bound) { settings.dataset.bound = '1'; navigation(settings); }
  }
  document.addEventListener('xwiki:dom:updated', boot);
  document.addEventListener('luxpedia:languagechange', function () { var tools = document.getElementById('luxpedia-admin-tools'); if (tools && tools.luxpediaReload) { tools.luxpediaReload(); } var settings = document.getElementById('luxpedia-navigation-settings'); if (settings) { message(settings.querySelector('#luxpedia-navigation-notice'), 'navLanguageHint'); } boot(); });
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); } else { boot(); }
})();
