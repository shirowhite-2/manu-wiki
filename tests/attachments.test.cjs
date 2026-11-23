// Local HTTP/editor-contract fixture. Native CKEditor conversion and publication need real-instance acceptance.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const http = require('node:http');
const path = require('node:path');
const { chromium } = require('playwright');

const extracted = spawnSync('python3', ['-c',
  'import sys; sys.path.insert(0,"scripts"); from apply_video_insert import VIDEO_INSERT_JS; print(VIDEO_INSERT_JS)'],
{ cwd: path.join(__dirname, '..'), encoding: 'utf8' });
assert.equal(extracted.status, 0, extracted.stderr);
const script = extracted.stdout;
const sourcePath = '/xwiki/bin/edit/Drafts/%E4%B8%AD%E6%96%87%20Space/WebHome';
const restPath = '/xwiki/rest/wikis/xwiki/spaces/Drafts/spaces/%E4%B8%AD%E6%96%87%20Space/pages/WebHome/attachments';
const files = ['流程 & 装配.png', '说明 100%.PDF', 'Procedure.docx', 'Data.xlsx', 'slides.pptx', 'archive.zip', 'demo.mp4'];
let browser;
before(async () => { browser = await chromium.launch({ headless: true,
  executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined }); });
after(async () => { await browser?.close(); });

async function fixture(options = {}) {
  const state = { files: [...(options.files || files)], requests: [], errors: [], status: options.status || 200 };
  const server = http.createServer((request, response) => {
    const url = new URL(request.url, 'http://fixture.test');
    if (url.pathname === restPath) {
      state.requests.push({ path: request.url, method: request.method });
      response.writeHead(state.status, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify(options.invalidResponse ? {} : { attachments: state.files.map(name => ({ name })) }));
      return;
    }
    if (url.pathname.endsWith('/resource')) {
      response.writeHead(200, { 'Content-Type': 'image/png' });
      response.end(Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=', 'base64'));
      return;
    }
    const edit = !options.view;
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end(`<!doctype html><html lang="${options.language || 'en'}"><meta charset="utf-8">
      <title>Local attachment fixture — native editor conversion is not loaded</title>
      <p>Local HTTP fixture; production theme is not loaded.</p>
      <div><div id="cke"><div id="article" contenteditable="true">Saved article text</div></div></div>
      <script>
      var serialized = function(ref) {
        var text = String(!!ref.typed) + '|-|' + ref.type + '|-|' + ref.reference;
        if (ref.parameters) { text += '|-|queryString="' + ref.parameters.queryString + '"'; }
        return text;
      };
      var resourceURL = function(ref) {
        if (ref.type === 'doc') { return '/xwiki/bin/view/Drafts/Test/?' + ref.parameters.queryString; }
        return '/xwiki/resource?' + new URLSearchParams({type: ref.type, reference: ref.reference});
      };
      window.XWiki = { EntityType: {ATTACHMENT: 3}, EntityReference: function(name, type) {this.name=name;this.type=type;},
        Model: { serialize: function(ref) { return ref.name.replace(/[\\\\:@]/g, function(c) {return '\\\\' + c;}); } } };
      var editor = { status: ${JSON.stringify(options.delayed ? 'loading' : 'ready')}, readOnly: ${!!options.readOnly},
        container: {$: document.getElementById('cke')}, focus: function() {}, events: {},
        on: function(name, callback) {this.events[name]=callback;},
        config: {sourceDocument: {getRestURL: function() {return ${JSON.stringify(restPath)};},
          getURL: function() {return '/xwiki/bin/view/Drafts/Test/?viewer=attachments';}}},
        insertHtml: function(html) {document.getElementById('article').insertAdjacentHTML('beforeend',html);},
        getCommand: function(name) {return name === 'xwiki-macro-insert' ? {} : null;},
        execCommand: function(name, data) {
          window.macroInput = {name:name, data:data};
          var block=document.createElement('div');block.textContent=data.parameters.attachment;
          document.getElementById('article').appendChild(block);return true;
        },
        widgets: {registered: {image: {insert: function(input) {
          window.imageInput = input;
          var image=document.createElement('img'); image.alt=input.setImageData.alt;
          image.src=input.setImageData.src; image.dataset.reference=serialized(input.setImageData.resourceReference);
          document.getElementById('article').appendChild(image);
        }}}}
      };
      ${options.missingNative ? 'delete editor.config.sourceDocument;' : ''}
      window.CKEDITOR = {instances: {content: editor}, plugins: {
        xwikiResource: {serializeResourceReference: serialized, getResourceURL: resourceURL},
        xwikiMarker: {serializeParameters: function(p) {
          return 'attachment="' + p.attachment.replace(/[\\\\"]/g, function(c){return '\\\\'+c;}) + '"';
        }}}, tools: {escapeComment: function(value) {return value.replace(/--/g,'-\\\\-');}}};
      </script>
      ${edit ? '<script>' + script + '</script>' : '<script>' + script + '</script>'}`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('pageerror', error => state.errors.push(error.message));
  await page.goto('http://127.0.0.1:' + server.address().port +
    (options.view ? sourcePath.replace('/edit/', '/view/') : sourcePath) + '?xwikilanguage=' + (options.language || 'en'));
  return { page, context, state,
    async ready() { await page.waitForFunction(() => document.querySelector('#luxpedia-attachment-select')?.disabled === false); },
    async close() { await context.close(); await new Promise(resolve => server.close(resolve)); assert.deepEqual(state.errors, []); } };
}

test('lists current-page imported images and files using native encoded REST paths', async () => {
  const f = await fixture();
  try {
    await f.ready();
    assert.equal(await f.page.locator('#luxpedia-attachment-select option').count(), files.length);
    assert.equal(f.state.requests[0].path, restPath);
    assert.equal(f.state.requests[0].method, 'GET');
    await f.page.selectOption('#luxpedia-attachment-filter', 'images');
    assert.deepEqual(await f.page.locator('#luxpedia-attachment-select option').allTextContents(), ['流程 & 装配.png']);
    await f.page.selectOption('#luxpedia-attachment-filter', 'files');
    assert.equal(await f.page.locator('#luxpedia-attachment-select option').count(), 5);
    assert.equal(await f.page.locator('#luxpedia-video-bar').count(), 0);
  } finally { await f.close(); }
});

test('images render in the editor and use attachment-only references for publication', async () => {
  const name = '生产@线:100% & 图.png';
  const f = await fixture({ files: [name] });
  try {
    await f.ready();
    await f.page.getByRole('button', { name: 'Insert into article', exact: true }).click();
    await f.page.waitForFunction(() => document.querySelector('#article img')?.naturalWidth > 0);
    const image = await f.page.evaluate(() => window.imageInput.setImageData);
    assert.equal(image.resourceReference.type, 'attach');
    assert.equal(image.resourceReference.typed, false);
    assert.equal(image.resourceReference.reference, '生产\\@线\\:100% & 图.png');
    assert.equal(image.alt, name);
    assert.ok(!image.resourceReference.reference.includes('Drafts'));
    assert.match(await f.page.locator('#article').innerText(), /Saved article text/);
  } finally { await f.close(); }
});

test('Word, Excel and other file titles open the attachment page with separate relative download links', async () => {
  const f = await fixture();
  try {
    await f.ready();
    for (const name of ['Procedure.docx', 'Data.xlsx', 'slides.pptx', 'archive.zip']) {
      await f.page.selectOption('#luxpedia-attachment-select', name);
      await f.page.getByRole('button', { name: 'Insert into article', exact: true }).click();
      const title = f.page.getByRole('link', { name, exact: true });
      assert.equal(await title.getAttribute('data-reference'), 'true|-|doc|-||-|queryString="viewer=attachments"');
      assert.equal(await title.getAttribute('target'), '_blank');
      const download = title.locator('xpath=..').getByRole('link', { name: 'Download file' });
      assert.match(await download.getAttribute('data-reference'), new RegExp('true\\|\\-\\|attach\\|\\-\\|' + name.replace('.', '\\.')));
      assert.match(await download.getAttribute('data-reference'), /force-download=1/);
      assert.ok(!(await download.getAttribute('data-reference')).includes('Drafts'));
    }
    assert.equal(await f.page.locator('#article iframe, #article object').count(), 0);
  } finally { await f.close(); }
});

test('PDF links target the attachment itself and include download and attachment-page fallbacks', async () => {
  const name = '说明 100%.PDF';
  const f = await fixture({ files: [name] });
  try {
    await f.ready();
    await f.page.getByRole('button', { name: 'Insert into article', exact: true }).click();
    assert.equal(await f.page.getByRole('link', { name, exact: true }).getAttribute('data-reference'), 'true|-|attach|-|' + name);
    assert.equal(await f.page.getByRole('link', { name: 'Download file' }).count(), 1);
    assert.equal(await f.page.getByRole('link', { name: 'Attachments', exact: true }).count(), 1);
  } finally { await f.close(); }
});

test('videos use native standalone macro insertion with unmodified filenames', async () => {
  const name = 'clip "--><img src=x onerror=alert(1)>.mp4';
  const f = await fixture({ files: [name] });
  try {
    await f.ready();
    await f.page.getByRole('button', { name: 'Insert into article', exact: true }).click();
    assert.equal(await f.page.locator('#article img, #article script').count(), 0);
    assert.ok((await f.page.locator('#article').innerText()).includes(name));
    const command = await f.page.evaluate(() => window.macroInput);
    assert.deepEqual(command, {name: 'xwiki-macro-insert', data: {name: 'embed', parameters: {attachment: name}, inline: false}});
  } finally { await f.close(); }
});

test('refresh and native upload completion discover new attachments without losing article edits', async () => {
  const f = await fixture({ files: [] });
  try {
    await f.page.waitForFunction(() => document.getElementById('luxpedia-attachment-refresh')?.disabled === false);
    assert.equal(await f.page.locator('#luxpedia-attachment-insert').isDisabled(), true);
    await f.page.locator('#article').fill('Unsaved content');
    f.state.files.push('new.jpg');
    await f.page.getByRole('button', { name: 'Refresh attachments' }).click();
    await f.ready();
    assert.equal(await f.page.locator('#article').innerText(), 'Unsaved content');
    f.state.files.push('second.docx');
    await f.page.evaluate(() => window.CKEDITOR.instances.content.events.fileUploadResponse());
    await f.page.waitForFunction(() => document.querySelector('#luxpedia-attachment-select')?.options.length === 2);
    assert.equal(await f.page.getByRole('link', { name: 'Upload / manage attachments' }).getAttribute('target'), '_blank');
  } finally { await f.close(); }
});

test('English-only controls preserve filenames, selection and unsaved text', async () => {
  const f = await fixture({ language: 'zh' });
  try {
    await f.ready();
    await f.page.selectOption('#luxpedia-attachment-filter', 'files');
    await f.page.selectOption('#luxpedia-attachment-select', 'Data.xlsx');
    await f.page.locator('#article').fill('未保存正文');
    assert.equal(await f.page.locator('#luxpedia-language-select').count(), 0);
    assert.equal(await f.page.locator('#luxpedia-attachment-select').inputValue(), 'Data.xlsx');
    assert.equal(await f.page.locator('#article').innerText(), '未保存正文');
    assert.equal(await f.page.getByRole('button', { name: 'Refresh attachments' }).count(), 1);
    assert.ok((await f.page.locator('#luxpedia-attachment-select option').allTextContents()).includes('说明 100%.PDF'));
  } finally { await f.close(); }
});

for (const status of [401, 403, 500]) {
  test('attachment HTTP ' + status + ' stays disabled and never retries with another document', async () => {
    const f = await fixture({ status });
    try {
      await f.page.locator('#luxpedia-attachments-bar [role="status"]').getByText('Could not load attachments').waitFor();
      assert.equal(await f.page.locator('#luxpedia-attachment-insert').isDisabled(), true);
      assert.equal(f.state.requests.length, 1);
      assert.equal(f.state.requests[0].method, 'GET');
    } finally { await f.close(); }
  });
}

test('a not-yet-saved page explains how to upload its first attachments', async () => {
  const f = await fixture({ status: 404 });
  try {
    await f.page.getByText('Save this page first, then upload attachments and refresh the list.').waitFor();
    assert.equal(await f.page.locator('#luxpedia-attachment-insert').isDisabled(), true);
  } finally { await f.close(); }
});

test('unsupported marker delimiters fail visibly without damaging filenames or inserting content', async () => {
  const f = await fixture({ files: ['bad|-|file.docx'] });
  try {
    await f.ready();
    await f.page.getByRole('button', { name: 'Insert into article', exact: true }).click();
    assert.equal(await f.page.locator('#article a').count(), 0);
    assert.equal(await f.page.locator('#luxpedia-attachment-select option').innerText(), 'bad|-|file.docx');
    assert.match(await f.page.locator('#luxpedia-attachments-bar [role="status"]').innerText(), /native image \/ attachment picker/);
  } finally { await f.close(); }
});

for (const option of [{ view: true }, { readOnly: true }, { missingNative: true }]) {
  test('does not list attachments outside a supported editable page ' + JSON.stringify(option), async () => {
    const f = await fixture(option);
    try {
      assert.equal(await f.page.locator('#luxpedia-attachments-bar').count(), 0);
      assert.equal(f.state.requests.length, 0);
    } finally { await f.close(); }
  });
}
