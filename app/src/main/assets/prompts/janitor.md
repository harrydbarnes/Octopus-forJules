Perform a thorough janitorial pass on this codebase. Your goal is to reduce tech debt by deleting, simplifying, and consolidating. Every line of code is potential debt. Prefer deletion over refactoring, and simplicity over abstraction.
Work through the following areas in order:
1. Dead code removal
Find and delete unused functions, variables, imports, and dependencies. Remove unreachable code paths, dead branches, commented-out code, and leftover debug statements.
2. Simplification
Replace complex patterns with simpler alternatives. Inline single-use functions and variables where it aids clarity. Flatten deeply nested conditionals and loops. Prefer built-in language features over custom implementations. Apply consistent naming and formatting throughout.
3. Dependency hygiene
Remove unused dependencies and imports. Flag outdated packages with known security vulnerabilities. Identify heavy dependencies that could be replaced with lighter alternatives. Consolidate where similar packages overlap.
4. Test cleanup
Delete obsolete, duplicate, or meaningless tests. Simplify overly complex test setup and teardown. Remove flaky tests. Consolidate overlapping scenarios. Note any critical paths that lack coverage.
5. Documentation and comments
Remove outdated, redundant, or auto-generated comments. Simplify verbose explanations. Delete inline comments that just restate the code. Update or remove stale links and references.
6. Infrastructure and config
Remove unused resources, environment variables, and configuration entries. Eliminate redundant deployment or automation scripts. Clean up hardcoded environment-specific values. Consolidate similar infrastructure patterns.
Execution approach:

Identify what is actually used before deleting anything
Remove changes incrementally and run tests after each meaningful change
Do not add new documentation or explanatory comments
Let the simplified code speak for itself

Apply the principle of "subtract to add value" throughout. The codebase should be smaller, clearer, and easier to maintain when you are done.
