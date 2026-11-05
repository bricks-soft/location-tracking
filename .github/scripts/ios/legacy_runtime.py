import json
import os
import pathlib
import re
import subprocess
from ios_simulator_launch import show_simulator, simctl_output

root = pathlib.Path(os.environ['RUNNER_TEMP'])
report = root / 'ios-legacy-runtime.txt'
required = os.environ.get('LEGACY_REQUIRED') == 'true'

def run(command, timeout=600):
    with report.open('a') as output:
        output.write('\n' + ' '.join(command) + '\n')
        output.flush()
        try:
            return subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=timeout).returncode
        except subprocess.TimeoutExpired:
            output.write('Command timed out\n')
            return 124

def available(version):
    data = json.loads(simctl_output('list', 'runtimes', '-j', timeout=60))
    return [r for r in data['runtimes'] if r.get('isAvailable') and r['version'].startswith(version)]

def boot(runtime):
    if run(['xcrun', 'simctl', 'runtime', 'dyld_shared_cache', 'update', runtime['identifier']], timeout=1500) != 0:
        with report.open('a') as output:
            output.write('dyld shared cache unavailable for ' + runtime['version'] + '\n')
        return None
    device = 'iPhone-13' if runtime['version'].startswith('15.') else 'iPhone-14'
    command = ['xcrun', 'simctl', 'create', 'LT Legacy', 'com.apple.CoreSimulator.SimDeviceType.' + device, runtime['identifier']]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    except subprocess.TimeoutExpired:
        with report.open('a') as output:
            output.write('Device creation timed out for ' + runtime['version'] + '\n')
        return None
    with report.open('a') as output:
        output.write(result.stdout + result.stderr)
    if result.returncode != 0:
        return None
    identifier = result.stdout.strip()
    if run(['xcrun', 'simctl', 'boot', identifier], timeout=120) == 0 and run(['xcrun', 'simctl', 'bootstatus', identifier, '-b'], timeout=300) == 0:
        return identifier
    run(['xcrun', 'simctl', 'shutdown', identifier], timeout=60)
    return None

run(['xcodebuild', '-version'])
run(['xcrun', 'simctl', 'list', 'runtimes'])
selected = None
for version in ['16.4', '15.5']:
    if not available(version):
        destination = root / ('runtime-' + version)
        destination.mkdir(exist_ok=True)
        if run(['xcodebuild', '-downloadPlatform', 'iOS', '-buildVersion', version, '-exportPath', str(destination)]) == 0 and not available(version):
            for image in destination.rglob('*.dmg'):
                run(['xcodebuild', '-importPlatform', str(image)])
    for runtime in available(version):
        selected = boot(runtime)
        if selected:
            show_simulator(selected)
            with open(os.environ['GITHUB_ENV'], 'a') as output:
                output.write('IOS_SIMULATOR=' + selected + '\nLEGACY_AVAILABLE=true\n')
            break
    if selected:
        break
if not selected:
    with report.open('a') as output:
        output.write('\nNo iOS 15/16 runtime booted within the probe deadlines.\n')
summary = '\n'.join(report.read_text().replace('\r', '\n').splitlines()[-60:])
print(summary)
with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as output:
    output.write('```\n' + summary + '\n```\n')
if required and not selected:
    raise SystemExit('Required legacy XCTest runtime is unavailable')
