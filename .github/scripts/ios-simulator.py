import json
import os
import re
import subprocess
from ios_simulator_launch import show_simulator, simctl_output

devices = json.loads(simctl_output('list', 'devices', 'available', '-j', timeout=600))['devices']
runtimes = {runtime: entries for runtime, entries in devices.items() if 'iOS' in runtime and any('iPhone' in device['name'] for device in entries)}
if not runtimes:
    raise RuntimeError('No available iPhone simulator')
runtime = max(runtimes, key=lambda name: tuple(int(part) for part in re.findall(r'\d+', name)))
phones = [device for device in runtimes[runtime] if 'iPhone' in device['name']]
try:
    subprocess.run(['xcrun', 'simctl', 'runtime', 'dyld_shared_cache', 'update', runtime], check=False, timeout=1500)
except subprocess.TimeoutExpired:
    print('dyld shared cache update did not finish; continuing with the existing runtime state')
preferred = next((device for device in phones if device['state'] == 'Booted'), phones[0])
identifier = preferred['udid']
try:
    result = subprocess.run(['xcrun', 'simctl', 'create', 'LT Current', 'com.apple.CoreSimulator.SimDeviceType.iPhone-13', runtime], capture_output=True, text=True, timeout=300)
    if result.returncode == 0:
        identifier = result.stdout.strip()
        preferred = {'name': 'LT Current (iPhone 13)', 'udid': identifier}
except subprocess.TimeoutExpired:
    print('Current runtime device creation timed out; using the available simulator')
print(json.dumps({'device': preferred['name'], 'runtime': runtime}))
show_simulator(identifier)
with open(os.environ['GITHUB_ENV'], 'a') as output:
    output.write(f'IOS_SIMULATOR={identifier}\n')
