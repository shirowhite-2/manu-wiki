// Local HTTP fixture; Java tests exercise the actual authorization and native storage helper.
const assert = require('node:assert/strict');
const { test, before, after } = require('node:test');
const { spawnSync } = require('node:child_process');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');
const result = spawnSync('python3', ['-c',
  'import sys; sys.path.insert(0,"scripts"); from apply_favorites import FAVORITES_JS; print(FAVORITES_JS)'],
{ cwd: path.join(__dirname, '..'), encoding: 'utf8' });
assert.equal(result.status, 0, result.stderr);
const script = result.stdout;
let browser;
before(async () => { browser = await chromium.launch({ headless: true,
  executablePath: process.env.CHROMIUM_EXECUTABLE_PATH || undefined }); });
after(async () => { await browser?.close(); });
const item = (index, title) => ({ reference: 'Knowledge.Article' + index,
  title: title || 'Article ' + index, category: 'Knowledge.Technical', url: '/bin/view/Knowledge/Article' + index });

async function fixture(options = {}) {
  const state = { users: {alice: [...(options.items || [])], bob: []}, versions: {alice: 1, bob: 1},
    writes: [], reads: [], errors: [], fail: false, conflict: false, legacyCalls: 0 };
  const server = http.createServer(async (request, response) => {
    const url = new URL(request.url, 'http://fixture.test');
    const actor = /actor=bob/.test(request.headers.cookie || '') ? 'bob' : 'alice';
    function json(data, status = 200) { response.writeHead(status, {
      'Content-Type': 'application/json', 'Cache-Control': 'private, no-store' }); response.end(JSON.stringify(data)); }
    if (url.pathname === '/bin/get/LuxPedia/Workflow/Favorites') {
      if (request.method === 'POST') {
        let body = ''; for await (const chunk of request) { body += chunk; }
        const fields = Object.fromEntries(new URLSearchParams(body)); state.writes.push({actor, fields});
        if (state.fail) { return json({status: 'unavailable'}, 500); }
        if (fields.form_token !== 'test-token') { return json({status: 'bad_token'}); }
        if (state.conflict) {
          state.conflict = false; state.users[actor].push(item(2)); state.versions[actor]++;
          return json({status: 'conflict'});
        }
        if (fields.version !== state.versions[actor] + '.1') { return json({status: 'conflict'}); }
        if (fields.operation === 'remove') { state.users[actor] = state.users[actor].filter(i => i.reference !== fields.reference); }
        else if (!state.users[actor].some(i => i.reference === fields.reference)) { state.users[actor].push(item(1)); }
        state.versions[actor]++;
        return json({status: 'ok'});
      }
      state.reads.push({actor, url: request.url});
      if (options.guest) { return json({status: 'forbidden', authenticated: false}); }
      if (options.readFail) { return json({status: 'unavailable'}); }
      const all = [...state.users[actor]].reverse(), q = url.searchParams.get('q') || '';
      const matches = all.filter(i => (i.title + ' ' + i.category).toLowerCase().includes(q.toLowerCase()));
      const pages = Math.max(1, Math.ceil(matches.length / 25));
      const page = Math.min(pages, Math.max(1, Number(url.searchParams.get('page')) || 1));
      return json({status: 'ok', authenticated: true, token: 'test-token', version: state.versions[actor] + '.1',
        eligible: !options.full && options.eligible !== false,
        favorite: all.some(i => i.reference === 'Knowledge.Article1'), recent: all.slice(0, 5),
        items: matches.slice((page - 1) * 25, page * 25), total: matches.length, page, pages});
    }
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    const reference = options.full ? 'LuxPedia.Favorites.WebHome' : 'Knowledge.Article1';
    response.end(`<!doctype html><html lang="${options.language || 'en'}"><meta charset="utf-8">
      <meta name="document" content="${reference}"><title>Local favorites fixture — production theme not loaded</title>
      <style>body{margin:12px}.btn{white-space:nowrap}</style>
      <p>Local HTTP fixture. Production theme is not loaded.</p>
      <div id="document-title"><h1>Article title</h1></div>
      <aside id="luxpedia-favorites-sidebar" hidden><h3 data-luxpedia-i18n="myFavorites"></h3>
        <div class="luxpedia-favorites-recent"></div><a class="luxpedia-favorites-all" data-luxpedia-i18n="favoritesViewAll"></a></aside>
      <ul><li><a id="addFavoriteMenu" href="#">Old menu favorite</a></li></ul>
      ${options.full ? '<div id="luxpedia-favorites-page"><form id="luxpedia-favorites-search"><label for="luxpedia-favorites-query">Search</label><input id="luxpedia-favorites-query" maxlength="100"><button>Search</button></form><div class="luxpedia-favorites-results"></div></div>' : ''}
      <div id="user-body" contenteditable="true">Unsaved article content</div>
      <script>window.legacyCalls=0;document.addEventListener('click',function(e){if(e.target.closest('#addFavoriteMenu'))window.legacyCalls++;});</script>
      <script>${script}</script>`);
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const origin = 'http://127.0.0.1:' + server.address().port;
  const contexts = [];
  async function open(actor = 'alice') {
    const context = await browser.newContext({viewport: options.viewport || {width: 1100, height: 800}});
    contexts.push(context);
    await context.addCookies([{name:'actor',value:actor,url:origin}]);
    const page = await context.newPage(); page.on('pageerror', e => state.errors.push(e.message));
    await page.goto(origin + '/bin/' + (options.edit ? 'edit' : 'view') + '/' +
      (options.full ? 'LuxPedia/Favorites/WebHome' : 'Knowledge/Article1') + '?xwikilanguage=' + (options.language || 'en'));
    return page;
  }
  const page = await open();
  return {page, state, open, async close() {
    for (const context of contexts) { await context.close(); }
    await new Promise(resolve => server.close(resolve)); assert.deepEqual(state.errors, []);
  }};
}

test('title star toggles with feedback, updates the sidebar and persists after reload', async () => {
  const f = await fixture();
  try {
    const toggle = f.page.locator('#luxpedia-favorite-toggle');
    await toggle.getByText('☆ Favorite', {exact: true}).waitFor();
    await toggle.click();
    await f.page.getByText('Added to your favorites.', {exact:true}).waitFor();
    assert.equal(await toggle.getAttribute('aria-pressed'), 'true');
    assert.equal(await f.page.locator('#luxpedia-favorites-sidebar li a').innerText(), 'Article 1');
    assert.equal(f.state.writes[0].fields.operation, 'add');
    assert.equal(f.state.writes[0].fields.form_token, 'test-token');
    assert.equal(f.state.writes[0].fields.version, '1.1');
    assert.equal(f.state.writes[0].fields.user, undefined);
    await f.page.reload();
    await f.page.getByRole('button', {name:'★ Favorited', exact:true}).waitFor();
    await toggle.click();
    await f.page.getByText('Removed from your favorites.', {exact:true}).waitFor();
    assert.equal(await toggle.getAttribute('aria-pressed'), 'false');
    assert.equal(await f.page.locator('#luxpedia-favorites-sidebar li').count(), 0);
  } finally { await f.close(); }
});

test('the native dropdown uses the same POST state and prevents its old GET handler', async () => {
  const f = await fixture();
  try {
    await f.page.getByRole('button', {name:'☆ Favorite', exact:true}).waitFor();
    await f.page.locator('#addFavoriteMenu').click();
    await f.page.getByRole('button', {name:'★ Favorited', exact:true}).waitFor();
    assert.match(await f.page.locator('#addFavoriteMenu').innerText(), /Remove favorite/);
    assert.equal(await f.page.evaluate(() => window.legacyCalls), 0);
    assert.equal(f.state.writes.length, 1);
  } finally { await f.close(); }
});

test('each account has its own server-backed favorites', async () => {
  const f = await fixture({items:[item(1)]});
  try {
    await f.page.getByRole('button', {name:'★ Favorited', exact:true}).waitFor();
    const bob = await f.open('bob');
    await bob.getByRole('button', {name:'☆ Favorite', exact:true}).waitFor();
    assert.equal(await bob.locator('#luxpedia-favorites-sidebar li').count(), 0);
    await bob.getByRole('button', {name:'☆ Favorite', exact:true}).click();
    await bob.getByRole('button', {name:'★ Favorited', exact:true}).waitFor();
    assert.equal(f.state.writes[0].actor, 'bob');
    assert.equal(f.state.users.alice.length, 1); assert.equal(f.state.users.bob.length, 1);
  } finally { await f.close(); }
});

test('sidebar shows five recent favorites and links to the complete list', async () => {
  const f = await fixture({items:Array.from({length:12},(_,i)=>item(i+1))});
  try {
    await f.page.locator('#luxpedia-favorites-sidebar li').first().waitFor();
    assert.equal(await f.page.locator('#luxpedia-favorites-sidebar li').count(), 5);
    assert.equal(await f.page.locator('#luxpedia-favorites-sidebar li a').first().innerText(), 'Article 12');
    assert.match(await f.page.getByRole('link', {name:'View all favorites'}).getAttribute('href'), /LuxPedia\/Favorites\/WebHome/);
  } finally { await f.close(); }
});

test('complete list supports title/category search, pagination and removal', async () => {
  const f = await fixture({full:true,items:Array.from({length:60},(_,i)=>item(i+1))});
  try {
    await f.page.locator('tbody tr').first().waitFor();
    assert.equal(await f.page.locator('tbody tr').count(), 25);
    await f.page.getByRole('button', {name:'Next',exact:true}).click();
    await f.page.waitForFunction(() => document.querySelector('tbody a')?.textContent === 'Article 35');
    await f.page.locator('#luxpedia-favorites-query').fill('Article 60');
    await f.page.locator('#luxpedia-favorites-search').getByRole('button').click();
    await f.page.waitForFunction(() => document.querySelectorAll('tbody tr').length === 1);
    assert.equal(await f.page.locator('tbody a').innerText(), 'Article 60');
    await f.page.getByRole('button', {name:'Remove favorite',exact:true}).click();
    await f.page.getByText('No matching favorites.',{exact:true}).waitFor();
    assert.equal(f.state.writes[0].fields.reference, 'Knowledge.Article60');
    assert.ok(!f.state.users.alice.some(i=>i.reference==='Knowledge.Article60'));
  } finally { await f.close(); }
});

test('a failed write does not show a false saved state and allows retry', async () => {
  const f = await fixture();
  try {
    const toggle = f.page.getByRole('button', {name:'☆ Favorite',exact:true}); await toggle.waitFor();
    f.state.fail = true; await toggle.click();
    await f.page.locator('#luxpedia-favorites-notice').getByText(/Could not update favorites/).waitFor();
    assert.equal(await toggle.getAttribute('aria-pressed'), 'false');
    assert.equal(await toggle.isDisabled(), false);
    f.state.fail = false; await toggle.click();
    await f.page.getByRole('button',{name:'★ Favorited',exact:true}).waitFor();
  } finally { await f.close(); }
});

test('conflicting versions refresh instead of overwriting changes made elsewhere', async () => {
  const f = await fixture();
  try {
    const toggle=f.page.getByRole('button',{name:'☆ Favorite',exact:true}); await toggle.waitFor();
    f.state.conflict=true; await toggle.click();
    await f.page.getByText('Your favorites changed elsewhere. The list has been refreshed; try again.',{exact:true}).waitFor();
    assert.equal(f.state.users.alice.length,1); assert.equal(f.state.users.alice[0].reference,'Knowledge.Article2');
    assert.equal(await toggle.getAttribute('aria-pressed'),'false');
    await toggle.click(); await f.page.getByRole('button',{name:'★ Favorited',exact:true}).waitFor();
    assert.equal(f.state.users.alice.length,2);
  } finally { await f.close(); }
});

test('Chinese controls work while editing without losing unsaved article content', async () => {
  const f=await fixture({language:'zh',edit:true});
  try {
    await f.page.getByRole('button',{name:'☆ 收藏',exact:true}).waitFor();
    await f.page.locator('#user-body').fill('未保存的编辑内容'); const before=f.page.url();
    await f.page.getByRole('button',{name:'☆ 收藏',exact:true}).click();
    await f.page.getByText('已加入我的收藏。',{exact:true}).waitFor();
    assert.equal(await f.page.getByRole('button',{name:'★ 已收藏',exact:true}).getAttribute('aria-pressed'),'true');
    assert.equal(await f.page.locator('#user-body').innerText(),'未保存的编辑内容'); assert.equal(f.page.url(),before);
    assert.equal(await f.page.getByRole('link',{name:'查看全部收藏'}).count(),1);
  } finally { await f.close(); }
});

test('user titles render as text and untrusted navigation targets are not executed', async () => {
  const evil={...item(2,'<img src=x onerror=alert(1)>'),url:'javascript:alert(1)'};
  const f=await fixture({full:true,items:[evil]});
  try {
    await f.page.locator('tbody tr').waitFor();
    assert.equal(await f.page.locator('tbody img, aside img').count(),0);
    assert.equal(await f.page.locator('tbody a').innerText(),evil.title);
    assert.ok(!(await f.page.locator('tbody a').getAttribute('href')).startsWith('javascript:'));
  } finally { await f.close(); }
});

test('the complete list wraps long titles and actions on a narrow screen', async()=>{
  const f=await fixture({full:true,items:[item(1,'LongTitle'.repeat(80))],viewport:{width:375,height:800}});
  try{
    await f.page.locator('tbody tr').waitFor();
    assert.ok((await f.page.locator('table').boundingBox()).width<=351);
    assert.ok(await f.page.locator('tbody button').evaluate(button=>button.getBoundingClientRect().width<=button.parentNode.getBoundingClientRect().width));
  }finally{await f.close();}
});

for(const option of [{guest:true},{eligible:false}]) {
  test('does not expose a favorite toggle for an unauthorized or technical page '+JSON.stringify(option),async()=>{
    const f=await fixture(option);
    try {
      await f.page.waitForTimeout(200);
      assert.equal(await f.page.locator('#luxpedia-favorite-toggle').isVisible(),false);
      assert.equal(f.state.writes.length,0);
      if(option.guest){assert.equal(await f.page.locator('#luxpedia-favorites-sidebar').isVisible(),false);}
    } finally {await f.close();}
  });
}
