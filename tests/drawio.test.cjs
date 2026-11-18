// Local browser fixture only; draw.io requests are intercepted and no live Wiki is changed.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const extracted = spawnSync('python3', ['-c',
  'import sys; sys.path.insert(0, "scripts"); from apply_drawio import DRAWIO_JS; print(DRAWIO_JS)'],
{ cwd: path.join(__dirname, '..'), encoding: 'utf8' });
assert.equal(extracted.status, 0, extracted.stderr);
const script = extracted.stdout;
const diagram = 'https://app.diagrams.net/?lightbox=1&edit=_blank#RtestDiagram';
const escape = text => text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
let browser;
before(async () => { browser = await chromium.launch({ headless: true,
  executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined }); });
after(async () => { await browser?.close(); });

async function fixture(options = {}) {
  const state = { links: options.links || [diagram], requests: [], errors: [] };
  const context = await browser.newContext({ viewport: options.viewport || { width: 1100, height: 800 } });
  await context.route('https://viewer.diagrams.net/**', route => {
    state.requests.push(route.request().url());
    return route.fulfill({ contentType: 'text/html', body: '<p>Local viewer stub</p>' });
  });
  const server = http.createServer((request, response) => {
    const editing = request.url.includes('/edit/');
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end(`<!doctype html><html lang="${options.language || 'en'}"><meta charset="utf-8">
      <title>Local draw.io fixture — production theme not loaded</title>
      <style>body{margin:12px}#xwikicontent{max-width:900px}a{overflow-wrap:anywhere}</style>
      <p>Local browser fixture; external viewer is stubbed.</p>
      ${editing ? '<div id="editor-container"><div id="cke"><div id="editor-content" contenteditable="true"></div></div></div>' : ''}
      <div id="xwikicontent">${editing ? '' : state.links.map((url, i) =>
        `<p>Before <a href="${escape(url)}">Diagram ${i}</a> after</p>`).join('')}
        <pre><a href="${escape(diagram)}">Example code link</a></pre></div>
      ${editing ? `<script>window.CKEDITOR = { instances: { content: {
        status: ${JSON.stringify(options.delayedEditor ? 'loading' : 'ready')}, readOnly: ${!!options.readOnly},
        container: { $: document.getElementById('cke') }, focus: function () {},
        insertHtml: function (html) { document.getElementById('editor-content').insertAdjacentHTML('beforeend', html); }
      } } };</script>` : ''}
      <script>${script}</script>`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const page = await context.newPage();
  page.on('pageerror', error => state.errors.push(error.message));
  const location = mode => origin + '/bin/' + mode + '/Drafts/Test/WebHome?xwikilanguage=' + (options.language || 'en');
  await page.goto(location(options.edit ? 'edit' : 'view'));
  return { page, context, state, location,
    async close() {
      await context.close();
      await new Promise(resolve => server.close(resolve));
      assert.deepEqual(state.errors, []);
    } };
}

test('published links retain navigation and load an isolated preview only on request', async () => {
  const f = await fixture();
  try {
    const link = f.page.locator('#xwikicontent p a');
    assert.equal(await link.getAttribute('href'), diagram);
    assert.equal(await link.getAttribute('target'), '_blank');
    assert.equal(await link.getAttribute('rel'), 'noopener noreferrer');
    assert.equal(await f.page.locator('iframe').count(), 0);
    assert.equal(f.state.requests.length, 0);
    const button = f.page.getByRole('button', { name: 'Preview diagram' });
    await button.click();
    const frame = f.page.locator('iframe');
    await frame.waitFor();
    const url = new URL(await frame.getAttribute('src'));
    assert.equal(url.origin, 'https://viewer.diagrams.net');
    assert.equal(url.hash, '#RtestDiagram');
    assert.equal(url.searchParams.get('edit'), null);
    assert.equal(await frame.getAttribute('referrerpolicy'), 'no-referrer');
    assert.ok(!(await frame.getAttribute('sandbox')).includes('allow-top-navigation'));
    await f.page.getByRole('button', { name: 'Hide diagram' }).click();
    assert.equal(await frame.isVisible(), false);
    await f.page.getByRole('button', { name: 'Preview diagram' }).click();
    assert.equal(await f.page.locator('iframe').count(), 1);
    assert.match(await f.page.locator('#xwikicontent p').first().innerText(), /Before.*Diagram 0.*after/s);
  } finally { await f.close(); }
});

test('public file URLs preserve the source and page while removing editor configuration', async () => {
  const source = 'https://example.com/public/diagram.drawio?rev=2&download=1';
  const link = 'https://app.diagrams.net/?plugins=evil&configure=1&page=2#U' + encodeURIComponent(source);
  const f = await fixture({ links: [link, 'https://viewer.diagrams.net/?url=' + encodeURIComponent(source)] });
  try {
    await f.page.getByRole('button', { name: 'Preview diagram', exact: true }).first().click();
    const url = new URL(await f.page.locator('iframe').getAttribute('src'));
    assert.equal(decodeURIComponent(url.hash.slice(2)), source);
    assert.equal(url.searchParams.get('page'), '2');
    assert.equal(url.searchParams.get('plugins'), null);
    assert.equal(url.searchParams.get('configure'), null);
    await f.page.getByRole('button', { name: 'Preview diagram', exact: true }).click();
    assert.equal(await f.page.locator('iframe').count(), 2);
  } finally { await f.close(); }
});

test('private storage links and bare editor URLs keep the original link with a clear fallback', async () => {
  const links = ['https://app.diagrams.net/#Gprivate-file', 'https://app.diagrams.net/',
    'https://app.diagrams.net/#U' + encodeURIComponent('https://name:secret@example.com/file.drawio')];
  const f = await fixture({ links });
  try {
    assert.equal(await f.page.locator('.luxpedia-drawio-preview').count(), 3);
    assert.equal(await f.page.locator('.luxpedia-drawio-preview button').count(), 0);
    assert.equal(await f.page.locator('iframe').count(), 0);
    assert.equal(f.state.requests.length, 0);
    assert.equal(await f.page.locator('#xwikicontent p a').count(), 3);
    assert.match(await f.page.locator('.luxpedia-drawio-preview').first().innerText(), /original link/);
  } finally { await f.close(); }
});

test('lookalike hosts, credentials, alternate ports, unsafe schemes and code examples are not embedded', async () => {
  const links = ['https://app.diagrams.net.evil.test/#Rpayload', 'http://app.diagrams.net/#Rpayload',
    'https://user:secret@app.diagrams.net/#Rpayload', 'https://app.diagrams.net:444/#Rpayload',
    'javascript:alert(1)', 'data:text/html,evil', 'https://viewer.diagrams.net/other#Rpayload'];
  const f = await fixture({ links });
  try {
    assert.equal(await f.page.locator('.luxpedia-drawio-preview').count(), 0);
    assert.equal(await f.page.locator('iframe').count(), 0);
    assert.equal(await f.page.locator('#xwikicontent pre a').getAttribute('target'), null);
    assert.equal(f.state.requests.length, 0);
  } finally { await f.close(); }
});

test('dynamic content refresh is idempotent and supports newly rendered links', async () => {
  const f = await fixture();
  try {
    await f.page.evaluate(url => {
      document.dispatchEvent(new CustomEvent('xwiki:dom:updated'));
      const p = document.createElement('p'); const a = document.createElement('a');
      a.href = url; a.textContent = 'New diagram'; p.appendChild(a);
      document.getElementById('xwikicontent').appendChild(p);
      document.dispatchEvent(new CustomEvent('xwiki:dom:updated'));
      document.dispatchEvent(new CustomEvent('xwiki:dom:updated'));
    }, diagram);
    assert.equal(await f.page.locator('.luxpedia-drawio-preview').count(), 2);
    const ids = await f.page.locator('[aria-controls]').evaluateAll(nodes => nodes.map(node => node.getAttribute('aria-controls')));
    assert.equal(new Set(ids).size, 2);
    assert.equal(f.state.requests.length, 0);
  } finally { await f.close(); }
});

test('Chinese controls and responsive preview work on narrow screens', async () => {
  const f = await fixture({ language: 'zh', viewport: { width: 375, height: 800 } });
  try {
    await f.page.getByRole('button', { name: '预览图表' }).click();
    const frame = f.page.getByTitle('draw.io 图表预览');
    assert.ok((await frame.boundingBox()).width <= 351);
    assert.equal(new URL(await frame.getAttribute('src')).searchParams.get('lang'), 'zh');
    assert.match(await f.page.locator('.luxpedia-drawio-preview').innerText(), /若加载受限或需要登录/);
  } finally { await f.close(); }
});

test('editor inserts a plain link that gains preview controls after saving, without altering typed content', async () => {
  const f = await fixture({ edit: true, delayedEditor: true });
  try {
    await f.page.evaluate(() => { window.CKEDITOR.instances.content.status = 'ready'; });
    await f.page.locator('#luxpedia-drawio-url').waitFor();
    await f.page.locator('#editor-content').fill('Existing article content');
    await f.page.locator('#luxpedia-drawio-url').fill(diagram);
    await f.page.getByRole('button', { name: 'Insert draw.io', exact: true }).click();
    assert.match(await f.page.locator('#editor-content').innerText(), /Existing article content/);
    assert.equal(await f.page.locator('#editor-content a').getAttribute('href'), diagram);
    assert.equal(await f.page.locator('iframe').count(), 0);
    assert.equal(await f.page.locator('#luxpedia-drawio-url').inputValue(), '');
    f.state.links = [await f.page.locator('#editor-content a').getAttribute('href')];
    await f.page.goto(f.location('view'));
    assert.equal(await f.page.getByRole('button', { name: 'Preview diagram' }).count(), 1);
    assert.equal(await f.page.locator('#luxpedia-drawio-bar').count(), 0);
  } finally { await f.close(); }
});

test('editor rejects HTML and non-draw.io destinations without inserting markup', async () => {
  const f = await fixture({ edit: true });
  try {
    for (const value of ['<iframe src="https://evil.test/"></iframe>', 'javascript:alert(1)',
      'https://evil.test/#Rdata', 'https://app.diagrams.net.evil.test/#Rdata']) {
      await f.page.locator('#luxpedia-drawio-url').fill(value);
      await f.page.getByRole('button', { name: 'Insert draw.io', exact: true }).click();
      assert.equal(await f.page.locator('#editor-content a').count(), 0);
      assert.match(await f.page.locator('#luxpedia-drawio-bar [role="status"]').innerText(), /HTTPS share link/);
    }
    assert.equal(await f.page.locator('iframe').count(), 0);
    assert.equal(f.state.requests.length, 0);
  } finally { await f.close(); }
});

test('read-only editors do not get insertion controls', async () => {
  const f = await fixture({ edit: true, readOnly: true });
  try {
    await f.page.waitForTimeout(550);
    assert.equal(await f.page.locator('#luxpedia-drawio-bar').count(), 0);
  } finally { await f.close(); }
});

test('changing editor language preserves the article and a partly entered diagram link', async () => {
  const f = await fixture({ edit: true });
  try {
    await f.page.locator('#luxpedia-drawio-url').fill('not yet a valid link');
    await f.page.locator('#editor-content').fill('Unsaved article text');
    const before = f.page.url();
    await f.page.locator('#luxpedia-language-select').selectOption('zh');
    assert.equal(f.page.url(), before);
    assert.equal(await f.page.locator('#editor-content').innerText(), 'Unsaved article text');
    assert.equal(await f.page.locator('#luxpedia-drawio-url').inputValue(), 'not yet a valid link');
    assert.equal(await f.page.getByRole('button', { name: '插入 draw.io', exact: true }).count(), 1);
    assert.equal(await f.page.locator('#luxpedia-drawio-url').evaluate(input => input.checkValidity()), true);
    await f.page.locator('#luxpedia-drawio-url').fill(diagram);
    await f.page.locator('#luxpedia-drawio-url').press('Enter');
    assert.equal(await f.page.locator('#editor-content a').getAttribute('href'), diagram);
    assert.equal(f.page.url(), before);
  } finally { await f.close(); }
});
