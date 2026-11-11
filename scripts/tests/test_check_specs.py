"""Behavior tests for specification inventory integrity."""
import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_specs


class InventoryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        (self.root / 'specs/features').mkdir(parents=True)
        (self.root / 'specs/features/ARCHIVE.md').write_text('# Archive\n\n## 001-example\n', encoding='utf-8')
        (self.root / 'specs/spec_01_a.md').write_text('# Epic\n', encoding='utf-8')
        (self.root / 'specs/spec_01_findings.json').write_text(json.dumps([{'id': 'A'}]), encoding='utf-8')
        def row(kind, ident, path):
            return {'kind': kind, 'id': ident, 'paths': [path], 'disposition': 'unresolved',
                    'related_ids': [], 'evidence': [], 'gaps': ['not established']}
        self.data = {'schema_version': 1, 'reviewed_at': None, 'reviewed_commit': 'abc', 'records': [
            row('feature', '001', 'specs/features/ARCHIVE.md#001-example'),
            row('epic', '01', 'specs/spec_01_a.md'),
            row('finding', '01:A', 'specs/spec_01_findings.json#A')]}

    def errors(self, data=None, exceptions=None):
        return check_specs.validate(self.root, data or self.data, exceptions)

    def test_valid_archive_and_documented_gap(self):
        self.assertEqual([], self.errors())

    def test_duplicate_missing_invalid_disposition_and_reference(self):
        d = copy.deepcopy(self.data)
        d['records'].append(copy.deepcopy(d['records'][0]))
        d['records'].pop(1)
        d['records'][0]['disposition'] = 'invented'
        d['records'][0]['paths'] = ['specs/missing.md']
        errors = ' '.join(self.errors(d))
        for expected in ('duplicate', 'missing epic', 'invalid disposition', 'invalid reference'):
            self.assertIn(expected, errors)

    def test_exception_requires_source_target_and_reason(self):
        self.assertIn('invalid exception', self.errors(exceptions=[{'source': 'x', 'target': 'y', 'reason': ''}]))
        self.assertEqual([], self.errors(exceptions=[{'source': 'x', 'target': 'y', 'reason': 'historical'}]))

    def test_status_only_features_are_discovered_and_reference_checked(self):
        status = self.root / 'specs/STATUS.md'
        status.write_text('| 005 | Legacy feature |\n', encoding='utf-8')
        self.assertIn('missing feature:005', ' '.join(self.errors()))
        self.data['records'].append({'kind': 'feature', 'id': '005', 'paths': ['specs/STATUS.md#feature-005'],
                                     'disposition': 'unresolved', 'related_ids': [], 'evidence': [], 'gaps': ['unreviewed']})
        self.assertEqual([], self.errors())
        self.data['records'][-1]['paths'] = ['specs/STATUS.md#feature-006']
        self.assertIn('invalid reference', ' '.join(self.errors()))

    def test_missing_anchor_and_dangling_relationship_fail(self):
        d = copy.deepcopy(self.data)
        d['records'][0]['paths'] = ['specs/features/ARCHIVE.md#001-nonexistent']
        d['records'][1]['related_ids'] = ['feature:999']
        self.assertIn('invalid reference', ' '.join(self.errors(d)))
        self.assertIn('invalid related id', ' '.join(self.errors(d)))


if __name__ == '__main__':
    unittest.main()
