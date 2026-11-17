---
name: implement-feature
description: Implement one GitHub issue end to end on a feature branch - plan, build in small steps, test, verify, commit.
---

1. Read the issue in `docs/issues/GITHUB_ISSUES.md`; confirm its dependencies are done.
2. Branch `feature/<issue>/<slug>` from `main`.
3. List the BR-xx and AT-xx the issue owns (see `docs/requirements/TRACEABILITY.md`) and read their exact wording in `docs/requirements/`. Each one gets a test whose `@DisplayName` starts with its id (e.g. `AT-09 ...`); a rule or scenario without a test is reported as open, not ticked.
4. Plan the smallest vertical slice; use `principal-engineer`'s delegation map for specialists.
5. Write the failing test first (`test-engineer`), then the code; keep each commit Conventional-Commits formatted.
6. Run the `verify-project` skill; review with `code-reviewer` (and `security-reviewer` when auth/PII is touched).
7. Fill the "Test" column of `docs/requirements/TRACEABILITY.md` for the BR/AT you covered, tick the issue's acceptance criteria, then open the PR with the `create-pr` skill (`docs/issues/create-pr.ps1`) (title `PR#<n> : <issue title>`, body filled from the issue and `Closes #<github issue>`). Use `-DryRun` to preview.
