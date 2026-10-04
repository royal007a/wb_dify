#!/usr/bin/env python3
"""132 smoke with synthetic own Workflow records and an earlier synthetic chat."""
import json
from pathlib import Path
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
state = json.loads((ROOT / 'harness/state.json').read_text())
if state.get('currentTaskId') != 'CAPABILITY-ROLLOUT-001':
    raise SystemExit('CAPABILITY-ROLLOUT-001 must be active')
API = 'https://118.196.123.132/hify/api/v1'
client = urllib.request.build_opener(urllib.request.ProxyHandler({}),
    urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
owned = []
results = []
marker = 'capability-smoke-'+str(uuid.uuid4())

def request(method, path, body=None, expected=200, key=None, legacy=False):
    headers = {'Content-Type': 'application/json'}
    if key:
        headers['Idempotency-Key'] = key
    base = API.removesuffix('/v1') if legacy else API
    req = urllib.request.Request(base+path, method=method, headers=headers,
        data=None if body is None else json.dumps(body).encode())
    try:
        response = client.open(req, timeout=65)
    except urllib.error.HTTPError as failure:
        response = failure
    with response:
        result = json.load(response)
        # Register our own creation result before checking the response status.
        if method == 'POST' and path == '/workflows' and isinstance(result.get('data'), str):
            owned.append(result['data'])
        assert response.status == expected, (method, path, response.status, expected)
        if expected == 400:
            assert result['code'] == 40000
        return result

def node(key, kind, config):
    return {'nodeKey': key, 'type': kind, 'name': key, 'config': config}

def workflow(nodes):
    graph = {'name': marker+'-'+str(len(owned)), 'schemaVersion': 1, 'nodes': nodes,
        'edges': [{'edgeKey': 'e'+str(i), 'sourceNodeKey': a['nodeKey'],
                   'targetNodeKey': b['nodeKey'], 'defaultBranch': False}
                  for i, (a,b) in enumerate(zip(nodes,nodes[1:]))]}
    wid = request('POST', '/workflows', graph, 201)['data']
    return wid, request('POST', '/workflows/'+wid+'/versions')['data']

def run(version, inputs, expected_nodes):
    result = request('POST', '/workflow-versions/'+version['id']+'/runs',
                     {'input': 'synthetic acceptance', 'inputs': inputs}, 202)['data']
    assert result['status'] == 'SUCCEEDED', (result['id'], result['status'])
    assert [n['nodeKey'] for n in result['nodes']] == expected_nodes
    assert all(n['status'] == 'SUCCEEDED' for n in result['nodes'])
    persisted = request('GET', '/workflow-runs/'+result['id'])['data']
    # PG timestamps round to microseconds; compare execution facts, not transient
    # Java Instant nanoseconds from the create response.
    for field in ('id','workflowVersionId','workflowDigest','status','input','output',
                  'context','errorMessage','elapsedMs','nodes'):
        assert persisted[field] == result[field], field
    assert persisted['createdAt'] and persisted['finishedAt']
    return persisted

try:
    wid, version = workflow([
        node('start','START',{'inputs':[
            {'name':'count','type':'number','required':True},
            {'name':'flag','type':'boolean','required':True},
            {'name':'tiny','type':'number','required':False,'default':1e-7}]}),
        node('end','END',{'output':'{{start.count}}|{{start.flag}}|{{start.tiny}}'})])
    before = request('GET','/workflow-versions/'+version['id'])
    positive = run(version, {'count':0,'flag':False}, ['start','end'])
    assert positive['output'] == '0|false|0.0000001'
    for bad in ({'count':'0','flag':False},{'count':0},{'count':None,'flag':False}):
        request('POST','/workflow-versions/'+version['id']+'/runs',
                {'input':'synthetic rejection','inputs':bad},400)
    assert request('GET','/workflow-versions/'+version['id']) == before
    results.append({'case':'typed-inputs','workflowId':wid,'versionId':version['id'],
                    'runId':positive['id'],'output':positive['output'],'rejections':3})

    denied_graph={'name':marker+'-unapproved-http','schemaVersion':1,
        'nodes':[node('start','START',{}),node('read','API_CALL',{'endpoint':'https://unapproved.invalid/read'}),
                 node('end','END',{'output':'{{read.result}}'})],
        'edges':[{'edgeKey':'e0','sourceNodeKey':'start','targetNodeKey':'read','defaultBranch':False},
                 {'edgeKey':'e1','sourceNodeKey':'read','targetNodeKey':'end','defaultBranch':False}]}
    denied=request('POST','/workflows',denied_graph,201)['data']
    request('POST','/workflows/'+denied+'/versions',expected=400)
    assert request('GET','/workflows/'+denied+'/versions')['data'] == []
    results.append({'case':'unapproved-http-publication','workflowId':denied,'httpStatus':400,'versions':0})

    provider='229711fc-8546-473d-b9f8-aa20ab291b0a'
    wid, version = workflow([node('start','START',{}),
        node('llm','LLM',{'providerId':provider,'modelId':'doubao-seed-2.1-lite',
            'systemPrompt':'Only return the requested digits. Do not explain.',
            'prompt':'Repeat exactly: 42','temperature':0,'maxOutputTokens':256}),
        node('end','END',{'output':'{{llm.result}}'})])
    result = run(version,{},['start','llm','end'])
    assert result['output'].strip() == '42', 'synthetic LLM answer must be 42'
    results.append({'case':'real-doubao-workflow','workflowId':wid,'versionId':version['id'],
                    'runId':result['id'],'output':result['output'],
                    'nodes':[{'key':n['nodeKey'],'status':n['status']} for n in result['nodes']]})

    # This is explicitly our retained synthetic conversation from DEPLOY007,
    # never an enumerated user conversation. Verify its immutable pin survives.
    old = json.loads((ROOT / 'harness/evidence/SPEC-DEPLOY-007/SPEC-DEPLOY-007-20261004T082939Z-1f8dfa88/smoke-current.json').read_text())['conversationId']
    pinned = request('GET','/conversations/'+old,legacy=True)['conversation']['agentVersionId']
    key = str(uuid.uuid4())
    result = request('POST','/conversations/'+old+'/runs',{'message':'请计算 6*7'},202,key)
    run_id = result['id']
    for _ in range(150):
        result = request('GET','/runs/'+run_id)
        if result['state'] != 'RUNNING':
            break
        time.sleep(.2)
    assert result['state'] == 'COMPLETED' and '42' in result['outputMessage']
    assert result['agentVersionId'] == pinned
    results.append({'case':'previous-synthetic-conversation','conversationId':old,
                    'runId':run_id,'agentVersionId':pinned,'output':result['outputMessage']})
finally:
    primary = sys.exc_info()[1]
    (ROOT / state['evidencePath'] / 'capability-progress.json').write_text(json.dumps({
        'checksCompleted':results,'ownedWorkflows':owned,
        'primaryErrorType':type(primary).__name__ if primary else None},ensure_ascii=False,indent=2)+'\n')
    failures=[]
    for wid in dict.fromkeys(owned):
        try:
            request('DELETE','/workflows/'+wid)
        except Exception as error:
            failures.append({'workflowId':wid,'errorType':type(error).__name__})
    if failures:
        detail='Own workflow archive failures: '+json.dumps(failures)
        if primary:
            primary.add_note(detail)
        else:
            raise RuntimeError(detail)
print(json.dumps({'checks':results,'tlsCertificateValidation':False,
    'cleanup':'own workflow drafts archived; immutable versions/runs and synthetic chats retained',
    'embedding':'not configured on 132; local live is separate',
    'httpNode':'no grants changed; unapproved publication rejected; successful GET fixture tested locally only'},ensure_ascii=False,indent=2))
