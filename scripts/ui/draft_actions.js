  var draftActionsSnapshot = null;
  var draftActionsLoading = false;
  var previewSaving = false;
  var previewSaved = false;
  var withdrawVersion = '';
  var actionPending = false;
  var PREVIEW_PREFIX = 'luxpedia.workflow.preview.';
  var EDIT_TARGET = 'luxpedia.workflow.editTarget';
  var ACTIONS_URL = '/bin/get/LuxPedia/Workflow/Actions?outputSyntax=plain';

  function actionResponse(response) {
    if (!response.ok) { throw new Error('HTTP ' + response.status); }
    return response.text().then(function (body) {
      return new DOMParser().parseFromString(body, 'text/html').body.textContent.trim();
    });
  }
  function openKnowledgeDraft(reference) {
    if (actionPending) { return; }
    actionPending = true; renderDraftActions();
    leaseRequest(reference, 'open').then(function (value) {
      if (value.status === 'owned') {
        acceptLease(value, true); location.assign(value.editURL); return;
      }
      if (['busy', 'locked'].includes(value.status) && value.viewURL) {
        if (value.status === 'busy') { window.alert(t('leaseBusy').replace('{owner}', value.owner || '')
          .replace('{time}', value.expiresAt ? new Date(Number(value.expiresAt)).toLocaleString() : '')); }
        location.assign(value.viewURL); return;
      }
      throw new Error('Unavailable');
    }).catch(function () { actionPending = false; window.alert(t('leaseUnavailable')); renderDraftActions(); });
  }

  function renderDraftActions() {
    if (!uiRoles || (!uiRoles.editor && !uiRoles.admin) || location.pathname.indexOf('/bin/view/') !== 0) { return; }
    var reference = documentReference();
    var draft = reference.indexOf('Drafts.') === 0 && reference !== 'Drafts.WebHome';
    var knowledge = reference.indexOf('Knowledge.') === 0;
    var content = document.getElementById('xwikicontent');
    if ((!draft && !knowledge) || !content) { return; }
    if (!draftActionsSnapshot && !draftActionsLoading) {
      draftActionsLoading = true;
      Promise.all([workflowStatus(reference), workflowTarget(reference)]).then(function (values) {
        draftActionsSnapshot = { status: values[0] || 'unstarted', target: values[1] };
        draftActionsLoading = false;
        if (draft && ['moderating', 'validating'].includes(values[0])) {
          fetch(ACTIONS_URL + '&reference=' + encodeURIComponent(reference), { credentials: 'same-origin', cache: 'no-store' })
            .then(actionResponse).then(function (value) {
              var match = value.match(/^WITHDRAW_VERSION=([A-Za-z0-9._-]+)$/);
              withdrawVersion = match ? match[1] : '';
              renderDraftActions();
            }).catch(function () { /* A failed capability check never grants an action. */ });
        }
        renderDraftActions();
      });
      return;
    }
    if (!draftActionsSnapshot) { return; }
    if (draft && !workspaceSnapshot) {
      if (!workspaceLoading) {
        workspaceLoading = true;
        fetch(WORKSPACE_URL + '&reference=' + encodeURIComponent(reference), { credentials: 'same-origin', cache: 'no-store' })
          .then(actionResponse).then(function (body) { workspaceSnapshot = JSON.parse(body); renderDraftActions(); })
          .catch(function () { workspaceLoading = false; });
      }
      return;
    }
    var abandoned = draft && workspaceSnapshot.abandoned === true;
    var state = draftActionsSnapshot.status;
    if (knowledge && ['unstarted', 'archived'].includes(state)) { return; }
    if (draft && sessionStorage.getItem(EDIT_TARGET) &&
        sessionStorage.getItem(EDIT_TARGET) === draftActionsSnapshot.target.replace(/^xwiki:/, '')) {
      sessionStorage.removeItem(EDIT_TARGET);
      if (state === 'draft' && !abandoned && leaseCanWrite()) { location.assign(location.href.replace('/bin/view/', '/bin/edit/')); return; }
    }
    var editable = draft && !abandoned && ['unstarted', 'draft'].includes(state);
    if (editable && leaseCanWrite() && sessionStorage.getItem(PREVIEW_PREFIX + reference) === '1' && !previewSaving && !previewSaved) {
      previewSaving = true;
      var selected = readChosenCategory(reference);
      var persist = saveChosenCategory(reference, selected);
      persist.then(function (saved) {
        previewSaving = false;
        previewSaved = true;
        sessionStorage.removeItem(PREVIEW_PREFIX + reference);
        if (!saved) { window.alert(t('categorySaveFailed')); }
        renderDraftActions();
        if (saved) { releaseAfterPreview(reference); }
      });
    }
    var card = document.getElementById('luxpedia-draft-actions');
    if (!card) {
      card = document.createElement('section'); card.id = 'luxpedia-draft-actions';
      card.className = 'alert alert-info'; card.setAttribute('aria-live', 'polite');
      card.innerHTML = '<strong></strong><p></p><div class="luxpedia-draft-buttons"></div>';
      content.parentNode.insertBefore(card, content);
    }
    card.hidden = (!editable || !leaseCanWrite()) && !knowledge && !withdrawVersion && !abandoned;
    text(card.querySelector('strong'), t(abandoned ? 'abandonedTitle' : knowledge ? 'editArticle' : editable ? 'previewTitle' : 'pendingTitle'));
    text(card.querySelector('p'), t(abandoned ? 'abandonedHint' : knowledge ? 'editArticleHint' : editable ? 'previewHint' : 'withdrawHint'));
    var buttons = card.querySelector('.luxpedia-draft-buttons');
    function button(id, label, handler, primary) {
      var node = buttons.querySelector('#' + id);
      if (!node) {
        node = document.createElement('button'); node.type = 'button'; node.id = id;
        node.className = 'btn ' + (primary ? 'btn-primary' : 'btn-default');
        node.style.marginRight = '.5em'; node.addEventListener('click', handler); buttons.appendChild(node);
      }
      text(node, t(label)); node.disabled = actionPending || previewSaving || busy;
      return node;
    }
    if (abandoned) {
      if (workspaceSnapshot.restore) {
        button('luxpedia-restore-draft', 'restoreDraft', function () { workspaceButtonAction(reference, 'restore'); }, true);
      }
      document.querySelectorAll('.publicationworkflow-info form, .publicationworkflow-information form').forEach(function (form) { form.hidden = true; });
    } else if (editable) {
      button('luxpedia-submit-review', 'submit', function () {
        if (busy || previewSaving || !leaseCanWrite()) { return; }
        sessionStorage.setItem(PREFIX + reference, '1');
        continuePendingSubmission(reference); renderDraftActions();
      }, true);
      button('luxpedia-continue-editing', 'continueEditing', function () {
        claimAndEdit(reference);
      }, false);
      if (workspaceSnapshot.abandon) {
        button('luxpedia-abandon-draft', 'abandonDraft', function () { workspaceButtonAction(reference, 'abandon'); }, false);
      } else {
        var sharedHint = card.querySelector('.luxpedia-workspace-hint');
        if (!sharedHint) { sharedHint = document.createElement('p'); sharedHint.className = 'luxpedia-workspace-hint'; card.appendChild(sharedHint); }
        text(sharedHint, t('workspaceSharedHint'));
      }
      // Keep native forms for their authenticated workflow transport. Submission is explicit after preview.
      document.querySelectorAll('form').forEach(function (form) {
        var action = form.querySelector('input[name="action"]');
        if (action && ['start', 'submitformoderation', 'submitforvalidation'].includes(action.value)) { form.hidden = true; }
      });
    } else if (knowledge) {
      button('luxpedia-edit-article', 'editArticle', function () {
        openKnowledgeDraft(reference);
      }, true);
      // Native Edit must open the linked draft, rather than request EDIT on the published copy.
      // Administrators retain their native recovery/edit tools.
      if (!uiRoles.admin) {
        document.querySelectorAll('a#tmEdit, #tmEdit a').forEach(function (link) {
          link.href = location.pathname + location.search + '#luxpedia-draft-actions';
          link.title = t('editArticleHint');
          if (!link.dataset.luxpediaDraftEdit) {
            link.dataset.luxpediaDraftEdit = '1';
            link.addEventListener('click', function (event) {
              event.preventDefault(); event.stopImmediatePropagation();
              var button = document.getElementById('luxpedia-edit-article');
              if (button) { button.click(); }
            }, true);
          }
          var menu = document.getElementById('tmEdit');
          if (menu) { menu.hidden = false; }
        });
      }
    } else if (withdrawVersion) {
      button('luxpedia-withdraw-review', 'withdraw', function () {
        if (actionPending || !window.confirm(t('withdrawConfirm'))) { return; }
        actionPending = true; renderDraftActions();
        freshFormToken().then(function (token) {
          var fields = new URLSearchParams({ reference: reference, version: withdrawVersion, form_token: token });
          return fetch(ACTIONS_URL, { method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: fields.toString() });
        }).then(actionResponse).then(function (value) {
          if (value !== 'WITHDRAWN') { throw new Error(value); }
          sessionStorage.removeItem(PREFIX + reference);
          location.reload();
        }).catch(function () {
          actionPending = false; window.alert(t('withdrawFailed')); renderDraftActions();
        });
      }, false);
    }
    renderEditLease();
  }
