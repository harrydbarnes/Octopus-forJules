You are tasked with simplifying the codebase by applying DRY (Don't Repeat Yourself) principles and reducing overall code size. Your singular constraint is this: the application must behave identically before and after every single change you make.

**Read the entire codebase before touching anything.** Map out the structure, understand the patterns in use, and identify duplication before writing a single line.

---

**1. Identify Candidates for Simplification**

Look for the following and list them all before making any changes:

- Duplicated logic or near-identical code blocks that appear in two or more places.
- Functions or methods that do the same thing with minor variations that could be parameterised.
- Utility code that has been copy-pasted across files instead of being extracted into a shared module.
- Overly verbose code that can be expressed more concisely using language features (e.g. list comprehensions, ternaries, built-in methods) without sacrificing readability.
- Dead code: unused functions, variables, imports, commented-out blocks, and unreachable branches.
- Config or constant values hardcoded in multiple places that should live in a single source of truth.
- Deeply nested logic that can be flattened using early returns or guard clauses.

Present this audit as a structured list grouped by category before proceeding.

---

**2. Rules for Every Change**

- **One change at a time.** Do not batch multiple simplifications into a single commit or edit. Each logical simplification is its own isolated change.
- **No behaviour changes.** Simplifying code must not alter what the code does. If you are unsure whether a change is purely cosmetic/structural, do not make it.
- **No new abstractions for their own sake.** Only extract shared logic if it is genuinely used in two or more places, or if the extraction makes the code meaningfully clearer.
- **Preserve all public interfaces.** Function signatures, exported names, API endpoints, and component props must remain identical.
- **Do not rename things purely for style.** Renaming variables or functions is only acceptable if the current name is actively misleading.
- **Remove dead code only if you are certain it is unreachable or unused.** Use static analysis where possible. If in doubt, leave it and flag it.

---

**3. After Each Change**

- Run the full test suite and confirm all tests pass.
- Run the build pipeline and confirm it completes without errors.
- Confirm no new lint or type errors have been introduced.

---

**4. Output a Simplification Report**

When complete, produce a summary with:
- Total lines of code removed (net).
- Each simplification made: what was changed, where, and why.
- Any candidates that were identified but not acted on, with a brief explanation of why they were left alone.
- Any dead code that was flagged but not removed due to uncertainty.
