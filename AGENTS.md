# AGENTS.md

## Purpose
This repo packages a modified `proxyt` server into an Android app for local hotspot use. Most changes touch the Android app, the bundled Go server, or the build/deploy path between them.

## Default Workflow
1. Start from `origin/main`, not local `main`.
2. Always `git fetch origin main` before starting a new task branch.
3. Create the task branch from `origin/main` before creating the worktree.
4. Create a dedicated git worktree for that branch.
5. Make changes inside that worktree only.
6. Verify locally before pushing.
7. When implementation is complete, push the branch and open or update a PR against `main` immediately.
8. Continue monitoring the PR until all checks finish green, and keep working on the same branch until they do.
9. If more work is needed later, continue on the same branch and update the existing PR rather than starting a second branch or PR for the same worktree.
10. If `origin/main` moves, fetch, rebase the branch onto `origin/main`, and force-push with lease.
11. Do not merge `main` or `origin/main` into a task branch. Keep PR branches linear by rebasing only.

## Branch And Worktree Pattern
- Prefer branch names like `codex/<topic>-<date>` or another short task-specific name.
- Prefer sibling worktrees in the workspace root, for example:
  - `hotspot-tailscale-proxy-tabs-ui`
  - `hotspot-tailscale-proxy-healthcheck-fix`
- Do not do feature work directly on the root `main` worktree.

## Typical Commands
Create a worktree:
```bash
git fetch origin main
git branch codex/<topic> origin/main
git worktree add ../hotspot-tailscale-proxy-<topic> codex/<topic>
```

Rebase an existing branch:
```bash
git fetch origin main
git rebase origin/main
git push --force-with-lease origin <branch>
```

Do not use merge-based update flows such as `git merge origin/main` on a task branch.

## Android Build Requirements
- Use JDK 17 for Gradle:
```bash
export JAVA_HOME="/Library/Java/JavaVirtualMachines/jdk-17.jdk/Contents/Home"
```
- Set Android SDK variables:
```bash
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
```

## Verification
Run these for Android app changes:
```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug :app:assembleDebug
```

Notes:
- The Go proxy binary is built through Gradle as part of the Android build.
- For changes limited to `upstream-proxyt`, also run:
```bash
cd upstream-proxyt
go test ./...
```

## Deploy To Phone
With a connected Android device and USB debugging enabled:
```bash
./scripts/install-android-debug.sh
~/Library/Android/sdk/platform-tools/adb shell am start -n io.proxyt.hotspot.debug/io.proxyt.hotspot.MainActivity
```

Useful checks:
```bash
~/Library/Android/sdk/platform-tools/adb devices -l
~/Library/Android/sdk/platform-tools/adb logcat -d -t 300
~/Library/Android/sdk/platform-tools/adb shell run-as io.proxyt.hotspot.debug cat files/proxyt.log
~/Library/Android/sdk/platform-tools/adb shell run-as io.proxyt.hotspot.debug cat shared_prefs/proxy_preferences.xml
```

## PR Workflow
- Once implementation in a worktree is done, push the task branch to `origin` and open or update its PR against `main` right away.
- Keep watching the PR checks until they all complete successfully. If a check fails, keep iterating on the same branch and PR until the full check set is green.
- If you return to the same worktree later, keep committing on the same branch and update the same PR until that worktree is merged or abandoned.
- If the PR goes stale or shows conflicts, fetch `origin/main`, rebase the branch onto it, and `git push --force-with-lease`.
- Never resolve PR drift by merging `main` into the branch.
- GitHub also runs `.github/workflows/pr-auto-rebase.yml` after pushes to `main` to rebase stale same-repo PR branches automatically. That workflow must use the `PR_AUTOREBASE_TOKEN` secret so its force-pushes trigger the normal PR checks. Fork PRs are skipped, and conflicted rebases still require a local manual rebase.
- Include:
  - a short summary
  - verification commands actually run
  - any device deployment notes if relevant

Example:
```bash
gh pr create --repo adzienis/hotspot-tailscale-proxy --base main --head adzienis:<branch>
```

## Repo-Specific Notes
- `IMPLEMENTATION_PLANS.md` is currently untracked in the root worktree; do not accidentally include it unless explicitly requested.
- This repo often has multiple active worktrees. Check `git worktree list --porcelain` before creating another one.
- When debugging hotspot behavior, prefer distinguishing:
  - local loopback reachability on the phone
  - advertised hotspot URL reachability from other devices
- Do not assume those are equivalent on Android hotspot interfaces.
