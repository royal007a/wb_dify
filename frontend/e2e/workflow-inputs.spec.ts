import { expect, test } from '@playwright/test'

const fields=[
  {name:'owner',label:'负责人',type:'text',required:true,maxLength:64},
  {name:'count',label:'数量',type:'number',required:false,default:0},
  {name:'urgent',label:'加急',type:'boolean',required:true},
  {name:'mode',label:'方式',type:'enum',required:false,default:'普通',options:['普通','加急']},
]
test('list and canvas run the published typed schema, preserving false and zero',async({page})=>{
  const requests:unknown[]=[]
  const graph={id:'wf-inputs',name:'输入演示',description:'',schemaVersion:1,draftRevision:2,publishedVersionId:'version-one',nodes:[{nodeKey:'entry',type:'START',name:'开始',config:{inputs:[{name:'draftOnly',label:'草稿新字段',type:'text',required:true}]}},{nodeKey:'end',type:'END',name:'结束',config:{output:'done'}}],edges:[{edgeKey:'e',sourceNodeKey:'entry',targetNodeKey:'end',defaultBranch:false}]}
  await page.route('**/api/v1/workflows**',route=>route.fulfill({json:{code:200,data:route.request().url().includes('/wf-inputs')?graph:[graph],total:1,page:1,size:10}}))
  await page.route('**/api/v1/workflow-versions/version-one',route=>route.fulfill({json:{code:200,data:{id:'version-one',versionNo:1,workflowId:graph.id,nodes:[{...graph.nodes[0],config:{inputs:fields}},graph.nodes[1]],edges:graph.edges}}}))
  await page.route('**/api/v1/workflow-versions/version-one/runs',async route=>{
    requests.push(route.request().postDataJSON())
    return route.fulfill({status:202,json:{code:200,data:{id:'run-one',status:'SUCCEEDED',output:'published answer',nodes:[{},{}],elapsedMs:2}}})
  })
  await page.goto('./workflows')
  await page.getByRole('button',{name:'运行',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'运行已发布版本'})
  await expect(dialog.getByLabel('负责人',{exact:true})).toBeVisible()
  await expect(dialog.getByText('草稿新字段')).toHaveCount(0)
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect(dialog.getByText('请检查 负责人 的文本长度')).toBeVisible()
  expect(requests).toHaveLength(0)
  await dialog.getByLabel('负责人',{exact:true}).fill('小林')
  await dialog.getByLabel('userMessage',{exact:true}).fill('安排任务')
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect(dialog.getByText('published answer')).toBeVisible()
  expect(requests).toEqual([{input:'安排任务',inputs:{owner:'小林',count:0,urgent:false,mode:'普通'}}])
  await dialog.getByRole('spinbutton',{name:'数量',exact:true}).fill('')
  await dialog.getByLabel('负责人',{exact:true}).click() // commit Element Plus's null-on-clear
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect.poll(()=>requests.length).toBe(2)
  expect(requests[1]).toEqual({input:'安排任务',inputs:{owner:'小林',urgent:false,mode:'普通'}})
  await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  await page.getByRole('button',{name:'画布',exact:true}).click()
  await page.getByRole('button',{name:'试跑',exact:true}).click()
  await expect(dialog.getByLabel('负责人',{exact:true})).toBeVisible()
  await expect(dialog.getByText('草稿新字段')).toHaveCount(0)
})

test('late published schema and late execution cannot overwrite another version dialog',async({page})=>{
  const graphs=['a','b'].map(id=>({id,name:`工作流-${id}`,publishedVersionId:`version-${id}`,nodes:[],edges:[]}))
  let releaseSchema!:()=>void, releaseRun!:()=>void
  const schemaBarrier=new Promise<void>(resolve=>{releaseSchema=resolve})
  const runBarrier=new Promise<void>(resolve=>{releaseRun=resolve})
  let schemaRequested=false, runRequested=false
  await page.route('**/api/v1/workflows**',route=>route.fulfill({json:{code:200,data:graphs,total:2,page:1,size:10}}))
  await page.route('**/api/v1/workflow-versions/version-*',async route=>{
    const id=route.request().url().endsWith('version-a')?'a':'b'
    if(id==='a'){schemaRequested=true;await schemaBarrier}
    await route.fulfill({json:{code:200,data:{id:`version-${id}`,versionNo:id==='a'?1:2,nodes:[{nodeKey:'start',type:'START',name:'开始',config:{inputs:[{name:'text',label:`字段-${id}`,type:'text',required:false,default:id}]}}],edges:[]}}})
  })
  await page.route('**/api/v1/workflow-versions/version-b/runs',async route=>{
    runRequested=true;await runBarrier
    await route.fulfill({status:202,json:{code:200,data:{id:'late-b',status:'SUCCEEDED',output:'旧运行晚到',nodes:[]}}})
  })
  await page.goto('./workflows')
  const dialog=page.getByRole('dialog',{name:'运行已发布版本'})
  await page.getByRole('button',{name:'运行',exact:true}).nth(0).click()
  await expect.poll(()=>schemaRequested).toBe(true)
  await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  await page.getByRole('button',{name:'运行',exact:true}).nth(1).click()
  await expect(dialog.getByLabel('字段-b',{exact:true})).toBeVisible()
  const oldSchema=page.waitForResponse('**/api/v1/workflow-versions/version-a')
  releaseSchema();await oldSchema
  await expect(dialog.getByLabel('字段-b',{exact:true})).toBeVisible()
  await expect(dialog.getByLabel('字段-a',{exact:true})).toHaveCount(0)
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect.poll(()=>runRequested).toBe(true)
  await dialog.getByRole('button',{name:'关闭',exact:true}).click()
  await page.getByRole('button',{name:'运行',exact:true}).nth(0).click()
  await expect(dialog.getByLabel('字段-a',{exact:true})).toBeVisible()
  const oldRun=page.waitForResponse('**/api/v1/workflow-versions/version-b/runs')
  releaseRun();await oldRun
  await expect(dialog.getByLabel('字段-a',{exact:true})).toBeVisible()
  await expect(dialog.getByText('旧运行晚到')).toHaveCount(0)
  await expect(dialog.getByRole('button',{name:'执行已发布版本'})).toBeEnabled()
})

test('legacy form works and a failed run does not display a successful result',async({page})=>{
  const graph={id:'old',name:'旧工作流',publishedVersionId:'legacy',nodes:[{nodeKey:'start',type:'START',name:'开始',config:{}}],edges:[]}
  await page.route('**/api/v1/workflows**',route=>route.fulfill({json:{code:200,data:[graph],total:1,page:1,size:10}}))
  await page.route('**/api/v1/workflow-versions/legacy',route=>route.fulfill({json:{code:200,data:{...graph,id:'legacy',versionNo:1}}}))
  let posts=0
  await page.route('**/api/v1/workflow-versions/legacy/runs',route=>{
    posts++
    expect(route.request().postDataJSON()).toEqual({input:'hello',inputs:{}})
    if(posts===1)return route.fulfill({status:400,json:{code:40000,message:'输入不合法'}})
    return route.fulfill({status:202,json:{code:200,data:{id:'legacy-run',status:'SUCCEEDED',output:'hello',nodes:[{},{}]}}})
  })
  await page.goto('./workflows');await page.getByRole('button',{name:'运行',exact:true}).click()
  const dialog=page.getByRole('dialog',{name:'运行已发布版本'})
  await dialog.getByLabel('userMessage',{exact:true}).fill('hello')
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect(dialog.getByRole('alert')).toBeVisible();await expect(dialog.getByText('SUCCEEDED')).toHaveCount(0)
  await dialog.getByRole('button',{name:'执行已发布版本'}).click()
  await expect(dialog.getByText('SUCCEEDED')).toBeVisible();expect(posts).toBe(2)
})
