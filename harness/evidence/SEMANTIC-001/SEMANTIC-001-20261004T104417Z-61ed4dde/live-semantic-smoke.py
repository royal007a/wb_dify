"""Synthetic documents + real embeddings; no mock vectors or private document reads."""
import argparse, json, time, urllib.request, uuid

p = argparse.ArgumentParser()
p.add_argument('--api', default='http://127.0.0.1:28082/api/v1')
p.add_argument('--embedding-url', default='http://127.0.0.1:11434/v1')
p.add_argument('--model', default='bge-m3:latest')
p.add_argument('--dimensions', type=int, default=1024)
p.add_argument('--credential-ref', default='system:SEMANTIC_LIVE_KEY')
a = p.parse_args()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
def request(method, path, body=None, content_type='application/json'):
    raw = json.dumps(body).encode() if isinstance(body, dict) else body
    req = urllib.request.Request(a.api + path, data=raw, method=method,
                                 headers={'Content-Type': content_type})
    with opener.open(req, timeout=90) as response:
        return json.load(response)
def upload(base, text):
    boundary = 'hify-' + uuid.uuid4().hex
    body = ('--' + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="synthetic.txt"\r\n'
            'Content-Type: text/plain\r\n\r\n' + text + '\r\n--' + boundary + '--\r\n').encode()
    return request('POST', '/knowledge-bases/' + base + '/documents', body,
                   'multipart/form-data; boundary=' + boundary)['data']
owned=[];base=None;provider=None;observations=[]
try:
    provider=request('POST','/providers',{'name':'semantic-smoke-'+uuid.uuid4().hex,
        'type':'OPENAI_COMPATIBLE','baseUrl':a.embedding_url,'auth':{'credentialRef':a.credential_ref},
        'models':[{'displayName':'Embedding','modelId':a.model,'enabled':True,'isDefault':True}]})['data']
    base=request('POST','/knowledge-bases',{'name':'semantic-smoke-'+uuid.uuid4().hex,
        'chunkSize':256,'chunkOverlap':16,'embedding':{'providerId':provider,'model':a.model,'dimensions':a.dimensions}})['data']
    texts=['车辆定期养护应更换机油，检查制动系统与轮胎气压。',
           '果园灌溉应根据土壤湿度调整水量，避免根系积水。',
           '夜间睡眠不足可导致白天注意力下降，建议规律作息。']
    for text in texts: owned.append(upload(base,text))
    for doc in owned:
        for _ in range(180):
            view=request('GET','/documents/'+doc)['data']
            state=view.get('indexingState')
            if state=='DONE': break
            if state=='FAILED': raise AssertionError('index failed: '+str(view.get('failureReason')))
            time.sleep(.5)
        else: raise AssertionError('index did not complete')
    for query,index in [('汽车保养有哪些注意事项？',0),('种植水果的土地该如何浇水？',1),('熬夜后为什么难以集中精神？',2)]:
        started=time.monotonic()
        candidates=request('POST','/knowledge-bases/'+base+'/retrieval-tests',{'query':query,'topK':1})['data']
        assert candidates and candidates[0]['documentId']==owned[index], candidates
        observations.append({'query':query,'expectedDocument':owned[index],'actualDocument':candidates[0]['documentId'],
                             'content':candidates[0]['content'],'latencyMs':round((time.monotonic()-started)*1000)})
    print(json.dumps({'result':'passed','model':a.model,'dimensions':a.dimensions,'realModel':True,
                      'scope':'three synthetic Chinese paraphrases, not a semantic quality benchmark',
                      'observations':observations},ensure_ascii=False,indent=2))
finally:
    errors=[]
    for path in ['/documents/'+d for d in owned]+(['/knowledge-bases/'+base] if base else [])+(['/providers/'+provider] if provider else []):
        try: request('DELETE',path)
        except Exception as e: errors.append(type(e).__name__+': '+path)
    if errors: raise RuntimeError('Synthetic cleanup failed: '+str(errors))
