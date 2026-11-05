import os
import pathlib
import plistlib
import shutil
import subprocess

root = pathlib.Path(os.environ['RUNNER_TEMP'])
source = root / 'LTExample.xcarchive/Products/Applications/App.app'
payload = root / 'adhoc/Payload'
payload.mkdir(parents=True)
app = payload / 'App.app'
shutil.copytree(source, app)
entitlements = root / 'release-entitlements.plist'
extracted = subprocess.run(['codesign', '-d', '--entitlements', ':-', str(source)], capture_output=True)
try:
    values = plistlib.loads(extracted.stdout)
except plistlib.InvalidFileException:
    values = {}
entitlements.write_bytes(plistlib.dumps(values))
objects = [p for p in app.rglob('*') if p.suffix in ['.framework', '.dylib', '.appex', '.app']]
for item in sorted(objects, key=lambda p: len(p.parts), reverse=True):
    subprocess.run(['codesign', '--force', '--sign', '-', '--timestamp=none', '--generate-entitlement-der', str(item)], check=True)
subprocess.run(['codesign', '--force', '--sign', '-', '--timestamp=none', '--generate-entitlement-der', '--entitlements', str(entitlements), str(app)], check=True)
subprocess.run(['codesign', '--verify', '--deep', '--strict', '--verbose=4', str(app)], check=True)
summary = subprocess.run(['codesign', '-dv', '--verbose=4', str(app)], capture_output=True, text=True, check=True)
(root / 'ios-release-adhoc-codesign.txt').write_text(summary.stdout + summary.stderr)
print(summary.stdout + summary.stderr)
if list(app.rglob('embedded.mobileprovision')):
    raise RuntimeError('Ad-hoc artifact must have no provisioning profile')
subprocess.run(['ditto', '-c', '-k', '--keepParent', 'Payload', str(root / 'location-tracking-example-adhoc.ipa')], cwd=payload.parent, check=True)
