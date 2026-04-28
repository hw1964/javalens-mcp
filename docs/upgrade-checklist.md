# Eclipse target-platform upgrade checklist

This document lists every version-sensitive piece of code in the fork. Run through it whenever you bump the Eclipse target platform in [`org.javalens.target/org.javalens.target.target`](../org.javalens.target/org.javalens.target.target). Internal-API breakage shows up at compile time; behavior changes in JDT public API show up as test failures.

## Embedded jars (Bundle-ClassPath)

- `org.javalens.core/lib/gradle-tooling-api-8.10.jar` — pinned via `Bundle-ClassPath` in [org.javalens.core/META-INF/MANIFEST.MF](../org.javalens.core/META-INF/MANIFEST.MF) and `bin.includes` / `jars.extra.classpath` in [build.properties](../org.javalens.core/build.properties).
  - **Bumping**: drop in a newer `gradle-tooling-api-x.y.jar`, update both files, run `mvn -pl org.javalens.core.tests verify -Dtest=ProjectImporterGradleToolingTest`. The Tooling API can drive any Gradle ≥ the version of the API jar; first test run downloads the matching distribution into `~/.gradle/caches/dists`.
  - **Compatibility**: `EclipseProject` model surface has been stable across Gradle 5–9. If the test fails on a newer API version, check `EclipseSourceDirectory.getDirectory()` and `EclipseExternalDependency.getFile()` haven't been deprecated.

## Internal JDT API (Sprint 11 Phase E refactorings)

[AbstractRefactoringTool](../org.javalens.mcp/src/org/javalens/mcp/tools/AbstractRefactoringTool.java) and the five Phase E tools depend on `org.eclipse.jdt.internal.corext.*` classes. Eclipse marks these `x-internal:=true` but their de-facto stability is high — the IDE's own refactoring UI is built on them.

### Classes touched

| File | Internal classes |
|---|---|
| [PullUpTool](../org.javalens.mcp/src/org/javalens/mcp/tools/PullUpTool.java) | `org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings`, `org.eclipse.jdt.internal.corext.refactoring.structure.PullUpRefactoringProcessor` |
| [PushDownTool](../org.javalens.mcp/src/org/javalens/mcp/tools/PushDownTool.java) | `org.eclipse.jdt.internal.corext.refactoring.structure.PushDownRefactoringProcessor` |
| [EncapsulateFieldTool](../org.javalens.mcp/src/org/javalens/mcp/tools/EncapsulateFieldTool.java) | `org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring` |

`MoveClassTool` and `MovePackageTool` use only public descriptor API (`MoveDescriptor` and `RenameJavaElementDescriptor`).

### What to verify after a target-platform bump

1. Build `org.javalens.mcp` — internal-API renames / deletions fail at compile time. Fix mechanically by tracking the renamed/moved class.
2. Run `mvn -pl org.javalens.mcp.tests verify -Dtest='*RefactoringTool*'`. The validation/conflict tests must stay green; happy-path tests are currently `@Disabled` (see below).
3. Re-check `Require-Bundle: org.eclipse.jdt.core.manipulation` in [org.javalens.mcp/META-INF/MANIFEST.MF](../org.javalens.mcp/META-INF/MANIFEST.MF) — Eclipse occasionally splits or merges plugin bundles between releases. The dependency may need to point at a different bundle or split into two.

### Known limitation — JDT.UI preference defaults (v1.5.1)

In JDT 2024-09, the import-rewrite path used by `MoveDescriptor`, `PullUpRefactoringProcessor`, `PushDownRefactoringProcessor`, and `SelfEncapsulateFieldRefactoring` reads import-order / on-demand-threshold preferences via `JavaManipulation.getPreference(...)`. Eclipse IDE registers defaults for these via `org.eclipse.jdt.ui`'s plugin activator; we don't import that bundle, and our own `InstanceScope`/`DefaultScope` writes from `AbstractRefactoringTool#initializeJdtManipulation` aren't found by JDT's lookup chain in headless mode.

The four happy-path tests for `move_class`, `pull_up`, `push_down`, `encapsulate_field` are therefore `@Disabled` in v1.5.1. Validation and conflict semantics still tested. The full happy-path coverage lands in **v1.5.2** once we have a clean way to register the defaults — likely either:

- Registering `org.eclipse.jdt.ui` (and its transitive UI deps) as a target-platform dep purely for its preference activator, or
- Calling `IPreferencesService.applyPreferences(...)` against a pre-built `IExportedPreferences` snapshot, or
- A custom `BundleActivator` on `org.javalens.mcp` that programmatically registers the defaults early.

`MovePackageTool`'s happy-path test uses `RenameJavaElementDescriptor`, which doesn't touch the import-rewrite path, and stays green — confirming the LTK plumbing itself works.

## Tycho project-import edges

- [ProjectImporter.readPomPackaging](../org.javalens.core/src/org/javalens/core/project/ProjectImporter.java) — reads `<packaging>` directly from pom.xml. Stable across Maven versions.
- The Maven shell-out for non-Tycho projects (`mvn dependency:build-classpath -Dmdep.outputFile=...`) still depends on the Maven plugin behaving as in 3.9.x. Re-run `mvn -pl org.javalens.core.tests verify -Dtest=ProjectImporterTest` after Maven major-version bumps.

## File watcher

- [WorkspaceFileWatcher](../org.javalens.core/src/org/javalens/core/workspace/WorkspaceFileWatcher.java) uses `java.nio.file.WatchService`. JVM/Linux/macOS-stable; Windows quirk: WatchService events for renames sometimes fire as `ENTRY_DELETE` + `ENTRY_CREATE` rather than `ENTRY_MODIFY`. We already handle both.

## Workflow

When bumping the target platform:

1. Edit [`org.javalens.target/org.javalens.target.target`](../org.javalens.target/org.javalens.target.target) — update repository URLs and bump `sequenceNumber`.
2. `mvn -pl org.javalens.target install`.
3. `mvn -pl org.javalens.core install -DskipTests` — catches public-API renames in JDT/LTK.
4. `mvn -pl org.javalens.mcp install -DskipTests` — catches internal-API breakage in the Phase E tools.
5. `mvn -pl org.javalens.core.tests verify` — public-API regression coverage.
6. `mvn -pl org.javalens.mcp.tests verify` — tool-surface regression coverage.
7. Smoke-test against a real project (e.g. JATS) through the manager. Regressions in JDT search semantics or Tycho project-import don't show up in unit tests.
