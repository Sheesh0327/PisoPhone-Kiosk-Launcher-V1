#!/usr/bin/env python3
"""Validate the legacy specification inventory and its coverage."""
import argparse
import json
import re
from pathlib import Path

DISPOSITIONS = {'active', 'historical', 'superseded', 'cancelled', 'unresolved'}
KINDS = {'feature', 'epic', 'finding'}


def discovered(root):
    result = set()
    features = root / 'specs/features'
    if features.exists():
        for path in features.iterdir():
            if path.is_dir() and re.match(r'^\d{3}-', path.name):
                result.add(('feature', path.name[:3]))
        archive = features / 'ARCHIVE.md'
        if archive.exists():
            result.update(('feature', x) for x in re.findall(r'^## (\d{3})-', archive.read_text(encoding='utf-8'), re.MULTILINE))
    status = root / 'specs/STATUS.md'
    if status.exists():
        result.update(('feature', x) for x in re.findall(r'^\| (\d{3}) \|', status.read_text(encoding='utf-8-sig'), re.MULTILINE))
    for path in (root / 'specs').glob('spec_[0-9][0-9]_*.md'):
        result.add(('epic', path.name[5:7]))
    for path in (root / 'specs').glob('spec_[0-9][0-9]_findings.json'):
        for row in json.loads(path.read_text(encoding='utf-8')):
            result.add(('finding', path.name[5:7] + ':' + row['id']))
    return result


def reference_exists(root, reference):
    file, _, anchor = reference.partition('#')
    path = root / file
    if not path.exists():
        return False
    if not anchor:
        return True
    if file.replace('\\', '/') == 'specs/STATUS.md' and re.fullmatch(r'feature-\d{3}', anchor):
        return bool(re.search(r'^\| ' + anchor[-3:] + r' \|', path.read_text(encoding='utf-8-sig'), re.MULTILINE))
    if path.suffix == '.json':
        return any(row.get('id') == anchor for row in json.loads(path.read_text(encoding='utf-8')))
    return any(line.strip().lower().lstrip('#').strip().replace(' ', '-') == anchor.lower()
               for line in path.read_text(encoding='utf-8').splitlines() if line.startswith('#'))


def validate(root, data, exceptions=None):
    errors = []
    if not isinstance(data, dict) or data.get('schema_version') != 1 or not isinstance(data.get('records'), list):
        return ['invalid inventory schema']
    if 'reviewed_at' not in data or not isinstance(data.get('reviewed_commit'), str):
        errors.append('missing review metadata')
    seen = set()
    for row in data['records']:
        if not isinstance(row, dict):
            errors.append('invalid record')
            continue
        kind, ident = row.get('kind'), row.get('id')
        if kind not in KINDS or not isinstance(ident, str) or not ident:
            errors.append('invalid kind or id')
            continue
        key = (kind, ident)
        if key in seen:
            errors.append(f'duplicate {kind}:{ident}')
        seen.add(key)
        if row.get('disposition') not in DISPOSITIONS:
            errors.append(f'{kind}:{ident}: invalid disposition')
        for field in ('paths', 'related_ids', 'evidence', 'gaps'):
            if not isinstance(row.get(field), list) or not all(isinstance(x, str) and x for x in row[field]):
                errors.append(f'{kind}:{ident}: invalid {field}')
        if not row.get('paths'):
            errors.append(f'{kind}:{ident}: missing path')
        if not row.get('evidence') and not row.get('gaps'):
            errors.append(f'{kind}:{ident}: missing evidence or gap')
        for ref in row.get('paths', []) + row.get('evidence', []):
            if not isinstance(ref, str) or not reference_exists(root, ref):
                errors.append(f'{kind}:{ident}: invalid reference {ref}')
    expected = discovered(root)
    for kind, ident in sorted(expected - seen):
        errors.append(f'missing {kind}:{ident}')
    for kind, ident in sorted(seen - expected):
        errors.append(f'unexpected {kind}:{ident}')
    for row in data['records']:
        for ref in row.get('related_ids', []):
            kind, sep, ident = ref.partition(':')
            if not sep or (kind, ident) not in seen:
                errors.append(f"{row.get('id')}: invalid related id {ref}")
    if exceptions is not None:
        if not isinstance(exceptions, list):
            errors.append('invalid exceptions')
        else:
            for item in exceptions:
                if not isinstance(item, dict) or set(item) != {'source', 'target', 'reason'} or not all(isinstance(item[k], str) and item[k].strip() for k in item):
                    errors.append('invalid exception')
    return errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parent.parent)
    args = parser.parse_args()
    try:
        inventory = json.loads((args.root / 'specs/legacy-inventory.json').read_text(encoding='utf-8-sig'))
        exceptions_path = args.root / 'specs/link-exceptions.json'
        exceptions = json.loads(exceptions_path.read_text(encoding='utf-8-sig')) if exceptions_path.exists() else []
        errors = validate(args.root, inventory, exceptions)
    except (OSError, ValueError, KeyError, TypeError) as exc:
        print(f'Invalid inventory: {exc}')
        return 1
    for error in errors:
        print(error)
    print(f"Inventory: {len(inventory['records'])} records checked, {len(errors)} errors")
    return int(bool(errors))


if __name__ == '__main__':
    raise SystemExit(main())
