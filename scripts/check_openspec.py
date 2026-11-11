#!/usr/bin/env python3
"""Strictly validate ready OpenSpec artifacts and require governance coverage."""
import json
import shutil
import subprocess
from pathlib import Path


def verify(specs, changes, status, validate):
    errors = []
    checked = 0
    governance = False
    for spec in specs:
        ident = spec['id']
        result = validate(ident, 'spec')
        checked += 1
        if not result:
            errors.append(f'invalid specification: {ident}')
        if ident == 'spec-governance' and result:
            governance = True
    for change in changes:
        name = change['name']
        state = status(name)
        ready = state.get('isPlanningComplete', False)
        if name == 'clean-spec-workflow' and not ready:
            errors.append('governance bootstrap change is missing required artifacts')
        if not ready:
            print(f'DRAFT {name}: incomplete planning artifacts; not counted as validated')
            continue
        result = validate(name, 'change')
        checked += 1
        if not result:
            errors.append(f'invalid change: {name}')
        if name == 'clean-spec-workflow' and result:
            governance = True
    if not governance:
        errors.append('no validated governance baseline or bootstrap change')
    return checked, errors


def main():
    root = Path(__file__).resolve().parent.parent
    cli = shutil.which('openspec')
    if not cli:
        print('OpenSpec CLI is unavailable')
        return 1
    def call(*args):
        result = subprocess.run([cli, *args], cwd=root, text=True, capture_output=True, check=False)
        if result.returncode:
            raise RuntimeError(result.stderr.strip() or result.stdout.strip())
        return json.loads(result.stdout)
    try:
        specs = call('list', '--specs', '--json')['specs']
        changes = call('list', '--json')['changes']
        def status(name):
            return call('status', '--change', name, '--json')
        def validate(name, kind):
            try:
                result = call('validate', name, '--type', kind, '--strict', '--json')
                return result['summary']['totals']['passed'] == 1 and result['summary']['totals']['failed'] == 0
            except RuntimeError as exc:
                print(exc)
                return False
        count, errors = verify(specs, changes, status, validate)
    except (RuntimeError, ValueError, KeyError, OSError) as exc:
        print(f'OpenSpec check failed: {exc}')
        return 1
    for error in errors:
        print(error)
    print(f'OpenSpec: {count} items validated; {len(errors)} errors')
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
