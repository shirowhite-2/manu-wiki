#!/usr/bin/env python3
"""Configure the two-role publication workflow for LuxPedia.

Roles:
  - contributor = XWiki.LuxEditors   (edit drafts, submit for review)
  - validator   = XWiki.LuxReviewers (review and publish to Knowledge)
  - moderator   left empty           (two-role setup, no separate moderator)

Creates the configuration page LuxPedia.Workflow.Config with a
PublicationWorkflowConfigClass object. Idempotent: an existing object with the
same values is left unchanged. Reuses apply_sprint1.XWiki.
Default is a dry run; pass --apply to write.
"""
import argparse
from html.parser import HTMLParser
import importlib.util
import os
import re
import sys
import urllib.parse
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from interface_assets import I18N_JS, UI
CONFIG_SPACE = "LuxPedia/Workflow"
CONFIG_PAGE = "Config"
CONFIG_CLASS = "PublicationWorkflow.PublicationWorkflowConfigClass"

CONFIG_VALUES = {
    "contributor": "XWiki.LuxEditors",
    "validator": "XWiki.LuxReviewers",
    "moderator": "",
    "viewer": "",
    "commenter": "",
    "defaultDraftSpace": "Drafts",
    "defaultTargetSpace": "Knowledge",
    "moveStrategy": "doNothing",
    # ACLs protect Drafts; hiding pages at the UI/index layer also hid the
    # pending entries from the reviewer queue.
    "draftsHidden": "0",
    "skipDraftRights": "0",
    "allowCustomPublicationComment": "0",
}

CONFIG_CONTENT = """Two-role publication workflow configuration for LuxPedia.

* Editors (XWiki.LuxEditors) edit drafts in Drafts and submit them for review.
* Reviewers (XWiki.LuxReviewers) review and publish to Knowledge.
"""

UX_SPACE = "LuxPedia/Code"
UX_PAGE = "WorkflowUXScript"
WORKFLOW_UX_JS = r"""(function () {
  'use strict';

  var PREFIX = 'luxpedia.workflow.submit.';
  var CONFIG = 'xwiki:LuxPedia.Workflow.Config';
  var CLASS = 'PublicationWorkflow.PublicationWorkflowClass';
  var ARTICLE_CLASS = 'LuxPedia.Code.ArticleClass';
  var REST = '/rest/wikis/xwiki/';
  var busy = false;
  var t = window.LuxPediaI18n.t;
  var uiRoles = null;
  var categoryFields = new WeakMap();

  /* LUXPEDIA_WORKFLOW_PANEL */
  /* LUXPEDIA_EDIT_LOCK */
  /* LUXPEDIA_DRAFT_ACTIONS */
  /* LUXPEDIA_CATEGORY_PICKER */
  /* LUXPEDIA_DRAFT_WORKSPACE */

  function documentReference() {
    var meta = document.querySelector('meta[name="document"]');
    var native = document.documentElement.getAttribute('data-xwiki-reference') || '';
    return meta && meta.content || native.replace(/^xwiki:/, '');
  }

  function currentUser() {
    var user = window.XWiki && window.XWiki.currentUser;
    if (user) { return user.replace(/^xwiki:/, ''); }
    var link = document.querySelector('#tmUser a[href*="/bin/view/XWiki/"], #tmUser[href*="/bin/view/XWiki/"]');
    var match = link && link.getAttribute('href').match(/\/bin\/view\/XWiki\/([^/?#]+)/);
    return match ? 'XWiki.' + decodeURIComponent(match[1]) : '';
  }

  function restSpaces(reference) {
    var parts = reference.split('.');
    var spaces = parts.slice(0, -1).map(function (part) {
      return 'spaces/' + encodeURIComponent(part);
    });
    return spaces.join('/') + (spaces.length ? '/' : '') + 'pages/' + encodeURIComponent(parts[parts.length - 1]);
  }

  function getJson(path) {
    return fetch(REST + path, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
      .then(function (response) {
        if (!response.ok) { throw new Error('HTTP ' + response.status); }
        return response.json();
      });
  }

  var rolesPromise;
  function currentRoles() {
    if (!rolesPromise) {
      rolesPromise = fetch('/bin/get/LuxPedia/Workflow/CurrentRoles?outputSyntax=plain', {
        credentials: 'same-origin', cache: 'no-store'
      }).then(function (response) {
        if (!response.ok) { throw new Error('HTTP ' + response.status); }
        return response.text();
      }).then(function (body) {
        var data = JSON.parse(new DOMParser().parseFromString(body, 'text/html').body.textContent);
        return { editor: data.editor === true, reviewer: data.reviewer === true, admin: data.admin === true };
      });
    }
    return rolesPromise;
  }

  function spaceAndPage(reference) {
    var parts = reference.split('.');
    return { page: parts.pop(), spaces: parts };
  }

  function publicationLocation(reference) {
    var info = spaceAndPage(reference);
    // A normal XWiki page is Drafts.<article>.WebHome. WebHome is the
    // storage document name, not the article name. Publish a terminal article
    // in the category, as expected by the category's ArticleClass listing.
    var nested = info.page === 'WebHome' && info.spaces.length > 1;
    return {
      name: nested ? info.spaces[info.spaces.length - 1] : info.page,
      categories: info.spaces.slice(1, nested ? -1 : undefined)
    };
  }

  function primaryCategory(reference) {
    var base = restSpaces(reference);
    return getJson(base + '/objects/' + encodeURIComponent(ARTICLE_CLASS))
      .then(function (data) {
        var object = (data.objectSummaries || [])[0];
        if (!object) { return ''; }
        return getJson(base + '/objects/' + encodeURIComponent(ARTICLE_CLASS) +
          '/' + object.number + '/properties/primaryCategory').then(function (property) {
          return property.value || '';
        });
      }).catch(function () { return ''; });
  }

  function categoryOptions() {
    return fetch('/bin/view/Knowledge/CategoryIndex', { credentials: 'same-origin' })
      .then(function (response) { return response.ok ? response.text() : ''; })
      .then(function (html) {
        var parsed = new DOMParser().parseFromString(html, 'text/html');
        var list = parsed.querySelector('.luxpedia-system-list');
        var links = Array.from((list || parsed).querySelectorAll('a[href]'));
        var result = [];
        for (var i = 0; i < links.length; i++) {
          var link = links[i];
          var label = link.textContent.trim();
          var url = new URL(link.getAttribute('href'), location.origin);
          var match = url.pathname.match(/^\/bin\/view\/Knowledge\/(.+?)\/(?:WebHome)?$/);
          if (url.origin !== location.origin || !match || !label) { continue; }
          var parts = match[1].split('/').map(decodeURIComponent);
          if (parts.some(function (part) { return !part || /[.:\\/]/.test(part); })) { continue; }
          var key = parts.join('.');
          if (!result.some(function (entry) { return entry.key === key; })) {
            result.push({ key: key, label: label });
          }
        }
        return result;
      }).catch(function () { return []; });
  }

  function categorySpace(label) {
    if (!label) { return Promise.resolve(''); }
    return categoryOptions().then(function (options) {
      var selected = matchCategoryOption(label, '', options);
      return selected ? selected.key : '';
    });
  }

  function localCategoryValue(value) {
    var local = value || '';
    if (local.indexOf(':') >= 0) {
      var wiki = window.XWiki && (XWiki.currentWiki || XWiki.Document && XWiki.Document.currentWiki) || 'xwiki';
      if (!local.startsWith(wiki + ':')) { return null; }
      local = local.slice(wiki.length + 1);
    }
    return local;
  }

  function matchCategoryOption(value, label, options) {
    var exact = options.find(function (entry) { return entry.key === value || entry.label === value; });
    if (exact) { return exact; }
    var local = localCategoryValue(value);
    if (local === null) { return null; }
    return options.find(function (entry) {
      return entry.key === local || entry.label === local || 'Knowledge.' + entry.key === local ||
        'Knowledge.' + entry.key + '.WebHome' === local;
    }) || (!/^(?:Knowledge\.|[A-Za-z]+:)/.test(value || '') && options.find(function (entry) { return entry.label === label; }));
  }

  function isRootCategory(value) {
    return ['', '__root__', 'Knowledge', 'Knowledge.WebHome'].includes(localCategoryValue(value));
  }

  function nativeCategoryField() {
    var selectors = ['select[name$="_primaryCategory"]', 'select[id$="_primaryCategory"]',
      'select[name="primaryCategory"]', 'select[data-field="primaryCategory"]', 'select[name="category"]'];
    for (var selector of selectors) {
      var field = Array.from(document.querySelectorAll(selector)).find(function (node) {
        return node.id !== 'luxpedia-publish-category' && !node.multiple;
      });
      if (field) { return field; }
    }
    for (var label of document.querySelectorAll('label[for]')) {
      if (!/^(?:primary category|category|主分类|分类)$/i.test(label.textContent.trim().replace(/[\s:：*]+$/, ''))) { continue; }
      var field = document.getElementById(label.htmlFor);
      if (field && field.tagName === 'SELECT' && !field.multiple && field.id !== 'luxpedia-publish-category') { return field; }
    }
    return null;
  }

  function reuseNativeCategoryField(options) {
    var native = nativeCategoryField();
    if (!native || !native.options.length) { return false; }
    if (categoryFields.has(native)) { installCategoryTree(native, categoryFields.get(native)); return true; }
    var fallback = document.getElementById('luxpedia-publish-category');
    options = options || fallback && categoryFields.get(fallback);
    if (!options) { return false; }
    if (fallback && fallback.dataset.userChanged) {
      var match = Array.from(native.options).find(function (option) {
        var entry = matchCategoryOption(option.value, option.textContent.trim(), options);
        return entry ? entry.key === fallback.value : fallback.value === '__root__' && isRootCategory(option.value);
      });
      if (!match) { return false; }
      native.value = match.value;
      native.dispatchEvent(new Event('change', { bubbles: true }));
    }
    categoryFields.set(native, options);
    native.setAttribute('data-luxpedia-publication-category', 'true');
    if (fallback) { document.getElementById('luxpedia-category-choice').remove(); }
    installCategoryTree(native, options);
    return true;
  }

  function selectedCategory() {
    var native = document.querySelector('select[data-luxpedia-publication-category]');
    var field = native || document.getElementById('luxpedia-publish-category');
    if (!field) { return null; }
    if (field.dataset.categoryTreeUnavailable) { return { invalid: true }; }
    var option = field.options[field.selectedIndex];
    if (!native) { return { key: field.value, label: option && option.dataset.label || '' }; }
    if (!option) { return { invalid: true }; }
    var value = field.value;
    var entry = matchCategoryOption(value, option && option.textContent.trim(), categoryFields.get(field) || []);
    var root = isRootCategory(value);
    if (!entry && !root) { return { invalid: true }; }
    var result = { key: entry ? entry.key : '__root__', label: entry ? entry.label : '' };
    // Let the native field keep its stored key/reference. Other native category fields only supply the destination.
    if ([field.name, field.id].some(function (name) { return /(?:^|_)primaryCategory$/.test(name || ''); })) {
      if (entry && !matchCategoryOption(value, '', categoryFields.get(field) || [])) { return { invalid: true }; }
      result.propertyValue = value;
    }
    return result;
  }

  function saveChosenCategory(reference, chosen) {
    if (!chosen) { return Promise.resolve(true); }
    if (chosen.invalid) { return Promise.resolve(false); }
    var value = chosen.propertyValue !== undefined ? chosen.propertyValue : chosen.label || '';
    return persistPrimaryCategory(reference, value);
  }

  function defaultTarget(reference, selectedKey) {
    var info = publicationLocation(reference);
    if (selectedKey !== undefined && selectedKey !== null) {
      return Promise.resolve({
        parent: selectedKey === '__root__' ? 'Knowledge' : 'Knowledge.' + selectedKey,
        name: info.name
      });
    }
    return primaryCategory(reference).then(function (category) {
      if (category && isRootCategory(category)) { return { parent: 'Knowledge', name: info.name }; }
      return categorySpace(category).then(function (categoryKey) {
        var parentParts = ['Knowledge'];
        if (categoryKey) {
          parentParts.push(categoryKey);
        } else {
          parentParts = parentParts.concat(info.categories);
        }
        return { parent: parentParts.join('.'), name: info.name };
      });
    });
  }

  function persistPrimaryCategory(reference, categoryLabel) {
    var base = restSpaces(reference);
    return getJson(base + '/objects/' + encodeURIComponent(ARTICLE_CLASS)).then(function (data) {
      var object = (data.objectSummaries || [])[0];
      if (!object) {
        // A plain page without ArticleClass still needs searchable category
        // metadata. Create the object after the page has been saved.
        var info = spaceAndPage(reference);
        var titleNode = document.querySelector('#document-title h1');
        var fields = new URLSearchParams();
        fields.set('className', ARTICLE_CLASS);
        fields.set('property#title', titleNode ? titleNode.textContent.trim() : info.page);
        fields.set('property#primaryCategory', categoryLabel);
        fields.set('property#owner', currentUser());
        fields.set('property#status', 'draft');
        fields.set('property#tagsText', '');
        fields.set('property#summary', '');
        return freshFormToken().then(function (token) {
          if (!token) { return false; }
          fields.set('form_token', token);
          return fetch(REST + base + '/objects', {
            method: 'POST', credentials: 'same-origin',
            headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
            body: fields.toString()
          }).then(function (response) { return response.ok; });
        });
      }
      var propertyPath = base + '/objects/' + encodeURIComponent(ARTICLE_CLASS) + '/' + object.number +
        '/properties/primaryCategory';
      return getJson(propertyPath).then(function (property) {
        if ((property.value || '') === categoryLabel) { return true; }
        return freshFormToken().then(function (token) {
          if (!token) { return false; }
          return fetch(REST + propertyPath, {
            method: 'PUT', credentials: 'same-origin',
            headers: { 'Content-Type': 'text/plain', 'XWiki-Form-Token': token },
            body: categoryLabel
          }).then(function (response) { return response.ok; });
        });
      });
    }).catch(function () { return false; });
  }

  function formTokenFromDocument(doc) {
    var tokenNode = doc.querySelector('[data-xwiki-form-token]');
    var token = tokenNode && tokenNode.getAttribute('data-xwiki-form-token');
    if (!token) {
      var tokenInput = doc.querySelector('input[name="form_token"]');
      token = tokenInput && tokenInput.value;
    }
    if (!token) {
      var logout = doc.querySelector('#tmLogout');
      if (logout) { token = new URL(logout.href, location.origin).searchParams.get('form_token'); }
    }
    return token || '';
  }

  function freshFormToken() {
    return fetch('/bin/view/Main/', { credentials: 'same-origin', cache: 'no-store' })
      .then(function (response) { return response.ok ? response.text() : ''; })
      .then(function (html) {
        if (!html) { return formTokenFromDocument(document); }
        return formTokenFromDocument(new DOMParser().parseFromString(html, 'text/html'));
      }).catch(function () { return formTokenFromDocument(document); });
  }

  function readChosenCategory(reference) {
    try {
      var value = sessionStorage.getItem(PREFIX + reference + '.category');
      return value ? JSON.parse(value) : null;
    } catch (error) { return null; }
  }

  function workflowStatus(reference) {
    var base = restSpaces(reference);
    return getJson(base + '/objects/' + encodeURIComponent(CLASS))
      .then(function (data) {
        var object = (data.objectSummaries || [])[0];
        if (!object) { return ''; }
        return getJson(base + '/objects/' + encodeURIComponent(CLASS) + '/' + object.number + '/properties/status')
          .then(function (property) { return property.value || ''; });
      }).catch(function () { return ''; });
  }

  function workflowObjectExists(reference) {
    var base = restSpaces(reference);
    return getJson(base + '/objects/' + encodeURIComponent(CLASS))
      .then(function (data) { return (data.objectSummaries || []).length > 0; })
      .catch(function () { return false; });
  }

  function workflowTarget(reference) {
    var base = restSpaces(reference);
    return getJson(base + '/objects/' + encodeURIComponent(CLASS)).then(function (data) {
      var object = (data.objectSummaries || [])[0];
      if (!object) { return ''; }
      return getJson(base + '/objects/' + encodeURIComponent(CLASS) + '/' + object.number + '/properties/target')
        .then(function (property) { return property.value || ''; });
    }).catch(function () { return ''; });
  }

  function setFormValue(form, name, value) {
    var input = form.querySelector('[name="' + name + '"]');
    if (input) {
      input.value = value;
      input.dispatchEvent(new Event('input', { bubbles: true }));
      input.dispatchEvent(new Event('change', { bubbles: true }));
    }
  }

  function hideAdvancedOptions() {
    var link = document.querySelector('#workflow-options, a.workflow-options');
    if (link) { link.style.display = 'none'; }
  }

  function workflowActionForm(actions) {
    return Array.from(document.querySelectorAll('form')).find(function (form) {
      var action = form.querySelector('input[name="action"]');
      return action && actions.indexOf(action.value) !== -1 &&
        (form.getAttribute('action') || '').indexOf('PublicationWorkflow/Script') !== -1;
    });
  }

  function workflowFormSubmit(form) {
    if (!form) { return false; }
    stampLeaseForm(form);
    var submit = form.querySelector('input[type="submit"],button[type="submit"]');
    if (!submit) { return false; }
    submit.click();
    return true;
  }

  function prepareStartForm(reference, target) {
    var form = workflowActionForm(['start']);
    if (!form) { return false; }
    setFormValue(form, 'targetSpace', target.parent);
    setFormValue(form, 'targetName', target.name);
    setFormValue(form, 'targetIsTerminal', 'on');
    var terminal = form.querySelector('[name="targetIsTerminal"]');
    if (terminal) { terminal.checked = true; }
    // Normal pages default to including children in the upstream start form.
    // This shortcut publishes one article to a terminal category entry.
    var includeChildren = form.querySelector('[name="includeChildren"]');
    if (includeChildren) { includeChildren.checked = false; }
    var startAsTarget = form.querySelector('[name="startAsTarget"]');
    var editingPublishedArticle = reference.indexOf('Knowledge.') === 0;
    if (startAsTarget) { startAsTarget.checked = editingPublishedArticle; }
    var workflow = form.querySelector('[name="workflow"]');
    if (workflow) {
      var option = Array.from(workflow.options).find(function (item) {
        return item.value.replace(/^xwiki:/, '') === CONFIG.replace(/^xwiki:/, '') || item.value.endsWith('/LuxPedia/Workflow/Config') ||
          item.textContent.indexOf('LuxPedia 发布流程配置') !== -1;
      });
      if (option) { workflow.value = option.value; }
    }
    hideAdvancedOptions();
    return true;
  }

  function continuePendingSubmission(reference) {
    if (reference.startsWith('Drafts.') && reference !== 'Drafts.WebHome' && !leaseCanWrite()) {
      if (leaseSnapshot) {
        sessionStorage.removeItem(PREFIX + reference);
        if (leaseSnapshot.status === 'locked') { sessionStorage.removeItem(PREFIX + reference + '.category'); }
      }
      return Promise.resolve();
    }
    var key = PREFIX + reference;
    if (sessionStorage.getItem(key) !== '1' || busy || !uiRoles || (!uiRoles.editor && !uiRoles.admin)) { return; }
    var params = new URLSearchParams(location.search);
    if (params.get('targetexists') === 'true' || params.get('same') === 'true' || params.has('error')) {
      sessionStorage.removeItem(key);
      sessionStorage.removeItem(key + '.category');
      window.alert(t('submitFailed'));
      return;
    }
    busy = true;
    workflowObjectExists(reference).then(function (exists) {
      if (!exists) {
        var chosen = readChosenCategory(reference);
        var persist = saveChosenCategory(reference, chosen);
        return persist.then(function (saved) {
          if (!saved) {
            sessionStorage.removeItem(key);
            if (!chosen || !chosen.invalid) { sessionStorage.removeItem(key + '.category'); }
            window.alert(t('categorySaveFailed'));
            return 'done';
          }
          return defaultTarget(reference, chosen ? chosen.key : undefined).then(function (target) {
            var form = workflowActionForm(['start']);
            if (form && prepareStartForm(reference, target) && workflowFormSubmit(form)) { return 'started'; }
            return 'waiting';
          });
        });
      }
      return workflowStatus(reference).then(function (status) {
        if (status === 'draft') {
          var form = workflowActionForm(['submitformoderation', 'submitforvalidation']);
          if (workflowFormSubmit(form)) { return 'submitted'; }
          return 'waiting';
        }
        if (status === 'moderating' || status === 'validating' || status === 'validated' ||
            status === 'published' || status === 'refused') {
          sessionStorage.removeItem(key);
          sessionStorage.removeItem(key + '.category');
          return 'done';
        }
        return 'waiting';
      });
    }).then(function (state) {
      if (state === 'done') { busy = false; renderDraftActions(); return; }
      if (state === 'waiting') {
        busy = false;
        setTimeout(function () { continuePendingSubmission(reference); }, 900);
      }
      // After starting or submitting, XWiki redirects/reloads the same page. The
      // session flag and busy lock stay set until the new document is loaded.
    }).catch(function () { busy = false; renderDraftActions(); });
  }

  function installDoneButton(reference, isAdmin) {
    if (isAdmin || reference === 'Drafts.WebHome' || !location.pathname.match(/^\/bin\/edit\/Drafts(?:\/|$)/)) { return true; }
    var done = document.querySelector('button.realtime-action-done, input[name="action_save"], button[name="action_save"]');
    if (!done) { return false; }
    if (done.getAttribute('data-luxpedia-preview-bound') === '1') { return true; }
    done.setAttribute('data-luxpedia-preview-bound', '1');
    done.title = t('savePreview');
    Array.from(done.childNodes).forEach(function (node) {
      if (node.nodeType === Node.TEXT_NODE) { node.textContent = ''; }
    });
    var text = Array.from(done.querySelectorAll('*')).find(function (node) {
      return node.childElementCount === 0 && node.textContent.trim() === 'Done';
    });
    if (done.tagName === 'INPUT') { done.value = t('savePreview'); }
    else if (text) { text.textContent = t('savePreview'); }
    else { done.appendChild(document.createTextNode(t('savePreview'))); }
    done.addEventListener('click', function () {
      sessionStorage.removeItem(PREFIX + reference);
      sessionStorage.setItem(PREVIEW_PREFIX + reference, '1');
      var category = selectedCategory();
      if (category) { sessionStorage.setItem(PREFIX + reference + '.category', JSON.stringify(category)); }
    }, true);
    return true;
  }

  function waitForDoneButton(reference, isAdmin, attempts) {
    if (installDoneButton(reference, isAdmin) || attempts >= 40) { return; }
    setTimeout(function () { waitForDoneButton(reference, isAdmin, attempts + 1); }, 500);
  }

  function installCategorySelector(reference, isAdmin, attempts) {
    if (isAdmin || reference === 'Drafts.WebHome' || attempts >= 30 || !location.pathname.match(/^\/bin\/edit\/Drafts(?:\/|$)/) ||
        document.getElementById('luxpedia-category-choice') || document.querySelector('select[data-luxpedia-publication-category]')) { return; }
    workflowObjectExists(reference).then(function (hasWorkflow) {
      var container = document.createElement('div');
      container.id = 'luxpedia-category-choice';
      container.className = 'xform';
      if (hasWorkflow) {
        workflowTarget(reference).then(function (target) {
          var information = document.createElement('p');
          information.className = 'xHint';
          information.textContent = target ? t('linkedTarget') + ' ' + target : t('linkedFallback');
          container.appendChild(information);
          var editor = document.querySelector('#xwikieditor');
          if (editor && editor.parentNode) { editor.parentNode.insertBefore(container, editor); }
        });
        return;
      }
      Promise.all([categoryOptions(), primaryCategory(reference)]).then(function (values) {
        var options = Array.isArray(values[0]) ? values[0] : [];
        if (reuseNativeCategoryField(options)) {
          // Reuse the existing property control, including its native picker, ID, name, options and current selection.
          return;
        }
        var current = values[1];
        var info = publicationLocation(reference);
        var select = document.createElement('select');
        select.id = 'luxpedia-publish-category';
        categoryFields.set(select, options);
        select.addEventListener('change', function () { select.dataset.userChanged = 'true'; });
        var rootOption = document.createElement('option');
        rootOption.value = '__root__';
        rootOption.textContent = t('root');
        rootOption.dataset.luxpediaI18n = 'root';
        rootOption.dataset.label = '';
        select.appendChild(rootOption);
        options.forEach(function (entry) {
          var option = document.createElement('option');
          option.value = entry.key;
          option.textContent = entry.label;
          option.dataset.label = entry.label;
          select.appendChild(option);
        });
        var selected = matchCategoryOption(current, '', options);
        if (selected) { select.value = selected.key; }
        else if (!(current && isRootCategory(current)) && info.categories.length) {
          var nestedKey = info.categories.join('.');
          if (options.some(function (entry) { return entry.key === nestedKey; })) { select.value = nestedKey; }
        }
        var label = document.createElement('label');
        label.htmlFor = select.id;
        label.textContent = t('chooseCategory');
        label.dataset.luxpediaI18n = 'chooseCategory';
        var hint = document.createElement('div');
        hint.id = 'luxpedia-category-hint';
        hint.className = 'xHint';
        select.setAttribute('aria-describedby', hint.id);
        hint.textContent = t('categoryHint');
        hint.dataset.luxpediaI18n = 'categoryHint';
        // Match core XWiki fields (xform / dl / dt / dd / xHint); the site theme owns their appearance.
        var fields = document.createElement('dl');
        var term = document.createElement('dt'); term.appendChild(label);
        var value = document.createElement('dd');
        value.appendChild(hint); value.appendChild(select);
        fields.appendChild(term); fields.appendChild(value); container.appendChild(fields);
        var editor = document.querySelector('#xwikieditor');
        if (editor && editor.parentNode) { editor.parentNode.insertBefore(container, editor); }
        installCategoryTree(select, options);
      });
    }).catch(function () {
      setTimeout(function () { installCategorySelector(reference, isAdmin, attempts + 1); }, 500);
    });
  }

  function prepareVisibleStartForm(reference, attempts) {
    if (attempts >= 30 || sessionStorage.getItem(PREFIX + reference) === '1') { return; }
    if (!workflowActionForm(['start'])) {
      setTimeout(function () { prepareVisibleStartForm(reference, attempts + 1); }, 500);
      return;
    }
    defaultTarget(reference).then(function (target) {
      // An automatic submission owns the selected target until it completes.
      if (sessionStorage.getItem(PREFIX + reference) === '1') { return; }
      if (!prepareStartForm(reference, target)) {
        setTimeout(function () { prepareVisibleStartForm(reference, attempts + 1); }, 500);
      }
    });
  }

  var initPending = false;
  var initializedReference = '';
  function init() {
    var reference = documentReference();
    var user = currentUser();
    if (!reference || !user || initPending || initializedReference === reference) { return; }
    initPending = true;
    currentRoles().then(function (roles) {
      initPending = false;
      uiRoles = roles;
      initEditLease();
      translateWorkflowUI();
      if (!roles.editor && !roles.admin) { return; }
      initializedReference = reference;
      var isAdmin = roles.admin;
      waitForDoneButton(reference, isAdmin, 0);
      installCategorySelector(reference, isAdmin, 0);
      if (location.pathname.indexOf('/bin/view/') === 0) {
        continuePendingSubmission(reference);
        // Drafts use category-derived targets; published pages start as targets,
        // which preserves the exact existing Knowledge location.
        if (reference.indexOf('Drafts.') === 0 || reference.indexOf('Knowledge.') === 0) {
          prepareVisibleStartForm(reference, 0);
        }
      }
    }).catch(function () { initPending = false; });
  }

  // The global navigation identity and the realtime editor controls can be
  // injected after this extension is loaded. Retry briefly instead of silently
  // leaving editors with the standard Done button.
  var bootAttempts = 0;
  function boot() {
    translateWorkflowUI();
    init();
    resumePendingSubmission();
    bootAttempts += 1;
    if (bootAttempts < 30) { setTimeout(boot, 500); }
  }
  function resumePendingSubmission() {
    if (location.pathname.indexOf('/bin/view/') !== 0) { return; }
    var reference = documentReference();
    if (reference && sessionStorage.getItem(PREFIX + reference) === '1') {
      continuePendingSubmission(reference);
    }
  }
  document.addEventListener('luxpedia:languagechange', translateWorkflowUI);
  if (document.readyState === 'loading') { document.addEventListener('DOMContentLoaded', boot); }
  else { boot(); }
  window.setInterval(resumePendingSubmission, 700);
  if (document.body) {
    new MutationObserver(translateWorkflowUI).observe(document.body, {
      subtree: true, childList: true, characterData: true
    });
  }
})();
"""

WORKFLOW_UX_JS = I18N_JS + WORKFLOW_UX_JS.replace(
    '/* LUXPEDIA_WORKFLOW_PANEL */', (UI / 'workflow_panel.js').read_text()).replace(
    '/* LUXPEDIA_EDIT_LOCK */', (UI / 'edit_lock.js').read_text()).replace(
    '/* LUXPEDIA_DRAFT_ACTIONS */', (UI / 'draft_actions.js').read_text()).replace(
    '/* LUXPEDIA_CATEGORY_PICKER */', (UI / 'category_picker.js').read_text()).replace(
    '/* LUXPEDIA_DRAFT_WORKSPACE */', (UI / 'draft_workspace.js').read_text())

WORKFLOW_UX_PROPERTIES = {
    "name": "luxpedia.workflowUX",
    "code": WORKFLOW_UX_JS,
    "use": "always",
    "parse": "0",
    "cache": "long",
}


def load_apply_module():
    spec = importlib.util.spec_from_file_location("apply_sprint1", ROOT / "scripts" / "apply_sprint1.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# Right-column workflow panel, shown only in Drafts (space preference object).
PANEL_VALUES = {"rightPanels": "PublicationWorkflow.PublicationWorkflowPanel"}
LUXPEDIA_VIEW_ROLES = ("XWiki.LuxEditors", "XWiki.LuxReviewers", "XWiki.LuxReaders")
# The writing-area home is editable content; its list/toolbar code lives in protected renderer pages.
def read_page(wiki, space, page):
    status, body = wiki.request("GET", wiki.page_path(space, page))
    if status != 200:
        return None, None
    root = ET.fromstring(body)
    ns = "{http://www.xwiki.org}"
    return (
        root.findtext(ns + "title") or page,
        root.findtext(ns + "content") or "",
        (root.findtext(ns + "hidden") or "false").lower() == "true",
        root.findtext(ns + "author") or "",
    )


class KnowledgeCategoryLinks(HTMLParser):
    def __init__(self):
        super().__init__()
        self.href = None
        self.text = []
        self.links = []

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "a":
            self.href = attrs.get("href", "")
            self.text = []

    def handle_data(self, data):
        if self.href is not None:
            self.text.append(data)

    def handle_endtag(self, tag):
        if tag == "a" and self.href is not None:
            self.links.append((self.href, "".join(self.text).strip()))
            self.href = None
            self.text = []


def knowledge_categories(wiki):
    """Return {category label: nested-space key} from the rendered category index."""
    status, body = wiki.request("GET", "/bin/view/Knowledge/CategoryIndex")
    if status != 200:
        return {}
    parser = KnowledgeCategoryLinks()
    parser.feed(body.decode("utf-8", errors="replace"))
    result = {}
    for href, label in parser.links:
        match = re.match(r"^/bin/view/Knowledge/([^/?#]+)/?(?:WebHome)?$", href)
        if match and label and label not in ("知识库", "文档库", "分类索引", "分类说明"):
            result[label] = urllib.parse.unquote(match.group(1))
    return result


def list_page_names(wiki, space):
    """List direct page names for a space using the XWiki REST API."""
    path = "/rest/wikis/xwiki/spaces/" + "/spaces/".join(
        urllib.parse.quote(part, safe="") for part in space.split("/")
    ) + "/pages"
    status, body = wiki.request("GET", path)
    if status != 200:
        return []
    root = ET.fromstring(body)
    ns = "{http://www.xwiki.org}"
    return [entry.findtext(ns + "name") for entry in root.findall(ns + "pageSummary") if entry.findtext(ns + "name")]


def bootstrap_workflow_targets(module, wiki):
    """Connect existing Knowledge articles to the workflow without moving them.

    Legacy pages predating Publication Workflow don't have workflow metadata, so
    the extension cannot make an editable draft for them. Add a published-target
    record to ArticleClass pages and link a same-name Drafts article when present.
    New pages continue using the normal Start Workflow flow.
    """
    categories = knowledge_categories(wiki)
    module.log(f"discovered Knowledge categories: {len(categories)}")
    target_refs = {}
    for label, key in categories.items():
        space = f"Knowledge/{key}"
        for page in list_page_names(wiki, space):
            if not wiki.has_object(space, page, "LuxPedia.Code.ArticleClass"):
                continue
            target = f"Knowledge.{key}.{page}"
            target_refs[(label, page)] = target
            if not wiki.has_object(space, page, "PublicationWorkflow.PublicationWorkflowClass"):
                wiki.add_object(space, page, "PublicationWorkflow.PublicationWorkflowClass", {
                    "status": "published",
                    "workflow": "LuxPedia.Workflow.Config",
                    "includeChildren": "0",
                    "target": target,
                    "istarget": "1",
                    "publicationComment": "",
                })
                module.log(f"registered existing published article: {target}")

    # Draft articles are intentionally marked hidden, so REST page listings
    # omit them. Drafts.WebHome is an admin-authored trusted index that links
    # the pages carrying ArticleClass objects.
    status, draft_home = wiki.request("GET", "/bin/view/Drafts/WebHome")
    draft_pages = set()
    if status == 200:
        parser = KnowledgeCategoryLinks()
        parser.feed(draft_home.decode("utf-8", errors="replace"))
        for href, _ in parser.links:
            match = re.match(r"^/bin/view/Drafts/([^/?#]+)$", href)
            if match and match.group(1) != "WebHome":
                draft_pages.add(urllib.parse.unquote(match.group(1)))
    for page in sorted(draft_pages):
        objects = wiki.objects("Drafts", page, "LuxPedia.Code.ArticleClass")
        if not objects or wiki.has_object("Drafts", page, "PublicationWorkflow.PublicationWorkflowClass"):
            continue
        values = wiki.object_values("Drafts", page, "LuxPedia.Code.ArticleClass", objects[0])
        label = values.get("primaryCategory", "")
        target = target_refs.get((label, page))
        if target:
            wiki.add_object("Drafts", page, "PublicationWorkflow.PublicationWorkflowClass", {
                "status": "draft",
                "workflow": "LuxPedia.Workflow.Config",
                "includeChildren": "0",
                "target": target,
                "istarget": "0",
                "publicationComment": "",
            })
            module.log(f"linked existing draft Drafts.{page} -> {target}")


def restore_draft_creation(module, wiki):
    """Remove our obsolete home EDIT denials, which also block the create action.

    The installed server policy protects Drafts.WebHome saves independently of
    core EDIT access. Preserve unrelated/custom ACLs and all space restrictions.
    """
    for class_name in ("XWiki.XWikiGlobalRights", "XWiki.XWikiRights"):
        for number in wiki.objects("Drafts", "WebHome", class_name):
            values = wiki.object_values("Drafts", "WebHome", class_name, number)
            if (values.get("groups") == "XWiki.LuxEditors" and not values.get("users")
                    and values.get("levels") == "edit" and values.get("allow") == "0"):
                status, _ = wiki.request(
                    "DELETE", wiki.page_path("Drafts", "WebHome") + f"/objects/{class_name}/{number}",
                    token=wiki.form_token())
                if status not in (200, 204):
                    raise RuntimeError(f"Could not restore the Drafts create entry: HTTP {status}")
                module.log(f"removed obsolete Drafts home editor denial ({class_name})")


def apply(module, wiki):
    from apply_workflow_security import apply as apply_security
    from apply_interface import apply as apply_interface
    apply_security(module, wiki)
    module.log("== workflow configuration")
    wiki.ensure_page(CONFIG_SPACE, CONFIG_PAGE, "LuxPedia publication workflow", CONFIG_CONTENT, hidden=True, update=True)
    wiki.ensure_object_values(CONFIG_SPACE, CONFIG_PAGE, CONFIG_CLASS, CONFIG_VALUES)
    module.log("== lock LuxPedia configuration/help space")
    wiki.ensure_page("LuxPedia", "WebPreferences", "WebPreferences", "", hidden=True)
    wiki.ensure_right("LuxPedia", "WebPreferences", {
        "groups": "XWiki.LuxManagers", "levels": "view,edit,delete,admin", "allow": "1"})
    for group in LUXPEDIA_VIEW_ROLES:
        wiki.ensure_right("LuxPedia", "WebPreferences", {
            "groups": group, "levels": "view", "allow": "1"})
        wiki.ensure_right("LuxPedia", "WebPreferences", {
            "groups": group, "levels": "edit,delete,admin", "allow": "0"})
    wiki.ensure_right("LuxPedia", "WebPreferences", {
        "users": "XWiki.XWikiGuest", "levels": "view,comment,edit,delete,admin", "allow": "0"})
    module.log("== workflow panel in Drafts")
    wiki.ensure_object_values("Drafts", "WebPreferences", "XWiki.XWikiPreferences", PANEL_VALUES)
    module.log("== restore editor draft permissions")
    # The editor ACL had been reduced to view-only, which made all draft edit
    # pages return 403. Update the existing matching allow rule instead of
    # accumulating duplicate rights objects.
    for group, levels, denied in (
        ("XWiki.LuxEditors", "view,comment,edit", "delete"),
        ("XWiki.LuxReviewers", "view,comment", "edit,delete"),
    ):
        updated = False
        for number in wiki.objects("Drafts", "WebPreferences", "XWiki.XWikiGlobalRights"):
            values = wiki.object_values("Drafts", "WebPreferences", "XWiki.XWikiGlobalRights", number)
            if values.get("groups") == group and values.get("allow") == "1":
                if values.get("levels") != levels:
                    wiki.set_object_property("Drafts", "WebPreferences", "XWiki.XWikiGlobalRights", number,
                                             "levels", levels)
                    updated = True
                break
        else:
            wiki.ensure_right("Drafts", "WebPreferences", {
                "groups": group, "levels": levels, "allow": "1"})
            updated = True
        wiki.ensure_right("Drafts", "WebPreferences", {
            "groups": group, "levels": denied, "allow": "0"})
        module.log(f"{group} draft rights {'restored' if updated else 'unchanged'}")

    module.log("== repair mistakenly started workflow on Drafts home")
    workflow_numbers = wiki.objects("Drafts", "WebHome", "PublicationWorkflow.PublicationWorkflowClass")
    for number in workflow_numbers:
        status, body = wiki.request(
            "DELETE", wiki.page_path("Drafts", "WebHome") +
            f"/objects/PublicationWorkflow.PublicationWorkflowClass/{number}",
            token=wiki.form_token())
        if status not in (200, 204):
            raise RuntimeError(f"移除草稿首页误启动的工作流失败: {status} {body[:200]}")
        module.log("removed accidental workflow from Drafts.WebHome")

    module.log("== restore draft creation; server policy protects home saves")
    restore_draft_creation(module, wiki)

    # Editable writing-area content is migrated conservatively by apply_interface.

    module.log("== workflow panel on published Knowledge articles")
    wiki.ensure_object_values("Knowledge", "WebPreferences", "XWiki.XWikiPreferences", PANEL_VALUES)
    module.log("== connect existing published articles and drafts")
    bootstrap_workflow_targets(module, wiki)
    module.log("== editor save, preview and review workflow UX")
    wiki.ensure_page(UX_SPACE, UX_PAGE, "Workflow UX script", "Draft preview, explicit review submission and withdrawal controls.", hidden=True)
    wiki.ensure_object_values(UX_SPACE, UX_PAGE, "XWiki.JavaScriptExtension", WORKFLOW_UX_PROPERTIES)
    apply_interface(module, wiki)
    from apply_drawio import apply as apply_drawio
    apply_drawio(module, wiki)
    from apply_favorites import apply as apply_favorites
    apply_favorites(module, wiki)
    from apply_content_tools import apply as apply_content_tools
    apply_content_tools(module, wiki)
    from apply_admin_tools import apply as apply_admin_tools
    apply_admin_tools(module, wiki)
    from apply_department_roles import apply as apply_department_roles
    apply_department_roles(module, wiki)
    # Update auxiliary controls to use the same persisted interface language.
    for filename in ("apply_video_insert.py", "apply_admin_upload.py"):
        spec = importlib.util.spec_from_file_location(filename[:-3], ROOT / "scripts" / filename)
        auxiliary = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(auxiliary)
        if filename == "apply_video_insert.py":
            auxiliary.apply(module, wiki)
        else:
            wiki.ensure_page(auxiliary.JS_SPACE, auxiliary.JS_PAGE, "Upload limit control", "Upload limit control.", hidden=True)
            wiki.ensure_object_values(auxiliary.JS_SPACE, auxiliary.JS_PAGE, "XWiki.JavaScriptExtension", auxiliary.JS_PROPERTIES)
            for number in wiki.objects("XWiki", "XWikiPreferences", "XWiki.ConfigurableClass"):
                values = wiki.object_values("XWiki", "XWikiPreferences", "XWiki.ConfigurableClass", number)
                if values.get("displayInSection") == "attachmentsrestriction":
                    wiki.set_object_property("XWiki", "XWikiPreferences", "XWiki.ConfigurableClass", number,
                                             "heading", "Attachment upload limit (MB)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="write to XWiki; default is a dry run")
    parser.add_argument("--base-url", default=os.environ.get("XWIKI_URL", "http://127.0.0.1:8080"))
    args = parser.parse_args()

    module = load_apply_module()
    username, password = module.load_admin()
    wiki = module.XWiki(args.base_url, username, password)
    status, _ = wiki.request("GET", "/rest/wikis/xwiki")
    if status != 200:
        raise SystemExit(f"无法连接 XWiki REST: {args.base_url} ({status})")

    if not args.apply:
        module.log("dry run: pass --apply to write")
        module.log(f"target: {args.base_url}")
        for key, value in CONFIG_VALUES.items():
            module.log(f"  {key} = {value}")
        module.log("  Drafts.WebPreferences rightPanels = PublicationWorkflow.PublicationWorkflowPanel")
        module.log("  Drafts editors: view, comment, edit")
        module.log("  Drafts.WebHome: restore editor create entry; server policy protects index saves")
        return

    apply(module, wiki)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        raise
