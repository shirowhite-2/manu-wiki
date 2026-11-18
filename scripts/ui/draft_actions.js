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
  function linkedDraftLocation() {
    var forms = document.querySelectorAll('.publicationworkflow-info form, .publicationworkflow-information form');
    for (var form of forms) {
      if ((form.getAttribute('method') || 'get').toLowerCase() !== 'get') { continue; }
      var url;
      try { url = new URL(form.getAttribute('action') || '', location.href); } catch (error) { continue; }
      if (url.origin !== location.origin || !url.pathname.startsWith('/bin/view/Drafts/')) { continue; }
      var parts = url.pathname.slice('/bin/view/'.length).split('/').filter(Boolean);
      if (url.pathname.endsWith('/')) { parts.push('WebHome'); }
      if (parts.length < 2 || parts.length === 2 && parts[1] === 'WebHome') { continue; }
      // Use URL segments, preserving literal dots and escaped names in page names.
      var page = parts.pop();
      var rest = parts.map(function (part) { return 'spaces/' + encodeURIComponent(decodeURIComponent(part)); }).join('/') +
        '/pages/' + encodeURIComponent(decodeURIComponent(page));
      return { url: url, rest: rest };
    }
    return null;
  }

  function requestFreshDraft(reference) {
    // A stale page can retain a link to a draft that an administrator deleted.
    // Ask the native workflow to find or recreate it; never synthesize or overwrite content.
    if (!draftActionsSnapshot || draftActionsSnapshot.status !== 'published') {
      return Promise.reject(new Error('Target is not published'));
    }
    return freshFormToken().then(function (token) {
      if (!token) { throw new Error('Missing form token'); }
      var form = document.createElement('form');
      form.method = 'post'; form.action = '/bin/view/PublicationWorkflow/Script'; form.hidden = true;
      var fields = { action: 'getEditableDraft', workflowdoc: reference, form_token: token };
      Object.keys(fields).forEach(function (name) {
        var input = document.createElement('input'); input.type = 'hidden'; input.name = name; input.value = fields[name];
        form.appendChild(input);
      });
      document.body.appendChild(form); form.submit();
    });
  }

  function openKnowledgeDraft(reference) {
    actionPending = true;
    sessionStorage.setItem(EDIT_TARGET, reference.replace(/^xwiki:/, ''));
    renderDraftActions();
    var form = workflowActionForm(['getEditableDraft', 'editDraft']);
    if (form && workflowFormSubmit(form)) { return; }
    var linked = linkedDraftLocation();
    var open = linked ? fetch(REST + linked.rest, {
      credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' }
    }).then(function (response) {
      if (response.status === 404) { return requestFreshDraft(reference); }
      if (!response.ok) {
        var error = new Error('HTTP ' + response.status); error.status = response.status; throw error;
      }
      location.assign(linked.url.href);
    }) : requestFreshDraft(reference);
    open.catch(function (error) {
      actionPending = false; sessionStorage.removeItem(EDIT_TARGET);
      window.alert(t(error.status === 401 || error.status === 403 ? 'draftAccessDenied' : 'draftOpenFailed'));
      renderDraftActions();
    });
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
    var state = draftActionsSnapshot.status;
    if (knowledge && state === 'unstarted') { return; }
    if (draft && sessionStorage.getItem(EDIT_TARGET) &&
        sessionStorage.getItem(EDIT_TARGET) === draftActionsSnapshot.target.replace(/^xwiki:/, '')) {
      sessionStorage.removeItem(EDIT_TARGET);
      if (state === 'draft') { location.assign(location.href.replace('/bin/view/', '/bin/edit/')); return; }
    }
    var editable = draft && ['unstarted', 'draft'].includes(state);
    if (editable && sessionStorage.getItem(PREVIEW_PREFIX + reference) === '1' && !previewSaving && !previewSaved) {
      previewSaving = true;
      var selected = readChosenCategory(reference);
      var persist = selected ? persistPrimaryCategory(reference, selected.label || '') : Promise.resolve(true);
      persist.then(function (saved) {
        previewSaving = false;
        previewSaved = true;
        sessionStorage.removeItem(PREVIEW_PREFIX + reference);
        if (!saved) { window.alert(t('categorySaveFailed')); }
        renderDraftActions();
      });
    }
    var card = document.getElementById('luxpedia-draft-actions');
    if (!card) {
      card = document.createElement('section'); card.id = 'luxpedia-draft-actions';
      card.className = 'alert alert-info'; card.setAttribute('aria-live', 'polite');
      card.innerHTML = '<strong></strong><p></p><div class="luxpedia-draft-buttons"></div>';
      content.parentNode.insertBefore(card, content);
    }
    card.hidden = !editable && !knowledge && !withdrawVersion;
    text(card.querySelector('strong'), t(knowledge ? 'editArticle' : editable ? 'previewTitle' : 'pendingTitle'));
    text(card.querySelector('p'), t(knowledge ? 'editArticleHint' : editable ? 'previewHint' : 'withdrawHint'));
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
    if (editable) {
      button('luxpedia-submit-review', 'submit', function () {
        if (busy || previewSaving) { return; }
        sessionStorage.setItem(PREFIX + reference, '1');
        continuePendingSubmission(reference); renderDraftActions();
      }, true);
      button('luxpedia-continue-editing', 'continueEditing', function () {
        location.assign(location.href.replace('/bin/view/', '/bin/edit/'));
      }, false);
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
  }
