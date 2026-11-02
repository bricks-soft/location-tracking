import json
import os
import pathlib
import subprocess
from ios_simulator_launch import launch_and_wait, simctl_output

identifier = os.environ['IOS_SIMULATOR']
app = pathlib.Path(os.environ['RUNNER_TEMP']) / 'ios-simulator/Build/Products/Debug-iphonesimulator/App.app'
bundle = 'com.brickssoft.locationtracking.example'

def sim(*args, timeout=120):
    return simctl_output(*args, timeout=timeout)

subprocess.run(['xcrun', 'simctl', 'boot', identifier], check=False)
subprocess.run(['xcrun', 'simctl', 'bootstatus', identifier, '-b'], check=True, timeout=600)
sim('install', identifier, str(app), timeout=600)
container = pathlib.Path(sim('get_app_container', identifier, bundle, 'data'))
result = container / 'Library/Application Support/ios-stage1-smoke.json'
result.unlink(missing_ok=True)
gps = pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-gps.log').open('w')
route = None
try:
    sim('privacy', identifier, 'grant', 'location-always', bundle, timeout=600)
    sim('location', identifier, 'set', '24.7136,46.6753')
    route = subprocess.Popen(['xcrun', 'simctl', 'location', identifier, 'start', '--speed=20', '--interval=1', '24.7136,46.6753', '24.9,46.8'], stdout=gps, stderr=subprocess.STDOUT)
    def ready():
        if route.poll() not in [None, 0]:
            raise RuntimeError('Simulator GPS route failed; see ios-smoke-gps.log')
        if result.exists():
            return json.loads(result.read_text())
        return None
    data = launch_and_wait(identifier, bundle, ['--ios-stage1-smoke'], ready, pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-launch.log'), timeout=600)
    pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke.json').write_text(json.dumps(data, indent=2))
    print(json.dumps(data, indent=2))
    if not data['success']:
        raise RuntimeError('WebView bridge smoke failed')
    view = data['view']
    if not view['visible'] or min(view['width'], view['height'], view['windowWidth'], view['windowHeight']) <= 0:
        raise RuntimeError('WebView smoke ran without a visible, laid out window')
finally:
    if route is not None and route.poll() is None:
        route.terminate()
        try:
            route.wait(timeout=5)
        except subprocess.TimeoutExpired:
            route.kill()
            route.wait(timeout=5)
    gps.close()
    webview = container / 'Library/Application Support/ios-stage1-webview.png'
    if webview.exists():
        pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-webview.png').write_bytes(webview.read_bytes())
    state = container / 'Library/Application Support/ios-stage1-state.json'
    if state.exists():
        pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-state.json').write_bytes(state.read_bytes())
        print(state.read_text())
    try:
        with open(pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke-native.log'), 'w') as output:
            subprocess.run(['xcrun', 'simctl', 'spawn', identifier, 'log', 'show', '--last', '15m', '--style', 'compact', '--predicate', 'process == "App" OR process == "SpringBoard" OR process == "splashboardd" OR process == "locationd" OR process == "tccd" OR process == "installcoordinationd"'], stdout=output, stderr=subprocess.STDOUT, check=False, timeout=60)
    except subprocess.SubprocessError:
        print('Simulator native log capture did not finish')
    try:
        subprocess.run(['xcrun', 'simctl', 'io', identifier, 'screenshot', str(pathlib.Path(os.environ['RUNNER_TEMP'], 'ios-smoke.png'))], check=False, timeout=15)
        sim('location', identifier, 'clear', timeout=15)
    except subprocess.SubprocessError:
        print('Simulator diagnostic cleanup did not finish')
