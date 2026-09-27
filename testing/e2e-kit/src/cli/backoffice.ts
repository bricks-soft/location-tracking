// STUB — owned by Unit 7 (e2e-kit). Standalone mock back office for agents and curl:
//   npm run backoffice -- [--port 8787] [--host 0.0.0.0]
// Serves the endpoints of docs/e2e/architecture.md §7 until SIGINT/SIGTERM and logs one line per request.
import { parseArgs } from 'node:util';
import { MockBackOffice } from '../backoffice.ts';
import { DEFAULT_BACKEND_PORT } from '../env.ts';

const { values } = parseArgs({
  options: {
    port: { type: 'string', default: process.env['E2E_BACKEND_PORT'] ?? String(DEFAULT_BACKEND_PORT) },
    host: { type: 'string', default: '0.0.0.0' },
  },
});

const office = new MockBackOffice({
  port: Number.parseInt(values.port, 10),
  host: values.host,
  log: (line) => console.log(line),
});
await office.start();
console.log(`mock back office on http://${values.host}:${office.port} (emulator: ${office.url('/')})`);
const shutdown = async () => {
  await office.stop();
  process.exit(0);
};
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
