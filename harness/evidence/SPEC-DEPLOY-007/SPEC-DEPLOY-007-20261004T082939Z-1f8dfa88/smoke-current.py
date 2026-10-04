#!/usr/bin/env python3
"""Explicit 132 smoke: synthetic own records only; TLS ignored for this probe."""
import json
import ssl
import time
import urllib.error
import urllib.request
from urllib.parse import quote
import uuid

API = 'https://118.196.123.132/hify/api/v1'
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}),
    urllib.request.HTTPSHandler(context=ssl._create_unverified_context()))
checks, owned = [], []
marker = 'spec-redeploy-' + str(uuid.uuid4())


def request(method, path, value=None, key=None, content_type='application/json'):
    body = json.dumps(value).encode() if isinstance(value, dict) else value
    headers = {'Content-Type': content_type}
    if key:
        headers['Idempotency-Key'] = key
    req = urllib.request.Request(API+path, data=body, method=method, headers=headers)
    try:
        response = opener.open(req, timeout=30)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.status, json.loads(response.read()), response.headers


def call(method, path, value, expected, label, key=None):
    status, result, headers = request(method, path, value, key)
    assert status == expected, (label, status)
    if expected == 400:
        assert result['code'] == 40000, label
        assert '\x00' not in result['message'], label
    checks.append({'case': label, 'status': status})
    return result


def create(resource, body):
    status, response, _ = request('POST', '/'+resource, body)
    identity = response.get('data')
    if isinstance(identity, str) and identity:
        owned.append('/'+resource+'/'+quote(identity, safe=''))
    assert status == 201 and isinstance(identity, str), resource
    return identity


def upload(kb, content, expected):
    boundary = 'hify-smoke-'+uuid.uuid4().hex
    raw = ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="smoke.txt"\r\n'
           'Content-Type: text/plain\r\n\r\n').encode()+content+('\r\n--'+boundary+'--\r\n').encode()
    status, body, _ = request('POST', '/knowledge-bases/'+kb+'/documents', raw,
                            content_type='multipart/form-data; boundary='+boundary)
    doc = body.get('data')
    if isinstance(doc, str) and doc:
        owned.append('/documents/'+quote(doc, safe=''))
    assert status == expected, ('upload', status)
    if expected == 400:
        assert body['code'] == 40000
    checks.append({'case': 'upload-'+('nul' if expected == 400 else 'valid'), 'status': status})
    return doc


conversation = run = None
try:
    call('GET', '/health', None, 200, 'health')
    kb = create('knowledge-bases', {'name': marker})
    original = call('GET', '/knowledge-bases/'+kb, None, 200, 'read-own-kb')
    call('PUT', '/knowledge-bases/'+kb, {'name': marker+'\x00'}, 400, 'kb-nul-update')
    assert request('GET', '/knowledge-bases/'+kb)[1] == original
    before = request('GET', '/knowledge-bases/'+kb+'/documents')[1]
    upload(kb, b'a\x00b', 400)
    assert request('GET', '/knowledge-bases/'+kb+'/documents')[1] == before
    doc = upload(kb, marker.encode(), 202)
    deadline = time.monotonic()+30
    while time.monotonic() < deadline:
        data = request('GET', '/documents/'+doc)[1]['data']
        if data['indexingState'] == 'DONE':
            break
        assert data['indexingState'] != 'FAILED'
        time.sleep(.2)
    else:
        raise AssertionError('own document indexing deadline')
    citations = call('POST', '/knowledge-bases/'+kb+'/retrieval-tests', {'query': marker}, 200, 'knowledge-positive')['data']
    assert any(c['documentId'] == doc and marker in c['content'] for c in citations)
    call('POST', '/knowledge-bases/'+kb+'/retrieval-tests', {'query': '\x00'+marker}, 400, 'knowledge-read-nul')

    agent = create('agents', {'name': marker, 'instructions': 'synthetic smoke', 'providerId': 'mock',
        'modelId': 'hify-mock', 'temperature': .2, 'maxTokens': 1024, 'maxTurns': 3,
        'maxContextTurns': 3, 'enabledTools': ['calculator'], 'enabled': True})
    original = request('GET', '/agents/'+agent)[1]
    for route, payload in [
        ('knowledge-bindings', {'bindings': [{'knowledgeBaseId': kb+'\x00', 'topK': 3, 'priority': 0}]}),
        ('mcp-bindings', {'bindings': [{'serverId': 'synthetic\x00', 'toolNames': ['read']}]}),
        ('workflow-binding', {'workflowId': 'synthetic\x00'}),
        ('tools', {'toolIds': ['calculator\x00']})]:
        call('PUT', '/agents/'+agent+'/'+route, payload, 400, route+'-nul')
        assert request('GET', '/agents/'+agent)[1] == original
    call('PUT', '/agents/'+agent+'/knowledge-bindings',
         {'bindings': [{'knowledgeBaseId': kb, 'topK': 3, 'priority': 0}]}, 200, 'binding-positive')

    call('POST', '/conversations', {'agentId': 'demo-agent', 'title': marker+'\x00'}, 400, 'conversation-nul')
    conversation = call('POST', '/conversations', {'agentId': 'demo-agent', 'title': marker}, 201, 'conversation-positive')['id']
    for label, payload in [('length', {'message': 'x'*20001}), ('message-nul', {'message': 'x\x00'}),
                           ('resume-nul', {'message': 'hi', 'resume': {'runId': 'x\x00', 'gapIds': ['gap-1']}})]:
        key = str(uuid.uuid4())
        call('POST', '/conversations/'+conversation+'/runs', payload, 400, label, key)
        status, body, headers = request('GET', '/conversations/'+conversation+'/runs/by-key', key=key)
        assert status == 404 and 'no-store' in headers.get('Cache-Control', ''), label
    key = str(uuid.uuid4())
    run = call('POST', '/conversations/'+conversation+'/runs', {'message': '请计算 6*7'}, 202, 'run-positive', key)['id']
    deadline = time.monotonic()+30
    while time.monotonic() < deadline:
        data = request('GET', '/runs/'+run)[1]
        if data['state'] != 'RUNNING':
            break
        time.sleep(.2)
    assert data['state'] == 'COMPLETED' and '42' in data['outputMessage']
    replay = call('POST', '/conversations/'+conversation+'/runs', {'message': '请计算 6*7'}, 200, 'idempotent-replay', key)
    assert replay['id'] == run
    call('POST', '/runs/'+run+'/memory/search', {'query': '\x00hello'}, 400, 'memory-read-nul')
    call('POST', '/intent-decisions', {'agentId': 'demo-agent\x00', 'input': 'hi'}, 400, 'intent-agent-nul')
    call('POST', '/intent-decisions', {'agentId': 'demo-agent', 'input': '\x00hi'}, 400, 'intent-input-nul')
    result = call('POST', '/intent-decisions', {'agentId': 'demo-agent', 'input': '请计算 12.5*4'}, 200, 'intent-positive')
    assert result['intent'] == 'calculate'
finally:
    failures = []
    # Children first, then the owned Agent draft and KB. Never enumerate existing IDs.
    for target in sorted(set(owned), key=lambda item: 0 if item.startswith('/documents/') else 1 if item.startswith('/agents/') else 2):
        try:
            status, body, _ = request('DELETE', target)
            assert status == 200 and body['code'] == 200
        except Exception as error:
            failures.append({'target': target, 'type': type(error).__name__})
    if failures:
        raise RuntimeError('own record cleanup failed: '+json.dumps(failures))
print(json.dumps({'api': API, 'tlsCertificateValidation': False, 'checks': checks,
    'conversationId': conversation, 'runId': run, 'cleanup': 'own Agent, KB and document archived',
    'retained': 'synthetic conversation and Run: no deletion API',
    'scope': 'real deployed PostgreSQL + Mock model; not external-provider evaluation'}, indent=2))
