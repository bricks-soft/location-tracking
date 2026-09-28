// SCAFFOLD (working): prints the scenario catalogue. `npm run catalogue` in testing/e2e-kit.
import { CATALOGUE, type CatalogueSuite } from '../catalogue.ts';

const suites: CatalogueSuite[] = ['plugin-lifecycle', 'plugin-heartbeat', 'plugin-permissions', 'field-force', 'manual'];
for (const suite of suites) {
  const entries = CATALOGUE.filter((entry) => entry.suite === suite);
  console.log(`\n${suite} (${entries.length})`);
  for (const entry of entries) console.log(`  ${entry.id.padEnd(6)} ${entry.title}`);
}
