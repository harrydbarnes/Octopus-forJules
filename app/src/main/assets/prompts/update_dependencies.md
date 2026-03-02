You are tasked with auditing and updating all dependencies in this project. Your primary objective is to modernise dependencies while guaranteeing nothing breaks — including but not limited to APK generation, web builds, publishing pipelines, CI/CD workflows, and any platform-specific tooling.

Follow these steps carefully:

**1. Audit First**
- List every dependency (direct and transitive) along with the current version and latest stable version.
- Identify which dependencies are outdated, deprecated, or have known security vulnerabilities (cross-reference CVE databases where possible).
- Flag any dependencies that are no longer maintained or have recommended replacements.

**2. Compatibility Analysis Before Touching Anything**
- Before updating any package, check the changelog and release notes for every major/minor version bump between the current and target version.
- Identify any breaking changes, deprecated APIs, or peer dependency conflicts.
- Pay special attention to:
  - Build tooling (Gradle, Maven, Webpack, Vite, etc.) — version mismatches here commonly break APK generation or web publishing.
  - SDK/platform version constraints (Android SDK, iOS deployment targets, Node engine fields, Java version).
  - Peer dependency chains — if updating Package A requires updating Package B, trace the full chain before committing.
  - Lock files (package-lock.json, yarn.lock, pubspec.lock, Gemfile.lock, etc.) — ensure these are regenerated correctly after updates.

**3. Update Strategy — Incremental and Safe**
- Prefer updating to the latest patch version first, then minor, then major — one step at a time.
- For any major version bump, isolate the change in its own commit with a clear message explaining what changed and why.
- Do not batch multiple major version bumps into a single change.
- If a dependency cannot be safely updated without significant refactoring, document it clearly and leave it at its current version with a comment explaining why.

**4. Verify Build Integrity After Each Significant Update**
- After updating, run the full build pipeline and confirm:
  - The project compiles without errors or warnings introduced by the updates.
  - APK generation (if applicable) completes successfully and the output is valid.
  - Web build/publish pipeline (if applicable) runs without errors.
  - All existing tests pass.
  - No new lint errors or type errors have been introduced.

**5. Document Every Change**
- Produce a clear summary of every dependency that was updated, including: package name, old version, new version, reason for update, and any code changes required as a result.
- If any dependency was intentionally left at its current version, explain why.
- If any dependency was removed and replaced, document the replacement and migration steps taken.

**Constraints:**
- Do not introduce new dependencies unless they are direct replacements for removed ones.
- Do not change any application logic, UI, or behaviour as part of this task.
- Preserve all existing functionality exactly as it was before.
- If in doubt about a change, err on the side of caution and document the uncertainty rather than proceeding blindly.