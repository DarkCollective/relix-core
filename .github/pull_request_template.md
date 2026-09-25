<!--
Thanks for contributing. Pull requests target the `develop` branch, not `main`;
`develop` is promoted to `main` in batches. See CONTRIBUTING.md.
-->

## What and why

<!-- What does this change, and what problem does it solve? Link the issue: "Fixes #123". -->

## Checklist

- [ ] `./gradlew clean build` passes locally
- [ ] Tests cover the change, and any coverage baseline rows that fell are lowered
- [ ] A language change has its `docs/reference` page; a new API has its `docs/guide` page
- [ ] If it touches a connector or pushdown, `./gradlew integrationTest` passes (needs Docker)
- [ ] I have the right to submit this work, and it is contributed under the Apache License 2.0
