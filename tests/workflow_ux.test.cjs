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
    actions: [], alerts: [], errors: [], withdrawals: [], confirmations: 0,
  };
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
      state.status === 'published' ? form('getEditableDraft') + form('archive') + form('unpublish') : '';
    return `<!doctype html><html lang="${nativeLanguage}"><title>Local workflow test fixture — production theme not loaded</title><meta name="document" content="${reference}">
      ${options.navbar ? '<header id="headerglobal"><nav id="menuview"><ul class="navbar-right"><li id="original-menu">Original menu</li></ul></nav></header>' : ''}
      <p role="note">Local test fixture. The production LuxPedia theme is not loaded.</p>
      <div data-xwiki-form-token="test-token"></div>
      <a id="tmUser" href="/bin/view/XWiki/${options.admin ? 'Admin' : 'editor'}">Profile</a>
      ${options.nativeEdit ? '<a id="tmEdit" href="' + editPath + '">Edit</a>' : ''}
      <div id="document-title"><h1>New article</h1></div>
      ${edit ? `<div><textarea id="xwikieditor"></textarea></div>
        <button class="realtime-action-done" onclick="location.href='${viewPath}'">Done</button>` : '<div class="panel"><h2 class="panel-title">Workflow</h2><div class="publicationworkflow-info">' + workflow + '</div></div>'}
      <div id="xwikicontent"><article id="user-content">用户撰写的正文 stays unchanged.</article></div>
      <script>${ux}</script>`;
  }
  async function handle(route) {
    const request = route.request();
    const url = new URL(request.url());
    const pathname = decodeURIComponent(url.pathname);
    const json = body => route.fulfill({ json: body });
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
    if (pathname.startsWith('/rest/')) {
      if (pathname.includes('/objects/XWiki.XWikiGroups')) {
        const editorGroup = pathname.includes('/pages/LuxEditors/') && !['reviewer', 'reader'].includes(options.role) ||
          pathname.includes('/pages/LuxReviewers/') && options.role === 'reviewer';
        return json(pathname.endsWith('/properties/member') ? { value: 'XWiki.editor' } :
          { objectSummaries: editorGroup ? [{ number: 0 }] : [] });
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
      await page.locator('#luxpedia-publish-category').waitFor();
      if (category !== undefined) { await page.selectOption('#luxpedia-publish-category', category); }
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
  return { context, page, state, preview, submit, expectPending, pendingKey, origin };
}

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
    const rolesChecked = wiki.page.waitForResponse(response => response.url().includes('/pages/XWikiAdminGroup/objects/'));
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
