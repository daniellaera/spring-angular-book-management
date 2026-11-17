// Run with: npm run test:scripts (node:test, no dependency). Never uses a real key.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, existsSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { generate } from './generate-primeui-license.mjs';

const DUMMY = 'dummy-key-for-test';

function run(env, require) {
  const dir = mkdtempSync(join(tmpdir(), 'primeui-lic-'));
  const outFile = join(dir, 'nested', 'primeui-license.generated.ts');
  const out = [];
  const sink = (m) => out.push(m);
  const code = generate({ env, outFile, require, log: sink, warn: sink, error: sink });
  const content = existsSync(outFile) ? readFileSync(outFile, 'utf8') : null;
  rmSync(dir, { recursive: true, force: true });
  return { code, content, output: out.join('\n') };
}

test('strict mode fails when the variable is missing, and writes nothing', () => {
  const r = run({}, true);
  assert.equal(r.code, 1);
  assert.equal(r.content, null);
  assert.match(r.output, /PRIMEUI_LICENSE is empty or not set/);
});

test('strict mode fails when the variable is empty or only whitespace', () => {
  for (const v of ['', '   ', '\n']) {
    const r = run({ PRIMEUI_LICENSE: v }, true);
    assert.equal(r.code, 1, JSON.stringify(v));
    assert.equal(r.content, null);
  }
});

test('lenient mode warns and writes an empty licence when the variable is missing', () => {
  const r = run({}, false);
  assert.equal(r.code, 0);
  assert.match(r.output, /WARNING/);
  assert.match(r.content, /primeuiLicense: string = "";/);
});

test('a set variable generates the file with the (trimmed) key', () => {
  for (const require of [true, false]) {
    const r = run({ PRIMEUI_LICENSE: `  ${DUMMY}\n` }, require);
    assert.equal(r.code, 0);
    assert.match(r.content, new RegExp(`primeuiLicense: string = "${DUMMY}";`));
  }
});

test('the key value is never logged, in any mode', () => {
  for (const require of [true, false]) {
    assert.ok(!run({ PRIMEUI_LICENSE: DUMMY }, require).output.includes(DUMMY));
  }
  assert.ok(!run({ PRIMEUI_LICENSE: '' }, true).output.includes(DUMMY));
});

test('special characters are escaped so the generated TS stays valid', () => {
  const r = run({ PRIMEUI_LICENSE: 'a"b\\c`${x}' }, true);
  assert.match(r.content, /= "a\\"b\\\\c`\$\{x\}";/);
});
