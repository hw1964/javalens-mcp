# ADR 0001 — Source-resolution precedence

## Status

Accepted, implemented in `feature/source-resolution-and-workspace`.

## Context

`ProjectImporter.addSourcePathsFromDirectory` discovers source roots by walking a hardcoded `SOURCE_MAPPINGS` array (`src/main/java`, `src/test/java`, `src/main/kotlin`, `src/test/kotlin`) and falling back to `<root>/src/` if none of those exist.

This breaks for two real-world layouts:

1. **Maven projects with `<sourceDirectory>` overrides.** Maven lets a `pom.xml` declare `<build><sourceDirectory>custom/path</sourceDirectory></build>` to point at a non-standard source folder. Tycho-built Eclipse PDE bundles converted to Maven, hybrid Maven+PDE setups, and legacy Maven projects all use this. The current heuristic ignores the override entirely.

2. **Eclipse projects with non-conventional `.classpath` source folders.** Every Eclipse JDT project ships a `.classpath` file declaring source/output/lib folders via `<classpathentry kind="src" path="...">`. javalens has JDT loaded but does not read this file — it rebuilds the classpath from heuristics and ignores the user's actual project metadata. Pure-Eclipse projects only "work" today when their layout coincidentally matches the heuristic (`<root>/src/`).

A concrete trigger: a hybrid Maven+PDE project where pom.xml at the project root declares `<sourceDirectory>strategies/src</sourceDirectory>` and `.classpath` lives one level deeper. javalens loads the Maven root, the heuristic finds 55 files at `src/test/java`, and the 49 production files under `strategies/src/` are silently invisible. Search and refactoring tools return zero results for production code.

## Decision

`addSourcePathsFromDirectory` honors source-discovery precedence:

1. **pom.xml override.** If `pom.xml` declares `<build><sourceDirectory>` and/or `<build><testSourceDirectory>`, those paths win. Single XPath read, resolved relative to the pom's directory.
2. **Eclipse `.classpath`.** If a `.classpath` file exists at the project root, all `<classpathentry kind="src">` entries become source folders.
3. **Heuristic fallback.** Existing `SOURCE_MAPPINGS` walk + `<root>/src/` fallback (unchanged).

Earlier rules win; `.classpath` is consulted only if pom.xml had no override. Heuristic fires only if neither yielded source paths.

`addDependencyEntries` is also extended: when `.classpath` declares `<classpathentry kind="lib">` entries, those jars are added to the classpath alongside the build-system-resolved deps. For pure-Eclipse projects without a pom.xml, `.classpath` `kind="lib"` entries become the entire dependency set, removing the need to shell out to `mvn dependency:build-classpath` for projects that have no Maven wiring.

## Consequences

**Positive.**

- Hybrid Maven+PDE projects (the trigger case) work correctly: production source under `<sourceDirectory>` is indexed.
- Pure-Eclipse projects with non-conventional source layouts work correctly without further configuration.
- Pure-Eclipse projects without a pom.xml no longer need Maven installed for dependency resolution — `.classpath` `kind="lib"` entries cover that.
- The helpers (`readPomSourceDirs`, `readEclipseClasspath`) are pure DOM, no JDT in their signatures, so they're portable to a future Eclipse IDE plugin or LSP server (see ADR 0004).

**Negative.**

- One more file (`pom.xml` and `.classpath`) parsed per project at load time. Cost: a few ms per project; negligible.
- pom.xml parsed twice if the project also goes through `getMavenDependencies` (once for `<sourceDirectory>`, once via the `mvn` shell-out). Acceptable; consolidating is future cleanup.
- The PDE `<classpathentry kind="con" path="org.eclipse.pde.core.requiredPlugins"/>` container is **not** expanded here. For projects relying on a target-platform-resolved `Require-Bundle`, those deps remain unresolved through this change. ADR 0003 (workspace-as-target-platform, Sprint 11) addresses the inter-bundle case via `MANIFEST.MF` parsing across the workspace.

**Neutral.**

- Existing tests for the heuristic walk continue to pass — the heuristic is now a fallback, but its behavior is unchanged for projects that have no pom.xml override and no `.classpath`.

## Compatibility

No breaking change. Projects that worked under the heuristic continue to work. Projects that previously failed because of `<sourceDirectory>` or `.classpath` now succeed. No MCP tool API changed.
