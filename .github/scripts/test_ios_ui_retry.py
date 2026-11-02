import json
import os
import pathlib
import subprocess
import sys
import tempfile
import textwrap
import unittest

sys.path.insert(0, str(pathlib.Path(__file__).parent))
from ios_ui_retry import STARTUP_FAILURE, startup_only

SCRIPTS = pathlib.Path(__file__).parent


def runner_error(pid=36482):
    return 'PermissionUITests-Runner (' + str(pid) + ') encountered an error'


def summary(passed=0, failures=(), skipped=0):
    return {'title': 'Test - Permissions', 'result': 'Failed' if failures else 'Passed', 'passedTests': passed, 'failedTests': len(failures), 'skippedTests': skipped, 'expectedFailures': 0, 'totalTestCount': passed + len(failures) + skipped, 'testFailures': [{'testName': name, 'failureText': text, 'targetName': 'PermissionUITests'} for name, text in failures]}


def case(name, result, messages=()):
    return {'name': name, 'nodeType': 'Test Case', 'result': result, 'children': [{'name': message, 'nodeType': 'Failure Message'} for message in messages]}


def tree(*suites):
    return {'testNodes': [{'name': 'Permissions', 'nodeType': 'Test Plan', 'children': [{'name': 'PermissionUITests', 'nodeType': 'UI test bundle', 'children': [{'name': name, 'nodeType': 'Test Suite', 'children': list(cases)} for name, cases in suites]}]}]}


STARTUP = (summary(failures=[(runner_error(), STARTUP_FAILURE)]), tree(('System Failures', [case(runner_error(), 'Failed', [STARTUP_FAILURE])])))
ASSERTION = (summary(passed=1, failures=[('testLocationPromptDenial()', 'XCTAssertTrue failed')]), tree(('PermissionUITests', [case('testApproximatePermissionAndTemporaryPrecision()', 'Passed'), case('testLocationPromptDenial()', 'Failed', ['XCTAssertTrue failed'])])))
MIXED = (summary(failures=[('testLocationPromptDenial()', 'XCTAssertTrue failed'), (runner_error(), STARTUP_FAILURE)]), tree(('PermissionUITests', [case('testLocationPromptDenial()', 'Failed', ['XCTAssertTrue failed'])]), ('System Failures', [case(runner_error(), 'Failed', [STARTUP_FAILURE])])))
PASSED = (summary(passed=2), tree(('PermissionUITests', [case('testApproximatePermissionAndTemporaryPrecision()', 'Passed'), case('testLocationPromptDenial()', 'Passed')])))


class StartupOnlyTests(unittest.TestCase):
    def test_only_a_runner_that_never_started_is_retryable(self):
        self.assertTrue(startup_only(*STARTUP))

    def test_executed_or_failed_tests_are_never_retryable(self):
        self.assertFalse(startup_only(*ASSERTION))
        self.assertFalse(startup_only(*MIXED))
        self.assertFalse(startup_only(*PASSED))

    def test_other_runner_errors_and_inconsistent_results_fail_closed(self):
        other = (summary(failures=[(runner_error(), 'Test runner exited before starting test execution')]), tree(('System Failures', [case(runner_error(), 'Failed', ['Test runner exited before starting test execution'])])))
        self.assertFalse(startup_only(*other))
        hidden_case = (STARTUP[0], tree(('System Failures', [case(runner_error(), 'Failed', [STARTUP_FAILURE])]), ('PermissionUITests', [case('testLocationPromptDenial()', 'Failed', ['XCTAssertTrue failed'])])))
        self.assertFalse(startup_only(*hidden_case))
        miscounted = dict(STARTUP[0], totalTestCount=2)
        self.assertFalse(startup_only(miscounted, STARTUP[1]))
        self.assertFalse(startup_only({}, STARTUP[1]))
        self.assertFalse(startup_only(STARTUP[0], {}))
        self.assertFalse(startup_only(None, None))


FAKE_XCODEBUILD = textwrap.dedent('''
    import json, os, pathlib, sys
    state = pathlib.Path(os.environ['FAKE_STATE'])
    attempt = int(state.read_text()) if state.exists() else 0
    state.write_text(str(attempt + 1))
    outcome = json.loads(os.environ['FAKE_OUTCOMES'])[attempt]
    bundle = pathlib.Path(sys.argv[sys.argv.index('-resultBundlePath') + 1])
    bundle.mkdir(parents=True)
    if outcome['bundle'] is not None:
        (bundle / 'summary.json').write_text(json.dumps(outcome['bundle'][0]))
        (bundle / 'tests.json').write_text(json.dumps(outcome['bundle'][1]))
        for failure in outcome['bundle'][0]['testFailures']:
            print('\t' + failure['testName'] + ' (' + failure['failureText'] + ')', flush=True)
    sys.exit(outcome['code'])
''')

FAKE_XCRUN = textwrap.dedent('''
    #!/usr/bin/env python3
    import pathlib, sys
    arguments = sys.argv[1:]
    if arguments[:3] != ['xcresulttool', 'get', 'test-results']:
        sys.exit(1)
    path = pathlib.Path(arguments[arguments.index('--path') + 1]) / (arguments[3] + '.json')
    if not path.exists():
        sys.exit(1)
    sys.stdout.write(path.read_text())
''').lstrip()


class WrapperTests(unittest.TestCase):
    def run_wrapper(self, outcomes):
        work = pathlib.Path(tempfile.mkdtemp())
        binary = work / 'bin'
        binary.mkdir()
        (binary / 'xcrun').write_text(FAKE_XCRUN)
        (binary / 'xcrun').chmod(0o755)
        (work / 'xcodebuild.py').write_text(FAKE_XCODEBUILD)
        bundle = work / 'ios-permissions.xcresult'
        environment = dict(os.environ, RUNNER_TEMP=str(work), FAKE_STATE=str(work / 'attempts'), FAKE_OUTCOMES=json.dumps(outcomes), PATH=str(binary) + os.pathsep + os.environ['PATH'])
        environment.pop('IOS_SIMULATOR', None)
        result = subprocess.run([sys.executable, str(SCRIPTS / 'ios-ui-run.py'), sys.executable, str(work / 'xcodebuild.py'), '-resultBundlePath', str(bundle)], env=environment, capture_output=True, text=True, timeout=120)
        attempts = int((work / 'attempts').read_text())
        return result.returncode, attempts, bundle.exists(), (work / 'ios-permissions-runner-bootstrap.xcresult').exists()

    def test_pure_assertion_failure_runs_once_and_fails(self):
        self.assertEqual(self.run_wrapper([{'code': 65, 'bundle': ASSERTION}]), (65, 1, True, False))

    def test_pure_startup_failure_relaunches_once_and_keeps_both_bundles(self):
        self.assertEqual(self.run_wrapper([{'code': 65, 'bundle': STARTUP}, {'code': 0, 'bundle': PASSED}]), (0, 2, True, True))

    def test_mixed_assertion_and_startup_failure_is_not_retried(self):
        self.assertEqual(self.run_wrapper([{'code': 65, 'bundle': MIXED}, {'code': 0, 'bundle': PASSED}]), (65, 1, True, False))

    def test_second_startup_failure_is_reported(self):
        self.assertEqual(self.run_wrapper([{'code': 65, 'bundle': STARTUP}, {'code': 65, 'bundle': STARTUP}, {'code': 0, 'bundle': PASSED}]), (65, 2, True, True))

    def test_unreadable_result_bundle_fails_closed(self):
        self.assertEqual(self.run_wrapper([{'code': 65, 'bundle': None}, {'code': 0, 'bundle': PASSED}]), (65, 1, True, False))


if __name__ == '__main__':
    unittest.main()
