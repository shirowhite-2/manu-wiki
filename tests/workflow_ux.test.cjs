// Browser regression checks for the generated XWiki extension.
// Run: node --test tests/workflow_ux.test.cjs (requires Playwright + Chromium).
// Routes model the upstream workflow forms/redirects; no live wiki is changed.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const http = require('node:http');
const fs = require('node:fs');
const { chromium } = require('playwright');

const source = process.env.WORKFLOW_UX_SOURCE || path.join(__dirname, '../scripts/apply_workflow.py');
const extracted = spawnSync('python3', ['-c',
  'import runpy, sys; print(runpy.run_path(sys.argv[1])["WORKFLOW_UX_JS"])', source], { encoding: 'utf8' });
assert.equal(extracted.status, 0, extracted.stderr);
const ux = extracted.stdout;
const prefix = 'luxpedia.workflow.submit.';
let browser;
before(async () => { browser = await chromium.launch({
  headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined,
}); });
after(async () => { await browser?.close(); });

async function wikiFixture(options = {}) {
  const context = await browser.newContext();
  const page = await context.newPage();
  let reference = options.reference || 'Drafts.NewArticle.WebHome';
  let viewPath = '/bin/view/' + reference.split('.').join('/');
  let editPath = viewPath.replace('/view/', '/edit/');
  const pendingKey = prefix + reference;
  const state = {
    status: options.status || (options.linkedTarget ? 'draft' : ''),
    target: options.linkedTarget || '',
    category: options.primaryCategory || '',
    hasArticle: options.hasArticle !== false,
    actions: [], alerts: [], errors: [], withdrawals: [], workspaceActions: [], confirmations: 0,
  };
  const workspaceStates = { [reference]: !!options.abandoned };
  page.on('pageerror', error => state.errors.push(error.message));
  page.on('dialog', async dialog => {
    if (dialog.type() === 'confirm') { state.confirmations++; } else { state.alerts.push(dialog.message()); }
    await dialog.accept();
  });
  function form(action, fields = '') {
    return `<form method="post" action="/bin/view/PublicationWorkflow/Script">
      <input name="action" type="hidden" value="${action}">
      <input name="workflowdoc" type="hidden" value="${reference}">
      ${fields}<input type="submit" value="Continue"></form>`;
  }
  const linkedDraftReference = options.draftReference || 'Drafts.Linked.Article';
  const linkedDraftPath = options.draftPath || '/bin/view/' + linkedDraftReference.split('.').join('/');
  const escapeAttribute = value => String(value).replaceAll('&', '&amp;').replaceAll('"', '&quot;').replaceAll('<', '&lt;');
  function nativeCategoryMarkup() {
    const config = options.nativeCategory;
    if (!config) { return ''; }
    const id = config.id || 'LuxPedia.Code.ArticleClass_0_primaryCategory';
    const name = config.name || id;
    return `<dl id="article-properties"><dt><label for="${escapeAttribute(id)}">Category</label></dt><dd>
      <select id="${escapeAttribute(id)}" name="${escapeAttribute(name)}" class="native-property-widget"
        onchange="window.nativeCategoryChanges=(window.nativeCategoryChanges||0)+1">
        ${config.values.map(([value, label]) => `<option value="${escapeAttribute(value)}"${value === config.selected ? ' selected' : ''}>${escapeAttribute(label)}</option>`).join('')}
      </select></dd></dl>`;
  }
  function html(edit, requestedLanguage) {
    const nativeLanguage = requestedLanguage || options.serverLanguage || '';
    const start = form('start', `<input name="targetSpace" value="Drafts">
      <input name="targetName" value="WebHome">
      <input name="targetIsTerminal" type="checkbox">
      <input name="includeChildren" type="checkbox" checked>
      <input name="startAsTarget" type="checkbox">
      <select name="workflow"><option value="LuxPedia.Workflow.Config">LuxPedia 发布流程配置</option></select>`);
    const workflow = !state.status ? start : state.status === 'draft' ? form('submitformoderation') :
      state.status === 'validating' ? form('publish') + form('validate') + form('refusevalidation') + form('archive') :
      state.status === 'published' ? (options.existingDraftState ?
        `<form method="get" action="${linkedDraftPath}"><input type="submit" value="Go to draft"></form>` :
        options.noDraftForm ? '' : form('getEditableDraft')) + form('archive') + form('unpublish') :
      state.status === 'archived' ? form('publishfromarchive') + form('backtodraft') : '';
    return `<!doctype html><html lang="${nativeLanguage}"><title>Local workflow test fixture — production theme not loaded</title><meta name="document" content="${reference}">
      ${options.navbar ? '<header id="headerglobal"><nav id="menuview"><ul class="navbar-right"><li id="original-menu">Original menu</li></ul></nav></header>' : ''}
      <p role="note">Local test fixture. The production LuxPedia theme is not loaded.</p>
      <div data-xwiki-form-token="test-token"></div>
      <a id="tmUser" href="/bin/view/XWiki/${options.admin ? 'Admin' : 'editor'}">Profile</a>
      ${options.nativeEdit ? '<a id="tmEdit" href="' + editPath + '">Edit</a>' : ''}
      <div id="document-title"><h1>New article</h1></div>
      ${edit ? `${options.lateNativeCategory ? '' : nativeCategoryMarkup()}<div><textarea id="xwikieditor"></textarea></div>
        <button class="realtime-action-done" onclick="location.href='${viewPath}'">Done</button>` : '<div class="panel"><h2 class="panel-title">Workflow</h2><div class="publicationworkflow-info">' + workflow + '</div></div>'}
      ${options.list ? '<div id="luxpedia-workspace-list"><button type="button">Abandon selected drafts</button></div>' +
        options.list.map(name => '<input type="checkbox" class="luxpedia-draft-select" data-reference="' + name + '" data-version="4.2">').join('') : ''}
      <div id="xwikicontent"><article id="user-content">用户撰写的正文 stays unchanged.</article></div>
      <script>${ux}</script>`;
  }
  async function handle(route) {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = decodeURIComponent(url.pathname);
    const json = body => route.fulfill({ json: body });
    if (pathname === '/bin/get/LuxPedia/Workflow/DraftWorkspace') {
      const name = url.searchParams.get('reference');
      const actor = options.admin || !['reviewer', 'reader'].includes(options.role);
      const canAbandon = actor && (options.admin || options.ownWorkspace !== false && !options.sharedWorkspace);
      if (request.method() === 'GET') {
        return json({ abandoned: !!workspaceStates[name], abandon: canAbandon && !workspaceStates[name] &&
          ['', 'draft'].includes(state.status), restore: actor && !!workspaceStates[name], version: '4.2' });
      }
      const fields = Object.fromEntries(new URLSearchParams(request.postData()));
      state.workspaceActions.push(fields);
      if (options.workspaceReject === fields.reference || options.workspaceFail || fields.version !== '4.2' ||
          fields.form_token !== 'test-token') { return route.fulfill({ body: 'WORKSPACE_CONFLICT' }); }
      const restoring = fields.operation === 'restore';
      if (!actor || !['', 'draft'].includes(state.status) || restoring !== !!workspaceStates[fields.reference] ||
          !restoring && !canAbandon) { return route.fulfill({ body: 'WORKSPACE_FORBIDDEN' }); }
      workspaceStates[fields.reference] = !restoring;
      return route.fulfill({ body: restoring ? 'DRAFT_RESTORED' : 'DRAFT_ABANDONED' });
    }
    if (pathname === '/bin/get/LuxPedia/Workflow/Actions') {
      if (request.method() === 'GET') {
        return route.fulfill({ body: 'WITHDRAW_VERSION=' + (options.ownSubmission !== false && state.status === 'validating' ? '4.2' : '') });
      }
      const fields = Object.fromEntries(new URLSearchParams(request.postData()));
      state.withdrawals.push(fields);
      if (options.withdrawFail || fields.version !== '4.2' || fields.form_token !== 'test-token') {
        return route.fulfill({ body: 'WITHDRAW_CONFLICT' });
      }
      state.status = 'draft'; return route.fulfill({ body: 'WITHDRAWN' });
    }
    if (pathname.endsWith('/PublicationWorkflow/Script')) {
      const fields = Object.fromEntries(new URLSearchParams(request.postData()));
      state.actions.push(fields);
      let query = '';
      if (fields.action === 'start') {
        state.target = fields.targetSpace + '.' + fields.targetName +
          (fields.targetIsTerminal ? '' : '.WebHome');
        // A category homepage exists before any article is submitted.
        if (['Knowledge.Technical.WebHome', 'Knowledge.Operations.WebHome', options.collision]
          .includes(state.target)) {
          query = '?targetexists=true';
        } else {
          state.status = 'draft';
        }
      } else if (fields.action === 'submitformoderation') {
        assert.equal(state.status, 'draft');
        state.status = 'validating'; // two-role config has no moderator
      } else if (fields.action === 'publishfromarchive') {
        state.status = 'published';
      } else if (fields.action === 'getEditableDraft') {
        reference = options.draftReference || 'Drafts.Linked.Article';
        viewPath = '/bin/view/' + reference.split('.').join('/');
        editPath = viewPath.replace('/view/', '/edit/');
        state.status = 'draft';
      }
      return route.fulfill({ status: 303, headers: { location: viewPath + query }, body: '' });
    }
    if (pathname === '/bin/view/Knowledge/CategoryIndex') {
      return route.fulfill({ contentType: 'text/html', body:
        '<a href="/bin/view/Knowledge/Technical/WebHome">技术文档</a>' +
        '<a href="/bin/view/Knowledge/Operations/WebHome">操作手册</a>' });
    }
    if (pathname === '/bin/get/LuxPedia/Workflow/CurrentRoles') {
      return route.fulfill({ contentType: 'text/plain', body: JSON.stringify({
        editor: !['reviewer', 'reader'].includes(options.role) && !options.admin,
        reviewer: options.role === 'reviewer', admin: !!options.admin
      }) });
    }
    if (pathname === linkedDraftPath && request.method() === 'GET' && options.existingDraftState) {
      reference = linkedDraftReference;
      viewPath = linkedDraftPath;
      editPath = viewPath.replace('/view/', '/edit/');
      state.status = options.existingDraftState;
    }
    if (pathname.startsWith('/rest/')) {
      const linkedParts = linkedDraftPath.slice('/bin/view/'.length).split('/').filter(Boolean);
      if (linkedDraftPath.endsWith('/')) { linkedParts.push('WebHome'); }
      const existingDraftRest = '/rest/wikis/xwiki/' + linkedParts
        .map((part, index, parts) => (index === parts.length - 1 ? 'pages/' : 'spaces/') + part).join('/');
      if (pathname === existingDraftRest) {
        if (options.draftLookupStatus) { return route.fulfill({ status: options.draftLookupStatus, body: '' }); }
        return json({ fullName: linkedDraftReference });
      }
      if (pathname.includes('/objects/XWiki.XWikiGroups')) {
        return route.fulfill({ status: 403, body: 'Private role membership' });
      }
      if (pathname.includes('/objects/PublicationWorkflow.PublicationWorkflowClass')) {
        if (pathname.endsWith('/properties/status')) { return json({ value: state.status }); }
        if (pathname.endsWith('/properties/target')) { return json({ value: state.target }); }
        return json({ objectSummaries: state.status ? [{ number: 0 }] : [] });
      }
      if (pathname.endsWith('/objects') && request.method() === 'POST') {
        const fields = new URLSearchParams(request.postData());
        state.hasArticle = true;
        state.category = fields.get('property#primaryCategory');
        return json({});
      }
      if (pathname.includes('/objects/LuxPedia.Code.ArticleClass')) {
        if (pathname.endsWith('/properties')) {
          return json({ properties: [
            { name: 'primaryCategory', value: state.category }, { name: 'owner', value: 'XWiki.editor' },
            { name: 'status', value: 'draft' }, { name: 'summary', value: '<script>user data</script>' },
          ] });
        }
        if (pathname.endsWith('/properties/primaryCategory')) {
          if (request.method() === 'PUT') { state.category = request.postData(); }
          return json({ value: state.category });
        }
        return json({ objectSummaries: state.hasArticle ? [{ number: 0 }] : [] });
      }
      throw new Error('Unexpected REST request: ' + pathname);
    }
    return route.fulfill({ contentType: 'text/html', body: html(pathname === editPath, url.searchParams.get('xwikilanguage')) });
  }
  // A real local server exercises native POST/303 navigations as well as REST.
  const server = http.createServer(async (request, response) => {
    let body = '';
    for await (const chunk of request) { body += chunk; }
    try {
      await handle({
        request: () => ({ url: () => origin + request.url,
          method: () => request.method, postData: () => body }),
        fulfill: async result => {
          response.writeHead(result.status || 200, { ...result.headers,
            'Content-Type': (result.json ? 'application/json' : result.contentType || 'text/plain') + '; charset=utf-8' });
          response.end(result.json ? JSON.stringify(result.json) : result.body || '');
        },
      });
    } catch (error) {
      state.errors.push(error.message);
      response.writeHead(500);
      response.end(error.message);
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  context.on('close', () => server.close());
  async function preview(category) {
    await page.goto(origin + editPath);
    if (!options.linkedTarget) {
      const selector = options.nativeCategory && !options.lateNativeCategory ?
        'select[data-luxpedia-publication-category]' : '#luxpedia-publish-category';
      await page.locator(selector).waitFor();
      if (category !== undefined) { await page.selectOption(selector, category); }
    } else {
      await page.locator('#luxpedia-category-choice').waitFor();
      assert.equal(await page.locator('#luxpedia-publish-category').count(), 0);
    }
    await page.waitForFunction(() => document.querySelector('.realtime-action-done')
      ?.getAttribute('data-luxpedia-preview-bound') === '1');
    await page.locator('.realtime-action-done').click();
    await page.waitForFunction(() => document.querySelector('#luxpedia-submit-review') &&
      !document.querySelector('#luxpedia-submit-review').disabled);
  }
  async function submit(category) {
    await preview(category);
    await page.locator('#luxpedia-submit-review').click();
  }
  async function expectPending(target) {
    try {
      await page.waitForFunction(key => sessionStorage.getItem(key) === null,
        pendingKey, { timeout: 7000 });
    } catch (error) {
      throw new Error(JSON.stringify({ ...state, actions: state.actions.slice(0, 2),
        actionCount: state.actions.length, url: page.url() }), { cause: error });
    }
    assert.deepEqual(state.alerts, []);
    assert.deepEqual(state.errors, []);
    assert.equal(state.status, 'validating');
    assert.equal(state.target, target);
    assert.equal(await page.evaluate(key => sessionStorage.getItem(key + '.category'), pendingKey), null);
    const start = state.actions.find(action => action.action === 'start');
    if (start) {
      assert.equal(start.targetIsTerminal, 'on');
      assert.equal(start.includeChildren, undefined);
      assert.equal(start.startAsTarget, undefined);
      assert.equal(start.workflow, 'LuxPedia.Workflow.Config');
    }
  }
  return { context, page, state, preview, submit, expectPending, pendingKey, origin, nativeCategoryMarkup };
}

for (const value of ['Operations', '操作手册', 'Knowledge.Operations.WebHome', 'xwiki:Knowledge.Operations.WebHome']) {
  test('native article category is reused and preserves its stored value: ' + value, async () => {
    const wiki = await wikiFixture({ nativeCategory: {
      values: [['', 'Knowledge'], [value, '操作手册']], selected: value,
    } });
    try {
      await wiki.submit();
      await wiki.expectPending('Knowledge.Operations.NewArticle');
      assert.equal(wiki.state.category, value);
    } finally { await wiki.context.close(); }
  });
}

test('native property node, selection, options and event bindings survive language changes', async () => {
  const wiki = await wikiFixture({ nativeCategory: {
    values: [['', 'Knowledge'], ['Operations', '操作手册'], ['Technical', '技术文档']], selected: 'Operations',
  } });
  try {
    await wiki.page.goto(wiki.origin + '/bin/edit/Drafts/NewArticle/WebHome');
    const native = wiki.page.locator('select[data-luxpedia-publication-category]');
    await native.waitFor();
    assert.equal(await wiki.page.locator('#luxpedia-category-choice').count(), 0);
    await wiki.page.evaluate(() => { window.originalCategory = document.querySelector('select[data-luxpedia-publication-category]'); });
    assert.equal(await native.inputValue(), 'Operations');
    assert.equal(await native.getAttribute('name'), 'LuxPedia.Code.ArticleClass_0_primaryCategory');
    assert.equal(await native.getAttribute('class'), 'native-property-widget');
    await wiki.page.fill('#xwikieditor', 'Unsaved article');
    await native.selectOption('Technical');
    const url = wiki.page.url();
    await wiki.page.selectOption('#luxpedia-language-select', 'zh');
    assert.equal(wiki.page.url(), url);
    assert.equal(await wiki.page.locator('#xwikieditor').inputValue(), 'Unsaved article');
    assert.equal(await native.inputValue(), 'Technical');
    assert.deepEqual(await native.locator('option').allTextContents(), ['Knowledge', '操作手册', '技术文档']);
    assert.equal(await wiki.page.evaluate(() => window.originalCategory === document.querySelector('select[data-luxpedia-publication-category]')), true);
    assert.equal(await wiki.page.evaluate(() => window.nativeCategoryChanges), 1);
    await wiki.page.locator('.realtime-action-done').click();
    await wiki.page.locator('#luxpedia-submit-review').click();
    await wiki.expectPending('Knowledge.Technical.NewArticle');
  } finally { await wiki.context.close(); }
});

test('generic native category field maps its option label to the workflow category', async () => {
  const wiki = await wikiFixture({ nativeCategory: { name: 'category', id: 'native-category',
    values: [['category-42', '操作手册']], selected: 'category-42',
  } });
  try {
    await wiki.submit();
    await wiki.expectPending('Knowledge.Operations.NewArticle');
    assert.equal(wiki.state.category, '操作手册');
  } finally { await wiki.context.close(); }
});

test('native root selection overrides a nested draft location', async () => {
  const wiki = await wikiFixture({ reference: 'Drafts.Technical.NewArticle.WebHome', nativeCategory: {
    values: [['Knowledge.WebHome', 'Knowledge'], ['Technical', '技术文档']], selected: 'Knowledge.WebHome',
  } });
  try {
    await wiki.submit();
    await wiki.expectPending('Knowledge.NewArticle');
  } finally { await wiki.context.close(); }
});

for (const value of ['otherwiki:Knowledge.Operations.WebHome', 'unknown-category']) {
  test('invalid native category blocks submission and repeated retries: ' + value, async () => {
    const wiki = await wikiFixture({ nativeCategory: { values: [[value, 'Unknown']], selected: value } });
    try {
      await wiki.preview();
      assert.equal(wiki.state.alerts.length, 1);
      for (let attempt = 0; attempt < 2; attempt++) {
        await wiki.page.locator('#luxpedia-submit-review').click();
        await wiki.page.waitForFunction(() => document.querySelector('#luxpedia-submit-review') &&
          !document.querySelector('#luxpedia-submit-review').disabled);
        assert.equal(wiki.state.alerts.length, attempt + 2);
        assert.equal(wiki.state.actions.length, 0);
      }
      assert.equal(wiki.state.status, '');
      assert.deepEqual(wiki.state.errors, []);
    } finally { await wiki.context.close(); }
  });
}

test('late native property replaces fallback without losing a changed category or body', async () => {
  const wiki = await wikiFixture({ lateNativeCategory: true, nativeCategory: {
    values: [['Knowledge.WebHome', 'Knowledge'], ['Technical', '技术文档']], selected: 'Knowledge.WebHome',
  } });
  try {
    await wiki.page.goto(wiki.origin + '/bin/edit/Drafts/NewArticle/WebHome');
    await wiki.page.locator('#luxpedia-publish-category').selectOption('Technical');
    await wiki.page.fill('#xwikieditor', 'Keep this draft');
    await wiki.page.evaluate(markup => document.querySelector('#xwikieditor').insertAdjacentHTML('beforebegin', markup), wiki.nativeCategoryMarkup());
    const native = wiki.page.locator('select[data-luxpedia-publication-category]');
    await native.waitFor();
    assert.equal(await native.inputValue(), 'Technical');
    assert.equal(await wiki.page.locator('#luxpedia-publish-category').count(), 0);
    assert.equal(await wiki.page.locator('#xwikieditor').inputValue(), 'Keep this draft');
    await wiki.page.locator('.realtime-action-done').click();
    await wiki.page.locator('#luxpedia-submit-review').click();
    await wiki.expectPending('Knowledge.Technical.NewArticle');
  } finally { await wiki.context.close(); }
});

for (const scenario of [
  { name: 'normal nested page uses its article name in the selected category',
    primaryCategory: '操作手册', category: 'Technical', target: 'Knowledge.Technical.NewArticle' },
  { name: 'plain nested page creates ArticleClass metadata before submitting',
    hasArticle: false, category: 'Technical', target: 'Knowledge.Technical.NewArticle' },
  { name: 'terminal page keeps its name', reference: 'Drafts.TerminalArticle',
    category: 'Technical', target: 'Knowledge.Technical.TerminalArticle' },
  { name: 'second nested article does not collide with the category homepage',
    reference: 'Drafts.OtherArticle.WebHome', category: 'Technical', target: 'Knowledge.Technical.OtherArticle' },
  { name: 'explicit root choice overrides the source category',
    reference: 'Drafts.Operations.NewArticle.WebHome', primaryCategory: '操作手册',
    category: '__root__', target: 'Knowledge.NewArticle' },
  { name: 'source category inference excludes the article container',
    reference: 'Drafts.Technical.NewArticle.WebHome', target: 'Knowledge.Technical.NewArticle' },
  { name: 'primary category supplies the default selection',
    primaryCategory: '技术文档', target: 'Knowledge.Technical.NewArticle' },
  { name: 'reopened native category references supply the default selection',
    primaryCategory: 'xwiki:Knowledge.Operations.WebHome', target: 'Knowledge.Operations.NewArticle' },
  { name: 'reopened explicit native root selection overrides the draft folder',
    reference: 'Drafts.Technical.NewArticle.WebHome', primaryCategory: 'Knowledge.WebHome', target: 'Knowledge.NewArticle' },
  { name: 'linked published article keeps the exact original target',
    linkedTarget: 'Knowledge.Operations.Existing.WebHome', target: 'Knowledge.Operations.Existing.WebHome' },
]) {
  test(scenario.name, async () => {
    const wiki = await wikiFixture(scenario);
    try {
      await wiki.submit(scenario.category);
      await wiki.expectPending(scenario.target);
      assert.deepEqual(wiki.state.actions.map(item => item.action), scenario.linkedTarget ?
        ['submitformoderation'] : ['start', 'submitformoderation']);
      if (scenario.category === 'Technical') { assert.equal(wiki.state.category, '技术文档'); }
    } finally { await wiki.context.close(); }
  });
}

test('existing target reports submission failure and stops retrying', async () => {
  const wiki = await wikiFixture({ collision: 'Knowledge.Technical.NewArticle' });
  try {
    await wiki.submit('Technical');
    await wiki.page.waitForFunction(key => sessionStorage.getItem(key) === null,
      wiki.pendingKey, { timeout: 7000 });
    assert.equal(wiki.state.status, '');
    assert.equal(wiki.state.actions.length, 1);
    assert.equal(wiki.state.alerts.length, 1);
    assert.match(wiki.state.alerts[0], /was not submitted for review/);
  } finally { await wiki.context.close(); }
});

test('administrator keeps the standard Done button', async () => {
  const wiki = await wikiFixture({ admin: true });
  try {
    const rolesChecked = wiki.page.waitForResponse(response => response.url().includes('/Workflow/CurrentRoles?'));
    await wiki.page.goto(wiki.origin + '/bin/edit/Drafts/NewArticle/WebHome');
    // Wait until boot has performed its role checks.
    await rolesChecked;
    assert.equal(await wiki.page.locator('.realtime-action-done').textContent(), 'Done');
    assert.equal(await wiki.page.locator('#luxpedia-publish-category').count(), 0);
    assert.equal(wiki.state.actions.length, 0);
  } finally { await wiki.context.close(); }
});

test('English is the default and the panel explains the review step', async () => {
  const wiki = await wikiFixture({ status: 'validating', linkedTarget: 'Knowledge.Technical.Article', role: 'reviewer' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Awaiting review');
    assert.equal(await wiki.page.locator('#luxpedia-language-select').inputValue(), 'en');
    assert.match(await wiki.page.locator('.luxpedia-next').textContent(), /submitted version is locked/);
    assert.match(await wiki.page.locator('.luxpedia-workflow-summary dd').textContent(), /Knowledge.*Technical.*Article/);
    assert.equal(await wiki.page.locator('form:has(input[value="publish"]) input[type="submit"]').inputValue(), 'Approve and publish');
    await wiki.page.waitForFunction(() => document.querySelector('form:has(input[value="archive"])')?.hidden === true);
    if (process.env.WORKFLOW_SCREENSHOTS_DIR) {
      await wiki.page.screenshot({ path: path.join(process.env.WORKFLOW_SCREENSHOTS_DIR, 'workflow-en.png') });
    }
  } finally { await wiki.context.close(); }
});

test('Chinese selection persists across navigation and leaves article content intact', async () => {
  const wiki = await wikiFixture({ status: 'validating', linkedTarget: 'Knowledge.Technical.Article', role: 'reviewer' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    const before = await wiki.page.locator('#user-content').textContent();
    await wiki.page.selectOption('#luxpedia-language-select', 'zh');
    await wiki.page.waitForURL(/xwikilanguage=zh/);
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === '待审核');
    assert.equal(await wiki.page.locator('#user-content').textContent(), before);
    assert.equal(await wiki.page.locator('#luxpedia-article-metadata tr[data-field="status"] td').textContent(), '待审核');
    assert.equal(await wiki.page.locator('#luxpedia-article-metadata tr[data-field="summary"] td').textContent(), '<script>user data</script>');
    assert.equal(await wiki.page.locator('form:has(input[value="publish"]) input[type="submit"]').inputValue(), '批准并发布');
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    assert.equal(await wiki.page.locator('#luxpedia-language-select').inputValue(), 'zh');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === '待审核');
    if (process.env.WORKFLOW_SCREENSHOTS_DIR) {
      await wiki.page.screenshot({ path: path.join(process.env.WORKFLOW_SCREENSHOTS_DIR, 'workflow-zh.png') });
    }
    await wiki.page.selectOption('#luxpedia-language-select', 'en');
    await wiki.page.waitForURL(/xwikilanguage=en/);
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Awaiting review');
  } finally { await wiki.context.close(); }
});

test('language switching in the editor preserves unsaved text and the selected category', async () => {
  const wiki = await wikiFixture();
  try {
    await wiki.page.goto(wiki.origin + '/bin/edit/Drafts/NewArticle/WebHome');
    await wiki.page.locator('#luxpedia-publish-category').waitFor();
    await wiki.page.fill('#xwikieditor', 'Unsaved text 未保存的正文');
    await wiki.page.selectOption('#luxpedia-publish-category', 'Technical');
    const url = wiki.page.url();
    await wiki.page.selectOption('#luxpedia-language-select', 'zh');
    assert.equal(wiki.page.url(), url);
    assert.equal(await wiki.page.locator('#xwikieditor').inputValue(), 'Unsaved text 未保存的正文');
    assert.equal(await wiki.page.locator('#luxpedia-publish-category').inputValue(), 'Technical');
    assert.equal(await wiki.page.locator('.realtime-action-done').textContent(), '保存草稿并预览');
    await wiki.page.locator('.realtime-action-done').click();
    await wiki.page.locator('#luxpedia-submit-review').click();
    await wiki.expectPending('Knowledge.Technical.NewArticle');
  } finally { await wiki.context.close(); }
});

test('reviewer cannot see author or administrator workflow actions', async () => {
  const wiki = await wikiFixture({ status: 'published', linkedTarget: 'Knowledge.Technical.Article', role: 'reviewer' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.waitForFunction(() => document.querySelector('form:has(input[value="getEditableDraft"])')?.hidden === true);
    for (const action of ['getEditableDraft', 'archive', 'unpublish']) {
      assert.equal(await wiki.page.locator('form:has(input[value="' + action + '"])').isVisible(), false);
    }
  } finally { await wiki.context.close(); }
});

test('workflow controls inherit the host theme palette', async () => {
  const wiki = await wikiFixture({ status: 'validating', linkedTarget: 'Knowledge.Technical.Article', role: 'reviewer', navbar: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Awaiting review');
    await wiki.page.addStyleTag({ content: '.btn-primary{background-color:rgb(126,34,206);color:rgb(255,255,255)}' +
      '.label-default{background-color:rgb(22,101,52);color:rgb(255,255,255)}.text-primary{color:rgb(126,34,206)}' });
    assert.equal(await wiki.page.locator('form:has(input[value="publish"]) input[type="submit"]').evaluate(
      node => getComputedStyle(node).backgroundColor), 'rgb(126, 34, 206)');
    assert.equal(await wiki.page.locator('.luxpedia-state').evaluate(
      node => getComputedStyle(node).backgroundColor), 'rgb(22, 101, 52)');
    assert.equal(await wiki.page.locator('.luxpedia-steps [aria-current]').evaluate(
      node => getComputedStyle(node).color), 'rgb(126, 34, 206)');
    assert.equal(await wiki.page.locator('#menuview .navbar-right > li#luxpedia-language').count(), 1);
    assert.equal(await wiki.page.locator('#original-menu').textContent(), 'Original menu');
  } finally { await wiki.context.close(); }
});

test('a stale Chinese server locale is reconciled with the English default', async () => {
  const wiki = await wikiFixture({ serverLanguage: 'zh' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.waitForURL(/xwikilanguage=en/);
    assert.equal(await wiki.page.locator('html').getAttribute('lang'), 'en');
    assert.equal(await wiki.page.locator('select[name="workflow"] option').textContent(), 'LuxPedia publication workflow');
    assert.equal(wiki.state.errors.length, 0);
  } finally { await wiki.context.close(); }
});

test('language switching in a creation form preserves entered text', async () => {
  const wiki = await wikiFixture();
  try {
    await wiki.page.goto(wiki.origin + '/bin/create/Drafts/WebHome');
    await wiki.page.evaluate(() => {
      const title = document.createElement('input'); title.id = 'new-page-title'; document.body.appendChild(title);
    });
    await wiki.page.fill('#new-page-title', 'My unsaved article title');
    await wiki.page.selectOption('#luxpedia-language-select', 'zh');
    assert.equal(await wiki.page.locator('#new-page-title').inputValue(), 'My unsaved article title');
    assert.equal(new URL(wiki.page.url()).pathname, '/bin/create/Drafts/WebHome');
    assert.equal(await wiki.page.locator('select[name="workflow"] option').textContent(), 'LuxPedia 文章发布流程');
  } finally { await wiki.context.close(); }
});

test('rendered review queue keeps separate rows and fits desktop and mobile widths', async t => {
  const fixture = path.join(__dirname, '../extensions/workflow-policy/target/review-queue-en.html');
  if (!fs.existsSync(fixture)) {
    t.skip('Run Maven tests first to generate the real Velocity rendering fixture.'); return;
  }
  const page = await browser.newPage();
  try {
    for (const width of [1280, 375]) {
      await page.setViewportSize({ width, height: 800 });
      await page.setContent(fs.readFileSync(fixture, 'utf8'));
      const rows = page.locator('tbody tr');
      assert.equal(await rows.count(), 2);
      assert.equal(await rows.nth(0).locator('td').count(), 3);
      const first = await rows.nth(0).boundingBox();
      const second = await rows.nth(1).boundingBox();
      assert(second.y >= first.y + first.height - 1);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      assert.deepEqual(await page.locator('th').allTextContents(), ['Title', 'Status', 'Publication location']);
    }
  } finally { await page.close(); }
});

test('saving opens the rendered preview without starting or submitting a workflow', async () => {
  const wiki = await wikiFixture();
  try {
    await wiki.preview('Technical');
    assert.equal(new URL(wiki.page.url()).pathname, '/bin/view/Drafts/NewArticle/WebHome');
    assert.equal(wiki.state.status, '');
    assert.equal(wiki.state.actions.length, 0);
    assert.equal(wiki.state.category, '技术文档');
    assert.equal(await wiki.page.locator('#user-content').textContent(), '用户撰写的正文 stays unchanged.');
    await wiki.page.locator('#luxpedia-continue-editing').click();
    await wiki.page.waitForURL(/\/bin\/edit\//);
    assert.equal(wiki.state.actions.length, 0);
  } finally { await wiki.context.close(); }
});

test('submitter can withdraw a pending request using POST and CSRF then edit again', async () => {
  const wiki = await wikiFixture({ status: 'validating', linkedTarget: 'Knowledge.Technical.Article' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.locator('#luxpedia-withdraw-review').click();
    await wiki.page.locator('#luxpedia-continue-editing').waitFor();
    assert.equal(wiki.state.status, 'draft');
    assert.equal(wiki.state.withdrawals.length, 1);
    assert.equal(wiki.state.withdrawals[0].reference, 'Drafts.NewArticle.WebHome');
    assert.equal(wiki.state.withdrawals[0].form_token, 'test-token');
    assert.equal(wiki.state.withdrawals[0].version, '4.2');
    assert.equal(wiki.state.confirmations, 1);
    assert.deepEqual(wiki.state.alerts, []);
  } finally { await wiki.context.close(); }
});

test('another editor sees no withdrawal action for someone elses submission', async () => {
  const wiki = await wikiFixture({ status: 'validating', ownSubmission: false });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Awaiting review');
    assert.equal(await wiki.page.locator('#luxpedia-withdraw-review').count(), 0);
    assert.equal(wiki.state.withdrawals.length, 0);
  } finally { await wiki.context.close(); }
});

test('a stale withdrawal keeps the article pending and reports failure', async () => {
  const wiki = await wikiFixture({ status: 'validating', withdrawFail: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.locator('#luxpedia-withdraw-review').click();
    await wiki.page.waitForFunction(() => !document.querySelector('#luxpedia-withdraw-review').disabled);
    assert.equal(wiki.state.status, 'validating');
    assert.match(wiki.state.alerts[0], /could not be withdrawn/);
  } finally { await wiki.context.close(); }
});

test('an editor opens a linked draft from Knowledge while keeping the published target', async () => {
  const wiki = await wikiFixture({ reference: 'Knowledge.Technical.Article', status: 'published', linkedTarget: 'Knowledge.Technical.Article' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/Technical/Article');
    await wiki.page.locator('#luxpedia-edit-article').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/Linked\/Article/);
    assert.equal(wiki.state.target, 'Knowledge.Technical.Article');
    assert.deepEqual(wiki.state.actions.map(action => action.action), ['getEditableDraft']);
    assert.equal(wiki.state.status, 'draft');
  } finally { await wiki.context.close(); }
});

for (const pending of [false, true]) {
  test('existing native GET draft link opens ' + (pending ? 'pending review without editing' : 'the editable draft'), async () => {
    const target = 'Knowledge.Technical.Article';
    const wiki = await wikiFixture({ reference: target, status: 'published', linkedTarget: target,
      existingDraftState: pending ? 'validating' : 'draft', nativeEdit: true });
    try {
      await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/Technical/Article');
      await wiki.page.locator('#luxpedia-edit-article').click();
      await wiki.page.waitForURL(new RegExp('/bin/' + (pending ? 'view' : 'edit') + '/Drafts/Linked/Article'));
      if (pending) {
        await wiki.page.locator('#luxpedia-withdraw-review').waitFor();
        assert.equal(await wiki.page.locator('#luxpedia-continue-editing').count(), 0);
      }
      assert.equal(wiki.state.target, target);
      assert.deepEqual(wiki.state.actions, []);
      assert.deepEqual(wiki.state.alerts, []);
      assert.deepEqual(wiki.state.errors, []);
    } finally { await wiki.context.close(); }
  });
}

test('a nested draft homepage GET link preserves the trailing slash and opens its editor', async () => {
  const target = 'Knowledge.test.WebHome';
  const wiki = await wikiFixture({ reference: target, status: 'published', linkedTarget: target,
    existingDraftState: 'draft', draftReference: 'Drafts.test.WebHome', draftPath: '/bin/view/Drafts/test/' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/test/');
    await wiki.page.locator('#luxpedia-edit-article').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/test\//);
    assert.equal(wiki.state.target, target);
    assert.deepEqual(wiki.state.actions, []);
    assert.deepEqual(wiki.state.alerts, []);
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

test('a stale link to a deleted draft recreates through the native authenticated workflow', async () => {
  const target = 'Knowledge.test.WebHome';
  const wiki = await wikiFixture({ reference: target, status: 'published', linkedTarget: target,
    existingDraftState: 'draft', draftLookupStatus: 404 });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/test/');
    await wiki.page.locator('#luxpedia-edit-article').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/Linked\/Article/);
    assert.equal(wiki.state.target, target);
    assert.deepEqual(wiki.state.actions.map(action => action.action), ['getEditableDraft']);
    assert.equal(wiki.state.actions[0].workflowdoc, target);
    assert.equal(wiki.state.actions[0].form_token, 'test-token');
    assert.deepEqual(wiki.state.alerts, []);
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

test('a published target without a rendered native form still requests its draft through POST', async () => {
  const target = 'Knowledge.test.WebHome';
  const wiki = await wikiFixture({ reference: target, status: 'published', linkedTarget: target, noDraftForm: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/test/');
    await wiki.page.locator('#luxpedia-edit-article').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/Linked\/Article/);
    assert.equal(wiki.state.actions[0].workflowdoc, target);
    assert.equal(wiki.state.actions[0].form_token, 'test-token');
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

for (const status of [401, 403, 500]) {
  test('a draft lookup failure ' + status + ' never recreates or overwrites a draft', async () => {
    const target = 'Knowledge.Technical.Article';
    const wiki = await wikiFixture({ reference: target, status: 'published', linkedTarget: target,
      existingDraftState: 'draft', draftLookupStatus: status });
    try {
      await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/Technical/Article');
      await wiki.page.locator('#luxpedia-edit-article').click();
      await wiki.page.waitForFunction(() => !document.querySelector('#luxpedia-edit-article').disabled);
      assert.match(wiki.state.alerts[0], status === 401 || status === 403 ? /do not have permission/i : /draft could not be opened/i);
      assert.match(wiki.page.url(), /Knowledge\/Technical\/Article/);
      assert.deepEqual(wiki.state.actions, []);
      assert.deepEqual(wiki.state.errors, []);
    } finally { await wiki.context.close(); }
  });
}

test('owner can abandon and restore a saved draft without publishing or deleting it', async () => {
  const reference = 'Drafts.NewArticle.WebHome';
  const wiki = await wikiFixture({ reference, status: 'draft', linkedTarget: 'Knowledge.Technical.NewArticle', nativeEdit: true });
  try {
    const url = wiki.origin + '/bin/view/Drafts/NewArticle/WebHome';
    await wiki.page.goto(url);
    await wiki.page.locator('#luxpedia-abandon-draft').click();
    await wiki.page.waitForURL(wiki.origin + '/bin/view/Drafts/');
    assert.equal(wiki.state.workspaceActions[0].operation, 'abandon');
    assert.equal(wiki.state.workspaceActions[0].form_token, 'test-token');
    assert.equal(wiki.state.status, 'draft');
    await wiki.page.goto(url);
    await wiki.page.locator('#luxpedia-restore-draft').waitFor();
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Abandoned');
    assert.equal(await wiki.page.locator('#tmEdit').isVisible(), false);
    assert.equal(await wiki.page.locator('#luxpedia-submit-review').count(), 0);
    assert.equal(await wiki.page.locator('#luxpedia-continue-editing').count(), 0);
    await wiki.page.locator('#luxpedia-restore-draft').click();
    await wiki.page.locator('#luxpedia-submit-review').waitFor();
    assert.deepEqual(wiki.state.workspaceActions.map(action => action.operation), ['abandon', 'restore']);
    assert.deepEqual(wiki.state.actions, []);
    assert.equal(await wiki.page.locator('#user-content').textContent(), '用户撰写的正文 stays unchanged.');
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

for (const options of [{ sharedWorkspace: true }, { ownWorkspace: false }, { status: 'validating' }]) {
  test('shared, other-owned or submitted drafts do not expose abandonment ' + JSON.stringify(options), async () => {
    const wiki = await wikiFixture({ status: 'draft', ...options });
    try {
      await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
      await wiki.page.waitForFunction(() => document.querySelector('#luxpedia-draft-actions'));
      assert.equal(await wiki.page.locator('#luxpedia-abandon-draft').count(), 0);
      assert.deepEqual(wiki.state.workspaceActions, []);
      assert.deepEqual(wiki.state.errors, []);
    } finally { await wiki.context.close(); }
  });
}

test('stale abandonment is reported without hiding or submitting the draft', async () => {
  const wiki = await wikiFixture({ status: 'draft', workspaceFail: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/NewArticle/WebHome');
    await wiki.page.locator('#luxpedia-abandon-draft').click();
    await wiki.page.waitForFunction(() => !document.querySelector('#luxpedia-abandon-draft').disabled);
    assert.equal(wiki.state.status, 'draft');
    assert.match(wiki.state.alerts[0], /could not be completed/);
    assert.deepEqual(wiki.state.actions, []);
  } finally { await wiki.context.close(); }
});

test('a bulk abandonment reports partial failures and keeps independent versioned requests', async () => {
  const wiki = await wikiFixture({ reference: 'Drafts.WebHome', status: 'draft',
    list: ['Drafts.First', 'Drafts.Second'], workspaceReject: 'Drafts.Second' });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/');
    await wiki.page.locator('#luxpedia-workspace-list[data-bound="1"]').waitFor();
    for (const row of await wiki.page.locator('.luxpedia-draft-select').all()) { await row.check(); }
    await wiki.page.locator('#luxpedia-workspace-list button').click();
    await wiki.page.waitForFunction(() => document.querySelectorAll('.luxpedia-draft-select:checked').length === 0);
    assert.deepEqual(wiki.state.workspaceActions.map(action => action.reference), ['Drafts.First', 'Drafts.Second']);
    assert.ok(wiki.state.workspaceActions.every(action => action.version === '4.2' && action.form_token === 'test-token'));
    assert.match(wiki.state.alerts[0], /1 drafts could not be abandoned/);
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

test('archived articles link to the Archive category and admin restoration keeps the original target', async () => {
  const target = 'Knowledge.Technical.ArchivedArticle';
  const wiki = await wikiFixture({ reference: target, status: 'archived', linkedTarget: target, admin: true });
  try {
    const original = wiki.origin + '/bin/view/Knowledge/Technical/ArchivedArticle';
    await wiki.page.goto(original);
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Archived');
    assert.equal(await wiki.page.locator('.luxpedia-archive-link').getAttribute('href'), '/bin/view/Knowledge/Archive/');
    const restore = wiki.page.locator('form').filter({ has: wiki.page.locator('input[name="action"][value="publishfromarchive"]') });
    await restore.locator('input[type="submit"]').waitFor();
    assert.equal(await restore.locator('input[type="submit"]').inputValue(), 'Restore visibility');
    await restore.locator('input[type="submit"]').click();
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Published');
    assert.equal(new URL(wiki.page.url()).pathname, new URL(original).pathname);
    assert.equal(wiki.state.target, target);
    assert.deepEqual(wiki.state.actions.map(action => action.action), ['publishfromarchive']);
    assert.equal(await wiki.page.locator('#user-content').textContent(), '用户撰写的正文 stays unchanged.');
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

test('an editor can read an archived article without edit or restore actions', async () => {
  const target = 'Knowledge.Technical.ArchivedArticle';
  const wiki = await wikiFixture({ reference: target, status: 'archived', linkedTarget: target, nativeEdit: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/Technical/ArchivedArticle');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Archived');
    assert.equal(await wiki.page.locator('#luxpedia-edit-article').count(), 0);
    assert.equal(await wiki.page.locator('#tmEdit').isVisible(), false);
    assert.equal(await wiki.page.locator('input[name="action"][value="publishfromarchive"]').locator('..').isVisible(), false);
    assert.equal(await wiki.page.locator('.luxpedia-archive-link').textContent(), 'Archive');
    assert.deepEqual(wiki.state.actions, []);
  } finally { await wiki.context.close(); }
});

test('native Edit on Knowledge/test opens its linked draft instead of editing the published copy', async () => {
  const wiki = await wikiFixture({ reference: 'Knowledge.test.WebHome', status: 'published',
    linkedTarget: 'Knowledge.test.WebHome', nativeEdit: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/test/');
    await wiki.page.locator('#tmEdit[data-luxpedia-draft-edit="1"]').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/Linked\/Article/);
    assert.equal(wiki.state.target, 'Knowledge.test.WebHome');
    assert.deepEqual(wiki.state.actions.map(action => action.action), ['getEditableDraft']);
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.context.close(); }
});

test('an unregistered Knowledge page does not expose a native direct-edit link', async () => {
  const wiki = await wikiFixture({ reference: 'Knowledge.test.WebHome', nativeEdit: true });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Knowledge/test/');
    await wiki.page.waitForFunction(() => document.querySelector('.luxpedia-state')?.textContent === 'Not submitted');
    assert.equal(await wiki.page.locator('#tmEdit').isVisible(), false);
    assert.equal(wiki.state.actions.length, 0);
  } finally { await wiki.context.close(); }
});

for (const reference of ['Knowledge.WebHome', 'Knowledge.Catalog.WebHome']) {
  test('editor can propose content changes to ' + reference, async () => {
    const wiki = await wikiFixture({ reference, status: 'published', linkedTarget: reference, nativeEdit: true });
    try {
      await wiki.page.goto(wiki.origin + '/bin/view/' + reference.split('.').join('/'));
      await wiki.page.locator('#luxpedia-edit-article').click();
      await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/Linked\/Article/);
      assert.equal(wiki.state.target, reference);
      assert.deepEqual(wiki.state.actions.map(action => action.action), ['getEditableDraft']);
    } finally { await wiki.context.close(); }
  });
}

test('writing-area home is editable without being treated as a publication draft', async () => {
  const wiki = await wikiFixture({ reference: 'Drafts.WebHome', nativeEdit: true, hasArticle: false });
  try {
    await wiki.page.goto(wiki.origin + '/bin/view/Drafts/WebHome');
    await wiki.page.waitForFunction(() => document.querySelector('.panel')?.hidden);
    assert.equal(await wiki.page.locator('#tmEdit').isVisible(), true);
    await wiki.page.locator('#tmEdit').click();
    await wiki.page.waitForURL(/\/bin\/edit\/Drafts\/WebHome/);
    assert.equal(await wiki.page.locator('#luxpedia-publish-category').count(), 0);
    assert.equal(await wiki.page.locator('.realtime-action-done').getAttribute('data-luxpedia-preview-bound'), null);
    assert.equal(wiki.state.actions.length, 0);
  } finally { await wiki.context.close(); }
});
