  var panelSnapshot = null;
  var panelRequested = false;
  var metadataSnapshot = null;
  var metadataRequested = false;
  function text(node, value) {
    if (node && node.textContent !== value) { node.textContent = value; }
  }
  function translateWorkflowUI() {
    renderArticleMetadata();
    var actions = {
      start: 'start', submitformoderation: 'submit', submitforvalidation: 'submit',
      publish: 'publish', validate: 'validate', refusemoderation: 'refuse', refusevalidation: 'refuse',
      getEditableDraft: 'openDraft', editDraft: 'openDraft', archive: 'archive', unpublish: 'unpublish',
      publishfromarchive: 'restore', backtodraft: 'backToDraft'
    };
    document.querySelectorAll('.publicationworkflow-info, .publicationworkflow-information').forEach(function (panel) {
      var host = panel.closest('.xwikipanel, .panel');
      var heading = host && host.querySelector('.panel-title, .panel-heading, h2');
      if (heading) { text(heading, t('workflow')); }
      panel.querySelectorAll('form').forEach(function (form) {
        var action = form.querySelector('input[name="action"]');
        var key = action && actions[action.value];
        if (!key) { return; }
        var button = form.querySelector('input[type="submit"],button[type="submit"]');
        if (button) {
          if (button.tagName === 'INPUT') { if (button.value !== t(key)) { button.value = t(key); } }
          else { text(button, t(key)); }
          if (action.value === 'publish') { button.classList.add('btn', 'btn-primary'); }
        }
        if (action.value === 'validate') {
          var extra = panel.querySelector('.luxpedia-workflow-extra');
          if (!extra) {
            extra = document.createElement('details'); extra.className = 'luxpedia-workflow-extra';
            extra.appendChild(document.createElement('summary')); panel.appendChild(extra);
          }
          text(extra.querySelector('summary'), t('moreOptions'));
          if (form.parentNode !== extra) { extra.appendChild(form); }
        }
        if (uiRoles && !uiRoles.admin) {
          var review = ['publish', 'validate', 'refusemoderation', 'refusevalidation'].includes(action.value);
          var write = ['start', 'getEditableDraft', 'editDraft', 'submitformoderation', 'submitforvalidation'].includes(action.value);
          form.hidden = review ? !uiRoles.reviewer : write ? !uiRoles.editor : true;
        }
      });
      var summary = panel.querySelector('.luxpedia-workflow-summary');
      if (!summary) {
        summary = document.createElement('section');
        summary.className = 'luxpedia-workflow-summary';
        summary.setAttribute('aria-live', 'polite');
        summary.innerHTML = '<strong class="luxpedia-state label label-default"></strong><ol class="luxpedia-steps">' +
          '<li></li><li></li><li></li></ol><p class="luxpedia-next"></p>' +
          '<dl><dt></dt><dd></dd></dl><a class="luxpedia-review-queue" href="/bin/view/LuxPedia/Workflow/ReviewQueue"></a>';
        panel.insertBefore(summary, panel.firstChild);
      }
      if (!panelRequested) {
        panelRequested = true;
        Promise.all([workflowStatus(documentReference()), workflowTarget(documentReference())]).then(function (values) {
          panelSnapshot = { status: values[0] || 'unstarted', target: values[1] };
          translateWorkflowUI();
        });
      }
      var state = panelSnapshot ? panelSnapshot.status : 'unstarted';
      text(summary.querySelector('.luxpedia-state'), t(state));
      var steps = summary.querySelectorAll('li');
      ['stepDraft', 'stepReview', 'stepPublish'].forEach(function (key, index) {
        text(steps[index], t(key));
        var current = state === 'published' ? 2 : ['validating', 'moderating', 'valid'].includes(state) ? 1 : 0;
        steps[index].classList.toggle('text-primary', index === current);
        steps[index].classList.toggle('text-muted', index !== current);
        if (index === current) { steps[index].setAttribute('aria-current', 'step'); }
        else { steps[index].removeAttribute('aria-current'); }
      });
      var next = state === 'published' ? 'nextPublished' : state === 'archived' ? 'nextArchived' :
        state === 'valid' ? 'nextValid' : ['validating', 'moderating'].includes(state) ? 'nextReview' : 'nextDraft';
      text(summary.querySelector('.luxpedia-next'), t(next));
      text(summary.querySelector('dt'), t('destination'));
      var target = panelSnapshot && panelSnapshot.target;
      var parts = target ? target.replace(/^xwiki:/, '').split('.') : [];
      if (parts[0] === 'Knowledge') { parts[0] = t('knowledge'); }
      if (parts.length > 2 && parts[parts.length - 1] === 'WebHome') { parts.pop(); }
      text(summary.querySelector('dd'), target ? parts.join(' / ') : t('destinationPending'));
      text(summary.querySelector('.luxpedia-review-queue'), t('queue'));
      // The summary replaces the extension's technical status sentence.
      panel.querySelectorAll(':scope > p').forEach(function (node) { node.hidden = true; });
    });
    if (uiRoles && !uiRoles.admin) {
      var locked = panelSnapshot && !['unstarted', 'draft'].includes(panelSnapshot.status);
      document.querySelectorAll('#tmEdit, #tmSourceEdit').forEach(function (node) {
        node.hidden = !uiRoles.editor || Boolean(locked);
      });
    }
    var done = document.querySelector('[data-luxpedia-submit-bound="1"]');
    if (done) { text(done, t('saveSubmit')); done.title = t('saveSubmit'); }
    [['#tmSourceEdit', 'sourceEdit'], ['#tmEditGuide', 'editGuide']].forEach(function (item) {
      var link = document.querySelector(item[0] + ' a');
      if (link) { text(link.querySelector('.btn-label') || link, t(item[1])); link.title = t(item[1]); }
    });
    if (!document.getElementById('luxpedia-workflow-style')) {
      var style = document.createElement('style');
      style.id = 'luxpedia-workflow-style';
      // Layout only. Colors, typography, labels and buttons belong to the host theme.
      style.textContent = '.luxpedia-workflow-summary{padding-bottom:1em}' +
        '.luxpedia-steps{display:flex;flex-wrap:wrap;gap:.5em;list-style:none;padding:0;margin:1em 0}' +
        '.luxpedia-steps li{flex:1}.luxpedia-steps [aria-current]{font-weight:bold}' +
        '.luxpedia-workflow-summary dd{overflow-wrap:anywhere;margin:0 0 1em}' +
        '.publicationworkflow-info .buttonwrapper{margin:8px 0}.publicationworkflow-info [hidden],.publicationworkflow-information [hidden]{display:none!important}';
      style.textContent += '.publicationworkflow-info .xformInline{display:block}.luxpedia-workflow-extra{margin:1em 0}';
      document.head.appendChild(style);
    }
  }

  function renderArticleMetadata() {
    var content = document.querySelector('#xwikicontent');
    if (!content || location.pathname.indexOf('/bin/view/') !== 0) { return; }
    if (!metadataRequested) {
      metadataRequested = true;
      var base = restSpaces(documentReference()) + '/objects/' + encodeURIComponent(ARTICLE_CLASS);
      getJson(base).then(function (data) {
        var object = (data.objectSummaries || [])[0];
        if (!object) { return; }
        return Promise.all([getJson(base + '/' + object.number + '/properties'), workflowStatus(documentReference())])
          .then(function (values) {
            var fields = {};
            (values[0].properties || []).forEach(function (property) { fields[property.name] = property.value || ''; });
            fields.status = values[1] || (documentReference().indexOf('Knowledge.') === 0 ? 'published' : 'unstarted');
            metadataSnapshot = fields;
            renderArticleMetadata();
          });
      }).catch(function () { /* the article itself remains readable if metadata is unavailable */ });
    }
    if (!metadataSnapshot) { return; }
    var table = document.getElementById('luxpedia-article-metadata');
    if (!table) {
      table = document.createElement('table'); table.id = 'luxpedia-article-metadata';
      table.className = 'table table-striped';
      table.createTHead().insertRow().innerHTML = '<th></th><th></th>';
      var body = table.createTBody();
      ['primaryCategory', 'status', 'owner', 'applicableVersion', 'tagsText', 'summary'].forEach(function (field) {
        var row = body.insertRow(); row.dataset.field = field;
        row.innerHTML = '<th scope="row"></th><td></td>';
      });
      content.parentNode.insertBefore(table, content);
      // Hide only the original generated metadata table, never an article's own tables.
      var legacy = content.querySelector('table');
      if (legacy && legacy.rows.length === 7 && legacy.rows[0].cells[0].textContent.trim() === '字段' &&
          legacy.rows[1].cells[0].textContent.trim() === '主分类') { legacy.hidden = true; }
    }
    text(table.tHead.rows[0].cells[0], t('field')); text(table.tHead.rows[0].cells[1], t('value'));
    var labels = { primaryCategory: 'primaryCategory', status: 'status', owner: 'owner',
      applicableVersion: 'version', tagsText: 'tags', summary: 'summary' };
    table.querySelectorAll('tbody tr').forEach(function (row) {
      var field = row.dataset.field;
      text(row.cells[0], t(labels[field]));
      text(row.cells[1], field === 'status' ? t(metadataSnapshot[field]) : metadataSnapshot[field]);
    });
  }
