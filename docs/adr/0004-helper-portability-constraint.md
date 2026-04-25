# ADR 0004 — Helper portability constraint

## Status

Accepted, applied to all helpers introduced in this fork.

## Context

This fork's roadmap involves multiple downstream targets:

1. The **immediate fix** (Sprint 9): pom.xml `<sourceDirectory>` and Eclipse `.classpath` reads.
2. **Workspace consolidation** (Sprint 10): `load_workspace` MCP tool, multi-project `WorkspaceManager`.
3. **Workspace-as-target-platform** (Sprint 11): `MANIFEST.MF` `Bundle-SymbolicName` / `Require-Bundle` parsing for inter-bundle PDE resolution.
4. **Possible future direction** (out of scope here): a separate Eclipse IDE plugin (running inside Eclipse, hooking deeper Eclipse/Equinox/PDE/m2e services) or an LSP-based standalone server that reuses the same project-discovery logic without inheriting javalens's RCP shape.
5. **Possible future direction**: an upstream PR back to `pzalutski-pixel/javalens-mcp`.

All five paths benefit from the same helpers being usable in different runtime contexts.

## Decision

Every project-metadata helper added in this fork is designed as a **small, dependency-light, static or near-static method** with the following constraints:

- **Inputs**: only `java.nio.file.Path`, `String`, primitive types, or simple value DTOs (records).
- **Outputs**: only `java.nio.file.Path`, `String`, primitive types, `Optional<...>` of those, `List<...>` / `Map<...>` of those, or simple value DTOs.
- **No JDT, OSGi, Eclipse Workspace, or RCP types in helper signatures.** No `IPath`, `IFile`, `IProject`, `IJavaProject`, `IClasspathEntry`, `IConfigurationElement`, `Bundle`, `IExtensionRegistry`, etc.
- **Parsing**: pure DOM (`javax.xml.parsers.DocumentBuilder`) for XML, `java.util.jar.Manifest` for OSGi manifests. No Eclipse `org.eclipse.core.resources` / `org.eclipse.jdt.internal` reads in the helper.
- **Stateless** where possible: a static method that takes a `Path` and returns a result. Per-instance state (e.g. logger field) is fine; per-instance state from JDT is not.

Helpers covered today:

- `readPomSourceDirs(Path pomXml) -> SourceDirs` (Sprint 9, ADR 0001).
- `readEclipseClasspath(Path projectRoot) -> ClasspathInfo` (Sprint 9, ADR 0001).
- `readManifestSymbolicName(Path projectRoot) -> Optional<String>` (Sprint 11, ADR 0003).
- `readManifestRequireBundle(Path projectRoot) -> List<String>` (Sprint 11, ADR 0003).

Helpers NOT covered (legitimately leak Eclipse types, used internally by `ProjectImporter`):

- `addSourceEntries`, `addDependencyEntries`, `configureJavaProject` — these speak `IClasspathEntry`/`IJavaProject` and live inside the Eclipse-bound importer. They consume the portable helpers.

## Consequences

**Positive.**

- Helpers can be lifted into a future Eclipse IDE plugin or LSP-based server without touching their bodies — the code is identical, only the call sites differ.
- Easier to unit-test: no Eclipse runtime needed for tests of the helpers themselves (only the `ProjectImporter` integration tests need Eclipse fixtures).
- Easier to upstream: a PR with self-contained helpers is reviewer-friendly and doesn't drag in cross-cutting type changes.

**Negative.**

- Some duplication: `getMavenDependencies` already reads `pom.xml` (via `mvn` shell-out + classpath file) but `readPomSourceDirs` reads the same `pom.xml` separately via DOM. Acceptable cost; consolidating is future cleanup that doesn't have to land before the helpers ship.
- The constraint adds a small mental tax when designing new helpers: "Could I lift this into an LSP server tomorrow?" If no, redesign.

**Neutral.**

- The constraint applies only to *project-metadata* helpers (parsers / readers). It does not apply to JDT-bound code such as classpath construction, AST walking, refactoring engines, or anything that legitimately needs JDT. Those stay where they are.

## Enforcement

Code review: any new helper method whose signature includes a JDT, OSGi, or Eclipse Workspace type triggers a discussion. If the type is necessary, place the method outside the "portable helpers" set in `ProjectImporter` (or in a different class). If the type isn't necessary, refactor.
