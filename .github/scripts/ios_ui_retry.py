import json
import re
import subprocess

STARTUP_FAILURE = 'Test runner never began executing tests after launching'
RUNNER_ERROR = re.compile(r'^\S+-Runner \(\d+\) encountered an error$')


def test_cases(nodes, suite=None):
    for node in nodes:
        if node.get('nodeType') == 'Test Case':
            yield suite, node
        else:
            yield from test_cases(node.get('children', []), node.get('name') if node.get('nodeType') == 'Test Suite' else suite)


def startup_only(summary, tests):
    try:
        failures = summary['testFailures']
        if summary['passedTests'] != 0 or summary['skippedTests'] != 0 or summary['expectedFailures'] != 0:
            return False
        if not failures or summary['failedTests'] != len(failures) or summary['totalTestCount'] != len(failures):
            return False
        if any(failure['failureText'] != STARTUP_FAILURE or not RUNNER_ERROR.match(failure['testName']) for failure in failures):
            return False
        cases = list(test_cases(tests['testNodes']))
        if len(cases) != len(failures):
            return False
        for suite, case in cases:
            messages = [child.get('name') for child in case.get('children', []) if child.get('nodeType') == 'Failure Message']
            if suite != 'System Failures' or case.get('result') != 'Failed' or not RUNNER_ERROR.match(case.get('name', '')) or messages != [STARTUP_FAILURE]:
                return False
        return True
    except (KeyError, TypeError, AttributeError):
        return False


def read(bundle, kind):
    try:
        output = subprocess.run(['xcrun', 'xcresulttool', 'get', 'test-results', kind, '--path', str(bundle)], capture_output=True, text=True, timeout=300)
    except (OSError, subprocess.SubprocessError):
        return None
    if output.returncode != 0:
        return None
    try:
        return json.loads(output.stdout)
    except json.JSONDecodeError:
        return None


def bundle_startup_only(bundle):
    if not bundle.exists():
        return False
    summary = read(bundle, 'summary')
    tests = read(bundle, 'tests')
    return summary is not None and tests is not None and startup_only(summary, tests)
