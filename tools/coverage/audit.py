#!/usr/bin/env python3
"""Hold each file to its budget of coverage gaps that carry no recorded reason.

A gap here is narrower and more useful than "a line JaCoCo calls uncovered":

  * a **branch gap** is a line holding a `&&` or `||` where some arm has run and
    another has not.  The HTML report shows such a line as covered, which is why
    these accumulate silently — a condition with one arm exercised looks green.
  * a **method gap** is a method with zero covered instructions.

A gap is **justified** when register.tsv carries a reason for it, and unjustified
otherwise.  Most of the unjustified ones are defensible-but-unargued, so a gate at
zero would start red — which CLAUDE.md is explicit is the one thing a gate must not
do, a gate that starts red being one people learn to skip.  This is therefore a **ratchet** over baseline.tsv, holding each
file to the number it has today, by the four rules AstConstructionRatchetTest uses:

  1. a file with no row must have no unjustified gap — which is what stops new
     ones being written, a new file having a budget of zero without anyone
     deciding so;
  2. a file with a row must not exceed it;
  3. a file **below** its row fails, asking for the row to be lowered — the rule
     that makes "close what you touch" something the build records rather than
     something people remember, since a budget that only ratchets down cannot
     drift back up;
  4. a row naming a file that no longer exists fails.

Usage:
    python3 tools/coverage/audit.py [--report|--baseline] [<jacoco-xml>]

    --report     print every unjustified gap grouped by module and exit 0
    --baseline   print a fresh baseline.tsv to stdout (no gate)

IMPORTANT: run the coverage report first.  `./gradlew test jacocoAggregateReport`
is the gate's own configuration and what the baseline is a claim about; adding the
heavier tiers (`./gradlew verifyAll jacocoAggregateReport`) shows more truth and is
what you want for --report, since a tier that did not run makes its code look
untested and the register is not the place to record that.

Rules 1 to 3 compare a count against the baseline, so they hold only against the
report the baseline is a claim about.  A report that counted a heavier tier is not
that report, and the numbers in it are not comparable in *either* direction:
running a tier removes gaps (the method it covers) and adds them (a condition
inside that method, partly exercised, which was not a gap while nothing called it
at all).  So when a tier's exec file is present the three counting rules do not
run — the same conclusion --baseline already reaches when it refuses to be written
from such a report.  Rule 4 is a fact about the filesystem rather than about
coverage, so it runs either way.
"""
import collections
import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
DEFAULT_XML = os.path.join(ROOT, 'build/reports/jacoco/aggregate/jacocoAggregateReport.xml')
REGISTER = os.path.join(HERE, 'register.tsv')
BASELINE = os.path.join(HERE, 'baseline.tsv')

# The heavier test tiers, named as their exec files are (`ext.tieredTestTags` in the
# root build.gradle, suffixed `Test`). Which of them contributed to the report being
# read is a fact on disk rather than a guess: jacocoAggregateReport takes every exec
# file that exists, so an exec file present is a tier counted.
TIERS = ('integration', 'live', 'ml', 'benchmark')


def counter(el, kind):
    for c in el.findall('counter'):
        if c.get('type') == kind:
            return int(c.get('missed')), int(c.get('covered'))
    return 0, 0


def source_index():
    """Maps a JaCoCo `package/File.java` key to the real path on disk."""
    index = {}
    for base, dirs, files in os.walk(ROOT):
        dirs[:] = [d for d in dirs if d not in ('build', '.git')]
        if '/src/main/java/' not in base.replace(os.sep, '/') + '/':
            continue
        for f in files:
            if f.endswith('.java'):
                full = os.path.join(base, f)
                index.setdefault(full.replace(os.sep, '/').split('/src/main/java/')[1], full)
    return index


def warn_if_stale(xml_path, index):
    """Warns when the report is older than the code it claims to describe.

    A report on disk carries no record of what it was generated from, so a run
    against a stale one answers confidently about code that has since changed —
    the failure this grew out of named a package that had been renamed a
    fortnight earlier. Gradle orders the tasks so an in-build run cannot hit it;
    this covers the other way in, which is running the audit on its own."""
    report = os.path.getmtime(xml_path)
    newer = [p for p in index.values() if os.path.getmtime(p) > report]
    if not newer:
        return
    sys.stderr.write(
        "warning: %s is older than %d source file(s) it describes, so these "
        "numbers are stale.\n         Newest: %s\n         Refresh with: "
        "./gradlew test jacocoAggregateReport   (or verifyAll, for the tiers)\n"
        % (os.path.relpath(xml_path, ROOT),
           len(newer),
           os.path.relpath(max(newer, key=os.path.getmtime), ROOT)))


def gaps(xml_path):
    root = ET.parse(xml_path).getroot()
    index = source_index()
    warn_if_stale(xml_path, index)
    found = []
    for pkg in root.findall('package'):
        name = pkg.get('name')
        for sf in pkg.findall('sourcefile'):
            path = index.get(name + '/' + sf.get('name'))
            if not path:
                continue
            lines = open(path, encoding='utf-8').read().split('\n')
            for ln in sf.findall('line'):
                nr = int(ln.get('nr'))
                missed_b, covered_b = int(ln.get('mb')), int(ln.get('cb'))
                covered_i = int(ln.get('ci'))
                if missed_b == 0 or covered_i == 0 or nr > len(lines):
                    continue
                text = lines[nr - 1].strip()
                if '&&' not in text and '||' not in text:
                    continue
                found.append(('branch', path, nr, text, '%d/%d' % (missed_b, missed_b + covered_b)))
        for cls in pkg.findall('class'):
            path = index.get(name + '/' + cls.get('sourcefilename'))
            if not path:
                continue
            for m in cls.findall('method'):
                missed_i, covered_i = counter(m, 'INSTRUCTION')
                if covered_i or not missed_i:
                    continue
                found.append(('method', path, int(m.get('line') or 0),
                              m.get('name') + m.get('desc'), 'never called'))
    return found


def key_of(kind, path, text):
    """A gap's identity: its file plus what it *is*, never its line number.

    Lines move whenever anything above them is edited; a register keyed on line
    numbers would go stale on the first unrelated commit and quietly stop
    matching. A method is keyed by name, a branch by its own source text."""
    rel = os.path.relpath(path, ROOT).replace(os.sep, '/')
    if kind == 'method':
        return rel + '\t' + text.split('(')[0]
    return rel + '\t' + re.sub(r'\s+', ' ', text)


def read_register():
    entries = {}
    if not os.path.exists(REGISTER):
        return entries
    with open(REGISTER, encoding='utf-8') as rows:
        for raw in rows:
            if not raw.strip() or raw.lstrip().startswith('#'):
                continue
            parts = raw.rstrip('\n').split('\t')
            if len(parts) < 4:
                continue
            path, what, category = parts[0], parts[1], parts[2]
            reason = '\t'.join(parts[3:])
            # Collapsed exactly as key_of collapses a branch's source text, or a row
            # whose condition holds a run of whitespace — `startsWith("  ")` is the one
            # that found this — matches nothing, and is then reported as stale: the tool
            # asking for a correct row to be deleted. A method key has no whitespace
            # run, so this is a no-op for one.
            entries[path + '\t' + re.sub(r'\s+', ' ', what)] = (category, reason)
    return entries


def tiers_in_report():
    """Which heavier tiers contributed exec data to the report being read.

    `jacocoAggregateReport` takes every exec file that exists, so the presence of
    one *is* the tier being counted — this asks the same question the report asked,
    rather than inferring it from what the numbers look like."""
    return {tier for tier in TIERS
            if glob.glob(os.path.join(ROOT, '*', 'build', 'jacoco', tier + 'Test.exec'))}


def read_baseline():
    budgets = {}
    if not os.path.exists(BASELINE):
        return budgets
    with open(BASELINE, encoding='utf-8') as rows:
        for raw in rows:
            if not raw.strip() or raw.lstrip().startswith('#'):
                continue
            count, _, path = raw.rstrip('\n').partition('\t')
            budgets[path.strip()] = int(count.strip())
    return budgets


def ratchet(counts, tiers):
    """The four rules, as offender lines. Empty means green.

    Rules 1 to 3 are comparisons against the baseline, and the baseline is a claim
    about the gate's configuration — `test` alone. When `tiers` is non-empty the
    report being read is not that report, so those three are skipped rather than
    answered wrongly: a tier moves a file's count in *both* directions, removing the
    gap in a method it covers and adding one for a condition inside that method which
    was not a gap while nothing called it, so neither "over budget" nor "below budget"
    means what it says. Rule 4 is a fact about the filesystem, not about coverage, and
    runs either way."""
    budgets = read_baseline()
    offenders, slack = [], []
    for path in sorted(set(counts) | set(budgets)):
        if path in budgets and not os.path.exists(os.path.join(ROOT, path)):
            offenders.append('%s  — no such file; delete the row' % path)
            continue
        if tiers:
            continue
        actual, budget = counts.get(path, 0), budgets.get(path, 0)
        if actual > budget:
            offenders.append('%s  %d unjustified, budget %d' % (path, actual, budget))
        elif actual < budget:
            slack.append('%s  %d left, budget still says %d — lower it to %d%s'
                         % (path, actual, budget, actual,
                            ' (delete the row)' if actual == 0 else ''))
    return offenders, slack


def per_file(found, register):
    counts = collections.Counter()
    for kind, path, _, text, _ in found:
        if key_of(kind, path, text) not in register:
            counts[os.path.relpath(path, ROOT).replace(os.sep, '/')] += 1
    return counts


BASELINE_HEADER = """\
# Unjustified coverage gaps per file — a ratchet, not a register.
#
# A gap is a partly-exercised `&&`/`||` or a method with no covered instructions
# (audit.py's module docstring says why those two). register.tsv holds the ones
# with an argued reason; every remaining one is counted here, against the file it
# is in. audit.py holds each file to its row:
#
#   * a file with no row must have NO unjustified gap — this is what stops new
#     ones appearing, a new file having a budget of zero without anyone deciding;
#   * a file with a row must not exceed it;
#   * a file BELOW its row fails, asking for the row to be lowered — which makes
#     "close what you touch" a thing the build records rather than a thing people
#     remember. A budget that only ratchets down cannot drift back up;
#   * a row naming a missing file fails, the discipline register.tsv follows.
#
# So: when a gap goes, lower or delete the row in the same commit. There are two
# ways to spend a row down and they are not the same claim — write the test, or
# write a register.tsv row arguing the gap is not worth closing. The second is
# reviewed like any other claim ("hard to reach" is not a reason; "the grammar
# cannot express it" is), which is why this file records a number and register.tsv
# records prose.
#
# Regenerate with `python3 tools/coverage/audit.py --baseline`, and read the diff:
# every row that MOVES UP is a gap someone added, and the tool cannot tell that
# from a row someone lowered.
#
# Every number here is a claim about the gate's configuration, which is `test`
# alone. Running a heavier tier moves a file's count in BOTH directions — it covers
# the method, and uncovers a condition inside that method which was not a gap while
# nothing called it at all — so no single number holds on both a bare machine and a
# Docker-equipped one. audit.py therefore skips the three counting rules entirely
# when a tier's exec file is present, rather than comparing numbers that are not
# comparable; `./gradlew clean test jacocoAggregateReport` is what gates.
#
# A file register.tsv excuses through a TIER still keeps its row, and the gate holds
# it to that row like any other.
"""


def main(argv):
    args = [a for a in argv[1:] if not a.startswith('--')]
    report_only = '--report' in argv
    write_baseline = '--baseline' in argv
    xml_path = args[0] if args else DEFAULT_XML
    if not os.path.exists(xml_path):
        sys.exit("no coverage report at %s\n"
                 "run: ./gradlew test jacocoAggregateReport   (or verifyAll, for the tiers)"
                 % xml_path)

    register = read_register()
    found = gaps(xml_path)
    tiers = tiers_in_report()
    counts = per_file(found, register)
    justified = sum(1 for k, p, _, t, _ in found if key_of(k, p, t) in register)

    if write_baseline:
        if tiers:
            sys.exit("refusing to write a baseline from a report that includes the %s "
                     "tier(s).\nThe baseline is a claim about the gate's configuration, "
                     "which is `test` alone.\nRun: ./gradlew clean test "
                     "jacocoAggregateReport   then try again."
                     % ', '.join(sorted(tiers)))
        sys.stdout.write(BASELINE_HEADER)
        for path, n in sorted(counts.items()):
            print('%d\t%s' % (n, path))
        return 0

    # A tier:* row whose gap is absent is the *expected* state once that tier has
    # run — the method it excuses is covered. Reporting it as stale would make the
    # audit fail on exactly the machine that verified the most.
    stale = set(register) - {key_of(k, p, t) for k, p, _, t, _ in found}
    stale = {s for s in stale
             if not (register[s][0].startswith('tier:')
                     and register[s][0].split(':', 1)[1] in tiers)}

    if report_only:
        by_module = collections.defaultdict(list)
        for kind, path, nr, text, detail in found:
            if key_of(kind, path, text) in register:
                continue
            by_module[os.path.relpath(path, ROOT).split(os.sep)[0]].append(
                (path, nr, text, detail))
        for module in sorted(by_module, key=lambda m: -len(by_module[m])):
            print('\n%s  (%d unjustified)' % (module, len(by_module[module])))
            for path, nr, text, detail in sorted(by_module[module]):
                print('  %s:%d  [%s]  %s'
                      % (os.path.relpath(path, ROOT), nr, detail, text[:96]))

    print('\ncoverage gaps: %d total — %d justified, %d unjustified across %d file(s)'
          % (len(found), justified, sum(counts.values()), len(counts)))
    if report_only:
        return 0

    offenders, slack = ratchet(counts, tiers)
    if tiers:
        print('tiers counted: %s — the ratchet did not run: these counts are not the '
              'ones\nthe baseline is a claim about, and a tier moves a file both ways. '
              'To gate,\nrun: ./gradlew clean test jacocoAggregateReport coverageAudit'
              % ', '.join(sorted(tiers)))

    if offenders:
        print('\n%d file(s) over budget:' % len(offenders))
        for line in offenders:
            print('  ' + line)
        print('\nClose the gap with a test, or record a reason for it in '
              'tools/coverage/register.tsv.\nRaise the row in '
              'tools/coverage/baseline.tsv only with a reason in the commit — '
              'the\nratchet exists so that the number cannot climb quietly. '
              'Run with --report to list them.')
    if slack:
        print('\n%d file(s) now below budget:' % len(slack))
        for line in slack:
            print('  ' + line)
        print('\nLower the row — or delete it, if the file is done — in the same '
              'commit as the fix.\nThis is the ratchet: a budget nobody tightens '
              'is one that lets the next edit put the gap back.\nRegenerate with '
              '--baseline.')
    if stale:
        print('\n%d register entries no longer match a gap (the code changed — '
              'delete the row or re-justify it):' % len(stale))
        for s in sorted(stale):
            print('  ' + s.replace('\t', '  ::  '))

    return 1 if (offenders or slack or stale) else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
