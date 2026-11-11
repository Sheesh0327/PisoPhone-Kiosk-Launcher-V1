"""Bootstrap and draft behavior for OpenSpec validation."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import check_openspec


class OpenSpecGateTests(unittest.TestCase):
    def test_empty_scope_fails(self):
        count, errors = check_openspec.verify([], [], lambda _: {}, lambda *_: True)
        self.assertEqual(count, 0)
        self.assertTrue(errors)

    def test_bootstrap_or_main_spec_satisfies_coverage(self):
        status = lambda _: {'isPlanningComplete': True}
        validate = lambda *_: True
        for specs, changes in [([{'id': 'spec-governance'}], []), ([], [{'name': 'clean-spec-workflow'}])]:
            count, errors = check_openspec.verify(specs, changes, status, validate)
            self.assertEqual((count, errors), (1, []))

    def test_missing_bootstrap_artifact_fails(self):
        _, errors = check_openspec.verify([], [{'name': 'clean-spec-workflow'}], lambda _: {'isPlanningComplete': False}, lambda *_: True)
        self.assertTrue(any('missing required artifacts' in x for x in errors))

    def test_draft_is_not_treated_as_validated(self):
        count, errors = check_openspec.verify([{'id': 'spec-governance'}], [{'name': 'draft'}], lambda _: {'isPlanningComplete': False}, lambda *_: True)
        self.assertEqual((count, errors), (1, []))

    def test_invalid_ready_change_fails(self):
        _, errors = check_openspec.verify([], [{'name': 'clean-spec-workflow'}], lambda _: {'isPlanningComplete': True}, lambda *_: False)
        self.assertTrue(any('invalid change' in x for x in errors))


if __name__ == '__main__':
    unittest.main()
