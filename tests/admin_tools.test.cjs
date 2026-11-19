// Existing native administration routes, native recycle-bin contract and local QR generation.
const assert=require('node:assert/strict');
const {test,before,after}=require('node:test');
const {spawnSync}=require('node:child_process');
const fs=require('node:fs');
const {chromium}=require('playwright');
const decodeQR=require('jsqr');
const extracted=spawnSync('python3',['-c','import json,sys;sys.path.insert(0,"scripts");from apply_admin_tools import ADMIN_JS,PRESENTATION_JS;print(json.dumps([ADMIN_JS,PRESENTATION_JS]))'],{encoding:'utf8',maxBuffer:3e6});
assert.equal(extracted.status,0,extracted.stderr);
const [adminJS,presentationJS]=JSON.parse(extracted.stdout);
const bootstrap=fs.readFileSync(require.resolve('bootstrap/dist/css/bootstrap.css'),'utf8');
let browser;
before(async()=>{browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined});});
after(async()=>{await browser?.close();});
const clone=value=>JSON.parse(JSON.stringify(value));
async function fixture(options={}) {
 const context=await browser.newContext({acceptDownloads:true,viewport:options.mobile?{width:390,height:844}:{width:1280,height:900}});
 const page=await context.newPage();
 const state={requests:[],writes:[],errors:[],version:'1.1',settings:{menus:[],banners:[],qrEnabled:true},
  pages:[{reference:'Knowledge.A',title:'Article A',version:'1.1',state:'ready',url:'/bin/view/Knowledge/A'},
   {reference:'Knowledge.B',title:'Article B',version:'1.1',state:'busy',url:'/bin/view/Knowledge/B'}],
  trash:[],tickets:new Map(),nextTicket:1,presentation:options.presentation||{status:'ok',menus:[],banner:{},qrURL:'/bin/view/Knowledge/%E4%B8%AD%E6%96%87%20Page/WebHome?form_token=not-for-sharing#section'}};
 page.on('pageerror',e=>state.errors.push(e.message));
 await page.route('**/*',async route=>{
  const req=route.request(),url=new URL(req.url());state.requests.push({url:req.url(),method:req.method()});
  if(url.pathname.endsWith('/Presentation')){return route.fulfill({json:state.presentation});}
  if(url.pathname.endsWith('/AdminTools')) {
   const fields=Object.fromEntries(req.method()==='POST'?new URLSearchParams(req.postData()):url.searchParams);
   if(options.forbidden){return route.fulfill({json:{status:'forbidden'}});}
   const op=fields.operation;
   if(req.method()==='POST') {
    state.writes.push(fields);assert.equal(fields.form_token,'test-token');
    if(op==='save-settings'){
     const data=JSON.parse(fields.payload);if(options.conflict||data.version!==state.version){return route.fulfill({json:{status:'conflict'}});}
     state.settings=data.settings;state.version='2.1';return route.fulfill({json:{status:'ok',settings:clone(state.settings),version:state.version}});
    }
    if(op.startsWith('preview-')){
     const selected=JSON.parse(fields.payload),ticket=String(state.nextTicket++),operation=op.slice('preview-'.length);
     const items=selected.map(value=>operation==='delete'?{...state.pages.find(p=>p.reference===value),attachments:1,translations:1}:state.trash.find(p=>p.id===value));
     state.tickets.set(ticket,{operation,items:clone(items)});return route.fulfill({json:{status:'ok',ticket,operation,items}});
    }
    if(op==='execute') {
     const ticket=state.tickets.get(fields.payload);state.tickets.delete(fields.payload);if(!ticket){return route.fulfill({json:{status:'conflict'}});}
     const items=ticket.items.map(item=>{
      if(ticket.operation==='delete'){
       const current=state.pages.find(p=>p.reference===item.reference);if(!current||current.version!==item.version){return {reference:item.reference,result:'conflict'};}
       state.pages=state.pages.filter(p=>p.reference!==item.reference);state.trash.push({id:'7',reference:item.reference,locale:'',date:'2026-11-19T01:00:00Z',actor:'XWiki.Admin'});
       return {reference:item.reference,result:'deleted'};
      }
      state.trash=state.trash.filter(p=>p.id!==item.id);state.pages.push({reference:item.reference,title:'Restored',version:'2.1',state:'ready',url:'/bin/view/Knowledge/A'});
      return {reference:item.reference,result:'restored'};
     });return route.fulfill({json:{status:'ok',items,operation:ticket.operation}});
    }
    return route.fulfill({json:{status:'invalid_operation'}});
   }
   let result;
   if(op==='settings'){result={settings:clone(state.settings),version:state.version};}
   else if(op==='categories'){result={items:[{reference:'Knowledge.WebHome',title:'Knowledge'},{reference:'Knowledge.Technical.WebHome',title:'Technical'}]};}
   else if(op==='pages'||op==='trash'){const items=op==='pages'?state.pages:state.trash;result={items:clone(items),total:items.length,page:1,pages:1};}
   else {result={roles:{all:12,managers:1,editors:7,reviewers:2,readers:8,unassigned:0},activity:{available:true,days:30,edits:25,users:5,leaders:[{user:'XWiki.editor',count:7}]},
    runtime:{wiki:'17.10.15',policy:'luxpedia-workflow-policy-12',java:'17',uptime:60000,heapUsed:1000000,heapMax:10000000,diskTotal:100000000,diskFree:20000000,checks:[{name:'database',state:'ok'},{name:'containers',state:'unavailable'},{name:'backup',state:'unavailable'}]}};}
   return route.fulfill({json:{status:'ok',token:'test-token',...result}});
  }
  const operations=options.section==='operations',navigation=options.section==='navigation';
  const body=options.forbidden?'<p>Administrators only.</p>':operations?'<section id="luxpedia-admin-tools"><div><button type="button" data-admin-tab="dashboard">Overview</button><button type="button" data-admin-tab="pages">Bulk page removal</button><button type="button" data-admin-tab="trash">Recycle bin / restore</button></div><p id="luxpedia-admin-notice" role="status"></p><div id="luxpedia-admin-content"></div></section>':navigation?'<section id="luxpedia-navigation-settings"><p id="luxpedia-navigation-notice" role="status"></p><div id="luxpedia-navigation-editor"></div></section>':'<div id="document-title"><h1>Original title</h1></div><div id="xwikicontent"><p>Original article text.</p></div>';
  return route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="en" data-xwiki-reference="xwiki:${operations||navigation?'XWiki.XWikiPreferences':'Knowledge.中文 Page.WebHome'}"><head><meta charset="utf-8"><style>${bootstrap}</style></head><body><header id="headerglobal"><nav id="menuview"><ul class="navbar-left"><li>Original menu</li></ul><ul class="navbar-right"></ul></nav></header><aside id="leftPanels"></aside>${body}<script>window.define=function(){throw new Error('Unexpected anonymous AMD module')};window.define.amd={};</script><script>${adminJS}</script><script>${presentationJS}</script></body></html>`});
 });
 const url=options.section?'http://wiki.test/bin/admin/XWiki/XWikiPreferences?section='+ (options.section==='operations'?'LuxPediaOperations':'LuxPediaNavigation'):'http://wiki.test/bin/view/Knowledge/Chinese/WebHome?form_token=secret';
 await page.goto(url);
 if(options.section&&!options.forbidden){await page.waitForFunction(()=>document.querySelector('#luxpedia-admin-content table, #luxpedia-navigation-editor input'));}
 else if(!options.section){await page.locator('#luxpedia-page-qr').waitFor();}
 return {context,page,state,close:async()=>{await context.close();assert.deepEqual(state.errors,[]);}};
}

test('existing native admin section shows role overview, activity and explicit unavailable runtime checks',async()=>{
 const f=await fixture({section:'operations'});
 try{
  assert.match(f.page.url(),/\/bin\/admin\/XWiki\/XWikiPreferences/);
  assert.match(await f.page.locator('#luxpedia-admin-content').innerText(),/25 major revisions by 5/);
  assert.match(await f.page.locator('#luxpedia-admin-content').innerText(),/Container monitoring/);
  assert.match(await f.page.locator('#luxpedia-admin-content').innerText(),/Not connected/);
  assert.match(await f.page.getByRole('link',{name:'Native groups',exact:true}).getAttribute('href'),/section=Groups/);
  assert.equal(f.state.writes.length,0);
 }finally{await f.close();}
});

test('bulk removal requires a concrete preview and confirm; restore uses the same administration section',async()=>{
 const f=await fixture({section:'operations'});
 try{
  await f.page.getByRole('button',{name:'Bulk page removal',exact:true}).click();
  await f.page.getByRole('checkbox',{name:'Article A',exact:true}).check();
  assert.equal(await f.page.getByRole('checkbox',{name:'Article B',exact:true}).isDisabled(),true);
  await f.page.getByRole('button',{name:'Preview removal',exact:true}).click();
  await f.page.locator('#luxpedia-admin-preview').waitFor();assert.equal(f.state.pages.length,2);
  assert.match(await f.page.locator('#luxpedia-admin-preview').innerText(),/Knowledge.A/);
  await f.page.getByRole('button',{name:'Confirm move to recycle bin',exact:true}).click();
  await f.page.waitForFunction(()=>document.querySelector('#luxpedia-admin-results')?.textContent.includes('Moved to recycle bin'));
  assert.equal(f.state.pages.length,1);assert.equal(f.state.trash.length,1);
  assert.deepEqual(f.state.writes.map(x=>x.operation),['preview-delete','execute']);
  await f.page.getByRole('button',{name:'Recycle bin / restore',exact:true}).click();
  await f.page.getByRole('checkbox',{name:'Knowledge.A',exact:true}).check();
  await f.page.getByRole('button',{name:'Preview restore',exact:true}).click();
  await f.page.getByRole('button',{name:'Confirm restore',exact:true}).click();
  await f.page.waitForFunction(()=>document.querySelector('#luxpedia-admin-results')?.textContent.includes('Restored'));
  assert.equal(f.state.trash.length,0);assert.equal(f.state.pages.length,2);
 }finally{await f.close();}
});

test('canceling a batch preview leaves pages and recycle bin unchanged',async()=>{
 const f=await fixture({section:'operations'});
 try{
  await f.page.getByRole('button',{name:'Bulk page removal',exact:true}).click();await f.page.getByRole('checkbox',{name:'Article A',exact:true}).check();
  await f.page.getByRole('button',{name:'Preview removal',exact:true}).click();
  await f.page.locator('#luxpedia-admin-preview').getByRole('button',{name:'Cancel',exact:true}).click();
  assert.equal(f.state.pages.length,2);assert.equal(f.state.trash.length,0);assert.equal(f.state.writes.length,1);
 }finally{await f.close();}
});

test('page changes after preview are reported as conflicts instead of being deleted',async()=>{
 const f=await fixture({section:'operations'});
 try{
  await f.page.getByRole('button',{name:'Bulk page removal',exact:true}).click();await f.page.getByRole('checkbox',{name:'Article A',exact:true}).check();await f.page.getByRole('button',{name:'Preview removal',exact:true}).click();
  await f.page.locator('#luxpedia-admin-preview').waitFor();f.state.pages[0].version='4.1';
  await f.page.getByRole('button',{name:'Confirm move to recycle bin',exact:true}).click();
  await f.page.waitForFunction(()=>document.querySelector('#luxpedia-admin-results')?.textContent.includes('skipped'));
  assert.equal(f.state.pages.length,2);assert.equal(f.state.trash.length,0);
 }finally{await f.close();}
});

test('navigation GUI adds a bilingual nested menu and category banner without writing article content',async()=>{
 const f=await fixture({section:'navigation'});
 try{
  await f.page.getByRole('button',{name:'Add menu entry',exact:true}).click();
  await f.page.locator('#luxpedia-menu-form [data-admin-field="navLabelEn"]').fill('Resources');
  await f.page.locator('#luxpedia-menu-form [data-admin-field="navLabelZh"]').fill('资源');
  await f.page.getByRole('button',{name:'Keep this entry',exact:true}).click();
  await f.page.getByRole('button',{name:'Add menu entry',exact:true}).click();
  await f.page.locator('#luxpedia-menu-form [data-admin-field="navLabelEn"]').fill('Handbook');
  await f.page.locator('#luxpedia-menu-form [data-admin-field="navDestination"]').fill('Knowledge.Article');
  await f.page.locator('#luxpedia-menu-form [data-admin-field="navParent"]').selectOption({label:'Resources'});
  await f.page.getByRole('button',{name:'Keep this entry',exact:true}).click();
  await f.page.getByRole('button',{name:'Add category banner',exact:true}).click();
  await f.page.locator('#luxpedia-banner-form [data-admin-field="browserCategory"]').selectOption('Knowledge.Technical.WebHome');
  await f.page.locator('#luxpedia-banner-form [data-admin-field="navHeaderEn"]').fill('Technical documentation');
  await f.page.locator('#luxpedia-banner-form [data-admin-field="navFooterZh"]').fill('技术资料页脚');
  await f.page.getByRole('button',{name:'Keep this entry',exact:true}).click();
  assert.equal(f.state.writes.length,0);
  await f.page.getByRole('button',{name:'Save configuration',exact:true}).click();
  await f.page.waitForFunction(()=>document.getElementById('luxpedia-navigation-notice').textContent.includes('Configuration saved'));
  assert.equal(f.state.settings.menus.length,2);assert.equal(f.state.settings.menus[1].parent,f.state.settings.menus[0].id);
  assert.equal(f.state.settings.banners[0].headerEn,'Technical documentation');
  assert.deepEqual(f.state.writes.map(x=>x.operation),['save-settings']);
 }finally{await f.close();}
});

test('admin language switching retains unfinished menu fields and the native admin URL',async()=>{
 const f=await fixture({section:'navigation'});
 try{
  await f.page.getByRole('button',{name:'Add menu entry',exact:true}).click();await f.page.locator('#luxpedia-menu-form [data-admin-field="navLabelEn"]').fill('Unsaved menu');
  const before=f.page.url();await f.page.locator('#luxpedia-language-select').selectOption('zh');
  assert.equal(f.page.url(),before);assert.equal(await f.page.locator('#luxpedia-menu-form [data-admin-field="navLabelEn"]').inputValue(),'Unsaved menu');
  assert.equal(await f.page.getByRole('button',{name:'保留本条修改',exact:true}).count(),1);
  assert.equal(f.state.writes.length,0);
 }finally{await f.close();}
});

test('public navigation and nearest banners render as text and preserve native menus',async()=>{
 const f=await fixture({presentation:{status:'ok',qrURL:'/bin/view/Knowledge/Chinese/WebHome',menus:[
  {id:'public',parent:'',en:'Handbook',zh:'手册',location:'top',href:'/bin/view/Knowledge/Handbook'},
  {id:'group',parent:'',en:'References',zh:'参考',location:'sidebar',href:''},
  {id:'child',parent:'group',en:'Source',zh:'源文件',location:'sidebar',href:'https://github.com/org/repo'}],
  banner:{headerEn:'<script>plain header</script>',headerZh:'页眉',footerEn:'Review date: 2026-11-19',footerZh:'页脚'}}});
 try{
  assert.match(await f.page.locator('#menuview').innerText(),/Original menu/);
  await f.page.getByRole('button',{name:'Navigation links',exact:true}).click();
  assert.equal(await f.page.locator('#luxpedia-managed-top a').getAttribute('href'),'http://wiki.test/bin/view/Knowledge/Handbook');
  assert.equal(await f.page.locator('#luxpedia-managed-sidebar a').getAttribute('rel'),'noopener noreferrer');
  assert.equal(await f.page.locator('#luxpedia-category-header script').count(),0);
  assert.equal(await f.page.locator('#luxpedia-category-header').textContent(),'<script>plain header</script>');
  assert.equal(await f.page.locator('#xwikicontent').innerText(),'Original article text.');
  await f.page.waitForTimeout(650);assert.equal(await f.page.locator('#luxpedia-managed-top button').getAttribute('aria-expanded'),'true');
 }finally{await f.close();}
});

test('QR generation is local, AMD-safe and encodes the canonical URL without query tokens',async()=>{
 const f=await fixture();
 try{
  const before=f.state.requests.length;await f.page.getByRole('button',{name:'Page QR code',exact:true}).click();
  const image=await f.page.locator('#luxpedia-qr-dialog canvas').evaluate(canvas=>({width:canvas.width,height:canvas.height,data:Array.from(canvas.getContext('2d').getImageData(0,0,canvas.width,canvas.height).data)}));
  const qr=decodeQR(new Uint8ClampedArray(image.data),image.width,image.height);
  assert.ok(qr);assert.equal(qr.data,'http://wiki.test/bin/view/Knowledge/%E4%B8%AD%E6%96%87%20Page/WebHome');
  assert.equal(qr.data.includes('form_token'),false);assert.equal(f.state.requests.length,before);
  assert.match(await f.page.getByRole('link',{name:'Download QR image',exact:true}).getAttribute('href'),/^data:image\/png;base64,/);
  assert.match(await f.page.locator('#luxpedia-qr-dialog').innerText(),/existing login and access/);
 }finally{await f.close();}
});
