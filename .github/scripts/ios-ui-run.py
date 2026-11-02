import os
import pathlib
import shutil
import subprocess
import sys
import time
from ios_ui_retry import bundle_startup_only

directory = pathlib.Path(os.environ['RUNNER_TEMP'], 'ui-samples')
directory.mkdir(exist_ok=True)
log = (directory / 'simulator.log').open('a')
logger = None
if os.environ.get('IOS_SIMULATOR'):
    logger = subprocess.Popen(['xcrun', 'simctl', 'spawn', os.environ['IOS_SIMULATOR'], 'log', 'stream', '--style', 'compact', '--level', 'info', '--predicate', 'process == "App" OR process == "SpringBoard" OR process == "usernotificationsd" OR process == "runningboardd" OR process == "PermissionUITests-Runner" OR process == "splashboardd"'], stdout=log, stderr=subprocess.STDOUT)
command = sys.argv[1:]
result_bundle = pathlib.Path(command[command.index('-resultBundlePath') + 1]) if '-resultBundlePath' in command else None
seen = {}
sampled = set()
process = None


def attempt(deadline):
    global process
    process = subprocess.Popen(command)
    while process.poll() is None:
        if time.monotonic() > deadline:
            raise TimeoutError('UI test command exceeded its deadline')
        try:
            listing = subprocess.check_output(['ps', '-axo', 'pid=,comm='], text=True, timeout=10)
        except subprocess.SubprocessError:
            print('UI process inspection did not finish; continuing the test command', flush=True)
            listing = ''
        for line in listing.splitlines():
            fields = line.strip().split(maxsplit=1)
            if len(fields) != 2 or not fields[1].endswith(('/App.app/App', '/PermissionUITests-Runner.app/PermissionUITests-Runner')):
                continue
            pid = fields[0]
            first = seen.setdefault(pid, time.monotonic())
            if pid not in sampled and time.monotonic() - first > 20:
                sampled.add(pid)
                try:
                    subprocess.run(['sample', pid, '1', '10', '-file', str(directory / (pid + '.txt'))], check=False, timeout=60)
                except subprocess.SubprocessError:
                    print('UI stack sampling did not finish for ' + pid, flush=True)
        time.sleep(2)
    return process.returncode


try:
    deadline = time.monotonic() + 2400
    code = attempt(deadline)
    if code != 0 and result_bundle is not None and bundle_startup_only(result_bundle):
        kept = result_bundle.with_name(result_bundle.stem + '-runner-bootstrap.xcresult')
        print('The result bundle records only a test runner that never began executing tests; relaunching the same tests once and keeping ' + kept.name, flush=True)
        shutil.move(str(result_bundle), str(kept))
        code = attempt(deadline)
    raise SystemExit(code)
finally:
    if process is not None and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=10)
    if logger is not None and logger.poll() is None:
        logger.terminate()
        try:
            logger.wait(timeout=10)
        except subprocess.TimeoutExpired:
            logger.kill()
            logger.wait(timeout=10)
    log.close()
