#!/usr/bin/env node

import { execFileSync } from 'node:child_process';
import { existsSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import readline from 'node:readline/promises';

const outputFile = 'release-keystore.jks';
const base64File = 'keystore.b64.txt';
const keyFile = 'localtell-key.pem';
const certFile = 'localtell-cert.pem';
const alias = 'localtell';

async function resolvePassword() {
  const passwordIndex = process.argv.indexOf('--password');
  if (passwordIndex >= 0) {
    const password = process.argv[passwordIndex + 1];
    if (!password || password.startsWith('--')) throw new Error('--password requires a non-empty value.');
    return password;
  }

  if (process.env.KEYSTORE_PASSWORD) return process.env.KEYSTORE_PASSWORD;

  const input = readline.createInterface({ input: process.stdin, output: process.stdout });
  input._writeToOutput = value => {
    if (value.includes('Enter keystore password')) input.output.write(value);
  };
  const password = await input.question('Enter keystore password: ');
  input.output.write('\n');
  input.close();
  if (!password) throw new Error('Password cannot be empty.');
  return password;
}

const run = (command, args, environment = {}) =>
  execFileSync(command, args, { env: { ...process.env, ...environment }, stdio: 'pipe' });

const cleanup = () => {
  for (const file of [keyFile, certFile]) if (existsSync(file)) rmSync(file);
};

try {
  run('openssl', ['version']);
} catch {
  console.error('openssl was not found. Install OpenSSL and try again.');
  process.exit(1);
}

if (existsSync(outputFile) || existsSync(base64File)) {
  throw new Error('A keystore or Base64 keystore file already exists. Refusing to overwrite signing material.');
}

try {
  const password = await resolvePassword();
  run('openssl', ['genrsa', '-out', keyFile, '2048']);
  run('openssl', [
    'req',
    '-new',
    '-x509',
    '-key',
    keyFile,
    '-out',
    certFile,
    '-days',
    '36500',
    '-subj',
    '/CN=LocalTell/OU=Mobile/O=LocalTell/C=IN',
  ]);
  run(
    'openssl',
    [
      'pkcs12',
      '-export',
      '-in',
      certFile,
      '-inkey',
      keyFile,
      '-out',
      outputFile,
      '-name',
      alias,
      '-passout',
      'env:OPENSSL_PASS',
    ],
    { OPENSSL_PASS: password },
  );
  writeFileSync(base64File, readFileSync(outputFile).toString('base64'));
  cleanup();
  console.log(`Created ${outputFile}`);
  console.log(`Created ${base64File} (single-line Base64)`);
  console.log(`Alias: ${alias}`);
  console.log('Format: PKCS12');
  console.log('Verify: npm run keystore:type');
} catch (error) {
  cleanup();
  console.error(error instanceof Error ? error.message : 'Keystore generation failed.');
  process.exit(1);
}
