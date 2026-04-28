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
2. Run `mvn -pl org.javalens.mcp.tests verify -Dtest='*RefactoringTool*'`. All happy/validation/conflict tests must stay green. One happy-path (`EncapsulateFieldToolTest.happy_encapsulatePublicField`) is `@Disabled` pending an upstream JDT fix — see "JDT bug" note below.
3. Re-check `Require-Bundle: org.eclipse.jdt.core.manipulation` in [org.javalens.mcp/META-INF/MANIFEST.MF](../org.javalens.mcp/META-INF/MANIFEST.MF) — Eclipse occasionally splits or merges plugin bundles between releases. The dependency may need to point at a different bundle or split into two.

### Resolved in v1.5.2 — JDT-UI preference defaults + cache install + change-validation

Three JDT-internal initialisation steps that Eclipse IDE's `org.eclipse.jdt.ui` plugin activator does on startup were missing in our headless RCP runtime, causing the four happy-path refactoring tests to NPE. [`AbstractRefactoringTool#initializeJdtManipulation`](../org.javalens.mcp/src/org/javalens/mcp/tools/AbstractRefactoringTool.java) now does them itself, lazily on first refactoring call:

1. `JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui")` plus default-scope writes for `importorder` / `ondemandthreshold` / `staticondemandthreshold` so `JavaManipulation.getPreference(...)` (used by `CodeStyleConfiguration.configureImportRewrite`) returns non-null.
2. `JavaManipulation.setCodeTemplateStore(new TemplateStoreCore(...))` so `ProjectTemplateStore.fInstanceStore` is non-null when JDT looks up code templates.
3. `JavaManipulationPlugin.getDefault().getMembersOrderPreferenceCacheCommon().install()` so the cache singleton's `fPreferences` field is hydrated before any refactoring touches it.

In addition, `runRefactoring` now calls `change.initializeValidationData(monitor)` between `createChange` and `PerformChangeOperation`. Eclipse's refactoring wizard infrastructure does this implicitly via `CreateChangeOperation`; the headless path doesn't, so without it `TextFileChange.isValid()` throws "TextFileChange has not been initialialized".

Three of the four previously-disabled happy-path tests (`move_class`, `pull_up`, `push_down`) plus the new cross-bundle `pullUp_acrossOsgiBundles` integration test now pass.

### Known limitation — `EncapsulateField` happy-path (JDT 2024-09 bug)

`SelfEncapsulateFieldRefactoring.createSetterMethod` has a bug in its fallback path: when `CodeGeneration.getSetterMethodBodyContent()` returns null (because no `org.eclipse.jdt.ui.text.codetemplates.setterbody` template is registered), it creates a bare `Assignment` AST node and calls `block.statements().add(ass)`. `Block.statements()` expects `Statement` instances, so this fails with `class Assignment is not an instance of class Statement`.

To make this work in headless mode we'd need to recreate Eclipse JDT-UI's full code-template machinery: `JavaContextType` (subclass of `CodeTemplateContextType` registering `${field}` / `${enclosing_method}` / etc. variables) plus a populated `ContributionContextTypeRegistry` set via `JavaManipulation.setCodeTemplateContextRegistry(...)`. That's deeper into JDT-UI internals than is reasonable to maintain across target-platform bumps.

The fix belongs upstream — `createSetterMethod` should wrap the fallback `Assignment` in an `ExpressionStatement` before adding it to the block. Until then, `EncapsulateFieldToolTest.happy_encapsulatePublicField` stays `@Disabled` with an explanatory message; validation and conflict paths still cover the tool. The other four happy-path tests pass.

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
