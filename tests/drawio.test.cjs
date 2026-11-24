// Local browser fixture only; draw.io requests are intercepted and no live Wiki is changed.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');
const fs = require('node:fs');
const os = require('node:os');

const extracted = spawnSync('python3', ['-c',
  'import sys; sys.path.insert(0, "scripts"); from apply_drawio import DRAWIO_JS; print(DRAWIO_JS)'],
{ cwd: path.join(__dirname, '..'), encoding: 'utf8' });
assert.equal(extracted.status, 0, extracted.stderr);
const script = extracted.stdout;
const tables = spawnSync('python3', ['-c', 'import sys;sys.path.insert(0,"scripts");from apply_content_tools import CONTENT_TOOLS_JS;print(CONTENT_TOOLS_JS)'],
  {cwd:path.join(__dirname,'..'),encoding:'utf8',maxBuffer:2000000});
assert.equal(tables.status,0,tables.stderr);
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
    const htmlLanguage = new URL(request.url, 'http://wiki.test').searchParams.get('xwikilanguage') || options.language || 'en';
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end(`<!doctype html><html lang="${htmlLanguage}"><meta charset="utf-8">
      <title>Local draw.io fixture — production theme not loaded</title>
      <style>body{margin:12px}#xwikicontent{max-width:900px}a{overflow-wrap:anywhere}</style>
      <p>Local browser fixture; external viewer is stubbed.</p>
      ${editing ? '<div id="editor-container"><div id="cke"><div id="editor-content" contenteditable="true"></div></div></div>' : ''}
      <div id="xwikicontent">${editing ? '' : options.html !== undefined ? options.html : state.links.map((url, i) =>
        `<p>Before <a href="${escape(url)}">Diagram ${i}</a> after</p>`).join('')}
        <pre><a href="${escape(diagram)}">Example code link</a></pre></div>
      ${editing ? `<script>window.CKEDITOR = { instances: { content: {
        status: ${JSON.stringify(options.delayedEditor ? 'loading' : 'ready')}, readOnly: ${!!options.readOnly},
        container: { $: document.getElementById('cke') }, focus: function () {},
        insertHtml: function (html) { document.getElementById('editor-content').insertAdjacentHTML('beforeend', html); }
      } } };</script>` : ''}
      ${options.tablesFirst ? '<script>' + tables.stdout + '</script>' : ''}<script>${script}</script>
      ${options.withTables && !options.tablesFirst ? '<script>' + tables.stdout + '</script>' : ''}`);
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

test('English controls and responsive preview work on narrow screens', async () => {
  const f = await fixture({ language: 'zh', viewport: { width: 375, height: 800 } });
  try {
    await f.page.getByRole('button', { name: 'Preview diagram' }).click();
    const frame = f.page.getByTitle('draw.io diagram preview');
    assert.ok((await frame.boundingBox()).width <= 351);
    assert.equal(new URL(await frame.getAttribute('src')).searchParams.get('lang'), 'en');
    assert.match(await f.page.locator('.luxpedia-drawio-preview').innerText(), /If it is blocked or requires sign-in/);
    assert.equal(await f.page.locator('#luxpedia-language-select').count(), 0);
  } finally { await f.close(); }
});

test('editor inserts a plain link that gains preview controls after saving, without altering typed content', async () => {
  const f = await fixture({ edit: true, delayedEditor: true });
  try {
    await f.page.evaluate(() => { window.CKEDITOR.instances.content.status = 'ready'; });
    await f.page.locator('#luxpedia-drawio-url').waitFor();
    await f.page.locator('#editor-content').fill('Existing article content');
    await f.page.locator('#luxpedia-drawio-url').fill(diagram);
    await f.page.getByRole('button', { name: 'Insert link', exact: true }).click();
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

test('editor rejects HTML, credentials and unsafe protocols without inserting markup', async () => {
  const f = await fixture({ edit: true });
  try {
    for (const value of ['<iframe src="https://evil.test/"></iframe>', 'javascript:alert(1)',
      'data:text/html,evil', 'https://name:password@github.com/org/repo']) {
      await f.page.locator('#luxpedia-drawio-url').fill(value);
      await f.page.getByRole('button', { name: 'Insert link', exact: true }).click();
      assert.equal(await f.page.locator('#editor-content a').count(), 0);
      assert.match(await f.page.locator('#luxpedia-drawio-bar [role="status"]').innerText(), /HTTP or HTTPS link/);
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

test('English-only editor preserves the article and a partly entered diagram link', async () => {
  const f = await fixture({ edit: true });
  try {
    await f.page.locator('#luxpedia-drawio-url').fill('not yet a valid link');
    await f.page.locator('#editor-content').fill('Unsaved article text');
    const before = f.page.url();
    assert.equal(await f.page.locator('#luxpedia-language-select').count(), 0);
    assert.equal(await f.page.evaluate(() => window.LuxPediaI18n.language()), 'en');
    assert.equal(f.page.url(), before);
    assert.equal(await f.page.locator('#editor-content').innerText(), 'Unsaved article text');
    assert.equal(await f.page.locator('#luxpedia-drawio-url').inputValue(), 'not yet a valid link');
    assert.equal(await f.page.getByRole('button', { name: 'Insert link', exact: true }).count(), 1);
    assert.equal(await f.page.locator('#luxpedia-drawio-url').evaluate(input => input.checkValidity()), true);
    await f.page.locator('#luxpedia-drawio-url').fill(diagram);
    await f.page.locator('#luxpedia-drawio-url').press('Enter');
    assert.equal(await f.page.locator('#editor-content a').getAttribute('href'), diagram);
    assert.equal(f.page.url(), before);
  } finally { await f.close(); }
});

test('GitHub, Bitbucket and other web links insert normally and never create an embed', async () => {
  const f = await fixture({ edit: true });
  try {
    const values = [
      ['https://github.com/org/repo/blob/main/example.js#L20', /GitHub link detected/],
      ['https://bitbucket.org/team/repo/src/main/index.py', /Bitbucket link detected/],
      ['https://example.com/reference', /Web link detected/],
      ['https://github.com/org/repo/blob/main/design.drawio', /GitHub link detected/]
    ];
    for (const [url, expected] of values) {
      await f.page.locator('#luxpedia-drawio-url').fill(url);
      assert.match(await f.page.locator('#luxpedia-drawio-bar [role="status"]').innerText(), expected);
      await f.page.getByRole('button', { name: 'Insert link', exact: true }).click();
    }
    const links = await f.page.locator('#editor-content a').evaluateAll(nodes => nodes.map(node => node.getAttribute('href')));
    assert.deepEqual(links, values.map(value => value[0]));
    f.state.links = links; await f.page.goto(f.location('view'));
    assert.equal(await f.page.locator('.luxpedia-drawio-preview, iframe').count(), 0);
    assert.deepEqual(await f.page.locator('#xwikicontent p a').evaluateAll(nodes => nodes.map(node=>node.getAttribute('href'))), links);
    assert.equal(f.state.requests.length, 0);
    await f.context.route('https://github.com/**', route => route.fulfill({ body: 'GitHub target fixture' }));
    await Promise.all([f.page.waitForURL(values[0][0]), f.page.locator('#xwikicontent p a').first().click()]);
    assert.equal(f.page.url(), values[0][0]);
  } finally { await f.close(); }
});

test('imported HTML links preserve original labels, styles, destinations and normal navigation', async () => {
  const f = await fixture({ html: `<p><a id="code-link" href="https://github.com/org/repo/blob/main/file.js#L2" class="imported-link" style="color:rgb(90, 30, 20);font-weight:bold" target="_self" rel="nofollow" title="Original title"><span>代码示例</span></a></p>
    <p><a id="bb-link" href="https://bitbucket.org/org/repo/">Repository</a></p>
    <p><a id="graph-link" href="${escape(diagram)}" class="imported-graph"><em>原始图表名称</em></a></p>` });
  try {
    const link = f.page.locator('#code-link');
    assert.equal(await link.getAttribute('target'), '_self'); assert.equal(await link.getAttribute('rel'), 'nofollow');
    assert.equal(await link.getAttribute('class'), 'imported-link'); assert.equal(await link.getAttribute('title'), 'Original title');
    assert.match(await link.getAttribute('style'), /font-weight:bold/); assert.equal(await link.textContent(), '代码示例');
    assert.equal(await f.page.locator('#graph-link').getAttribute('class'), 'imported-graph');
    assert.equal(await f.page.locator('#graph-link em').textContent(), '原始图表名称');
    assert.equal(await f.page.getByRole('button',{name:'Preview diagram',exact:true}).count(),1);
    assert.equal(await f.page.locator('iframe').count(),0); assert.equal(f.state.requests.length,0);
  } finally { await f.close(); }
});

test('standalone imported URLs become links while code and editable text stay unchanged', async () => {
  const f = await fixture({ html: `<p id="plain-git">https://github.com/org/repo</p><p id="plain-graph">${escape(diagram)}</p>
    <p><code id="code-example">${escape(diagram)}</code></p><div id="editable-example" contenteditable="true">${escape(diagram)}</div>` });
  try {
    assert.equal(await f.page.locator('#plain-git a').getAttribute('href'), 'https://github.com/org/repo');
    assert.equal(await f.page.locator('#plain-graph a').getAttribute('href'), diagram);
    assert.equal(await f.page.locator('#code-example a, #editable-example a').count(),0);
    assert.equal(await f.page.getByRole('button',{name:'Preview diagram',exact:true}).count(),1);
    assert.equal(f.state.requests.length,0);
  } finally { await f.close(); }
});

test('asynchronous imports and changed links update once and remove orphan previews', async () => {
  const f = await fixture({ links: [] });
  try {
    await f.page.evaluate(url => {
      const p=document.createElement('p');p.id='dynamic-import';
      const link=document.createElement('a');link.href=url;link.target='_self';link.rel='nofollow';link.textContent='Imported diagram';p.appendChild(link);
      document.getElementById('xwikicontent').appendChild(p);
    },diagram);
    await f.page.getByRole('button',{name:'Preview diagram',exact:true}).waitFor();
    await f.page.locator('#dynamic-import a').evaluate(node=>{node.href='https://github.com/org/repo'});
    await f.page.waitForFunction(()=>document.querySelectorAll('.luxpedia-drawio-preview').length===0);
    assert.equal(await f.page.locator('#dynamic-import a').getAttribute('target'),'_self');
    assert.equal(await f.page.locator('#dynamic-import a').getAttribute('rel'),'nofollow');
    await f.page.locator('#dynamic-import a').evaluate((node,url)=>{node.href=url},diagram);
    await f.page.getByRole('button',{name:'Preview diagram',exact:true}).waitFor();
    await f.page.locator('#dynamic-import a').evaluate(node=>node.removeAttribute('href'));
    await f.page.waitForFunction(()=>document.querySelectorAll('.luxpedia-drawio-preview').length===0);
    await f.page.locator('#dynamic-import a').evaluate((node,url)=>{node.href=url},diagram);
    await f.page.getByRole('button',{name:'Preview diagram',exact:true}).waitFor();
    await f.page.locator('#dynamic-import a').evaluate(node=>node.remove());
    await f.page.waitForFunction(()=>document.querySelectorAll('.luxpedia-drawio-preview').length===0);
    assert.equal(f.state.requests.length,0);
  } finally { await f.close(); }
});

test('code-host drawio files and unrelated link formats still navigate without a preview', async () => {
  const links = [
    'https://github.com/org/repo/blob/main/design.drawio', 'https://raw.githubusercontent.com/org/repo/main/design.drawio',
    'https://bitbucket.org/team/repo/src/main/design.drawio', 'https://example.com/file.drawio',
    'https://app.diagrams.net.evil.test/#Rpayload'
  ];
  const f = await fixture({ links });
  try {
    assert.equal(await f.page.locator('.luxpedia-drawio-preview, iframe').count(),0);
    assert.deepEqual(await f.page.locator('#xwikicontent p a').evaluateAll(nodes=>nodes.map(node=>node.getAttribute('href'))),links);
    assert.equal(f.state.requests.length,0);
  } finally { await f.close(); }
});

for (const tablesFirst of [true,false]) {
  test('drawio controls coexist with table export without exporting interface text: tablesFirst='+tablesFirst, async()=>{
    const f=await fixture({withTables:true,tablesFirst,html:`<table><thead><tr><th>Reference</th></tr></thead><tbody><tr><td><a href="${escape(diagram)}">Design diagram</a></td></tr></tbody></table>`});
    const directory=fs.mkdtempSync(path.join(os.tmpdir(),'wiki-links-export-'));
    try {
      await f.page.locator('.luxpedia-table-tools').waitFor();
      await f.page.getByRole('button',{name:'Preview diagram',exact:true}).click();
      const waiting=f.page.waitForEvent('download');await f.page.getByRole('button',{name:'Export CSV',exact:true}).click();
      const download=await waiting,target=path.join(directory,'references.csv');await download.saveAs(target);
      assert.equal(fs.readFileSync(target,'utf8'),'\ufeff"Reference"\r\n"Design diagram"\r\n');
      assert.equal(await f.page.locator('.luxpedia-drawio-preview').count(),1);
    } finally {await f.close();fs.rmSync(directory,{recursive:true,force:true});}
  });
}
