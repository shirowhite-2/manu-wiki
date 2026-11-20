const assert=require('node:assert/strict');
const {test,before,after}=require('node:test');
const {spawnSync}=require('node:child_process');
const fs=require('node:fs');const path=require('node:path');const {chromium}=require('playwright');
const extracted=spawnSync('python3',['-c','import sys;sys.path.insert(0,"scripts");from interface_assets import UI,I18N_JS;print(I18N_JS+(UI/"department_roles.js").read_text())'],{cwd:path.join(__dirname,'..'),encoding:'utf8',maxBuffer:2e6});
assert.equal(extracted.status,0,extracted.stderr);const script=extracted.stdout,bootstrap=fs.readFileSync(require.resolve('bootstrap/dist/css/bootstrap.css'),'utf8');let browser;
before(async()=>{browser=await chromium.launch({headless:true,executablePath:process.env.CHROMIUM_EXECUTABLE_PATH||undefined});});after(async()=>{await browser?.close();});
const copy=value=>JSON.parse(JSON.stringify(value));
async function fixture(options={}){
 const context=await browser.newContext({viewport:{width:options.mobile?390:1280,height:900}}),page=await context.newPage();
 const state={errors:[],writes:[],version:'1.1',settings:{enabled:false,publicCategories:[]},departments:[],native:[],siteRoles:{'XWiki.alice':['editors'],'XWiki.bob':['reviewers']}};
 page.on('pageerror',e=>state.errors.push(e.message));
 const result=()=>({status:'ok',token:'csrf',version:state.version,settings:copy(state.settings),departments:copy(state.departments)});
 await page.route('**/*',async route=>{const req=route.request(),url=new URL(req.url());
  if(url.pathname.endsWith('/AdminTools')){
   const fields=Object.fromEntries(req.method()==='POST'?new URLSearchParams(req.postData()):url.searchParams);
   if(options.forbidden){return route.fulfill({json:{status:'forbidden'}});}
   if(req.method()==='POST'){
    assert.equal(fields.form_token,'csrf');state.writes.push(fields);const input=JSON.parse(fields.payload);
    if(options.conflict){return route.fulfill({json:{status:'conflict'}});}
    if(fields.operation==='save-department-settings'){assert.equal(input.version,state.version);state.settings={enabled:input.enabled,publicCategories:input.publicCategories};state.version='2.1';}
    else if(fields.operation==='save-department'){const id=input.id||'a'.repeat(32),department={...input,id,group:'XWiki.LuxDepartment_'+id,version:'2.1'};state.departments=state.departments.filter(d=>d.id!==id).concat(department);}
    else{throw new Error('Unexpected write');}return route.fulfill({json:result()});
   }
   if(fields.operation==='categories'){return route.fulfill({json:{status:'ok',items:[{reference:'Knowledge.WebHome',title:'Knowledge'},{reference:'Knowledge.Engineering.WebHome',title:'Engineering'},{reference:'Knowledge.Engineering.Child.WebHome',title:'Child'},{reference:'Knowledge.Finance.WebHome',title:'Finance'}]}});}
   if(fields.operation==='department-users'){
    const users=[{reference:'XWiki.alice',label:'Alice',roles:['editors']},{reference:'XWiki.bob',label:'Bob',roles:['reviewers']}];const index=Number(fields.page||1)-1;
    return route.fulfill({json:{status:'ok',users:fields.q?users.filter(u=>u.label.toLowerCase().includes(fields.q.toLowerCase())):[users[index]],page:index+1,pages:2,total:2}});
   }return route.fulfill({json:result()});
  }
  if(url.pathname.startsWith('/bin/view/Main/')){return route.fulfill({body:'language cookie'});}
  const body=options.rights?`<form><label for="authenticate_view"><input type="checkbox" id="authenticate_view" name="authenticate_view" checked>无论页面权限如何设置，禁止非注册用户编辑页面。</label><label for="authenticate_edit"><input type="checkbox" id="authenticate_edit" name="authenticate_edit">无论页面权限如何设置，禁止非注册用户编辑页面。</label></form><script>window.originalView=document.getElementById('authenticate_view');window.originalEdit=document.getElementById('authenticate_edit');[originalView,originalEdit].forEach(input=>input.addEventListener('change',()=>window.nativeEvents.push([input.id,input.checked])));window.nativeEvents=[];</script>`:options.forbidden?'<p>Administrators only</p>':'<section id="luxpedia-department-roles"><p role="status"></p><div data-department-content></div></section>';
  return route.fulfill({contentType:'text/html',body:`<!doctype html><html lang="en"><head><meta charset="utf-8"><style>${bootstrap}</style></head><body><header id="headerglobal"><nav id="menuview"><ul class="navbar-right"></ul></nav></header>${body}<script>${script}</script></body></html>`});
 });
 await page.goto('http://wiki.test/bin/admin/XWiki/XWikiPreferences?editor=globaladmin&section='+ (options.rights?'Rights':'LuxPediaDepartments'));
 if(!options.rights&&!options.forbidden){await page.getByRole('button',{name:'Create department role',exact:true}).waitFor();}
 return {page,state,close:async()=>{await context.close();assert.deepEqual(state.errors,[]);}};
}
async function newDepartment(f){await f.page.getByRole('button',{name:'Create department role',exact:true}).click();await f.page.locator('#luxpedia-department-editor input[aria-label="XWiki.alice"]').waitFor();return f.page.locator('#luxpedia-department-editor');}

test('native anonymous view/edit switches get distinct bilingual labels without changing inputs or autosave listeners',async()=>{
 const f=await fixture({rights:true});try{
  assert.equal(await f.page.getByRole('checkbox',{name:'Require login to view pages, regardless of page permissions.'}).isChecked(),true);
  assert.equal(await f.page.getByRole('checkbox',{name:'Require login to edit pages, regardless of page permissions.'}).isChecked(),false);
  await f.page.selectOption('#luxpedia-language-select','zh');
  await f.page.getByRole('checkbox',{name:'强制登录后才能编辑页面（不受页面权限设置影响）。'}).check();
  assert.equal(await f.page.getByRole('checkbox',{name:'强制登录后才能查看页面（不受页面权限设置影响）。'}).isChecked(),true);
  assert.deepEqual(await f.page.evaluate(()=>({same:originalView===document.getElementById('authenticate_view')&&originalEdit===document.getElementById('authenticate_edit'),events:nativeEvents})),{same:true,events:[['authenticate_edit',true]]});
 }finally{await f.close();}
});
test('department roles use the native admin route and initially leave existing category access unchanged',async()=>{
 const f=await fixture();try{assert.match(f.page.url(),/section=LuxPediaDepartments/);assert.equal(await f.page.getByRole('checkbox',{name:'Enable department category restrictions'}).isChecked(),false);assert.equal(await f.page.locator('input[value="Knowledge.WebHome"]').count(),0);assert.equal(f.state.writes.length,0);}finally{await f.close();}
});
test('creating a bilingual department preserves member selections across pages and leaves site roles unchanged',async()=>{
 const f=await fixture();try{const editor=await newDepartment(f);await editor.getByRole('textbox',{name:'English department name'}).fill('Engineering');await editor.getByRole('textbox',{name:'Chinese department name'}).fill('工程部');await editor.locator('input[value="Knowledge.Engineering.WebHome"]').check();await editor.getByRole('checkbox',{name:'XWiki.alice',exact:true}).check();await editor.getByRole('button',{name:'Next',exact:true}).click();await editor.getByRole('checkbox',{name:'XWiki.bob',exact:true}).check();await editor.getByRole('button',{name:'Save department role',exact:true}).click();await f.page.waitForFunction(()=>!document.querySelector('#luxpedia-department-editor'));
  assert.deepEqual(f.state.departments[0].members,['XWiki.alice','XWiki.bob']);assert.deepEqual(f.state.departments[0].categories,['Knowledge.Engineering.WebHome']);assert.equal(f.state.departments[0].zh,'工程部');assert.deepEqual(f.state.siteRoles,{'XWiki.alice':['editors'],'XWiki.bob':['reviewers']});assert.equal(f.state.settings.enabled,false);assert.equal(f.state.writes[0].operation,'save-department');
 }finally{await f.close();}
});
test('public categories and enablement require a separate explicit versioned save',async()=>{
 const f=await fixture();try{await f.page.getByRole('checkbox',{name:'Enable department category restrictions'}).check();await f.page.locator('input[value="Knowledge.Engineering.WebHome"]').check();assert.equal(f.state.writes.length,0);await f.page.getByRole('button',{name:'Save access rules',exact:true}).click();await f.page.waitForFunction(()=>document.querySelector('#luxpedia-department-roles [role="status"]').textContent==='Category access rules saved.');assert.deepEqual(f.state.settings,{enabled:true,publicCategories:['Knowledge.Engineering.WebHome']});assert.equal(JSON.parse(f.state.writes[0].payload).version,'1.1');}finally{await f.close();}
});
test('language changes preserve unfinished department fields, checked categories and member selection',async()=>{
 const f=await fixture();try{const editor=await newDepartment(f);await editor.getByRole('textbox',{name:'English department name'}).fill('Unfinished');await editor.locator('input[value="Knowledge.Engineering.WebHome"]').check();await editor.getByRole('checkbox',{name:'XWiki.alice',exact:true}).check();const url=f.page.url();await f.page.selectOption('#luxpedia-language-select','zh');assert.equal(f.page.url(),url);assert.equal(await editor.getByRole('textbox',{name:'部门英文名'}).inputValue(),'Unfinished');assert.equal(await editor.locator('input[value="Knowledge.Engineering.WebHome"]').isChecked(),true);assert.equal(await editor.getByRole('checkbox',{name:'XWiki.alice',exact:true}).isChecked(),true);assert.match(await editor.innerText(),/编辑者/);assert.equal(f.state.writes.length,0);}finally{await f.close();}
});
test('a conflicting department save retains unfinished inputs and does not overwrite server state',async()=>{
 const f=await fixture({conflict:true});try{const editor=await newDepartment(f);await editor.getByRole('textbox',{name:'English department name'}).fill('Keep me');await editor.getByRole('button',{name:'Save department role',exact:true}).click();await f.page.waitForFunction(()=>document.querySelector('#luxpedia-department-roles [role="status"]').textContent.includes('Reload before saving'));assert.equal(await editor.getByRole('textbox',{name:'English department name'}).inputValue(),'Keep me');assert.deepEqual(f.state.departments,[]);}finally{await f.close();}
});
test('administrator-only markup stays absent for ordinary users',async()=>{
 const f=await fixture({forbidden:true});try{assert.equal(await f.page.locator('#luxpedia-department-roles').count(),0);assert.equal(f.state.writes.length,0);}finally{await f.close();}
});
