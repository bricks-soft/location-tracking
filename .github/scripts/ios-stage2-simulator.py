import json
import os
import pathlib
import plistlib
import signal
import subprocess
import time
import urllib.request
import uuid
from ios_simulator_launch import launch_and_wait, simctl_output

identifier = os.environ['IOS_SIMULATOR']
app = pathlib.Path(os.environ['IOS_SCENARIO_APP'])
root = pathlib.Path(os.environ['RUNNER_TEMP'])
report = root / 'ios-stage2'
report.mkdir(exist_ok=True)
bundle = 'com.brickssoft.locationtracking.example'
checks = []
route = None

def sim(*args, timeout=600):
    return simctl_output(*args, timeout=timeout)

def check(value, description):
    if not value:
        raise AssertionError(description)
    checks.append(description)
    print(description, flush=True)

def wait_for(condition, description, timeout=45):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = condition()
        if result:
            return result
        time.sleep(0.5)
    raise TimeoutError(description)

check(plistlib.loads((app / 'Info.plist').read_bytes()).get('NSLocationDefaultAccuracyReduced') is False, 'route host defaults to precise location')
subprocess.run(['codesign', '--verify', '--deep', '--strict', '--verbose=2', str(app)], check=True, timeout=60)
subprocess.run(['xcrun', 'simctl', 'boot', identifier], check=False, timeout=120)
sim('bootstatus', identifier, '-b', timeout=600)
sim('install', identifier, str(app))
sim('privacy', identifier, 'reset', 'location', bundle)
sim('privacy', identifier, 'reset', 'location-always', bundle)
sim('privacy', identifier, 'grant', 'location-always', bundle)
sim('location', identifier, 'set', '24.7136,46.6753')
container = pathlib.Path(sim('get_app_container', identifier, bundle, 'data'))
directory = container / 'Library/Application Support/Stage2'
directory.mkdir(parents=True, exist_ok=True)

def launch():
    for name in ['ready.json', 'command.json', 'response.json']:
        (directory / name).unlink(missing_ok=True)
    def ready():
        path = directory / 'ready.json'
        return json.loads(path.read_text()) if path.exists() else None
    result = launch_and_wait(identifier, bundle, ['--ios-stage2-scenario'], ready, report / 'launch.log', timeout=300)
    return result['pid']

def call(method, options=None, allow_failure=False):
    command = {'id': str(uuid.uuid4()), 'method': method, 'options': options or {}}
    temporary = directory / 'command.tmp'
    temporary.write_text(json.dumps(command))
    temporary.replace(directory / 'command.json')
    def response():
        try:
            value = json.loads((directory / 'response.json').read_text())
            return value if value.get('id') == command['id'] else None
        except (FileNotFoundError, json.JSONDecodeError):
            return None
    result = wait_for(response, 'native response to ' + method, timeout=45)
    if not result['success'] and not allow_failure:
        raise RuntimeError(method + ': ' + json.dumps(result))
    return result.get('value', result)

def snapshot(name=None):
    value = call('snapshot')
    if name:
        (report / (name + '.json')).write_text(json.dumps(value, indent=2))
    return value

def matching_snapshot(predicate):
    value = snapshot()
    return value if predicate(value) else None

def pending_snapshot():
    return matching_snapshot(lambda value: bool(value["pending"]))

def stop_route():
    global route
    if route and route.poll() is None:
        route.terminate()
        try:
            route.wait(timeout=5)
        except subprocess.TimeoutExpired:
            route.kill()
            route.wait(timeout=5)
    route = None

def kill(pid):
    os.kill(pid, signal.SIGKILL)
    wait_for(lambda: subprocess.run(['kill', '-0', str(pid)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=5).returncode != 0, 'SIGKILL completion', timeout=15)

try:
    pid = launch()
    call('stop')
    call('ready', {'config': {'http': {'autoSync': False}, 'heartbeat': {'enabled': False, 'minInterval': 60, 'maxInterval': 60}, 'geolocation': {'stopTimeout': 1, 'stopAfterElapsedMinutes': 20, 'filter': {'maxImpliedSpeed': 0}}, 'notification': {'resume': {'enabled': True, 'delay': 120, 'title': 'CI route check-in'}}, 'app': {'stopOnTerminate': False}}})
    call('destroyLocations')
    check(call('getProviderState')['accuracy'] == 'precise', 'route scenario uses precise simulator authorization')
    sensors = call('getSensors')
    permissions = call('checkPermissions')
    (report / 'capabilities.json').write_text(json.dumps({'sensors': sensors, 'permissions': permissions}, indent=2))
    check(permissions['notifications'] == 'granted', 'notification permission retained from the real UI grant')
    check(not sensors['significantMotion'] or permissions['activityRecognition'] != 'granted', 'no authorized Core Motion source; displacement fallback exercised')
    call('start')
    initial = wait_for(lambda: pending_snapshot(), 'check-in scheduled')
    original_fire_bound = time.time() + 120
    check(initial['pending'][0]['interval'] == 120, 'check-in uses the configured 120 second delay')
    start_record = next(record for record in initial['locations'] if record['event'] == 'tracking_start')
    check(start_record['device']['manufacturer'] == 'Apple' and start_record['device']['ram_total_bytes'] > 0 and bool(start_record['device']['os_version']), 'tracking start carries Apple model OS and RAM readings')
    check('disk_free_bytes' not in start_record['device'], 'records omit free disk telemetry')
    check(len(initial['pending']) == 1, 'one check-in notification scheduled')
    gps = (report / 'route.log').open('w')
    route = subprocess.Popen(['xcrun', 'simctl', 'location', identifier, 'start', '--speed=20', '--interval=1', '24.7136,46.6753', '24.9,46.8'], stdout=gps, stderr=subprocess.STDOUT)
    moving = wait_for(lambda: matching_snapshot(lambda s: s['state']['isMoving'] and any(r['event'] == 'location' for r in s['locations'])), 'moving route records', timeout=60)
    check(moving['state']['isMoving'], 'moving route detected without Core Motion')
    soon = snapshot('moving')
    count = len(soon['locations'])
    sim('launch', identifier, 'com.apple.Preferences')
    background = wait_for(lambda: matching_snapshot(lambda s: s['appState'] == 'background' and len(s['locations']) > count), 'background route records', timeout=45)
    check(background['appState'] == 'background', 'route continues while Settings is foreground')
    snapshot('background')
    time.sleep(max(0, original_fire_bound + 15 - time.time()))
    moved = snapshot('moved')
    check(len(moved['pending']) == 1 and moved['delivered'] == 0 and moved['state']['isMoving'], 'activity moved the single pending check-in past its original fire time')
    sim('launch', identifier, bundle, '--ios-stage2-scenario')
    coords = moved['runtime']['lastLocation']
    stop_route(); gps.close()
    sim('location', identifier, 'clear')
    sim('location', identifier, 'set', str(coords['latitude']) + ',' + str(coords['longitude']))
    wait_for(lambda: not snapshot()['state']['isMoving'], 'stationary stop timeout', timeout=85)
    stationary = snapshot('stationary')
    check(not stationary['state']['isMoving'] and stationary['state']['enabled'], 'stationary profile keeps the session enabled')
    call('setConfig', {'config': {'heartbeat': {'enabled': True, 'minInterval': 60, 'maxInterval': 60, 'uploadKeepAwake': 1}}})
    check(call('getHeartbeatStatus')['strategy'] == 'timer', 'live route arms the production heartbeat timer')
    sim('launch', identifier, 'com.apple.Preferences')
    heartbeats = wait_for(lambda: matching_snapshot(lambda s: any(r['event'] == 'heartbeat' for r in s['locations'])), 'background heartbeat timer', timeout=80)
    heartbeat = next(record for record in heartbeats['locations'] if record['event'] == 'heartbeat')
    check(heartbeats['appState'] == 'background' and heartbeat['heartbeat']['strategy'] == 'timer', 'DispatchSourceTimer produces a real background heartbeat')
    check(bool(heartbeat['device']['thermal_status']), 'real heartbeat contains the current thermal reading')
    snapshot('heartbeat')
    sim('launch', identifier, bundle, '--ios-stage2-scenario')
    call('stop')
    wait_for(lambda: not snapshot()['pending'], 'check-in cancellation')
    check(not snapshot('stopped')['pending'], 'stop cancels the pending check-in')
    call('setConfig', {'config': {'notification': {'resume': {'enabled': False}}, 'http': {'url': 'http://127.0.0.1:8788/locations', 'autoSync': True, 'timeout': 500}}})
    call('destroyLocations'); call('start')
    queued = snapshot('offline')['locations']
    stable = {record['uuid'] for record in queued}
    check(bool(stable), 'unreachable server leaves audit records queued')
    call('setConfig', {'config': {'http': {'url': 'http://127.0.0.1:8787/locations', 'autoSync': True}}})
    call('sync', allow_failure=True)
    wait_for(lambda: not snapshot()['locations'], 'offline replay', timeout=45)
    received = json.loads(urllib.request.urlopen('http://127.0.0.1:8787/__records', timeout=10).read())
    rows = received if isinstance(received, list) else received.get('records', [])
    check(stable.issubset({row['record']['uuid'] for row in rows}), 'offline replay preserves record UUIDs at the real mock server')
    call('stop'); call('setConfig', {'config': {'http': {'url': None, 'autoSync': False}, 'notification': {'resume': {'enabled': True}}}})
    for stop_on_terminate in [True, False]:
        call('setConfig', {'config': {'app': {'stopOnTerminate': stop_on_terminate}}})
        call('start'); before = snapshot()
        kill(pid); pid = launch()
        restored = snapshot('kill-' + str(stop_on_terminate).lower())
        check(restored['state']['enabled'], 'SIGKILL without termination callback preserves intent with stopOnTerminate=' + str(stop_on_terminate))
        check(restored['runtime']['trackingStartedAt'] == before['runtime']['trackingStartedAt'] and restored['runtime']['trackingStartedElapsed'] == before['runtime']['trackingStartedElapsed'], 'kill restore preserves original deadline with stopOnTerminate=' + str(stop_on_terminate))
        check(restored['audit'][0]['event'] == 'tracking_start' and restored['audit'][0]['reason'] == 'restore', 'native listener registered before bootstrap receives restore first')
        call('willTerminate'); state = snapshot()
        check(state['state']['enabled'] != stop_on_terminate, 'termination callback honors stopOnTerminate=' + str(stop_on_terminate))
        if stop_on_terminate:
            check(state['state']['lastStopReason'] == 'terminate' and not state['pending'], 'termination persists stop reason and cancels check-in')
        call('stop')
    call('setConfig', {'config': {'app': {'stopOnTerminate': False}}}); call('start')
    before_restart = snapshot('before-restart')
    sim('shutdown', identifier)
    sim('boot', identifier); sim('bootstatus', identifier, '-b', timeout=600)
    pid = launch(); restored = snapshot('after-restart')
    check(restored['state']['enabled'], 'simulator shutdown and boot restore tracking intent')
    check(restored['runtime']['trackingStartedAt'] == before_restart['runtime']['trackingStartedAt'], 'simulator restart keeps the original session start')
    check(restored['audit'][0]['reason'] == 'restore', 'simulator restart records restore before JavaScript')
    call('stop')
    (report / 'report.json').write_text(json.dumps({'success': True, 'checks': checks}, indent=2))
finally:
    stop_route()
    try:
        sim('io', identifier, 'screenshot', str(report / 'final.png'), timeout=15)
    except subprocess.SubprocessError:
        pass
    (report / 'checks.json').write_text(json.dumps(checks, indent=2))
