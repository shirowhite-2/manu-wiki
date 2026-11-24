// Native-editor contract and real browser downloads. Actual XWiki conversion is a deployment acceptance step.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { chromium } = require('playwright');
const extracted = spawnSync('python3', ['-c', 'import sys;sys.path.insert(0,"scripts");from apply_content_tools import CONTENT_TOOLS_JS;print(CONTENT_TOOLS_JS)'],
  { cwd: path.join(__dirname, '..'), encoding: 'utf8', maxBuffer: 2e6 });
assert.equal(extracted.status, 0, extracted.stderr);
const script = extracted.stdout, bootstrap = fs.readFileSync(require.resolve('bootstrap/dist/css/bootstrap.css'), 'utf8');
const escape = value => String(value).replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;').replaceAll('"', '&quot;');
let browser;
before(async () => { browser = await chromium.launch({ headless: true, executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined }); });
after(async () => { await browser?.close(); });
function table(metadata) {
  const headings = metadata ? ['Title', 'Category', 'Owner', 'Status', 'Tags', 'Updated'] : ['Device', 'Condition', 'Notes'];
  const keys = ['title', 'category', 'owner', 'status', 'tags', 'browserUpdated'];
  const rows = Array.from({ length: metadata ? 60 : 40 }, (_, i) => {
    const state = i < 40 ? 'published' : i < 45 ? 'archived' : 'draft', area = i < 45 ? 'Knowledge' : 'Drafts';
    const updated = Date.UTC(2026, 10, 1 + i, 12);
    const cells = metadata ? ['Article ' + String(i).padStart(2, '0'), i % 2 ? 'Operations' : 'Technical', i % 2 ? 'Bob' : 'Alice', state, i % 3 ? 'safety' : 'release', 'Date'] :
      ['Device ' + i, i % 2 ? 'Stopped' : 'Ready', i === 0 ? '=1+1' : 'Note ' + i];
    return `<tr ${metadata ? `data-area="${area}" data-state="${state}" data-updated="${updated}"` : ''}>` +
      cells.map((value, c) => `<td ${metadata && c === 3 ? `data-luxpedia-state="${state}"` : metadata && c === 5 ? `data-luxpedia-updated="${updated}"` : ''}>${escape(value)}</td>`).join('') + '</tr>';
  });
  return `<table id="data-table" ${metadata ? 'class="table luxpedia-system-list" data-luxpedia-browser="1"' : 'class="table"'}><thead><tr>` +
    headings.map((value, i) => `<th${metadata ? ` data-luxpedia-i18n="${keys[i]}"` : ''}>${value}</th>`).join('') + '</tr></thead><tbody>' + rows.join('') + '</tbody></table>';
}
async function fixture(options = {}) {
  const context = await browser.newContext({ acceptDownloads: true, viewport: options.mobile ? { width: 390, height: 844 } : { width: 1280, height: 900 } });
  const page = await context.newPage(), state = { errors: [], requests: [] };
  page.on('pageerror', error => state.errors.push(error.message));
  await page.route('**/*', route => {
    const request = route.request(), url = new URL(request.url()); state.requests.push(request.method());
    const language = url.searchParams.get('xwikilanguage') || options.language || 'en';
    const html = `<!doctype html><html lang="${language}"><head><meta charset="utf-8"><title>Local content tools fixture</title><style>${bootstrap}</style></head><body>
      <p>Local fixture; native XWiki conversion is not loaded.</p>
      ${options.editor ? `<div><div id="cke"><div id="editor-content" contenteditable="true"><p>Original article text.</p></div></div></div>` :
        `<table id="outside-table"><tr><td>Navigation</td></tr></table><div id="xwikicontent">${options.html || table(options.metadata)}</div>`}
      <script>
        var content=document.getElementById('editor-content');
        window.editor={status:'ready',readOnly:${!!options.readOnly},container:{$:document.getElementById('cke')},config:{sourceDocument:{}},
          events:{},changes:[],focus:function(){},on:function(name,fn){this.events[name]=fn},fire:function(name){this.changes.push(name)},
          getSelection:function(){return {createBookmarks2:function(){return []},selectBookmarks:function(){},getStartElement:function(){return {$:window.cursorNode||content}}}},
          editable:function(){return {$:content}},insertHtml:function(html){content.insertAdjacentHTML('beforeend',html)}};
        window.CKEDITOR={instances:{content:window.editor}};
      </script><script>${script}</script></body></html>`;
    return route.fulfill({ contentType: 'text/html', body: html });
  });
  const route = options.editor ? 'edit/Drafts/Test/WebHome' : options.profile ? 'view/XWiki/Admin' :
    options.metadata ? 'view/LuxPedia/Help/PageBrowser' : 'view/Knowledge/Test/WebHome';
  const url = 'http://wiki.test/bin/' + route;
  await page.goto(url);
  if (!options.editor && !options.profile) { await page.locator('.luxpedia-table-tools').first().waitFor(); }
  else if (options.editor && !options.readOnly) { await page.locator('#luxpedia-components-bar').waitFor(); }
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'wiki-content-export-'));
  async function download(kind, scope = '.luxpedia-table-tools') {
    const waiting = page.waitForEvent('download'); await page.locator(scope + ' button[id$="-' + kind + '"]').click();
    const file = await waiting, target = path.join(directory, kind + (kind === 'csv' ? '.csv' : '.xlsx'));
    await file.saveAs(target); return { target, name: file.suggestedFilename(), bytes: fs.readFileSync(target) };
  }
  async function close() { await context.close(); fs.rmSync(directory, { recursive: true, force: true }); }
  return { context, page, state, download, close };
}
function inspectXlsx(target) {
  const result = spawnSync('python3', ['-c', `import json,sys,zipfile,xml.etree.ElementTree as ET
with zipfile.ZipFile(sys.argv[1]) as z:
 assert z.testzip() is None
 n={'s':'http://schemas.openxmlformats.org/spreadsheetml/2006/main'}
 root=ET.fromstring(z.read('xl/worksheets/sheet1.xml'))
 print(json.dumps({'rows':[[c.find('s:is/s:t',n).text or '' for c in r.findall('s:c',n)] for r in root.findall('s:sheetData/s:row',n)],'merges':[m.get('ref') for m in root.findall('s:mergeCells/s:mergeCell',n)],'formulas':len(root.findall('.//s:f',n))}))`, target], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr); return JSON.parse(result.stdout);
}
const visibleRows = page => page.locator('#data-table tbody tr:visible').filter({ hasNot: page.locator('.luxpedia-table-empty') });

test('page browser combines metadata filters, resets, switches areas and keeps default archives hidden', async () => {
  const wiki = await fixture({ metadata: true });
  try {
    const p = wiki.page;
    assert.match(await p.locator('.luxpedia-table-tools [role="status"]').textContent(), /40 matching rows/);
    await p.locator('select[id$="-browserCategory"]').selectOption('Technical');
    await p.locator('select[id$="-browserOwner"]').selectOption('Alice');
    await p.locator('input[id$="-browserTags"]').fill('release');
    await p.locator('input[id$="-browserFrom"]').fill('2026-11-03');
    await p.locator('input[id$="-browserTo"]').fill('2026-11-07');
    assert.match(await p.locator('.luxpedia-table-tools [role="status"]').textContent(), /^1 matching rows/);
    assert.equal(await p.locator('#data-table tbody tr:visible td').first().textContent(), 'Article 06');
    await p.locator('button[id$="-reset"]').click();
    await p.locator('select[id$="-browserState"]').selectOption('archived');
    assert.match(await p.locator('.luxpedia-table-tools [role="status"]').textContent(), /^5 matching rows/);
    await p.locator('select[id$="-browserArea"]').selectOption('Drafts');
    assert.match(await p.locator('.luxpedia-table-tools [role="status"]').textContent(), /^15 matching rows/);
    assert.deepEqual(wiki.state.errors, []);
    assert.equal(wiki.state.requests.some(method => method !== 'GET'), false);
  } finally { await wiki.close(); }
});

test('body table searches, sorts and paginates; Excel and CSV export all matching pages', async () => {
  const wiki = await fixture();
  try {
    const p = wiki.page;
    await p.locator('input[id$="-tableSearch"]').fill('Ready');
    await p.locator('select[id$="-size"]').selectOption('10');
    await p.locator('select[id$="-tableSort"]').selectOption('0'); await p.locator('button[id$="-direction"]').click();
    assert.equal(await p.locator('#data-table tbody tr:visible').count(), 10);
    assert.equal(await p.locator('#data-table tbody tr:visible td').first().textContent(), 'Device 38');
    await p.locator('button[id$="-next"]').click(); assert.equal(await p.locator('input[id$="-page"]').inputValue(), '2');
    const book = inspectXlsx((await wiki.download('xlsx')).target);
    assert.equal(book.rows.length, 21); assert.equal(book.rows[1][0], 'Device 38'); assert.equal(book.rows.at(-1)[0], 'Device 0');
    assert.equal(book.rows.at(-1)[2], '=1+1'); assert.equal(book.formulas, 0);
    const csv = (await wiki.download('csv')).bytes.toString('utf8');
    assert.ok(csv.startsWith('\ufeff')); assert.equal(csv.match(/"Device /g).length, 20); assert.ok(csv.includes("'="));
    assert.equal(await p.locator('#outside-table').getAttribute('data-luxpedia-table-bound'), null);
    assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.close(); }
});

test('metadata export preserves filters across pages with English headers', async () => {
  const wiki = await fixture({ metadata: true });
  try {
    const p = wiki.page;
    await p.locator('select[id$="-browserOwner"]').selectOption('Alice');
    await p.locator('select[id$="-size"]').selectOption('10');
    assert.equal(inspectXlsx((await wiki.download('xlsx')).target).rows.length, 21);
    assert.equal(await p.locator('#luxpedia-language-select').count(), 0);
    assert.equal(await p.evaluate(() => window.LuxPediaI18n.language()), 'en');
    await p.locator('select[id$="-browserOwner"]').selectOption('Alice');
    const book = inspectXlsx((await wiki.download('xlsx')).target);
    assert.equal(book.rows[0][0], 'Title'); assert.equal(book.rows[0][3], 'Status');
    assert.ok(book.rows.slice(1).every(row => row[2] === 'Alice'));
  } finally { await wiki.close(); }
});

test('XWiki profile tables and activity stream are not wrapped as article tables', async () => {
  const html = '<section class="profile-details">' + table(false).replaceAll('data-table', 'profile-table-one') +
    '</section><section class="activity-stream">' + table(false).replaceAll('data-table', 'profile-table-two') + '</section>';
  const wiki = await fixture({ profile: true, html });
  try {
    assert.equal(await wiki.page.locator('#xwikicontent table').count(), 2);
    assert.equal(await wiki.page.locator('#xwikicontent .luxpedia-table-tools').count(), 0);
    assert.equal(await wiki.page.locator('#profile-table-one').getAttribute('data-luxpedia-table-bound'), null);
    assert.equal(await wiki.page.locator('#profile-table-two').getAttribute('data-luxpedia-table-bound'), null);
  } finally { await wiki.close(); }
});

test('merged tables retain their layout and export real Excel merge ranges', async () => {
  const wiki = await fixture({ html: '<table class="table"><thead><tr><th colspan="2">Merged title</th><th>Notes</th></tr></thead><tbody><tr><td>A</td><td>B</td><td>中文</td></tr></tbody></table>' });
  try {
    assert.equal(await wiki.page.locator('input[id$="-tableSearch"]').isDisabled(), true);
    const book = inspectXlsx((await wiki.download('xlsx')).target); assert.deepEqual(book.merges, ['A1:B1']);
    assert.deepEqual(book.rows[1], ['A', 'B', '中文']);
  } finally { await wiki.close(); }
});

test('an empty permission-filtered browser stays usable without a false merged-table warning', async () => {
  const wiki = await fixture({ html: '<table data-luxpedia-browser="1"><thead><tr><th>Title</th><th>Owner</th></tr></thead><tbody><tr data-luxpedia-empty="1"><td colspan="2">No pages</td></tr></tbody></table>' });
  try {
    assert.equal(await wiki.page.locator('input[id$="-tableSearch"]').isDisabled(), false);
    assert.match(await wiki.page.locator('.luxpedia-table-tools [role="status"]').textContent(), /^0 matching rows/);
  } finally { await wiki.close(); }
});

async function openComponent(page, kind) { await page.locator('#luxpedia-component-type').selectOption(kind); await page.locator('#luxpedia-component-insert').click(); }

test('date and decision components use native editor content and preserve user text safely', async () => {
  const wiki = await fixture({ editor: true });
  try {
    const p = wiki.page;
    await openComponent(p, 'date'); await p.locator('#luxpedia-component-label').fill('Go live'); await p.locator('#luxpedia-component-date').fill('2026-12-15');
    await p.locator('#luxpedia-component-confirm').click(); assert.match(await p.locator('#editor-content').textContent(), /Go live: 2026-12-15/);
    await openComponent(p, 'decision'); await p.locator('#luxpedia-component-decision').fill('<script>literal decision</script>');
    await p.locator('#luxpedia-component-owner').fill('Alice'); await p.locator('#luxpedia-component-state').selectOption('accepted');
    await p.locator('#luxpedia-component-confirm').click();
    assert.equal(await p.locator('#editor-content script').count(), 0);
    assert.match(await p.locator('#editor-content table').textContent(), /literal decision/);
    assert.match(await p.locator('#editor-content table').textContent(), /Accepted/);
    assert.match(await p.locator('#editor-content').textContent(), /Original article text/);
    assert.equal(wiki.state.requests.some(method => method !== 'GET'), false); assert.deepEqual(wiki.state.errors, []);
  } finally { await wiki.close(); }
});

test('task components can toggle only the selected task and preserve editor change tracking', async () => {
  const wiki = await fixture({ editor: true });
  try {
    const p = wiki.page;
    await openComponent(p, 'checklist'); await p.locator('#luxpedia-component-tasks').fill('Inspect device\nRecord result');
    await p.locator('#luxpedia-component-owner').fill('Bob'); await p.locator('#luxpedia-component-confirm').click();
    assert.match(await p.locator('#editor-content li').first().textContent(), /^☐ Inspect device — Bob/);
    await p.evaluate(() => { window.cursorNode = document.querySelector('#editor-content li'); });
    await p.locator('#luxpedia-task-toggle').click();
    assert.match(await p.locator('#editor-content li').first().textContent(), /^☑ Inspect/);
    assert.match(await p.locator('#editor-content li').nth(1).textContent(), /^☐ Record/);
    assert.deepEqual(await p.evaluate(() => editor.changes), ['saveSnapshot', 'saveSnapshot', 'change']);
    await p.locator('#luxpedia-task-toggle').click(); assert.match(await p.locator('#editor-content li').first().textContent(), /^☐ Inspect/);
  } finally { await wiki.close(); }
});

test('English-only editor preserves unfinished component fields and article content', async () => {
  const wiki = await fixture({ editor: true });
  try {
    const p = wiki.page; await openComponent(p, 'decision'); await p.locator('#luxpedia-component-decision').fill('Unfinished decision');
    assert.equal(await p.locator('#luxpedia-language-select').count(), 0);
    assert.equal(await p.locator('#luxpedia-component-decision').inputValue(), 'Unfinished decision');
    assert.equal(await p.locator('#luxpedia-component-heading').textContent(), 'Decision overview');
    assert.equal(await p.locator('#editor-content').textContent(), 'Original article text.');
    await p.locator('#luxpedia-component-cancel').click(); assert.equal(await p.locator('#editor-content table').count(), 0);
  } finally { await wiki.close(); }
});

test('read-only editors expose no component insertion and role changes disable pending inserts', async () => {
  const readOnly = await fixture({ editor: true, readOnly: true });
  try { assert.equal(await readOnly.page.locator('#luxpedia-components-bar').count(), 0); } finally { await readOnly.close(); }
  const wiki = await fixture({ editor: true });
  try {
    await openComponent(wiki.page, 'date'); await wiki.page.evaluate(() => { editor.readOnly=true;editor.events.readOnly(); });
    assert.equal(await wiki.page.locator('#luxpedia-component-insert').isDisabled(), true);
    assert.equal(await wiki.page.locator('#luxpedia-component-dialog').evaluate(node => node.open), false);
    assert.equal(await wiki.page.locator('#editor-content').textContent(), 'Original article text.');
  } finally { await wiki.close(); }
});

test('table controls and component dialogs fit a narrow screen', async () => {
  const wiki = await fixture({ metadata: true, mobile: true });
  try { assert.ok(await wiki.page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)); } finally { await wiki.close(); }
  const editor = await fixture({ editor: true, mobile: true });
  try {
    await openComponent(editor.page, 'checklist');
    const bounds = await editor.page.locator('#luxpedia-component-dialog').boundingBox(); assert.ok(bounds.width <= 390 && bounds.x >= 0);
  } finally { await editor.close(); }
});

test('component validation preserves the article when too many tasks are supplied', async () => {
  const wiki = await fixture({ editor: true });
  try {
    await openComponent(wiki.page, 'checklist');
    await wiki.page.locator('#luxpedia-component-tasks').fill(Array.from({length:101},(_,i)=>'Task '+i).join('\n'));
    await wiki.page.locator('#luxpedia-component-confirm').click();
    assert.equal(await wiki.page.locator('#editor-content li').count(), 0);
    assert.equal(await wiki.page.locator('#luxpedia-component-dialog').evaluate(node=>node.open), true);
    assert.match(await wiki.page.locator('#luxpedia-component-dialog [role="alert"]').textContent(), /1 and 100/);
  } finally { await wiki.close(); }
});

test('original footer rows export with the full table and are omitted after reducing the rows', async () => {
  const wiki = await fixture({ html: '<table><thead><tr><th>Item</th><th>Value</th></tr></thead><tbody><tr><td>A</td><td>1</td></tr><tr><td>B</td><td>2</td></tr></tbody><tfoot><tr><td>Total</td><td>3</td></tr></tfoot></table>' });
  try {
    const original = inspectXlsx((await wiki.download('xlsx')).target); assert.equal(original.rows.at(-1)[0], 'Total');
    await wiki.page.locator('input[id$="-tableSearch"]').fill('A');
    assert.equal(await wiki.page.locator('tfoot').isVisible(), false);
    assert.deepEqual(inspectXlsx((await wiki.download('xlsx')).target).rows, [['Item', 'Value'], ['A', '1']]);
  } finally { await wiki.close(); }
});
