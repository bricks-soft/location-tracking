import json
import os
import pathlib
import subprocess
import time

identifier = os.environ['IOS_SIMULATOR']
app = pathlib.Path(os.environ['RUNNER_TEMP']) / 'ios-simulator/Build/Products/Debug-iphonesimulator/App.app'
bundle = 'com.brickssoft.locationtracking.example'

def sim(*args, timeout=120):
    return subprocess.check_output(['xcrun', 'simctl', *args], text=True, timeout=timeout).strip()

subprocess.run(['xcrun', 'simctl', 'boot', identifier], check=False)
subprocess.run(['xcrun', 'simctl', 'bootstatus', identifier, '-b'], check=True, timeout=600)
sim('install', identifier, str(app), timeout=240)
sim('privacy', identifier, 'grant', 'location-always', bundle)
sim('location', identifier, 'set', '24.7136,46.6753')
container = pathlib.Path(sim('get_app_container', identifier, bundle, 'data'))
result = container / 'Library/Application Support/ios-stage1-smoke.json'
gps = pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-gps.log').open('w')
route = subprocess.Popen(['xcrun', 'simctl', 'location', identifier, 'start', '--speed=20', '--interval=1', '24.7136,46.6753', '24.9,46.8'], stdout=gps, stderr=subprocess.STDOUT)
try:
    sim('launch', '--terminate-running-process', identifier, bundle, '--ios-stage1-smoke', timeout=120)

    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if route.poll() not in [None, 0]:
            raise RuntimeError('Simulator GPS route failed; see ios-smoke-gps.log')
        if result.exists():
            data = json.loads(result.read_text())
            pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke.json').write_text(json.dumps(data, indent=2))
            print(json.dumps(data, indent=2))
            if not data['success']:
                raise RuntimeError('WebView bridge smoke failed')
            break
        time.sleep(1)
    else:
        raise RuntimeError('Timed out waiting for WebView smoke report')
finally:
    if route.poll() is None:
        route.terminate()
        try:
            route.wait(timeout=5)
        except subprocess.TimeoutExpired:
            route.kill()
            route.wait(timeout=5)
    gps.close()
    state = container / 'Library/Application Support/ios-stage1-state.json'
    if state.exists():
        pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-state.json').write_bytes(state.read_bytes())
        print(state.read_text())
    try:
        subprocess.run(['xcrun', 'simctl', 'io', identifier, 'screenshot', str(pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke.png'))], check=False, timeout=15)
        with open(pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-native.log'), 'w') as output:
            subprocess.run(['xcrun', 'simctl', 'spawn', identifier, 'log', 'show', '--last', '3m', '--style', 'compact', '--predicate', 'process == "App"'], stdout=output, stderr=subprocess.STDOUT, check=False, timeout=15)
        sim('location', identifier, 'clear', timeout=15)
    except subprocess.SubprocessError:
        print('Simulator diagnostic cleanup did not finish')
