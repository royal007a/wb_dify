#!/usr/bin/env python3
"""Join reviewed assertion mappings to fresh Surefire cases; never infer whole-feature success."""
import argparse
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def observed_cases(directory, expected):
    observed = {}
    seen = set()
    simple_names = [name.rsplit('.', 1)[-1] for name in expected]
    if len(simple_names) != len(set(simple_names)):
        raise ValueError('ambiguous simple suite names')
    for path in directory.rglob('TEST-*.xml'):
        suite = ET.parse(path).getroot()
        name = suite.attrib['name']
        if name in seen or name not in expected or sha(path) != expected[name]['xmlSha256']:
            raise ValueError('XML differs from verified suite manifest')
        seen.add(name)
        for case in suite.findall('testcase'):
            method = case.attrib['name'].split('(')[0].split('[')[0]
            key = name.rsplit('.', 1)[-1] + '#' + method
            failed = any(case.find(tag) is not None for tag in
                         ('failure', 'error', 'flakyFailure', 'flakyError', 'rerunFailure', 'rerunError'))
            status = 'fail' if failed else 'not-run' if case.find('skipped') is not None else 'pass'
            observed.setdefault(key, []).append(status)
    if seen != set(expected):
        raise ValueError('verified suite XML missing')
    return observed


def outcome(selectors, observed):
    actual = {key: observed.get(key, []) for key in selectors}
    if any('fail' in values for values in actual.values()):
        return 'fail', actual
    if not selectors or any(not values or 'not-run' in values for values in actual.values()):
        return 'not-run', actual
    return 'pass', actual


def route_status(mapped, statuses):
    if not mapped:
        return 'not-run'
    return 'mapped-subcases-only' if all(statuses[key] == 'pass' for key in mapped) else 'partial'


def portable_input(observed, expected, invocation, summary_sha):
    # An auditable sanitized projection, not a signature or replacement for a test run.
    return {'schemaVersion': 1, 'invocationId': invocation, 'testSummarySha256': summary_sha,
            'sourceXmlSha256': {name: item['xmlSha256'] for name, item in expected.items()},
            'observed': observed}


def read_portable(data, expected, invocation, summary_sha):
    required = portable_input({}, expected, invocation, summary_sha)
    if not isinstance(data, dict) or set(data) != set(required):
        raise ValueError('invalid portable evidence fields')
    if any(data[key] != required[key] for key in required if key != 'observed'):
        raise ValueError('portable evidence binding mismatch')
    observed = data['observed']
    suites = {name.rsplit('.', 1)[-1] for name in expected}
    if len(suites) != len(expected) or not isinstance(observed, dict):
        raise ValueError('invalid portable suites')
    for selector, statuses in observed.items():
        if (not isinstance(selector, str) or selector.count('#') != 1
                or selector.split('#')[0] not in suites or not selector.split('#')[1]
                or not isinstance(statuses, list) or not statuses
                or any(status not in ('pass', 'fail', 'not-run') for status in statuses)):
            raise ValueError('invalid portable method status')
    return observed


def build(root, evidence, method_evidence=None, export_methods=None):
    verification = json.loads((evidence / 'verification.json').read_text())
    step = next(s for s in verification['steps'] if s['name'] == 'backend-tests')
    summary_path = evidence / 'backend-tests.tests.json'
    if sha(summary_path) != step['testSummarySha256']:
        raise ValueError('summary digest mismatch')
    summary = json.loads(summary_path.read_text())
    if summary['invocationId'] != verification['invocationId']:
        raise ValueError('invocation mismatch')
    expected = {item['class']: item for item in summary['tests']['classes']}
    if method_evidence is None:
        observed = observed_cases(evidence / summary['reportDirectory'], expected)
    else:
        observed = read_portable(json.loads(method_evidence.read_text()), expected,
                                 verification['invocationId'], sha(summary_path))
    if export_methods is not None:
        export_methods.write_text(json.dumps(portable_input(observed, expected, verification['invocationId'],
                                                           sha(summary_path)), ensure_ascii=False, indent=2) + '\n')
    spec = json.loads((root / 'docs/spec/behavior-cases.json').read_text())
    cases = []
    for definition in spec['cases']:
        status, actual = outcome(definition['tests'], observed)
        cases.append({**definition, 'commit': verification['headCommit'],
                      'command': summary['command'], 'status': status, 'actual': actual,
                      'evidence': str(summary_path.relative_to(root))})
    ids = {case['caseId'] for case in cases}
    for feature in spec['features']:
        if not set(feature['cases']) <= ids:
            raise ValueError('unknown feature case')
    inventory = json.loads((root / 'docs/spec/http-api.json').read_text())
    routes = []
    for endpoint in inventory['endpoints']:
        route = endpoint['method'] + ' ' + endpoint['path']
        mapped = spec['routes'].get(route, [])
        if not set(mapped) <= ids:
            raise ValueError('unknown route case')
        routes.append({'route': route, 'assertionCases': mapped,
                       'behaviorStatus': route_status(mapped, {case['caseId']: case['status'] for case in cases}),
                       'remaining': 'Not a claim that every error/state combination of this route passed.'})
    extra = set(spec['routes']) - {r['route'] for r in routes}
    if extra:
        raise ValueError('stale route mapping')
    return {'schemaVersion': 1, 'commit': verification['headCommit'],
            'definitionsSha256': sha(root / 'docs/spec/behavior-cases.json'),
            'routeInventorySha256': sha(root / 'docs/spec/http-api.json'),
            'verificationResult': verification['result'], 'cases': cases,
            'routes': routes, 'features': spec['features'],
            'meaning': 'Only named assertions get pass/fail/not-run. Routes and F01-F38 are not blanket passes. '
                       'Known residuals and external/deployment gaps remain explicit.'}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument('--evidence-dir', required=True, type=Path)
    parser.add_argument('--method-evidence', type=Path, help='Explicit sanitized input; never silently substitute stale XML')
    parser.add_argument('--export-methods', type=Path, help='Export selectors/statuses only, without XML properties or bodies')
    args = parser.parse_args()
    root = args.root.resolve()
    evidence = args.evidence_dir.resolve()
    report = build(root, evidence, args.method_evidence, args.export_methods)
    (evidence / 'behavior-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
    lines = ['# 方法级行为与接口映射', '', '代码：`' + report['commit'] + '`。只有具名子场景给pass/fail/not-run，不给整路由或F组打全通过。', '',
             '完整fixture、命令、实际参数化用例状态及残余边界见同目录behavior-report.json。', '',
             '| 子场景 | 状态 | 断言范围 |', '|---|---|---|']
    for case in report['cases']:
        lines.append('| ' + case['caseId'] + ' | ' + case['status'] + ' | ' + case['expected'].replace('|', '/') + ' |')
    lines += ['', '## 68条接口', '', '映射仅表示哪些具名方法提供了部分行为证据，不意味着错误码或状态组合穷举。', '',
              '| 接口 | 子场景 | 映射状态 |', '|---|---|---|']
    for route in report['routes']:
        lines.append('| `' + route['route'] + '` | ' + ', '.join(route['assertionCases']) + ' | ' + route['behaviorStatus'] + ' |')
    lines += ['', '## F01–F38及边界', '', '| 功能 | 子场景 | 未测或独立证据 |', '|---|---|---|']
    for feature in report['features']:
        lines.append('| ' + feature['id'] + ' | ' + ', '.join(feature['cases']) + ' | ' + feature['notRun'].replace('|', '/') + ' |')
    (evidence / 'behavior-report.md').write_text('\n'.join(lines) + '\n')
    print('Behavior report:', len(report['routes']), 'routes,', len(report['features']), 'feature groups,',
          {status: sum(c['status'] == status for c in report['cases']) for status in ('pass', 'fail', 'not-run')})


if __name__ == '__main__':
    main()
