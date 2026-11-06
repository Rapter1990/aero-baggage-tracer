---
name: verify-project
description: Run the full local quality gate (format, build, unit and integration tests, coverage) and report pass/fail honestly.
---

1. `mvn -q spotless:check` - formatting must pass; fix with `mvn spotless:apply`.
2. `mvn -q verify` - compiles, runs Surefire and Failsafe, checks JaCoCo thresholds.
3. `git status` - confirm no secrets, no `target/`, no case-study PDF staged.
4. Report exact failures with output; never claim success for a step you did not run.
