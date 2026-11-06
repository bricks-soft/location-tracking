import os
import pathlib
import subprocess
import tempfile


def root():
    path = pathlib.Path(os.environ.get('RUNNER_TEMP') or pathlib.Path(tempfile.gettempdir(), 'location-tracking-ios'))
    path.mkdir(parents=True, exist_ok=True)
    return path


def simctl_output(*arguments, timeout=120):
    with tempfile.TemporaryFile(mode='w+') as output:
        subprocess.run(['xcrun', 'simctl', *arguments], stdout=output, text=True, check=True, timeout=timeout)
        output.seek(0)
        return output.read().strip()


def show_simulator(identifier):
    developer = subprocess.check_output(['xcode-select', '-p'], text=True, timeout=15).strip()
    simulator = pathlib.Path(developer, 'Applications/Simulator.app')
    subprocess.run(['open', '-a', str(simulator), '--args', '-CurrentDeviceUDID', identifier], check=False, timeout=30)


def export(values):
    target = os.environ.get('GITHUB_ENV') or str(root() / 'github-env')
    with open(target, 'a') as output:
        for name, value in values.items():
            output.write(f'{name}={value}\n')
            os.environ[name] = value


def summary(text):
    target = os.environ.get('GITHUB_STEP_SUMMARY')
    if target:
        with open(target, 'a') as output:
            output.write(text)
