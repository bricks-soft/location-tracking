import json
import os
import re
import subprocess

devices = json.loads(subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'available', '-j']))['devices']
runtimes = {runtime: entries for runtime, entries in devices.items() if 'iOS' in runtime and any('iPhone' in device['name'] for device in entries)}
if not runtimes:
    raise RuntimeError('No available iPhone simulator')
runtime = max(runtimes, key=lambda name: tuple(int(part) for part in re.findall(r'\d+', name)))
phones = [device for device in runtimes[runtime] if 'iPhone' in device['name']]
preferred = next((device for device in phones if device['state'] == 'Booted'), phones[0])
identifier = preferred['udid']
print(json.dumps({'device': preferred['name'], 'runtime': runtime}))
with open(os.environ['GITHUB_ENV'], 'a') as output:
    output.write(f'IOS_SIMULATOR={identifier}\n')
