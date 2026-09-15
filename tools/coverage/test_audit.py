#!/usr/bin/env python3
"""Tests for the coverage ratchet (``audit.py``).

Run them with ``./gradlew coverageAuditTest``, or directly with
``python3 tools/coverage/test_audit.py``. Only the standard library is needed,
which is also true of the tool itself.

**Why these exist.** ``audit.py`` decides whether the build is green, and it had
no test: its four rules were probed once by breaking them by hand, and a probe
leaves nothing behind. The hole that found was the tier interaction — running
``verifyAll`` left the heavier tiers' exec files on disk, and the next ``build``
then compared a tier-inclusive count against a baseline that is a claim about the
gate alone, asking for three budgets to be lowered to numbers that would have
failed the very next bare checkout. Each test below names the rule or the claim it
pins down.
"""

from __future__ import annotations

import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import audit  # noqa: E402


class RatchetCase(unittest.TestCase):
    """A temporary tree and baseline, so the rules are exercised against a fixture
    rather than against whatever this checkout happens to contain."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self._root, self._baseline = audit.ROOT, audit.BASELINE
        audit.ROOT = self.tmp.name
        audit.BASELINE = os.path.join(self.tmp.name, 'baseline.tsv')

    def tearDown(self):
        audit.ROOT, audit.BASELINE = self._root, self._baseline

    def baseline(self, **rows):
        """Writes a baseline of ``path -> budget``, creating each named file."""
        with open(audit.BASELINE, 'w', encoding='utf-8') as out:
            out.write('# fixture\n')
            for path, budget in rows.items():
                out.write('%d\t%s\n' % (budget, path))

    def touch(self, *paths):
        for path in paths:
            full = os.path.join(self.tmp.name, path)
            os.makedirs(os.path.dirname(full), exist_ok=True)
            open(full, 'w', encoding='utf-8').close()


class TheFourRules(RatchetCase):
    """Each rule fires against the gate's own report — `test` alone, no tiers."""

    def test_a_file_with_no_row_must_have_no_gap(self):
        self.baseline()
        self.touch('m/New.java')
        offenders, slack = audit.ratchet({'m/New.java': 1}, tiers=set())
        self.assertEqual(slack, [])
        self.assertIn('m/New.java', offenders[0])
        self.assertIn('budget 0', offenders[0])

    def test_a_file_with_a_row_must_not_exceed_it(self):
        self.baseline(**{'m/Old.java': 3})
        self.touch('m/Old.java')
        offenders, _ = audit.ratchet({'m/Old.java': 4}, tiers=set())
        self.assertIn('4 unjustified, budget 3', offenders[0])

    def test_a_file_below_its_row_is_asked_to_lower_it(self):
        self.baseline(**{'m/Old.java': 3})
        self.touch('m/Old.java')
        offenders, slack = audit.ratchet({'m/Old.java': 1}, tiers=set())
        self.assertEqual(offenders, [])
        self.assertIn('lower it to 1', slack[0])

    def test_a_file_with_no_gap_left_is_asked_for_the_row_to_go(self):
        self.baseline(**{'m/Done.java': 2})
        self.touch('m/Done.java')
        _, slack = audit.ratchet({}, tiers=set())
        self.assertIn('delete the row', slack[0])

    def test_a_row_naming_a_missing_file_fails(self):
        self.baseline(**{'m/Gone.java': 2})          # deliberately not created
        offenders, _ = audit.ratchet({}, tiers=set())
        self.assertIn('no such file', offenders[0])

    def test_a_file_exactly_at_its_row_is_green(self):
        self.baseline(**{'m/Old.java': 3})
        self.touch('m/Old.java')
        self.assertEqual(audit.ratchet({'m/Old.java': 3}, tiers=set()), ([], []))


class WhenATierHasRun(RatchetCase):
    """The counting rules are comparisons against a baseline that is a claim about
    the gate's configuration. A tier-inclusive report is not that report, and its
    numbers differ in both directions, so the comparisons are not made."""

    TIERS = {'integration'}

    def test_over_budget_is_not_reported(self):
        # A tier can *add* a gap: a condition inside a method that was not a gap
        # while nothing called the method at all.
        self.baseline(**{'m/Old.java': 3})
        self.touch('m/Old.java')
        self.assertEqual(audit.ratchet({'m/Old.java': 9}, tiers=self.TIERS), ([], []))

    def test_below_budget_is_not_reported(self):
        # The regression this file exists for: lowering the row to the tier's number
        # would fail the next bare checkout, so the tool must not ask for it.
        self.baseline(**{'m/Old.java': 7})
        self.touch('m/Old.java')
        self.assertEqual(audit.ratchet({'m/Old.java': 6}, tiers=self.TIERS), ([], []))

    def test_a_file_with_no_row_is_not_reported(self):
        self.baseline()
        self.touch('m/New.java')
        self.assertEqual(audit.ratchet({'m/New.java': 1}, tiers=self.TIERS), ([], []))

    def test_but_a_row_naming_a_missing_file_still_fails(self):
        # Rule 4 asks the filesystem, not the coverage report, so a tier says
        # nothing about it either way.
        self.baseline(**{'m/Gone.java': 2})
        offenders, _ = audit.ratchet({}, tiers=self.TIERS)
        self.assertIn('no such file', offenders[0])


class WhichTiersRan(RatchetCase):
    """Which tiers contributed is read off the exec files, which is the same
    question `jacocoAggregateReport` asked — not a guess from the numbers."""

    def test_no_exec_file_means_the_gate_alone(self):
        self.assertEqual(audit.tiers_in_report(), set())

    def test_an_exec_file_present_is_a_tier_counted(self):
        self.touch('relix-mongo-connector/build/jacoco/integrationTest.exec')
        self.assertEqual(audit.tiers_in_report(), {'integration'})

    def test_the_gate_s_own_exec_file_is_not_a_tier(self):
        self.touch('relix-ast/build/jacoco/test.exec')
        self.assertEqual(audit.tiers_in_report(), set())

    def test_every_tier_is_recognised(self):
        for tier in audit.TIERS:
            self.touch('m/build/jacoco/%sTest.exec' % tier)
        self.assertEqual(audit.tiers_in_report(), set(audit.TIERS))


class GapIdentity(unittest.TestCase):
    """A gap is keyed by what it *is*, never by its line number — and a register
    row is matched against that key with whitespace collapsed on both sides."""

    def test_a_branch_is_keyed_by_its_source_text(self):
        self.assertEqual(
            audit.key_of('branch', os.path.join(audit.ROOT, 'm/A.java'), 'if (a && b) {'),
            'm/A.java\tif (a && b) {')

    def test_a_run_of_whitespace_is_collapsed(self):
        # The defect this pins: a row whose condition holds a run of whitespace
        # matched nothing and was then reported stale, the tool asking for a
        # correct row to be deleted.
        self.assertEqual(
            audit.key_of('branch', os.path.join(audit.ROOT, 'm/A.java'),
                         'name.startsWith("  ")  &&  ok'),
            'm/A.java\tname.startsWith(" ") && ok')

    def test_a_method_is_keyed_by_name_without_its_descriptor(self):
        self.assertEqual(
            audit.key_of('method', os.path.join(audit.ROOT, 'm/A.java'),
                         'render(Ljava/lang/String;)V'),
            'm/A.java\trender')


if __name__ == '__main__':
    unittest.main(verbosity=2)
