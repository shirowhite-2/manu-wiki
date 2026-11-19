  var workspaceSnapshot = null;
  var workspaceLoading = false;
  var WORKSPACE_URL = '/bin/get/LuxPedia/Workflow/DraftWorkspace?outputSyntax=plain';

  function manageWorkspace(reference, version, operation) {
    var acquireGeneration = operation === 'abandon' && !leaseGeneration(reference) ? leaseRequest(reference).then(function (value) {
      if (value.status !== 'owned' || !value.generation) { throw new Error('Editing right required'); }
      sessionStorage.setItem(leaseKey(reference), value.generation);
    }) : Promise.resolve();
    return acquireGeneration.then(freshFormToken).then(function (token) {
      return fetch(WORKSPACE_URL, { method: 'POST', credentials: 'same-origin',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ reference: reference, version: version, operation: operation, form_token: token, luxpedia_edit_lease: leaseGeneration(reference) }).toString()
      });
    }).then(actionResponse).then(function (result) {
      if (result !== (operation === 'restore' ? 'DRAFT_RESTORED' : 'DRAFT_ABANDONED')) { throw new Error(result); }
      sessionStorage.removeItem(PREFIX + reference);
      sessionStorage.removeItem(PREVIEW_PREFIX + reference);
      sessionStorage.removeItem(EDIT_TARGET);
    });
  }
  function workspaceButtonAction(reference, operation) {
    if (actionPending || !workspaceSnapshot) { return; }
    if (!window.confirm(t(operation === 'restore' ? 'restoreDraftConfirm' : 'abandonConfirm'))) { return; }
    actionPending = true; renderDraftActions();
    manageWorkspace(reference, workspaceSnapshot.version, operation).then(function () {
      if (operation === 'abandon') { location.assign('/bin/view/Drafts/'); }
      else { location.reload(); }
    }).catch(function () { actionPending = false; window.alert(t('workspaceFailed')); renderDraftActions(); });
  }
  function bindDraftWorkspaceList() {
    var toolbar = document.getElementById('luxpedia-workspace-list');
    if (!toolbar || !uiRoles || toolbar.dataset.bound) { return; }
    if (!uiRoles.editor && !uiRoles.admin) {
      toolbar.hidden = true;
      document.querySelectorAll('input.luxpedia-draft-select').forEach(function (input) { input.disabled = true; });
      return;
    }
    toolbar.dataset.bound = '1';
    var action = toolbar.querySelector('button');
    if (!action) { return; }
    action.addEventListener('click', async function () {
      var rows = Array.from(document.querySelectorAll('input.luxpedia-draft-select:checked'));
      if (!rows.length || rows.length > 20) { window.alert(t('workspaceSelectLimit')); return; }
      if (!window.confirm(t('abandonBatchConfirm').replace('{count}', rows.length))) { return; }
      action.disabled = true;
      var failed = 0;
      for (var row of rows) {
        try { await manageWorkspace(row.dataset.reference, row.dataset.version, 'abandon'); }
        catch (error) { failed++; }
      }
      if (failed) { window.alert(t('workspaceBatchFailed').replace('{count}', failed)); }
      location.reload();
    });
  }
