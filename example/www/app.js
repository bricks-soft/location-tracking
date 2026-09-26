// Minimal smoke UI; Unit 18 builds the real example.
const { LocationTracking } = capacitorLocationTracking;
const output = document.getElementById('output');

function print(label, value) {
  output.textContent = `${label}\n${JSON.stringify(value, null, 2)}`;
}

async function run(label, fn) {
  try {
    print(label, await fn());
  } catch (e) {
    print(`${label} failed`, { code: e.code, message: e.message });
  }
}

document.getElementById('ready').addEventListener('click', () => run('ready', () => LocationTracking.ready({})));
document.getElementById('getState').addEventListener('click', () => run('getState', () => LocationTracking.getState()));
