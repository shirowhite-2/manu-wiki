  // One persistent lease per default-locale draft; never renew it on saves or polling.
  var EDIT_LOCK_URL = '/bin/get/LuxPedia/Workflow/EditLock?outputSyntax=plain';
  var leaseSnapshot = null, leaseRequested = false, leaseLost = false, leaseReference = '';
  var LEASE_PREFIX = 'luxpedia.workflow.lease.';
  var RELEASE_PREFIX = 'luxpedia.workflow.release.';
  function leaseKey(reference) { return LEASE_PREFIX + currentUser() + '.' + reference.replace(/^xwiki:/, ''); }
  function leaseGeneration(reference) { return sessionStorage.getItem(leaseKey(reference)) || ''; }
  function leaseCanWrite() { return leaseSnapshot && leaseSnapshot.status === 'owned' && !leaseLost; }
  function leaseRequest(reference, operation, generation) {
    var options = { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } };
    var url = EDIT_LOCK_URL + '&reference=' + encodeURIComponent(reference);
    var token = operation ? freshFormToken() : Promise.resolve('');
    return token.then(function (value) {
      if (operation) {
        options.method = 'POST'; options.headers['Content-Type'] = 'application/x-www-form-urlencoded';
        options.body = new URLSearchParams({ reference: reference, operation: operation, form_token: value,
          luxpedia_edit_lease: generation || leaseGeneration(reference) }).toString();
      }
      return fetch(url, options);
    }).then(function (response) { if (!response.ok) { throw new Error('HTTP ' + response.status); } return response.json(); });
  }
  function acceptLease(value, explicit) {
    if (!value || !['owned', 'busy', 'free', 'locked'].includes(value.status)) { throw new Error('Lease unavailable'); }
    var reference = (value.reference || documentReference()).replace(/^xwiki:/, '');
    var existing = leaseGeneration(reference);
    if (value.status === 'owned' && (explicit || !existing)) {
      sessionStorage.setItem(leaseKey(reference), value.generation); leaseLost = false;
    } else if (existing && (value.status !== 'owned' || existing !== value.generation)) { leaseLost = true; }
    if (value.status === 'locked' && !location.pathname.startsWith('/bin/edit/')) { sessionStorage.removeItem(leaseKey(reference)); leaseLost = false; }
    leaseReference = reference; leaseSnapshot = value; renderEditLease();
    return value;
  }
  function claimAndEdit(reference) {
    if (actionPending) { return; } actionPending = true;
    leaseRequest(reference, 'claim').then(function (value) {
      acceptLease(value, true);
      if (leaseCanWrite()) { location.assign(value.editURL); }
      else { actionPending = false; renderEditLease(); renderDraftActions(); }
    }).catch(function () { actionPending = false; window.alert(t('leaseUnavailable')); });
  }
  function initEditLease() {
    var reference = documentReference();
    if (!reference.startsWith('Drafts.') || reference === 'Drafts.WebHome' || !uiRoles || leaseRequested) { return; }
    leaseRequested = true;
    leaseRequest(reference).then(function (value) { acceptLease(value, false); renderDraftActions(); })
      .catch(function () { leaseRequested = false; });
  }
  function stampLeaseForm(form) {
    var reference = documentReference();
    if (!reference.startsWith('Drafts.') || reference === 'Drafts.WebHome' || (form.method || 'get').toLowerCase() !== 'post') { return; }
    var input = form.querySelector('input[name="luxpedia_edit_lease"]');
    if (!input) { input = document.createElement('input'); input.type = 'hidden'; input.name = 'luxpedia_edit_lease'; form.appendChild(input); }
    input.value = leaseGeneration(reference);
  }
  document.addEventListener('submit', function (event) { stampLeaseForm(event.target); }, true);
  // Native workflow/realtime transports sometimes call form.submit() without emitting submit.
  var nativeLeaseSubmit = HTMLFormElement.prototype.submit;
  HTMLFormElement.prototype.submit = function () { stampLeaseForm(this); return nativeLeaseSubmit.call(this); };
  function leaseForURL(url, method) {
    if (!method || /^(GET|HEAD|OPTIONS)$/i.test(method)) { return ''; }
    try {
      var parsed = new URL(url, location.href);
      if (parsed.origin !== location.origin || !(/^\/rest\/wikis\/[^/]+\/spaces\/Drafts\//.test(parsed.pathname) ||
          /^\/bin\/(?:save|upload|delattachment|preview)\/Drafts\//.test(parsed.pathname))) { return ''; }
      return leaseGeneration(documentReference());
    } catch (error) { return ''; }
  }
  var nativeLeaseFetch = window.fetch;
  window.fetch = function (input, options) {
    options = options || {};
    var generation = leaseForURL(typeof input === 'string' || input instanceof URL ? String(input) : input.url, options.method || input.method);
    if (generation) { options = Object.assign({}, options); options.headers = new Headers(options.headers || input.headers); options.headers.set('X-LuxPedia-Edit-Lease', generation); }
    return nativeLeaseFetch.call(this, input, options);
  };
  var nativeLeaseOpen = XMLHttpRequest.prototype.open, nativeLeaseSend = XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open = function (method, url) { this.luxpediaLeaseURL = url; this.luxpediaLeaseMethod = method; return nativeLeaseOpen.apply(this, arguments); };
  XMLHttpRequest.prototype.send = function () {
    var generation = leaseForURL(this.luxpediaLeaseURL, this.luxpediaLeaseMethod);
    if (generation) { this.setRequestHeader('X-LuxPedia-Edit-Lease', generation); }
    return nativeLeaseSend.apply(this, arguments);
  };
  function renderEditLease() {
    if (!leaseSnapshot || leaseReference !== documentReference()) { return; }
    var content = document.getElementById('xwikicontent') || document.getElementById('xwikieditor');
    if (!content) { return; }
    var box = document.getElementById('luxpedia-edit-lease');
    if (!box) {
      box = document.createElement('section'); box.id = 'luxpedia-edit-lease'; box.className = 'alert alert-info'; box.setAttribute('aria-live', 'polite');
      box.innerHTML = '<p></p><div></div>'; content.parentNode.insertBefore(box, content);
    }
    var value = leaseSnapshot;
    var message = t(leaseLost ? 'leaseLost' : value.status === 'owned' ? 'leaseOwned' : value.status === 'busy' ? 'leaseBusy' : value.status === 'locked' ? 'leaseLocked' : 'leaseFree');
    message = message.replace('{owner}', value.owner || '').replace('{time}', value.expiresAt ? new Date(Number(value.expiresAt)).toLocaleString() : '');
    text(box.querySelector('p'), message);
    var actions = box.querySelector('div');
    function control(id, label, visible, handler) {
      var node = document.getElementById(id);
      if (!node) { node = document.createElement('button'); node.type = 'button'; node.id = id; node.className = 'btn btn-default'; node.style.marginRight = '.5em'; node.addEventListener('click', handler); actions.appendChild(node); }
      text(node, t(label)); node.hidden = !visible; node.style.display = visible ? '' : 'none'; node.disabled = actionPending;
    }
    var editing = location.pathname.startsWith('/bin/edit/');
    control('luxpedia-claim-edit', 'leaseClaim', value.canClaim && !leaseCanWrite(), function () {
      if (leaseLost && editing) { window.alert(t('leaseKeepText')); return; }
      claimAndEdit(leaseReference);
    });
    control('luxpedia-release-edit', 'leaseRelease', leaseCanWrite() && !editing, function () {
      leaseRequest(leaseReference, 'release').then(function (result) {
        sessionStorage.removeItem(leaseKey(leaseReference)); leaseLost = false; acceptLease(result, true); location.reload();
      }).catch(function () { window.alert(t('leaseUnavailable')); });
    });
    control('luxpedia-force-release', 'leaseForce', value.canForce, function () {
      if (!window.confirm(t('leaseForceConfirm'))) { return; }
      leaseRequest(leaseReference, 'force-release', value.generation).then(function () { location.reload(); })
        .catch(function () { window.alert(t('leaseUnavailable')); });
    });
    if (editing) {
      control('luxpedia-save-release', 'leaseSaveRelease', leaseCanWrite(), function () {
        var done = document.querySelector('button.realtime-action-done, input[name="action_save"], button[name="action_save"]');
        if (!done) { return; }
        sessionStorage.setItem(RELEASE_PREFIX + leaseReference, leaseGeneration(leaseReference)); sessionStorage.setItem(PREVIEW_PREFIX + leaseReference, '1'); done.click();
      });
      document.querySelectorAll('input[name="action_save"], button[name="action_save"], button.realtime-action-done, input[name="action_saveandcontinue"]').forEach(function (node) {
        node.disabled = !leaseCanWrite();
      });
    }
    if (!editing && uiRoles && (uiRoles.editor || uiRoles.admin) && value.status !== 'locked') {
      document.querySelectorAll('a#tmEdit, #tmEdit a').forEach(function (link) {
        link.href = location.pathname + '#luxpedia-edit-lease';
        if (link.dataset.luxpediaLeaseEdit) { return; }
        link.dataset.luxpediaLeaseEdit = '1';
        link.addEventListener('click', function (event) {
          event.preventDefault(); event.stopImmediatePropagation();
          if (leaseSnapshot.status === 'busy') { window.alert(box.querySelector('p').textContent); return; }
          claimAndEdit(leaseReference);
        }, true);
      });
    }
    document.querySelectorAll('form').forEach(stampLeaseForm);
    var writable = leaseCanWrite();
    ['luxpedia-submit-review', 'luxpedia-abandon-draft', 'luxpedia-continue-editing'].forEach(function (id) { var node = document.getElementById(id); if (node) { node.hidden = !writable; node.style.display = writable ? '' : 'none'; } });
  }
  function releaseAfterPreview(reference) {
    var expected = sessionStorage.getItem(RELEASE_PREFIX + reference);
    if (!expected || !leaseCanWrite()) { return; }
    if (expected !== leaseGeneration(reference)) { sessionStorage.removeItem(RELEASE_PREFIX + reference); return; }
    sessionStorage.removeItem(RELEASE_PREFIX + reference);
    leaseRequest(reference, 'release').then(function () { sessionStorage.removeItem(leaseKey(reference)); location.reload(); })
      .catch(function () { window.alert(t('leaseUnavailable')); });
  }
  window.addEventListener('focus', function () { leaseRequested = false; initEditLease(); });
  window.setInterval(function () {
    if (leaseSnapshot && leaseSnapshot.expiresAt && Number(leaseSnapshot.expiresAt) <= Date.now()) { leaseLost = true; renderEditLease(); }
    leaseRequested = false; initEditLease();
  }, 30000);

  function renderLeaseList() {
    document.querySelectorAll('[data-luxpedia-lease-owner]').forEach(function (node) {
      var active = Number(node.dataset.luxpediaLeaseExpires) > Date.now();
      text(node, active ? t('leaseListBusy').replace('{owner}', node.dataset.luxpediaLeaseOwner)
        .replace('{time}', new Date(Number(node.dataset.luxpediaLeaseExpires)).toLocaleString()) : t('leaseListFree'));
    });
    document.querySelectorAll('input.luxpedia-draft-select[data-lease-expires]').forEach(function (node) {
      node.disabled = Number(node.dataset.leaseExpires) <= Date.now();
    });
  }
