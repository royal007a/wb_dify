import contextlib
import importlib.util
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch


ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('upload_smoke', ROOT / 'deploy/smoke-upload.py')
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class Response:
    headers = {'Content-Type': 'application/json'}

    def __init__(self, status, code=200, data=None):
        self.status, self.body = status, {'code': code, 'data': data}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return False

    def read(self):
        return json.dumps(self.body).encode()


class FakeApi:
    def __init__(self, unexpected_accept=False, cleanup_failure=False, bad_retrieval=False):
        self.unexpected_accept = unexpected_accept
        self.cleanup_failure = cleanup_failure
        self.bad_retrieval = bad_retrieval
        self.created = []
        self.deleted = []
        self.sizes = {}

    def open(self, req, timeout):
        path = req.full_url.split('/api/v1', 1)[1]
        method = req.get_method()
        if method == 'DELETE':
            self.deleted.append(path)
            if self.cleanup_failure and path == '/documents/doc1':
                raise OSError('synthetic cleanup failure')
            return Response(200)
        if path == '/health':
            return Response(200)
        if path.startswith('/runs/'):
            return Response(404, 40400)
        if path == '/knowledge-bases':
            return Response(201, data='own-kb')
        if path.endswith('/retrieval-tests'):
            return Response(200, data=[{
                'documentId': 'someone-else' if self.bad_retrieval == 'id' else 'doc1',
                'content': 'unrelated' if self.bad_retrieval == 'text' else 'Hify synthetic upload boundary evidence.'}])
        if path.endswith('/documents'):
            size = len(req.data.split(b'\r\n\r\n', 1)[1].rsplit(b'\r\n--', 1)[0])
            if size <= 10*1024**2 or self.unexpected_accept:
                doc = 'doc'+str(len(self.created)+1)
                self.created.append(doc)
                self.sizes[doc] = size
                return Response(202, data=doc)
            return Response(413, 41300)
        if path.endswith('/chunks'):
            return Response(200, data=[{'content': 'synthetic chunk'}])
        if path.startswith('/documents/'):
            return Response(200, data={'fileSize': self.sizes[path.rsplit('/', 1)[1]]})
        raise AssertionError('unexpected synthetic request')


class DeploySmokeTest(unittest.TestCase):
    def run_smoke(self, api):
        with patch.object(smoke.urllib.request, 'build_opener', return_value=api), \
             patch('sys.argv', ['smoke', '--api', 'http://127.0.0.1:28080/api/v1']), \
             contextlib.redirect_stdout(io.StringIO()):
            smoke.main()

    def test_success_cleans_only_created_ids(self):
        api = FakeApi()
        self.run_smoke(api)
        self.assertEqual(api.deleted, ['/documents/doc1', '/documents/doc2', '/knowledge-bases/own-kb'])

    def test_unexpected_202_is_owned_before_assertion(self):
        api = FakeApi(unexpected_accept=True)
        with self.assertRaisesRegex(AssertionError, 'upload status'):
            self.run_smoke(api)
        self.assertEqual(api.created, ['doc1', 'doc2', 'doc3'])
        self.assertEqual(api.deleted, ['/documents/doc1', '/documents/doc2', '/documents/doc3', '/knowledge-bases/own-kb'])

    def test_cleanup_failure_still_attempts_every_owned_id_and_preserves_primary(self):
        api = FakeApi(unexpected_accept=True, cleanup_failure=True)
        with self.assertRaises(Exception) as caught:
            self.run_smoke(api)
        self.assertEqual(api.deleted, ['/documents/doc1', '/documents/doc2', '/documents/doc3', '/knowledge-bases/own-kb'])
        self.assertIn('upload status', str(caught.exception))
        self.assertIn('doc1', str(caught.exception))

    def test_cleanup_failure_without_primary_is_not_reported_as_success(self):
        api = FakeApi(cleanup_failure=True)
        with self.assertRaisesRegex(Exception, 'cleanup'):
            self.run_smoke(api)
        self.assertEqual(api.deleted, ['/documents/doc1', '/documents/doc2', '/knowledge-bases/own-kb'])

    def test_nonempty_but_wrong_document_and_text_fails(self):
        for defect in ('id', 'text'):
            with self.subTest(defect=defect):
                api = FakeApi(bad_retrieval=defect)
                with self.assertRaisesRegex(AssertionError, 'retrieval'):
                    self.run_smoke(api)
                self.assertEqual(len(api.deleted), 3)


if __name__ == '__main__':
    unittest.main()
