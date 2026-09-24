#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import { existsSync } from 'node:fs';

const file = process.argv[2];
if (!file || !existsSync(file)) {
  console.error('Usage: node scripts/detect-keystore-format.mjs <keystore>');
  process.exit(1);
}

const args = ['-list', '-v', '-keystore', file];
if (process.env.KEYSTORE_PASSWORD) args.push('-storepass:env', 'KEYSTORE_PASSWORD');

const result = spawnSync('keytool', args, { stdio: 'inherit', env: process.env });
if (result.error) {
  console.error('keytool was not found. Install a JDK and try again.');
  process.exit(1);
}
process.exit(result.status ?? 1);
