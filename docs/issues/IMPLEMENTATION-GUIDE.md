# Implementation guide: one issue, step by step

The commands for taking one backlog issue from the backlog to an open pull request. `<N>` is the backlog number in [GITHUB_ISSUES.md](GITHUB_ISSUES.md), `<slug>` a short kebab-case name, and `<GitHub issue>` the number GitHub gave the issue (it can differ from `<N>`, because PRs share the numbering). The skills are described in the [README](../../README.md#implementation-procedure).

## Steps

| Step | Command | What it does |
|---|---|---|
| 1 | `powershell -File docs/issues/create-issues.ps1 -DryRun -Only <N>` | Preview the issue; the title must read `Issue #<N> : Title` |
| 2 | `powershell -File docs/issues/create-issues.ps1 -Only <N>` | Create the issue on GitHub (outward-facing: confirm first) |
| 3 | `git fetch origin main` then `git checkout --no-track -b feature/<N>/<slug> origin/main` | Branch from the latest main |
| 4 | `/analyze-case <N>` | Exact BR-xx and AT-xx wording, owning issue, coverage gaps |
| 5 | Settle the open decisions the analysis found | Update the issue text in `GITHUB_ISSUES.md` before any code |
| 6 | `/architecture-review` | Design review, before the build and again on the finished diff |
| 7 | `/implement-feature <N>` | Plans, writes the failing tests first, then the code; names each test by its BR/AT id; fills the Test column in [TRACEABILITY.md](../requirements/TRACEABILITY.md) |
| 8 | `/verify-project` | Format and test gate (below) |
| 9 | `/run` | Start the stack and smoke-test through the gateway |
| 10 | `/code-review` | Review the diff against main; add `security-reviewer` when auth or PII is touched |
| 11 | commit and push | Conventional Commits (below) |
| 12 | `powershell -File docs/issues/create-pr.ps1 -DryRun` | Title `PR#<N> : <issue title>`, body with `Closes #<GitHub issue>` |
| 13 | `powershell -File docs/issues/create-pr.ps1` | Open the PR once the preview is confirmed |

Never merge the PR from the session.

### Format and test gate (step 8)

```powershell
mvn -q spotless:check
mvn -q verify
```

If Spotless fails, run `mvn -q spotless:apply` and check again. Run the gate after every meaningful change and before each push.

### Commit and push (step 11)

```powershell
git add -A
git commit -m "feat(<module>): <what changed>"
git push -u origin HEAD
```

The first push needs `-u`: a branch created with `--no-track` has no upstream yet.

## Notes

- `gh` lives in `%USERPROFILE%\tools\gh\bin`; add it to `PATH` if it is not found.
- Issues are created only through `create-issues.ps1`. Never re-run it for issues that already exist; use `-Only <N>`.
- A BR or AT without a test is reported as open, not ticked.
