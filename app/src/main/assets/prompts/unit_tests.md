You are tasked with creating or updating unit tests for this codebase. The goal is meaningful coverage of all key features and critical logic — not hitting an arbitrary percentage number, but ensuring that the behaviour the application promises is actually verified.

**Read the entire codebase thoroughly before writing any tests.** Understand what the application does, how it is structured, what the key user-facing features are, and what the critical internal logic is. Do not write tests for things that do not matter; prioritise ruthlessly.

---

**1. Audit Existing Tests First**

Before writing anything new:
- Map out what is already tested and what is not.
- Identify tests that are poorly written, fragile, or testing implementation details rather than behaviour — flag these for improvement.
- Identify gaps: key features or logic paths with no test coverage at all.
- Check that the test framework, runner, and configuration are set up correctly and working.

Present this audit before writing any new tests.

---

**2. Identify What Must Be Tested**

Prioritise test coverage in this order:

1. **Core business logic** — the functions and modules that implement the primary purpose of the application. If this breaks, the app is broken.
2. **Key user-facing features** — the main flows a user goes through. Happy path first, then edge cases.
3. **Data transformation and validation** — any code that processes, transforms, or validates input/output.
4. **Error handling** — confirm that the application fails gracefully and that error states are handled as expected.
5. **Integration points** — boundaries between modules, or between the app and external services (use mocks/stubs appropriately here).
6. **Utility functions** — only if they contain non-trivial logic. Do not write tests for one-liners that are obviously correct.

Do not write tests for:
- Third-party library behaviour (test your use of the library, not the library itself).
- Pure UI rendering with no logic (unless snapshot tests are already the established pattern).
- Trivial getters/setters with no logic.

---

**3. Rules for Writing Tests**

- **Test behaviour, not implementation.** Tests should describe what the code does, not how it does it internally. A refactor should not break tests unless the external behaviour changed.
- **One assertion per test where practical.** Tests should be focused and fail for exactly one reason.
- **Descriptive test names.** Every test name should complete the sentence: "it should..." — e.g. `it should return an empty array when no results are found`.
- **Arrange, Act, Assert.** Structure every test using this pattern for clarity.
- **Use mocks and stubs for external dependencies** (network calls, file system, databases, third-party APIs). Tests must be deterministic and not require external services to run.
- **Do not test with real credentials or production data.** Use fixtures and factory functions for test data.
- **Tests must be independent.** No test should depend on the outcome of another test or on shared mutable state.
- **Tests must be fast.** If a test takes more than a second, it probably needs to be rethought.

---

**4. If No Test Framework Exists Yet**

- Choose the most appropriate test framework for the language and ecosystem in use (e.g. Jest for JavaScript/TypeScript, pytest for Python, JUnit for Java, etc.).
- Set it up properly: install dependencies, configure the test runner, add an npm/make/gradle test script so tests can be run with a single command.
- Document the setup in a comment at the top of the test configuration file.

---

**5. After Writing Tests**

- Run the full test suite and confirm every new test passes.
- Confirm no existing tests have been broken.
- If you find a genuine bug while writing tests (i.e. the code does not behave as expected), **do not silently fix the bug**. Document it clearly and write a failing test that captures it, then flag it for a separate fix.

---

**6. Test Coverage Report**

When complete, produce a summary covering:
- Which features and modules now have test coverage.
- Which areas remain untested and why (acceptable gaps vs gaps that should be addressed).
- Any bugs or unexpected behaviours discovered while writing tests.
- Instructions for running the test suite locally.