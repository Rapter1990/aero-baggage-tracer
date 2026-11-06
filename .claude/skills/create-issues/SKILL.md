---
name: create-issues
description: Create the GitHub issues from docs/issues/GITHUB_ISSUES.md with create-issues.ps1 (dry run first, then confirm). Use when the backlog changed and issues must be created on GitHub.
---

Issues are created only through `docs/issues/create-issues.ps1`, never by hand-written `gh issue create` calls.

1. Check that `gh auth status` succeeds (portable gh lives in `%USERPROFILE%\tools\gh\bin`).
2. Dry run first and show the output to the user:
   `powershell -File docs/issues/create-issues.ps1 -DryRun`
   Titles must read `Issue #N : Title` and match the `## #N` headings in `GITHUB_ISSUES.md`.
3. Ask the user for confirmation. Creating issues is outward-facing and not easily undone.
4. Create them: `powershell -File docs/issues/create-issues.ps1`
   Options: `-Repo owner/name`, `-File <path to backlog>`.
5. Report the created issue URLs. Note: GitHub issue numbers can differ from backlog numbers because PRs share the numbering; `create-pr` resolves the issue by its `Issue #N :` title.

The script creates every section, so do not re-run it for issues that already exist.
