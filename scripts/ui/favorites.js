(function () {
  'use strict';
  if (window.LuxPediaFavorites) { return; }
  window.LuxPediaFavorites = true;
  var t = window.LuxPediaI18n.t;
  var snapshot, busy = false, generation = 0, search = '', pageNumber = 1;
  var reference = '';
  var notice, titleActions;
  var prefix = (location.pathname.split('/bin/')[0] || '');
  function documentReference() {
    var nativeReference = document.documentElement.getAttribute('data-xwiki-reference');
    if (nativeReference) { return nativeReference; }
    var meta = document.querySelector('meta[name="document"]');
    if (meta && meta.content) { return meta.content; }
    try {
      if (window.XWiki && XWiki.currentDocument && XWiki.Model && typeof XWiki.Model.serialize === 'function') {
        return XWiki.Model.serialize(XWiki.currentDocument.getDocumentReference());
      }
    } catch (error) { /* the page may still be initializing */ }
    return '';
  }
  function wikiURL(name, action, query) {
    try {
      if (window.XWiki && typeof XWiki.Document === 'function') {
        var parts = name.split('.'), page = parts.pop();
        // Model.resolve parses relative names; it does not supply the wiki component required by Document.
        return new XWiki.Document(page, parts.join('.'), XWiki.Document.currentWiki || XWiki.currentWiki).getURL(action, query);
      }
    } catch (error) {
      // A partially initialized native model must not prevent binding the favorite controls.
    }
    return prefix + '/bin/' + action + '/' + name.replace(/\./g, '/') + (query ? '?' + query : '');
  }
  var endpoint, allURL;

  function text(tag, value) { var node = document.createElement(tag); node.textContent = value; return node; }
  function safeURL(value) {
    try { var url = new URL(value, location.href); return url.origin === location.origin ? url.href : ''; }
    catch (error) { return ''; }
  }
  function itemLink(item) {
    var anchor = text('a', item.title); anchor.href = safeURL(item.url) || allURL;
    return anchor;
  }
  function report(key) { if (notice) { notice.dataset.luxpediaI18n = key; notice.textContent = key ? t(key) : ''; } }
  function updateButton(button) {
    var favorite = snapshot && snapshot.favorite === true;
    button.textContent = (favorite ? '★ ' : '☆ ') + t(favorite ? 'favoritesSaved' : 'favoritesAdd');
    button.setAttribute('aria-pressed', String(favorite));
    button.title = t(favorite ? 'favoritesRemove' : 'favoritesAdd');
    button.disabled = busy;
  }
  function syncMenu() {
    document.querySelectorAll('#addFavoriteMenu, #dropFavoriteMenu').forEach(function (link) {
      link.hidden = !snapshot || !snapshot.eligible;
      if (snapshot && snapshot.eligible) {
        var action = t(snapshot.favorite ? 'favoritesMenuRemove' : 'favoritesMenuAdd');
        link.textContent = (snapshot.favorite ? '★ ' : '☆ ') + action;
        link.title = action;
        link.setAttribute('aria-disabled', String(busy));
      }
    });
    // Replace the old profile-edit panel workflow with the personal list entry.
    var oldAdd = document.getElementById('addFavorite');
    var oldPanel = oldAdd && oldAdd.closest('.panel');
    if (oldPanel && document.getElementById('luxpedia-favorites-sidebar')) { oldPanel.hidden = true; }
  }
  function render() {
    var authenticated = snapshot && snapshot.status === 'ok' && snapshot.authenticated;
    if (titleActions) {
      titleActions.hidden = !authenticated || !snapshot.eligible;
      updateButton(titleActions.querySelector('button'));
    }
    syncMenu();
    var sidebar = document.getElementById('luxpedia-favorites-sidebar');
    if (sidebar) {
      sidebar.hidden = !authenticated;
      if (authenticated) {
        sidebar.querySelector('h3').textContent = t('myFavorites');
        var recent = sidebar.querySelector('.luxpedia-favorites-recent'); recent.replaceChildren();
        if (!snapshot.recent.length) { recent.appendChild(text('p', t('favoritesEmpty'))); }
        else {
          var ul = document.createElement('ul'); ul.style.overflowWrap = 'anywhere';
          snapshot.recent.forEach(function (item) { var li = document.createElement('li'); li.appendChild(itemLink(item)); ul.appendChild(li); });
          recent.appendChild(ul);
        }
        var all = sidebar.querySelector('.luxpedia-favorites-all'); all.href = allURL; all.textContent = t('favoritesViewAll');
      }
    }
    var results = document.querySelector('#luxpedia-favorites-page .luxpedia-favorites-results');
    if (!results) { return; }
    results.replaceChildren();
    if (!authenticated) { results.appendChild(text('p', t('favoritesSignIn'))); return; }
    results.appendChild(text('p', t('myFavorites') + ': ' + snapshot.total));
    if (!snapshot.items.length) { results.appendChild(text('p', t(search ? 'favoritesNoMatches' : 'favoritesEmpty'))); return; }
    var table = document.createElement('table'); table.className = 'table table-striped';
    table.style.cssText = 'width:100%;table-layout:fixed;overflow-wrap:anywhere;';
    var head = document.createElement('thead'), row = document.createElement('tr');
    ['title', 'primaryCategory', 'favoritesActions'].forEach(function (key) {
      var th = text('th', t(key)); th.scope = 'col'; row.appendChild(th);
    });
    head.appendChild(row); table.appendChild(head);
    var body = document.createElement('tbody');
    snapshot.items.forEach(function (item) {
      var tr = document.createElement('tr'), title = document.createElement('td'); title.appendChild(itemLink(item));
      tr.appendChild(title); tr.appendChild(text('td', item.category));
      var actions = document.createElement('td'), remove = text('button', t('favoritesRemove'));
      remove.type = 'button'; remove.className = 'btn btn-default btn-sm'; remove.disabled = busy;
      remove.style.cssText = 'max-width:100%;white-space:normal;';
      remove.addEventListener('click', function () { change(item.reference, 'remove'); });
      actions.appendChild(remove); tr.appendChild(actions); body.appendChild(tr);
    });
    table.appendChild(body); results.appendChild(table);
    var nav = document.createElement('nav'); nav.setAttribute('aria-label', t('favoritesPagination'));
    [['favoritesPrevious', -1], ['favoritesNext', 1]].forEach(function (entry) {
      var button = text('button', t(entry[0])); button.type = 'button'; button.className = 'btn btn-default btn-sm';
      button.disabled = busy || (entry[1] < 0 ? snapshot.page <= 1 : snapshot.page >= snapshot.pages);
      button.addEventListener('click', function () { pageNumber = snapshot.page + entry[1]; load(); });
      nav.appendChild(button); nav.appendChild(document.createTextNode(' '));
    });
    nav.appendChild(text('span', snapshot.page + ' / ' + snapshot.pages)); results.appendChild(nav);
  }

  async function load() {
    var request = ++generation;
    var url = new URL(endpoint, location.href);
    url.searchParams.set('reference', reference); url.searchParams.set('q', search); url.searchParams.set('page', pageNumber);
    var response = await fetch(url.href, { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) { throw new Error('favorite read failed'); }
    var data = await response.json();
    if (request !== generation) { return; }
    if (data.status === 'forbidden') { snapshot = data; render(); return; }
    if (data.status !== 'ok' || !Array.isArray(data.items) || !Array.isArray(data.recent)) { throw new Error('favorites unavailable'); }
    snapshot = data; render();
  }

  async function change(name, operation) {
    if (busy || !snapshot || snapshot.status !== 'ok') { return; }
    busy = true; report('favoritesSaving'); render();
    var saved = false;
    try {
      var fields = new URLSearchParams({ reference: name, operation: operation,
        version: snapshot.version, form_token: snapshot.token });
      var response = await fetch(endpoint, { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8' }, body: fields.toString() });
      if (!response.ok) { throw new Error('favorite save failed'); }
      var result = await response.json();
      if (result.status === 'conflict') { await load(); report('favoritesConflict'); return; }
      if (result.status !== 'ok') { throw new Error('favorite rejected'); }
      saved = true;
      await load();
      report(operation === 'add' ? 'favoritesAdded' : 'favoritesRemoved');
      try { localStorage.setItem('luxpedia.favorites.changed', String(Date.now())); } catch (ignored) { /* optional notification */ }
    } catch (error) { report(saved ? 'favoritesRefreshFailed' : 'favoritesError'); }
    finally { busy = false; render(); }
  }

  function boot() {
    if (!/\/bin\/(?:view|edit|inline)\//.test(location.pathname)) { return; }
    reference = documentReference();
    endpoint = wikiURL('LuxPedia.Workflow.Favorites', 'get', 'outputSyntax=plain');
    allURL = wikiURL('Favorites.WebHome', 'view');
    var title = document.querySelector('#document-title h1, #document-title, h1.document-title');
    if (title) {
      titleActions = document.createElement('span'); titleActions.id = 'luxpedia-favorite-actions';
      titleActions.className = 'pull-right'; titleActions.style.cssText = 'font-size:14px;'; titleActions.hidden = true;
      var button = document.createElement('button'); button.id = 'luxpedia-favorite-toggle';
      button.type = 'button'; button.className = 'btn btn-default btn-sm';
      button.addEventListener('click', function () { change(reference, snapshot.favorite ? 'remove' : 'add'); });
      titleActions.appendChild(button); title.appendChild(titleActions);
    }
    notice = document.createElement('div'); notice.id = 'luxpedia-favorites-notice';
    notice.setAttribute('role', 'status'); notice.style.fontSize = '14px';
    var full = document.getElementById('luxpedia-favorites-page');
    (full || (title && title.parentNode) || document.body).appendChild(notice);
    var form = document.getElementById('luxpedia-favorites-search');
    if (form) { form.addEventListener('submit', function (event) {
      event.preventDefault(); search = document.getElementById('luxpedia-favorites-query').value; pageNumber = 1;
      load().catch(function () { report('favoritesError'); });
    }); }
    document.addEventListener('click', function (event) {
      var target = event.target.closest('#addFavoriteMenu, #dropFavoriteMenu, #addFavorite');
      if (!target) { return; }
      event.preventDefault(); event.stopImmediatePropagation();
      if (snapshot && snapshot.eligible) { change(reference, snapshot.favorite ? 'remove' : 'add'); }
      else { report(snapshot ? 'favoritesPageUnavailable' : 'favoritesLoading'); }
    }, true);
    document.addEventListener('luxpedia:languagechange', render);
    document.addEventListener('xwiki:dom:updated', syncMenu);
    window.addEventListener('storage', function (event) {
      if (event.key === 'luxpedia.favorites.changed') { load().catch(function () { report('favoritesError'); }); }
    });
    syncMenu();
    load().catch(function () { report('favoritesError'); });
  }
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); }
  else { boot(); }
})();
