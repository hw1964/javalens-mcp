# JavaLens MCP — Bug Tracker

Living log of issues found during real-world usage by AI agents and humans.
Append new bugs at the **top**. Status values: `OPEN`, `IN_PROGRESS`, `FIXED in vX.Y.Z`, `WONTFIX`, `DUPLICATE`.

For each entry include: ID, date observed, severity, reproducer, expected vs actual, environment, and (when known) suspected root cause.

---

## #5 — javalens-mcp spawned by non-manager MCP clients starts with zero projects loaded

- **Status:** FIXED in v1.7.1
- **Date observed:** 2026-05-11
- **Reporter:** Claude (Opus 4.7), diagnosing during v1.7.1 planning session
- **Server version:** 1.7.0
- **Severity:** HIGH — every non-manager MCP client (Cursor, every Claude Code session, every Claude Desktop instance) operates against an empty workspace until the user manually calls `add_project` per project. Defeats the "deploy MCP entries into clients" usability story; agents see empty `list_projects` and look broken.

### Environment

- MCP server config (per `~/.cursor/mcp.json`, `~/.claude.json`, etc.):
  ```json
  "jl-javalens-ws": {
    "command": "java",
    "args": ["-jar", ".../javalens.jar", "-data", ".../workspaces/JAVALENS-WS"],
    "env": { "JAVALENS_WORKSPACE_NAME": "JAVALENS-WS" }
  }
  ```
- Manager workspace started in UI (visible in tray as Running).

### Reproducer

Start a fresh Claude Code session. Call `mcp__jl-javalens-ws__list_projects`. Observe `{ projects: [] }` despite the manager's UI showing the same workspace fully populated.

### Actual

```jsonc
{ "success": true, "data": { "projects": [] } }
```

### Expected

Same project list the manager has registered for that workspace.

### Root cause (revised after investigation)

The auto-load mechanism **already exists** (Sprint 10 v1.4.0): `JavaLensApplication.autoLoadProjects()` reads `<-data>/workspace.json` and calls the project-load path for each entry. The manager has been writing `<workspace>/workspace.json` since v1.4.0 — verified, files exist with the correct project lists.

The bug is the **interaction with the session-isolation wrapper.** `JavaLensLauncher` (the Main-Class for `javalens.jar`) generates a UUID and rewrites `-data <workspace>` to `-data <workspace>/<uuid>` *before* delegating to the Equinox launcher. So:

- Manager writes: `/home/harald/.cache/javalens-manager/workspaces/JAVALENS-WS/workspace.json`
- OSGi's `osgi.instance.area` resolves to: `/home/harald/.cache/javalens-manager/workspaces/JAVALENS-WS/<uuid>/`
- `autoLoadProjects()` looks for: `<uuid>/workspace.json` — **does not exist**
- Falls through to `JAVA_PROJECT_PATH` env var (not set), exits with empty project list.

Stdio-per-client + 4 JVMs per workspace is just the symptom magnifier — even one JVM has this bug. Confirmed by inspecting `JavaLensLauncher.main()` (`org.javalens.launcher/.../JavaLensLauncher.java:30-66`) which performs the UUID injection unconditionally.

### Fix (v1.7.1)

`JavaLensApplication.autoLoadProjects()` walks one directory up if `workspace.json` isn't found in the immediate OSGi data dir. The parent is the workspace root the manager writes to. One-file change in [`org.javalens.mcp/src/org/javalens/mcp/JavaLensApplication.java`](../org.javalens.mcp/src/org/javalens/mcp/JavaLensApplication.java) — new private helper `findWorkspaceJson(Path)` plus the call-site swap inside `autoLoadProjects()`. No manager-side change required. Backward-compatible with direct invocations that don't go through `JavaLensLauncher` (the immediate-dir check fires first).

### Alternative fix (NOT taken)

Either (a) write `workspace.json` into each UUID subdir from the launcher (more I/O on startup) or (b) HTTP/SSE single-tenant service (bigger refactor, v1.8.0+ candidate). Walking up one level is the smallest correct fix.

---

## #4 — `buildSystem` reports `"unknown"` for Eclipse PDE bundles even when `.classpath` parses successfully

- **Status:** FIXED in v1.7.1
- **Date observed:** 2026-05-02
- **Reporter:** Claude (Opus 4.7) via `jl-jats-orb-ws` workspace
- **Server version:** 2.0.0-SNAPSHOT
- **Severity:** LOW — labeling/UX issue; functionality works.

### Reproducer

```
mcp__jl-jats-orb-ws__add_project /home/harald/Projects/jats2/com.jats2.model
mcp__jl-jats-orb-ws__list_projects
```

### Actual

```jsonc
{
  "projectKey": "com-jats2-model",
  "projectPath": "/home/harald/Projects/jats2/com.jats2.model",
  "buildSystem": "unknown",
  "sourceFileCount": 1030,
  "packageCount": 123,
  "classpathEntryCount": 29,
  ...
}
```

`buildSystem: "unknown"` despite the project being a perfectly recognizable Eclipse PDE bundle (it has `META-INF/MANIFEST.MF`, `.classpath`, `.project`, and `build.properties` at the root). The README says: *"Supports Maven projects (pom.xml), Gradle projects, **Eclipse projects (.classpath src/lib entries honored when present)**, and Plain Java projects with src/ directory."* So PDE is explicitly supported — but the reported label gives the impression that the loader fell back to a generic mode.

### Expected

`buildSystem: "eclipse-pde"` (or `"eclipse"`, `"pde"`, etc.). Indexing actually works (1030 files / 123 packages, classpath resolved with 29 entries, all `find_references` / `get_type_hierarchy` calls return correct results across the bundle), so it really is being recognized — just labeled `"unknown"` in the response.

### Why it matters

A caller looking at `list_projects` and seeing `"unknown"` reasonably suspects the project failed to load properly. Wasted investigation cycles. Also makes it harder to write tools that branch on `buildSystem` (e.g. "if maven, use these flags; if pde, use those") because PDE bundles fall into the same bucket as actual unknown-shape projects.

### Suggested fix

- If `META-INF/MANIFEST.MF` with `Bundle-SymbolicName` exists at project root → `"eclipse-pde"`.
- Else if `.classpath` exists → `"eclipse"`.
- Else if `pom.xml` → `"maven"`, etc. (already works).
- Else `"unknown"`.

---

## #3 — `run_tests` schema description for `scope.kind="method"` mismatches validation

- **Status:** FIXED in v1.7.1
- **Date observed:** 2026-05-01
- **Reporter:** Claude (Opus 4.7)
- **Server version:** 2.0.0-SNAPSHOT
- **Severity:** LOW — documentation/UX bug; users get a clear error message but the schema docs steer them wrong first.

### Reproducer

The `run_tests` tool's description says (excerpted):

```
Inputs:
- scope.kind — "method" | "class" | "package".
- scope.filePath / line / column — for method/class; zero-based.
- scope.typeName — alternative to filePath for class scope.
- scope.methodName — for method scope.
```

Reading that, the natural call shape is:

```jsonc
{
  "scope": {
    "kind": "method",
    "filePath": "/abs/path/Test.java",
    "methodName": "testFoo"
  }
}
```

### Actual

```jsonc
{
  "success": false,
  "error": {
    "code": "INVALID_PARAMETER",
    "message": "Invalid parameter 'scope': kind='method' requires either {typeName, methodName} or {filePath, line, column}."
  }
}
```

The validation message is clear and correct, but it contradicts the schema description: the description says `methodName` is for method scope (implying it pairs with `filePath`), while validation requires `methodName` only with `typeName`, never with `filePath`.

### Expected

Either (a) update the schema description to spell out the actual valid combinations (`{typeName, methodName}` OR `{filePath, line, column}`, never `{filePath, methodName}`), or (b) make the validation accept `{filePath, methodName}` (resolve method by name within the file).

Option (a) is cheaper. Option (b) would be a small UX win — `{filePath, methodName}` is a natural shape and saves the caller from looking up line/column.

### Suggested fix

Schema description rewrite:

```
- scope.kind — "method" | "class" | "package".
- For kind="method": pass either
    {typeName, methodName}                      (find method by FQN + name)
    or {filePath, line, column}                 (find method at cursor position).
- For kind="class": pass either
    {typeName}                                  (FQN)
    or {filePath, line, column}                 (find class at cursor position).
- For kind="package": pass {packageName}.
```

---

## #2 — `search_symbols` leaks javalens-manager cache file paths into user-visible results

- **Status:** FIXED in v1.7.1
- **Date observed:** 2026-05-01
- **Reporter:** Claude (Opus 4.7) via `jl-jats-orb-ws` workspace
- **Server version:** 2.0.0-SNAPSHOT
- **Severity:** MEDIUM — pollutes search results with non-source paths, wastes user/agent attention, can mislead refactor tooling that expects every result to be a real source location.

### Reproducer

```jsonc
{
  "tool": "search_symbols",
  "arguments": { "query": "AlpacaFullProvider", "kind": "Class" }
}
```

### Actual

```jsonc
{
  "results": [
    {
      "name": "AlpacaFullProvider",
      "kind": "Class",
      "filePath": "/home/harald/.cache/javalens-manager/workspaces/JATS-ORB-WS/6d65bfe1/javalens-com.jats2.model-7e6f70c7",
      "qualifiedName": "com.jats2.model.provider.alpol.alpaca.AlpacaFullProvider",
      "package": "com.jats2.model.provider.alpol.alpaca"
    },
    {
      "name": "AlpacaFullProvider",
      "kind": "Class",
      "filePath": "/home/harald/.cache/javalens-manager/workspaces/JATS-ORB-WS/6d65bfe1/javalens-strategies_orb-7e6f70c7",
      "qualifiedName": "com.jats2.model.provider.alpol.alpaca.AlpacaFullProvider",
      "package": "com.jats2.model.provider.alpol.alpaca"
    },
    {
      "name": "AlpacaFullProvider",
      "kind": "Class",
      "filePath": "/home/harald/Projects/jats2/com.jats2.model/src/com/jats2/model/provider/alpol/alpaca/AlpacaFullProvider.java",
      "line": 53,
      "column": 6,
      ...
    }
  ]
}
```

The first two entries have the cache directory as their `filePath` and **no `line` / `column`** — they're not navigable source locations. The third is the real file. Same pattern observed for `TransactionProvider` (3 results: 2 cache + 1 real) and several other queries.

Inconsistent across queries: a few queries (`SlotManager`, `IExecutionAlgo`) returned only the real source path with no cache duplicates. So the leak isn't universal — appears to depend on whether the symbol resolves to a class that's also referenced by a sibling project's classpath entry (here `strategies_orb` depends on `com.jats2.model` JAR, so the same class shows up under both indices' cache snapshots).

### Expected

Either (a) suppress cache-path entries from `search_symbols` results entirely (deliver only real source-file matches with `line` / `column`), or (b) deduplicate by `qualifiedName` and prefer the entry that has a real source location.

### Why it matters

- Agents iterating over results to refactor each call site will hit cache paths that aren't real files. `Read` on the cache path returns binary or fails. Refactor tools that rely on `filePath:line:column` from search results break.
- Doubles or triples the result count for common types — agents waste tokens skimming duplicates.
- Hard to filter client-side because the `package` and `qualifiedName` are identical to the real entry — only the `filePath` shape distinguishes.

### Suggested fix

In the search-symbols handler, after collecting candidate entries, drop those whose `filePath` lacks `line`/`column` AND points into `~/.cache/javalens-manager/`. Or de-duplicate by `(qualifiedName, kind)` and keep only the entry that has source coordinates.

---

## #1 — `run_tests` returns `INTERNAL_ERROR` (NPE on `Bundle.getHeaders()`) for plain Maven projects

- **Status:** FIXED in v1.7.1 *(workaround dispatch; full launch path tracked for v1.8.0)*
- **Date observed:** 2026-05-01
- **Reporter:** Claude (Sonnet 4.6 / Opus 4.7) via `jl-jats-orb-ws` workspace
- **Server version:** 2.0.0-SNAPSHOT (per `health_check`)
- **Severity:** HIGH — blocks the documented MCP-driven TDD workflow for any non-Eclipse-PDE Maven project; agents are forced to fall back to `mvn test` via the Bash tool, which defeats the purpose of having a typed MCP test runner.

### Environment

- Workspace: `JATS-ORB-WS` with five loaded projects.
  - `strategies-orb` — Maven, `<sourceDirectory>strategies/src</sourceDirectory>` override.
  - `com-jats2-model` — Eclipse PDE bundle (`buildSystem: "unknown"`).
  - `execsim-java` — Maven.
  - `com-jats2-gateways-alpol` — Maven, plain `src/main/java` + `src/test/java` layout.
  - `orb-java` — Maven.
- Target project for the failing call: `com-jats2-gateways-alpol` (plain Maven, JUnit 4.12, Mockito 5.5.0, Java 21).

### Reproducer

```jsonc
// MCP request
{
  "tool": "run_tests",
  "arguments": {
    "scope": {
      "kind": "method",
      "typeName": "com.jats2.gateways.alpol.alpaca.orders.OrderProcessorTest",
      "methodName": "cancelBeforePendingNew_isQueuedAndDrained"
    },
    "framework": "junit4",
    "projectKey": "com-jats2-gateways-alpol",
    "timeoutSeconds": 60
  }
}
```

### Actual

```jsonc
{
  "success": false,
  "error": {
    "code": "INTERNAL_ERROR",
    "message": "Internal error: Cannot invoke \"org.osgi.framework.Bundle.getHeaders()\" because \"bundle\" is null",
    "hint": "This may be a bug. Check server logs for details."
  }
}
```

### Expected

Test runs successfully and returns the parsed pass/fail report shape documented in the tool's description (`{ framework, projectsTested, summary{...}, failures[...], stdoutTail, stderrTail }`).

The same test runs cleanly via `mvn test -Dtest='OrderProcessorTest#cancelBeforePendingNew_isQueuedAndDrained'` in the project root:

```
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

### Suspected root cause

The NPE on `org.osgi.framework.Bundle.getHeaders()` strongly suggests the JDT-LTK launching delegate (or its surrounding Equinox bootstrap inside javalens-mcp) is trying to resolve a *bundle* for the test project, finds none (because plain Maven projects are not OSGi bundles — they have no `MANIFEST.MF` headers, no PDE `.classpath` shape), and dereferences `null` instead of falling back to a non-OSGi launch path.

Likely call site: somewhere in `org.javalens.mcp` or `org.javalens.core` that wraps `org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationDelegate` (or the Tycho equivalent) and assumes the project resolves to an `IBundle`. Worth grepping the javalens codebase for `Bundle.getHeaders()` / `getBundle()` calls around the test launcher and adding a null check + plain-Maven fallback.

The symmetric tool `compile_workspace` works correctly on the same project (returns `errorCount: 0` in <1 s), so the JDT compile-side path is fine — the bug is specific to the JUnit launching path.

### Suggested fix shape

In the `run_tests` handler, before assuming the target is an OSGi bundle:
- Check whether the `IProject` has the `PluginNature`. If not, skip the bundle-headers lookup entirely.
- Branch on build system (`maven` / `gradle` / `unknown` per `list_projects`) and use the right launching delegate:
  - Plain Maven / Gradle → `JUnitLaunchConfigurationDelegate` directly with the project's `IClasspathContainer` resolved by m2e / Buildship.
  - Eclipse PDE → Tycho-aware Surefire delegate (current path).
- Surface a more actionable error message when the project type genuinely can't be resolved (e.g. `"target project com-jats2-gateways-alpol is not an OSGi bundle and has no JUnit launcher available"`) instead of bubbling an NPE as `INTERNAL_ERROR`.

### Workaround

Agents should fall back to running tests via the `Bash` tool with `mvn test -Dtest='...'` for now. This works for any Maven project on the workspace but loses the structured pass/fail summary and stack-trace parsing the MCP tool would otherwise provide. Per memory note `feedback_prefer_mcp.md`, agents should still try `run_tests` first and only fall back when this NPE fires.

### Cross-reference

- Workaround called out in: `~/CursorProjects/strategies_orb/.claude/plans/fizzy-watching-narwhal.md` (Phase 1A verification section).
- Production code being tested when this was hit: `com.jats2.gateways.alpol.alpaca.orders.OrderProcessor` cancel-defer-until-NEW logic for Alpaca workaround.
