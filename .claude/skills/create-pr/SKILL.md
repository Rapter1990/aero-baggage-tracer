---
name: create-pr
description: Open the pull request for the current feature/<N>/<slug> branch with create-pr.ps1 (title "PR#N : Title", body with "Closes #<issue>"). Use after implement-feature has committed and pushed.
---

Pull requests are opened only through `docs/issues/create-pr.ps1`.

1. Be on a branch named `feature/<N>/<slug>` where `<N>` is the backlog number in `GITHUB_ISSUES.md`; commit and push it first (`git push -u origin HEAD`).
2. Dry run first and show the title and body to the user:
   `powershell -File docs/issues/create-pr.ps1 -DryRun`
   Expect title `PR#<N> : <issue title>` and body containing `Closes #<GitHub issue number>`, commits and acceptance criteria.
3. Ask for confirmation, then run: `powershell -File docs/issues/create-pr.ps1`
   Options: `-Draft`, `-Base main`, `-Branch <name>`, `-Label <label>`, `-Repo owner/name`.
4. If it fails to find the issue, the matching `Issue #<N> : ...` must exist on GitHub (see `create-issues`).
5. Report the PR URL. Never merge the PR yourself.
