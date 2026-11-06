---
name: implement-feature
description: Implement one GitHub issue end to end on a feature branch - plan, build in small steps, test, verify, commit.
---

1. Read the issue in `docs/issues/GITHUB_ISSUES.md`; confirm its dependencies are done.
2. Branch `feature/<issue>/<slug>` from `main`.
3. Plan the smallest vertical slice; use `principal-engineer`'s delegation map for specialists.
4. Write the failing test first (`test-engineer`), then the code; keep each commit Conventional-Commits formatted.
5. Run the `verify-project` skill; review with `code-reviewer` (and `security-reviewer` when auth/PII is touched).
6. Tick the issue's acceptance criteria, then open the PR with `docs/issues/create-pr.ps1` (title `PR#<n> : <issue title>`, body filled from the issue and `Closes #<github issue>`). Use `-DryRun` to preview.
