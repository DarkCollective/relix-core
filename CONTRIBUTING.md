# Contributing to Relix

Thank you for considering a contribution. This page is everything you need to
produce a pull request that passes, and it is the only statement of these
conventions you will need — nothing here points anywhere you cannot read.

Everyone taking part in the project is expected to follow the
[code of conduct](CODE_OF_CONDUCT.md).

## Licensing of contributions

Relix is licensed under the [Apache License 2.0](LICENSE.txt). **Any contribution
you intentionally submit for inclusion is licensed under the same terms**, as
section 5 of that licence provides, with no additional terms or conditions. There
is no separate contributor licence agreement.

By opening a pull request you confirm that you have the right to submit the work
under that licence — that it is your own, or that you have permission to
contribute it — and that it carries no code under a licence incompatible with
Apache 2.0.

## How a pull request is merged

This repository is published from a larger one, and every file here is
regenerated on each publication. A pull request is therefore **not merged with
the merge button**: once it is reviewed and approved, a maintainer applies the
change upstream, and it arrives here with the next publication. Your authorship
is kept in the commit, and the pull request is closed with a link to it.

That changes nothing about how you prepare the change — open it against `main`
as usual.

## Before you start

For anything larger than a small fix, open an issue first and describe what you
intend. A language change in particular touches the grammar, the analyser, the
planner and a reference page, and agreeing on the shape before the code is
written saves everyone a rewrite.

**Security problems are the exception**: never report one in an issue. See
[`SECURITY.md`](SECURITY.md).

## The toolchain

**Java 21, and nothing else.** The build uses a Gradle toolchain pinned to 21.
Please do not change it, add `--enable-preview`, or add a `gradle.properties`
overriding the toolchain path — a pull request that does will be asked to drop
it. If your machine lacks Java 21, Gradle can provision it for you.

## The gate

```bash
./gradlew clean build
```

must be green before you open a pull request. It needs no Docker, no network and
no database, and it runs:

- compilation and the fast, hermetic tests;
- **both manuals' guards** — every example in `docs/reference` is parsed,
  analysed and, for worked examples, executed against the output the page prints;
  every Java snippet in `docs/guide` is compiled, run and its printed output
  compared;
- Javadoc, where a broken `{@link}` is an error;
- the **coverage ratchet** (below).

CI runs the same gate on every pull request, plus the Docker-backed integration
tier. A green run there is real evidence, but run the gate locally first — it is
much faster to fix a failure before review than after.

### Test tiers

Some suites need something a clean checkout does not have, so they are tagged and
kept out of the gate. You will see them skipped, and that is expected:

| Tier | Needs | Run with |
|---|---|---|
| `integration` | Docker (MongoDB, MySQL, Postgres containers) | `./gradlew integrationTest` |
| `live` | the network | `./gradlew liveTest` |
| `benchmark` | time | `./gradlew benchmarkTest` |

`./gradlew verifyAll` runs the gate and every tier the machine can support;
container tests skip rather than fail without a Docker daemon. If your change
touches a connector or SQL/MongoDB pushdown, please run `integrationTest` too.

## Branches and commits

Work on a branch named with a prefix — `feat/`, `fix/`, `docs/`, `chore/` or
`refactor/` — followed by a short description. Write commit messages that say
what changed and why.

## Tests ride with the change

A change is not complete without tests, and coverage should stay as close to
complete as reasonably possible.

- **The coverage ratchet** (`./gradlew coverageAudit`, part of `check`) records
  how many uncovered branches and methods each file has in
  `tools/coverage/baseline.tsv`, and the number may only fall. A file with more
  gaps than its row fails; a file with *fewer* fails too, asking you to lower the
  row — commit that change with your work.
- **A gap you believe is not worth closing** goes in
  `tools/coverage/register.tsv` with a reason. "Hard to reach" is not a reason;
  "the grammar cannot express it" is.
- **Use the published assertions** rather than hand-rolling casts. Each layer's
  test fixtures provide one: `AstAssertions`, `SymbolAssertions`,
  `SemanticAssertions`, `PlanAssertions`, `OptimizerAssertions`,
  `ProcessorAssertions`, `EmbedAssertions`. Their failures print the tree, plan or
  result that disagreed, and a build-time guard rejects the hand-written shapes
  they replaced.
- **Build ASTs with the factories** — `AstBuilders`, `Expr`, `ScriptBuilders` —
  rather than writing your own; another guard rejects a duplicate factory.

`./gradlew coverageReport` lists the current gaps.

## Documentation rides with the change

- **A language change needs its reference page.** A new operator, predicate,
  function or keyword gets a page under `docs/reference/`, registered in
  `docs/reference/README.md`; a change to existing behaviour updates the page that
  describes it. Non-trivial concepts need a fully worked example, whose output
  must be pasted from a real run — the build executes it and compares.
- **A new API surface needs its guide page** in `docs/guide/`.
- **Public classes need Javadoc**, and every module and package is documented.
- **Write a rejection as code.** To say something does *not* parse, use a
  ` ```relix-invalid ` fence rather than a sentence — the build then fails if the
  grammar ever accepts it.
- **The published docs describe what is, not what might be.** Javadoc and both
  manuals must not promise future work ("not yet supported"); state a limitation
  as a fact instead. A guard enforces this.

`./gradlew guides` runs only the documentation guards, which is the quick loop
while writing docs.

## Adding an operator

The core hierarchies are sealed, so the compiler walks you through most of the
places a new operator must be handled. [`ARCHITECTURE.md`](ARCHITECTURE.md)
describes the modules in dependency order; a new operator typically needs an AST
node and its builder, a parse rule, schema inference and validation, cost and
property derivation, a physical plan node, an executor, and its reference page.
The steps a `switch` cannot force — the `AstBuilders` factory and the reference
page — are each checked by a test.

## Questions

Ask in [Discussions](https://github.com/DarkCollective/relix-core/discussions).
There is no question too small, and one asked before the code is written is the
cheapest kind.
