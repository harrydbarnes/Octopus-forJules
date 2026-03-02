You are tasked with performing a single, focused refactor on this codebase. The guiding principle is: one thing, done properly, with zero regressions.

Do not attempt to refactor everything at once. A narrow, well-executed refactor that leaves the codebase in a provably better state is far more valuable than a sweeping change that introduces subtle bugs.

---

**1. Survey the Codebase First**

Read the entire codebase before making any decisions. As you read, take note of:

- Areas with high complexity (deeply nested logic, long functions, large files doing too many things).
- Architectural inconsistencies (e.g. some modules following a pattern that others ignore).
- Code that is difficult to test because of tight coupling or mixed concerns.
- Any existing TODO or FIXME comments that hint at known technical debt.
- Performance bottlenecks that could be addressed structurally.

---

**2. Choose One Thing to Refactor**

Select the single highest-value refactoring target. Prioritise based on:

- Which change would most improve maintainability or readability?
- Which change would make the code easiest to extend in future?
- Which change has the clearest scope with the lowest risk of unintended side effects?

State your chosen refactor clearly before proceeding. Include:
- What you are refactoring and where it lives in the codebase.
- Why this is the highest-value target.
- What the code looks like before, and what it will look like after (at a high level).
- What risks exist and how you plan to mitigate them.

Wait for confirmation before proceeding if you are operating in an interactive context. If running autonomously, document this decision clearly at the top of your output.

---

**3. Rules for the Refactor**

- **Scope is fixed.** Once you have chosen your target, do not expand the scope mid-task. If you discover adjacent issues, document them for a future refactor but do not act on them now.
- **Behaviour must be preserved exactly.** The application must work identically after the refactor. If you cannot guarantee this, do not make the change.
- **All public interfaces must remain stable.** Exported functions, component APIs, REST endpoints, event names — none of these may change signature or behaviour.
- **Incremental commits.** Break the refactor into the smallest logical steps possible, each with a descriptive commit message. Do not make one giant change.
- **Update tests alongside the code.** If you move or rename something, update all test files to match. Do not delete tests.
- **Update internal documentation.** If any inline comments, JSDoc/docstrings, or architectural notes describe the code you are changing, update them to reflect the new structure.

---

**4. Verification**

After completing the refactor:

- Run the full test suite. All tests must pass.
- Run the full build pipeline. It must complete without errors.
- Confirm no new lint or type errors have been introduced.
- Do a final side-by-side comparison of the before and after to confirm no unintended changes crept in.

---

**5. Refactor Report**

Produce a concise report covering:
- What was refactored and why it was chosen.
- A summary of every file changed.
- Any follow-up refactor candidates identified during the process (for future tasks).
- Confirmation that all tests and builds passed post-refactor.