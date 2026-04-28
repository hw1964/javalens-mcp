# JavaLens (hw1964 fork) — IDE-grade code analysis for Java agents

[![GitHub release](https://img.shields.io/github/v/release/hw1964/javalens-mcp)](https://github.com/hw1964/javalens-mcp/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)

An MCP server that gives AI coding agents the same compiler-accurate understanding of a Java codebase that a human developer gets in Eclipse or IntelliJ — call hierarchies, type hierarchies, references, refactorings, build classpath, JDK semantics — built directly on Eclipse JDT.

> **Java agents on steroids.** Pair with **[javalens-manager](https://github.com/hw1964/javalens-manager)** for a desktop control plane that turns one workspace's worth of Java projects into one MCP service.

---

## Fork relationship

This is a **substantially extended fork** of [pzalutski-pixel/javalens-mcp](https://github.com/pzalutski-pixel/javalens-mcp). The original v1.0–v1.2 work — Eclipse-JDT integration layer, the initial tool surface, the OSGi/Equinox plumbing — is by Piotr Zalutski. From v1.3 onward (Sprint 9–11, ~thousands of LOC) the fork has diverged substantially:

- **v1.3.0** — multi-project `WorkspaceManager`; one javalens process serves many sibling projects with workspace-scoped cross-project search.
- **v1.4.0** — `WorkspaceFileWatcher`: live `workspace.json` reconciliation so adding/removing a project doesn't require a process restart.
- **v1.5.0** — Sprint 11 (Phases A–D): Tycho-aware Maven detection, workspace bundle pool for `Require-Bundle`, Gradle Tooling API integration, parametric tool consolidation (66 → 55 tools), Phase E LTK-refactoring foundation.
- **v1.5.1** — Sprint 11 Phase E (this release): five JDT-LTK structural-refactoring tools — `move_class`, `move_package`, `pull_up`, `push_down`, `encapsulate_field`. Tool count 55 → 60.

See [`docs/release-notes/`](docs/release-notes/) for per-release detail.

---

## Built for AI agents

JavaLens exists because **AI coding assistants need compiler-accurate insights that text search cannot provide**. When an AI uses `grep` or `Read` to find usages of a method, it cannot distinguish:

- A method call from a method with the same name in an unrelated class
- A field read from a field write
- An interface implementation from an unrelated class
- A cast to a type from other references to that type
- An annotation usage from any other reference to the annotation type

The result is incorrect refactorings, missed usages, and incomplete understanding of code behavior.

JavaLens drives Eclipse JDT — the same engine that powers Eclipse IDE — so the agent gets:

- Type resolution across inheritance hierarchies
- Method overloading and overriding
- Generic type arguments
- Import and classpath dependency resolution
- Cross-bundle navigation (PDE / OSGi `Require-Bundle`)
- LTK-backed refactorings (since v1.5.1)

**Example.** Finding all places where `UserService.save()` is called:

| Approach | Result |
|---|---|
| `grep "save("` | Returns 47 matches including `orderService.save()`, `saveButton`, comments |
| `find_references` (JavaLens) | Returns exactly 12 calls to `UserService.save()` |

---

## What's in v1.5.x

### Multi-project workspace (since v1.3)

A *workspace* is a named group of Java projects loaded into one shared JavaLens process and exposed as one MCP service (`jl-<workspace-name>`). The agent sees the combined symbol set of every project in the workspace; cross-project navigation, find-references, and refactorings work across the whole group.

- `add_project` / `remove_project` / `list_projects` — agent-callable workspace mutations.
- Workspace-scoped cross-project search by default; optional `projectKey` parameter on every analysis tool to scope down to a single project.

### Live updates via `workspace.json` (since v1.4)

The manager (or any other driver) writes a JSON file at `<data-dir>/workspace.json`:

```json
{ "version": 1, "name": "jats", "projects": ["/abs/path/a", "/abs/path/b"] }
```

`WorkspaceFileWatcher` picks up changes through `java.nio.file.WatchService` and reconciles the running process's project list within ~1 s. No process restart, no MCP-client reload, no agent-session refresh.

### Detection-matrix completion (v1.5.0, Sprint 11)

Three correctness gaps in the project-detection layer closed:

| Layout | Source roots | Dependencies |
|---|---|---|
| Regular Maven | `<sourceDirectory>` override + heuristic | `mvn dependency:build-classpath` shell-out |
| **Maven-Tycho** | `.classpath` | **`Require-Bundle` resolved against the workspace bundle pool** (was: broken Maven shell-out) |
| **Pure Eclipse PDE** | `.classpath` | `.classpath kind="lib"` **plus `Require-Bundle`** (was: lib only) |
| **Gradle** | **Tooling API `EclipseProject` model** (was: heuristic) | **Tooling API resolved jars** (was: empty) |

Two PDE bundles loaded into one workspace where bundle A's `Require-Bundle` lists bundle B now have inter-bundle navigation working transparently.

### Tool-surface consolidation (v1.5.0)

Per-workspace tool count: **66 → 55 in v1.5.0 → 60 in v1.5.1** (after the LTK refactorings land). Two parametric tools replace 13 narrow ones:

- **`find_pattern_usages(kind, query)`** — `kind ∈ { annotation, instantiation, type_argument, cast, instanceof }`.
- **`find_quality_issue(kind, …)`** — `kind ∈ { naming, bugs, unused, large_classes, circular_deps, reflection, throws, catches }`.

Both declare typed `kind` enums in their schema with per-kind descriptions, so agents discover capabilities through `tools/list` without trial and error. Frees agent budget for the v1.5.1 refactorings and Sprint 12+ Ring 2 work — important under Antigravity's ≈100-tool cap.

### Structural refactorings (v1.5.1)

`org.eclipse.jdt.core.manipulation` and `org.eclipse.ltk.core.refactoring` ship in the target platform; `AbstractRefactoringTool` encapsulates the LTK plumbing — initial / final condition checks, `Change` creation, `PerformChangeOperation`, modified-CU collection. v1.5.1 adds five concrete tools on top:

| Tool | What it does |
|---|---|
| `move_class` | Move a Java type to a different package; rewrites every import and qualified reference workspace-wide. Creates the target package if missing. |
| `move_package` | Rename / relocate a whole package, recursing into every compilation unit. |
| `pull_up` | Move a method or field from a subtype up to its direct supertype; for methods, optionally leave an abstract declaration on the original. |
| `push_down` | Move a method or field from a supertype into all of its direct subtypes. |
| `encapsulate_field` | Generate getter/setter for a field, replace direct accesses, optionally tighten field visibility. |

All accept the inherited optional `projectKey` for workspace-scoped refactorings. On rejection (`REFACTORING_FAILED`), no files are modified. See [`docs/release-notes/v1.5.1.md`](docs/release-notes/v1.5.1.md) and [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md) — three of the five tools use `org.eclipse.jdt.internal.corext.*` processor classes; the upgrade checklist documents what to verify on Eclipse target-platform bumps.

---

## Installation

### Prerequisites

- **Java 21+** on `PATH` or `JAVA_HOME`. JavaLens runs on JDT 2024-09 and parses Java 1.1–23 source.

### Recommended: javalens-manager

For day-to-day use, drive JavaLens through **[javalens-manager](https://github.com/hw1964/javalens-manager)** — a Tauri desktop app that:

- Manages named workspaces of Java projects with a workspace-first UI.
- Writes `workspace.json` for the file-watcher.
- Deploys MCP server entries into Cursor / Claude Desktop / Antigravity / IntelliJ-style configs.
- Polls for fork releases and downloads the latest jar automatically.

```bash
# Linux
curl -sSL https://raw.githubusercontent.com/hw1964/javalens-manager/main/install.sh | bash
```

The manager handles the rest.

### Direct MCP client config (without the manager)

Download a `javalens.zip` / `javalens.tar.gz` from [Releases](https://github.com/hw1964/javalens-mcp/releases) and add to your MCP config:

```json
{
  "mcpServers": {
    "javalens": {
      "command": "java",
      "args": ["-jar", "/path/to/javalens/javalens.jar", "-data", "/path/to/javalens-workspaces"]
    }
  }
}
```

Drop a `workspace.json` into `/path/to/javalens-workspaces/` to load projects:

```json
{
  "version": 1,
  "name": "my-workspace",
  "projects": ["/abs/path/to/project-a", "/abs/path/to/project-b"]
}
```

The watcher loads them on startup and reconciles edits live. For single-project legacy use, `JAVA_PROJECT_PATH` env var still auto-loads on startup (no `workspace.json` needed).

---

## Tools (60 in v1.5.1)

### Workspace administration (5)

| Tool | Description |
|---|---|
| `health_check` | Server status, capabilities, workspace summary. |
| `load_project` | Replace the workspace with a single project. |
| `add_project` | Append a project to the workspace. |
| `remove_project` | Drop a project from the workspace. |
| `list_projects` | List loaded projects with their keys. |

### Navigation (10)

`search_symbols`, `go_to_definition`, `find_references`, `find_implementations`, `get_type_hierarchy`, `get_document_symbols`, `get_symbol_info`, `get_type_at_position`, `get_method_at_position`, `get_field_at_position`.

### Search (5 + 2 parametric)

| Tool | Description |
|---|---|
| `find_method_references` | All `Type::method` expressions. |
| `find_field_writes` | Locations where a field is mutated (vs read). |
| `find_tests` | Discover JUnit/TestNG test methods. |
| **`find_pattern_usages(kind, query)`** | Type-anchored pattern search: `annotation` / `instantiation` / `type_argument` / `cast` / `instanceof`. |
| **`find_quality_issue(kind, …)`** | Code-quality analyses: `naming` / `bugs` / `unused` / `large_classes` / `circular_deps` / `reflection` / `throws` / `catches`. |

### Analysis (16)

`get_diagnostics`, `validate_syntax`, `get_call_hierarchy_incoming`, `get_call_hierarchy_outgoing`, `get_hover_info`, `get_javadoc`, `get_signature_help`, `get_enclosing_element`, `analyze_change_impact`, `analyze_data_flow`, `analyze_control_flow`, `get_di_registrations`, `analyze_file`, `analyze_type`, `analyze_method`, `get_type_usage_summary`.

### Refactoring (15)

**Local:** `rename_symbol`, `organize_imports`, `extract_variable`, `extract_method`, `extract_constant`, `extract_interface`, `inline_variable`, `inline_method`, `change_method_signature`, `convert_anonymous_to_lambda`.

**Structural (LTK-backed, v1.5.1):** `move_class`, `move_package`, `pull_up`, `push_down`, `encapsulate_field`.

### Quick fixes (3)

`suggest_imports`, `get_quick_fixes`, `apply_quick_fix`.

### Metrics (2)

`get_complexity_metrics`, `get_dependency_graph`. (Other quality metrics are exposed as `find_quality_issue` kinds.)

### Project & infrastructure (4)

`get_project_structure`, `get_classpath_info`, `get_type_members`, `get_super_method`.

---

## AI training-bias warning

Models often default to native tools (`grep`, `Read`, generic LSP) over MCP tools, even when semantic analysis gives much better results. Training data is dominated by text-search patterns and the model may not recognise when JDT-driven analysis is correct where text search is wrong.

**Counter the bias** by adding guidance to your project instructions or system prompt (e.g. `CLAUDE.md` for Claude Code):

```markdown
## Code analysis preferences

For Java code analysis, prefer JavaLens MCP tools over text search:
- Use find_references instead of grep for finding usages.
- Use find_implementations instead of text search for implementations.
- Use analyze_type to understand a class before modifying it.
- Use the refactoring tools (rename_symbol, extract_method, …) for safe changes.

Semantic analysis from JDT is more accurate than text-based search,
especially for overloaded methods, inheritance, and generic types.
```

---

## Build system support

| System | Detection | Source roots | Dependencies |
|---|---|---|---|
| Maven | `pom.xml` | `<sourceDirectory>` + heuristic | `mvn dependency:build-classpath` shell-out |
| Maven-Tycho | `pom.xml` with `<packaging>eclipse-*</packaging>` | `.classpath` | `Require-Bundle` via workspace bundle pool |
| Pure Eclipse PDE | `MANIFEST.MF` + `.classpath`, no pom | `.classpath` | `.classpath kind="lib"` + `Require-Bundle` pool |
| Gradle | `build.gradle` / `build.gradle.kts` | `EclipseProject` Tooling API | Tooling API resolved jars |
| Bazel | `MODULE.bazel` / `WORKSPACE` | heuristic + BUILD-file scan | `bazel-bin` / `bazel-out` jar walk |
| Plain Java | `src/` directory | `src/`, `src/main/java`, `src/test/java`, … | (none) |

The Gradle Tooling API needs a Gradle distribution at runtime. First call downloads one (~150 MB into `~/.gradle/caches/dists`); subsequent calls use the cache. CI environments without network access can opt out with `-Djavalens.skip.gradle=true`.

---

## How workspaces work (data dir layout)

```
<data-dir>/
├── workspace.json         <- file-watcher's source of truth (manager writes this)
├── .metadata/             <- JDT's index, search caches, builder state
└── javalens-<project>-<session-uuid>/   <- linked-folder Eclipse project per loaded source tree
```

The Eclipse projects are **linked folders** pointing at your real source trees — JavaLens doesn't copy or modify them. No `.project` / `.classpath` files are added to your source repo.

---

## Configuration

| Env var | Effect | Default |
|---|---|---|
| `JAVA_PROJECT_PATH` | Auto-load this project on startup (legacy single-project mode; ignored when `workspace.json` is present). | (none) |
| `JAVALENS_TIMEOUT_SECONDS` | Per-operation timeout. | `30` |
| `JAVALENS_LOG_LEVEL` | `TRACE` / `DEBUG` / `INFO` / `WARN` / `ERROR`. | `INFO` |
| `JAVA_TOOL_OPTIONS` | JVM options (e.g. `-Xmx2g` for very large workspaces). | (eclipse.ini default 512m) |

---

## Building from source

```bash
git clone https://github.com/hw1964/javalens-mcp.git
cd javalens-mcp
mvn clean verify
```

Distribution archives are written to `org.javalens.product/target/products/`. Test counts as of v1.5.2: **122/122** in `org.javalens.core.tests`, **415/415** in `org.javalens.mcp.tests` (1 `EncapsulateField` happy-path `@Disabled` pending an upstream JDT 2024-09 fix — see [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md)).

### Build prerequisites

- Java 21+
- Maven 3.9+ (the repo no longer ships a wrapper — use the system `mvn`)
- ~3 GB free disk for the Tycho-resolved p2 cache on first build

### Bumping the Eclipse target platform

When you change the Eclipse release the fork builds against (currently 2024-09), walk through [`docs/upgrade-checklist.md`](docs/upgrade-checklist.md) — it lists the version-sensitive pieces (Sprint 11 Phase E refactorings depend on `org.eclipse.jdt.internal.corext.*`; `gradle-tooling-api` is embedded under `org.javalens.core/lib/`; etc.) and the verification commands to run.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  MCP client (Cursor / Claude / Antigravity / IntelliJ / manager)│
└─────────────────────────────────────────────────────────────────┘
                            │ JSON-RPC over stdio
┌─────────────────────────────────────────────────────────────────┐
│  org.javalens.mcp                                               │
│    JavaLensApplication → ToolRegistry → 60 tools                │
│      • workspace admin · navigation · search · analysis         │
│      • refactoring (15) · quick fixes · metrics                 │
└─────────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────────┐
│  org.javalens.core                                              │
│    JdtServiceImpl  ←→  WorkspaceManager (+ bundle pool)         │
│    SearchService                                                │
│    WorkspaceFileWatcher  ←─── workspace.json (live)             │
│    ProjectImporter (Tycho-aware · Gradle Tooling API · PDE)     │
└─────────────────────────────────────────────────────────────────┘
                            │
┌─────────────────────────────────────────────────────────────────┐
│  Eclipse JDT 2024-09 (via OSGi/Equinox)                         │
│    IWorkspace · IJavaProject · SearchEngine · ASTParser · LTK   │
└─────────────────────────────────────────────────────────────────┘
```

---

## License

MIT — see [LICENSE](LICENSE). Original work © Piotr Zalutski; fork additions (Sprint 9–11, v1.3.0+) © Harald Wegner. Both retained per MIT terms.
