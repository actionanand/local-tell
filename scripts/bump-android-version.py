#!/usr/bin/env python3
import argparse, json
from pathlib import Path

parser = argparse.ArgumentParser()
group = parser.add_mutually_exclusive_group()
group.add_argument('--patch', action='store_true')
group.add_argument('--minor', action='store_true')
group.add_argument('--major', action='store_true')
args = parser.parse_args()

path = Path('android-version.json')
data = json.loads(path.read_text())
code = data.get('versionCode')
name = str(data.get('versionName', ''))
parts = name.split('.')
if not isinstance(code, int) or not (1 <= code < 2_100_000_000):
    raise SystemExit('versionCode must be a positive Android-compatible integer below 2100000000')
if len(parts) != 3 or not all(p.isdigit() for p in parts):
    raise SystemExit('versionName must use major.minor.patch format')
major, minor, patch = map(int, parts)
data['versionCode'] = code + 1
if args.major: data['versionName'] = f'{major + 1}.0.0'
elif args.minor: data['versionName'] = f'{major}.{minor + 1}.0'
elif args.patch: data['versionName'] = f'{major}.{minor}.{patch + 1}'
path.write_text(json.dumps(data, indent=2) + '\n')
print(f"Android version: {data['versionName']} ({data['versionCode']})")
