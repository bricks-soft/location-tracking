import subprocess
import time
import pathlib
import tempfile


def simctl_output(*arguments, timeout=120):
    with tempfile.TemporaryFile(mode='w+') as output:
        subprocess.run(['xcrun', 'simctl', *arguments], stdout=output, text=True, check=True, timeout=timeout)
        output.seek(0)
        return output.read().strip()


def show_simulator(identifier):
    developer = subprocess.check_output(['xcode-select', '-p'], text=True, timeout=15).strip()
    simulator = pathlib.Path(developer, 'Applications/Simulator.app')
    subprocess.run(['open', '-a', str(simulator), '--args', '-CurrentDeviceUDID', identifier], check=True, timeout=30)


def launch_and_wait(identifier, bundle, arguments, ready, log, timeout=180):
    with log.open('w') as output:
        process = subprocess.Popen(['xcrun', 'simctl', 'launch', '--terminate-running-process', identifier, bundle, *arguments], stdout=output, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + timeout
            while time.monotonic() < deadline:
                result = ready()
                if result is not None:
                    return result
                if process.poll() not in [None, 0]:
                    raise RuntimeError('Simulator launch failed: ' + log.read_text())
                time.sleep(0.5)
            raise TimeoutError('Simulator app did not become ready: ' + log.read_text())
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=5)
