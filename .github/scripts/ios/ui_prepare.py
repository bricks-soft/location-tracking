import os
import pathlib
import subprocess
import sys
import time

identifier = os.environ['IOS_SIMULATOR']
products = pathlib.Path(sys.argv[1])
for name, bundle in [('App.app', 'com.brickssoft.locationtracking.example'), ('PermissionUITests-Runner.app', 'com.brickssoft.locationtracking.uitests.xctrunner')]:
    app = products / name
    subprocess.run(['xcrun', 'simctl', 'install', identifier, str(app)], check=True, timeout=600)
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        result = subprocess.run(['xcrun', 'simctl', 'appinfo', identifier, bundle], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        if result.returncode == 0:
            subprocess.run(['xcrun', 'simctl', 'get_app_container', identifier, bundle, 'app'], check=True, timeout=15)
            break
        time.sleep(0.5)
    else:
        raise TimeoutError('Installed UI application was not registered: ' + bundle)
