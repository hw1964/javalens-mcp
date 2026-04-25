# Architecture (fork notes)

This document captures the architecture as seen from the fork (`hw1964/javalens-mcp`). It is a living overview, not exhaustive — read alongside the upstream `README.md` and `CHANGELOG.md` for project-wide context.

## Repository layout

Tycho-built Eclipse RCP product. Modules:

- `org.javalens.target` — target platform (p2 IUs that the RCP product depends on).
- `org.javalens.core` — core Java analysis services (JDT-backed). Contains `ProjectImporter`, `WorkspaceManager`, `JdtServiceImpl`, `SearchService`, `PathUtilsImpl`. Source path: `org.javalens.core/src/`.
- `org.javalens.core.tests` — JUnit 5 tests for `org.javalens.core`. Includes `TestProjectHelper` fixture with sample projects under `test-resources/sample-projects/`.
- `org.javalens.mcp` — MCP server: registers analysis tools (`SearchSymbolsTool`, `FindReferencesTool`, `LoadProjectTool`, etc.) and the JSON-RPC transport. Source path: `org.javalens.mcp/src/`.
- `org.javalens.mcp.tests` — JUnit tests for `org.javalens.mcp`.
- `org.javalens.launcher` — small launcher wrapper jar.
- `org.javalens.product` — Tycho product definition; produces platform-specific RCP zips/tarballs in `target/products/org.javalens.product/<platform>/<arch>/javalens/`.

The product launches Equinox + the bundled plugins from `org.eclipse.*` and `org.javalens.*`.

## Build

`./mvnw clean verify` from the repo root runs the full Tycho build across all modules. CI workflow `.github/workflows/ci.yml` runs this on Linux/macOS/Windows for PRs to `master`. Release workflow `.github/workflows/release.yml` triggers on `v*` tags, packages `javalens-<tag>.tar.gz` and `.zip` from the Linux x86_64 product output, and publishes a GitHub Release.

## ProjectImporter discovery flow

`ProjectImporter.configureJavaProject(IProject, Path, WorkspaceManager)` is the entry point when an MCP `load_project` request arrives.

1. `JavaCore.create(project)` produces an `IJavaProject`.
2. JRE container entry added.
3. `addSourceEntries` calls `getAllSourcePaths(projectPath)` which:
   - Calls `addSourcePathsFromDirectory(projectPath, ...)` for the root.
   - If `isMultiModuleProject` (pom.xml contains `<modules>` or `<packaging>pom</packaging>`), iterates `getModules(...)` and adds each module's source paths.
   - Bazel fallback if nothing found.
4. For each source path, `WorkspaceManager.createLinkedFolder` creates an Eclipse linked folder pointing at the external dir; the linked folder is what's added to the classpath. This keeps `.metadata` in `<data_root>/workspaces/...` and avoids polluting the user's source tree.
5. `addDependencyEntries` runs build-system-specific resolution: Maven shells out `mvn dependency:build-classpath`; Gradle reads `build/classes/java/{main,test}`; Bazel scans `bazel-{bin,out}` for jars.
6. `target/classes`, `target/test-classes`, `build/classes/java/{main,test}` always added if present.

## Source-discovery gap (this fork's first fix)

`addSourcePathsFromDirectory` walks a hardcoded `SOURCE_MAPPINGS` array (`src/main/java`, `src/test/java`, `src/main/kotlin`, `src/test/kotlin`) plus an `<root>/src/` fallback. It does **not** read pom.xml's `<sourceDirectory>` override and does **not** read Eclipse `.classpath` source entries. ADR 0001 documents the precedence change introduced by this fork.

## Helper portability

ADR 0004 captures the design constraint: helpers added in this fork (`readPomSourceDirs`, `readEclipseClasspath`, future `readManifestSymbolicName` / `readManifestRequireBundle`) use only `java.nio.file.Path` + DOM/Manifest parsing — no JDT internals leak through their signatures. They're liftable into a future Eclipse IDE plugin or LSP-based standalone server verbatim.

## WorkspaceManager (current state, single project)

`WorkspaceManager` today holds one Eclipse workspace and one current `IJavaProject`. Sprint 10 will extend it to a `Map<String projectKey, IJavaProject>` so a single javalens process can serve all imported projects via `load_workspace(paths[])`.

## Release contract with javalens-manager

`javalens-manager` (`pzalutski-pixel` upstream is mirrored by `hw1964/javalens-manager`) installs releases by:

1. Calling GitHub Releases API for the configured repo (`release_repo` setting; this fork uses `hw1964/javalens-mcp`).
2. Downloading the first `.tar.gz` (preferred) or `.zip` asset.
3. Extracting to `<data_root>/tools/javalens/javalens-<version>/`.
4. Walking the extracted tree to find `javalens.jar` (`find_relative_jar_path`).
5. Writing `runtime.json` with version, install dir, jar path.

The fork's release archives must therefore contain `javalens.jar` at a discoverable depth (current upstream produces `javalens-<tag>/javalens.jar`, plus `plugins/`, `configuration/`, etc.). Don't break this layout.
