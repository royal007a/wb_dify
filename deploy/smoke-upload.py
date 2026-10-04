#!/usr/bin/env python3
"""Scoped synthetic upload/readback/retrieval smoke; archive only IDs created here."""
import argparse
import json
import ssl
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote
import uuid


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--api', required=True)
    p.add_argument('--self-signed-test', action='store_true')
    args = p.parse_args()
    if args.api not in ('http://127.0.0.1:28080/api/v1', 'https://118.196.123.132/hify/api/v1'):
        p.error('Only the explicitly scoped Hify endpoints are supported')
    context = ssl._create_unverified_context() if args.self_signed_test else ssl.create_default_context()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=context))
    results = []

    def request(method, path, data=None, content_type='application/json'):
        raw = json.dumps(data).encode() if isinstance(data, dict) else data
        req = urllib.request.Request(args.api+path, data=raw, method=method, headers={'Content-Type': content_type})
        try:
            response = opener.open(req, timeout=60)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            return response.status, response.headers, json.loads(response.read())

    def check(condition, label):
        if not condition:
            raise AssertionError(label)

    status, _, health = request('GET', '/health')
    check(status == 200 and health['code'] == 200, 'health')
    status, _, missing = request('GET', '/runs/spec-deploy-missing-'+str(uuid.uuid4()))
    check(status == 404 and missing['code'] == 40400, 'unknown Run safe 404')
    status, _, base = request('POST', '/knowledge-bases', {'name': 'spec-deploy-'+str(uuid.uuid4())})
    check(status == 201, 'create isolated KB')
    kb = base['data']
    owned = []
    phrase = b'Hify synthetic upload boundary evidence.'
    try:
        for size in (2*1024**2, 10*1024**2, 10*1024**2+1, 13*1024**2):
            boundary = 'hify-synthetic-boundary'
            prefix = ('--'+boundary+'\r\nContent-Disposition: form-data; name="file"; filename="boundary.txt"\r\n'
                      'Content-Type: text/plain\r\n\r\n').encode()
            # Full byte size with only one small meaningful chunk: no 10MiB indexing load.
            body = prefix + phrase + b' '*(size-len(phrase)) + ('\r\n--'+boundary+'--\r\n').encode()
            status, headers, result = request('POST', '/knowledge-bases/'+kb+'/documents', body,
                                              'multipart/form-data; boundary='+boundary)
            expected = 202 if size <= 10*1024**2 else 413
            # A regression may accept an oversized file. Own its returned ID before
            # asserting status/content type, so the failure does not leak test data.
            if status == 202 and isinstance(result.get('data'), str) and result['data']:
                owned.append(result['data'])
            check(status == expected, 'upload status for '+str(size))
            check('application/json' in headers.get('Content-Type',''), 'JSON upload response')
            if expected == 202:
                doc = result['data']
                status, _, readback = request('GET', '/documents/'+doc)
                check(status == 200 and readback['data']['fileSize'] == size, 'persisted file size')
                for _ in range(40):
                    status, _, chunks = request('GET', '/documents/'+doc+'/chunks')
                    if status == 200 and chunks.get('data'):
                        break
                    time.sleep(.25)
                check(status == 200 and len(chunks.get('data',[])) == 1, 'one indexed synthetic chunk')
            else:
                check(result['code'] == 41300, 'safe 41300')
            results.append({'bytes': size, 'httpStatus': expected, 'resultCode': result['code']})
        status, _, retrieved = request('POST', '/knowledge-bases/'+kb+'/retrieval-tests',
                                        {'query': 'Hify synthetic upload boundary evidence.', 'topK': 3})
        candidates = retrieved.get('data', [])
        check(status == 200 and bool(candidates) and all(
            candidate.get('documentId') in owned and phrase.decode() in candidate.get('content', '')
            for candidate in candidates), 'retrieval must return own indexed synthetic text')
        results.append({'retrieval': 'pass', 'scope': 'bootstrap embedding; exact synthetic text, not semantic quality'})
    finally:
        primary = sys.exc_info()[1]
        failures = []
        targets = ['/documents/'+quote(doc, safe='') for doc in dict.fromkeys(owned)]
        targets.append('/knowledge-bases/'+quote(kb, safe=''))
        for target in targets:
            try:
                status, _, result = request('DELETE', target)
                check(status == 200 and result.get('code') == 200, 'archive response')
            except Exception as error:
                # Attempt every own ID; never enumerate/delete pre-existing data.
                failures.append({'target': target, 'errorType': type(error).__name__})
        if failures:
            detail = 'cleanup failed: '+json.dumps(failures)
            if primary is not None:
                raise RuntimeError(str(primary)+'; '+detail) from primary
            raise RuntimeError(detail)
    print(json.dumps({'api': args.api, 'tlsCertificateValidation': not args.self_signed_test,
                      'checks': results, 'cleanup': 'own synthetic documents and KB archived'}, indent=2))


if __name__ == '__main__':
    main()
