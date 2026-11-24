// LightRAG sync administration section: settings, explicit preview/confirm and batched progress.
const assert=require('node:assert/strict');
const {test,before,after}=require('node:test');
const {spawnSync}=require('node:child_process');
const fs=require('node:fs');
const {chromium}=require('playwright');
const extracted=spawnSync('python3',['-c','import json,sys;sys.path.insert(0,"scripts");from apply_admin_tools import ADMIN_JS;print(json.dumps(ADMIN_JS))'],{encoding:'utf8',maxBuffer:3e6});
assert.equal(extracted.status,0,extracted.stderr);
const adminJS=JSON.parse(extracted.stdout);
const bootstrap=fs.readFileSync(require.resolve('bootstrap/dist/css/bootstrap.css'),'utf8');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined});});
after(async()=>{await browser?.close();});
const counts=(values={})=>({insert:0,update:0,move:0,delete:0,refresh:0,...values});
async function fixture(options={}) {
 const context=await browser.newContext({viewport:{width:1280,height:900}});
 const page=await context.newPage();
 const state={writes:[],errors:[],version:'1.1',continues:0,settings:{baseUrl:'http://10.41.28.59:19621',prefix:'wiki',maxChars:200000,timeoutSeconds:30,categories:{},apiKeySet:true},
  plan:{counts:counts({insert:2,delete:1}),total:3,items:[
   {type:'insert',reference:'Knowledge.Technical.A',title:'Article A',workspace:'wiki_Technical',fileSource:'Technical__A-1a2b3c4d5e.md',oldWorkspace:''},
   {type:'insert',reference:'Knowledge.Technical.Hydraulics.B',title:'Article B',workspace:'wiki_Technical',fileSource:'Technical__Hydraulics__B-0f0f0f0f0f.md',oldWorkspace:''},
   {type:'delete',reference:'Knowledge.Technical.Gone',title:'Gone',workspace:'',fileSource:'',oldWorkspace:'wiki_Technical'}]}};
 page.on('pageerror',e=>state.errors.push(e.message));
 await page.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url());
  if(url.pathname.endsWith('/AdminTools')) {
   const fields=Object.fromEntries(req.method()==='POST'?new URLSearchParams(req.postData()):url.searchParams),op=fields.operation;
   if(req.method()==='POST'){
    state.writes.push(fields);assert.equal(fields.form_token,'test-token');
    if(options.refuse===op){return route.fulfill({json:{status:'refused',reason:'Configure the LightRAG address first.'}});}
    if(op==='rag-save-settings'){const data=JSON.parse(fields.payload);state.saved=data.settings;state.version='2.1';return route.fulfill({json:{status:'ok',saved:{}}});}
    if(op==='rag-test'){const c=JSON.parse(fields.payload);state.tested=c;
     if(c.baseUrl.includes('10.0.0.9')){return route.fulfill({json:{status:'ok',baseUrl:c.baseUrl,reachable:false,error:'HttpConnectTimeoutException'}});}
     return route.fulfill({json:{status:'ok',baseUrl:c.baseUrl,reachable:true,core_version:'1.5.0',conflicts:['wiki_Faults'],missing:[]}});}
    if(op==='rag-preview'){
     if(options.lost){state.lost='2026-11-23T08:00:00Z';state.plan={counts:counts({insert:5}),total:5,items:[]};}
     return route.fulfill({json:{status:'ok',ticket:'t1',counts:state.plan.counts,total:state.plan.total,items:state.plan.items,lost:options.lost?['wiki_Technical']:[],recreated:[]}});}
    const progress=(processed,done)=>({id:'run1',processed,total:3,done,aborted:'',counts:{inserted:done?2:1,updated:0,moved:0,deleted:1,refreshed:0,skipped:0,failed:0},failures:[]});
    if(op==='rag-run'){assert.equal(fields.payload,'t1');return route.fulfill({json:{status:'ok',run:progress(1,false)}});}
    if(op==='rag-continue'){assert.equal(fields.payload,'run1');state.continues++;state.plan={counts:counts(),total:0,items:[]};return route.fulfill({json:{status:'ok',run:progress(3,true)}});}
    return route.fulfill({json:{status:'invalid_operation'}});
   }
   assert.equal(op,'rag-overview');
   return route.fulfill({json:{status:'ok',token:'test-token',version:state.version,settings:state.settings,
    categories:[{reference:'Knowledge.Technical.WebHome',title:'Technical',depth:1,enabled:true,explicit:false,workspace:'wiki_Technical',articles:2},
     {reference:'Knowledge.Technical.Hydraulics.WebHome',title:'Hydraulics',depth:2,enabled:false,explicit:false,workspace:'wiki_Technical',articles:0},
     {reference:'Knowledge.Archive.WebHome',title:'Archive',depth:1,enabled:false,explicit:false,workspace:'',articles:0}],
    plan:state.plan,skipped:[],excluded:{uncategorized:1,disabled:4,too_large:0},
    workspaces:[{name:'wiki_Technical',category:'Knowledge.Technical.WebHome',documents:state.lost?0:5,createdAt:'2026-11-20T01:00:00Z',lost:state.lost||''}],
    runs:[],failures:[],run:{}}});
  }
  return route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="en" data-xwiki-reference="xwiki:XWiki.XWikiPreferences"><head><meta charset="utf-8"><style>${bootstrap}</style></head><body><section id="luxpedia-rag-sync"><h2 data-luxpedia-i18n="ragSync"></h2><p role="status" id="luxpedia-rag-notice"></p><div id="luxpedia-rag-content"></div></section><script>${adminJS}</script></body></html>`});
 });
 await page.goto('http://wiki.test/bin/admin/XWiki/XWikiPreferences?section=LuxPediaRag');
 await page.waitForFunction(()=>document.querySelector('#luxpedia-rag-content table'));
 return {page,state,close:async()=>{await context.close();assert.deepEqual(state.errors,[]);}};
}

test('overview shows category workspaces and pending changes without writing or revealing the API key',async()=>{
 const f=await fixture();
 try{
  const text=await f.page.locator('#luxpedia-rag-content').innerText();
  assert.match(text,/New: 2 · Changed: 0 · Moved: 0 · Removed: 1/);
  assert.match(text,/1 articles outside any category, 4 in categories that are not enabled/);
  assert.match(text,/Technical__Hydraulics__B-0f0f0f0f0f\.md/);
  assert.match(text,/An API key is stored/);
  assert.equal(await f.page.locator('[data-rag-field="ragApiKey"]').inputValue(),'');
  assert.equal(await f.page.getByRole('checkbox',{name:'Archive',exact:true}).isChecked(),false);
  assert.equal(f.state.writes.length,0);
 }finally{await f.close();}
});

test('saving settings sends the category selection and keeps a blank API key blank',async()=>{
 const f=await fixture();
 try{
  await f.page.getByRole('checkbox',{name:'Hydraulics',exact:true}).check();
  await f.page.locator('[data-rag-field="ragPrefix"]').fill('lux');
  assert.equal(await f.page.getByRole('button',{name:'Preview sync',exact:true}).isDisabled(),true);
  await f.page.getByText('You have unsaved changes.').waitFor();
  await f.page.getByRole('button',{name:'Save settings',exact:true}).click();
  await f.page.getByText('Settings saved.').waitFor();
  assert.deepEqual(f.state.saved.categories,{'Knowledge.Technical.Hydraulics.WebHome':true});
  assert.equal(f.state.saved.prefix,'lux');assert.equal(f.state.saved.apiKey,'');assert.equal(f.state.saved.clearApiKey,false);
  assert.equal(JSON.parse(f.state.writes[0].payload).version,'1.1');
 }finally{await f.close();}
});

test('test connection uses the typed address, explains failures and reports foreign workspace name conflicts',async()=>{
 const f=await fixture();
 try{
  const address=f.page.locator('[data-rag-field="ragBaseUrl"]'),content=f.page.locator('#luxpedia-rag-content');
  await address.fill('http://10.0.0.9:19621');
  await f.page.getByRole('button',{name:'Test connection',exact:true}).click();
  await f.page.getByText(/cannot connect to http:\/\/10\.0\.0\.9:19621 \(HttpConnectTimeoutException\)/).waitFor();
  assert.equal(f.state.tested.baseUrl,'http://10.0.0.9:19621');
  await address.fill('http://lightrag.test:19621');
  await f.page.getByRole('button',{name:'Test connection',exact:true}).click();
  await f.page.getByText(/Connected to http:\/\/lightrag\.test:19621 \(LightRAG 1\.5\.0\)/).waitFor();
  assert.match(await content.innerText(),/will not write to them: wiki_Faults.*not saved yet/s);
  assert.equal(f.state.writes.filter(x=>x.operation==='rag-save-settings').length,0);
  await address.fill('');
  await f.page.getByRole('button',{name:'Test connection',exact:true}).click();
  await f.page.getByText('Enter the LightRAG address first.').waitFor();
  assert.equal(f.state.writes.length,2);
 }finally{await f.close();}
});

test('reload asks before discarding unsaved edits',async()=>{
 const f=await fixture();
 try{
  const address=f.page.locator('[data-rag-field="ragBaseUrl"]');
  await address.fill('http://typed.test:19621');
  f.page.once('dialog',d=>d.dismiss());
  await f.page.getByRole('button',{name:'Reload',exact:true}).click();
  assert.equal(await address.inputValue(),'http://typed.test:19621');
  f.page.once('dialog',d=>d.accept());
  await f.page.getByRole('button',{name:'Reload',exact:true}).click();
  await f.page.waitForFunction(()=>document.querySelector('[data-rag-field="ragBaseUrl"]').value==='http://10.41.28.59:19621');
 }finally{await f.close();}
});

test('sync requires preview and confirmation, then continues batches until done',async()=>{
 const f=await fixture();
 try{
  await f.page.getByRole('button',{name:'Preview sync',exact:true}).click();
  await f.page.locator('#luxpedia-rag-preview').waitFor();
  assert.deepEqual(f.state.writes.map(x=>x.operation),['rag-preview']);
  await f.page.getByRole('button',{name:'Start sync',exact:true}).click();
  await f.page.waitForFunction(()=>document.getElementById('luxpedia-rag-notice').textContent.includes('Sync finished'));
  assert.match(await f.page.locator('#luxpedia-rag-notice').innerText(),/2 new, 0 changed, 0 moved, 1 removed/);
  assert.deepEqual(f.state.writes.map(x=>x.operation),['rag-preview','rag-run','rag-continue']);
  assert.match(await f.page.locator('#luxpedia-rag-content').innerText(),/Nothing to sync/);
 }finally{await f.close();}
});

test('preview stays available with nothing pending and reports workspaces deleted in LightRAG',async()=>{
 const f=await fixture({lost:true});
 try{
  f.state.plan={counts:counts(),total:0,items:[]};
  await f.page.getByRole('button',{name:'Reload',exact:true}).click();
  await f.page.getByText('Nothing to sync.').waitFor();
  const content=f.page.locator('#luxpedia-rag-content');
  assert.match(await content.innerText(),/wiki_Technical[^\n]*Active/);
  await f.page.getByRole('button',{name:'Preview sync',exact:true}).click();
  const dialog=f.page.locator('#luxpedia-rag-preview');
  await dialog.getByText(/deleted in LightRAG: wiki_Technical\. Starting the sync recreates them/).waitFor();
  assert.match(await dialog.innerText(),/New: 5/);
  assert.match(await content.innerText(),/wiki_Technical[^\n]*Deleted in LightRAG/);
  assert.equal(await dialog.getByRole('button',{name:'Start sync',exact:true}).isDisabled(),false);
 }finally{await f.close();}
});

test('canceling the preview sends nothing and refusals show the server reason',async()=>{
 const f=await fixture({refuse:'rag-test'});
 try{
  await f.page.getByRole('button',{name:'Preview sync',exact:true}).click();
  await f.page.locator('#luxpedia-rag-preview').getByRole('button',{name:'Cancel',exact:true}).click();
  assert.deepEqual(f.state.writes.map(x=>x.operation),['rag-preview']);
  await f.page.getByRole('button',{name:'Test connection',exact:true}).click();
  await f.page.getByText(/refused the request: Configure the LightRAG address first/).waitFor();
 }finally{await f.close();}
});
