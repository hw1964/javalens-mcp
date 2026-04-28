package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.javalens.core.IJdtService;
import org.javalens.core.LoadedProject;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 12 (v1.6.0) — {@code compile_workspace}: trigger an incremental
 * build over every loaded project in the workspace and return aggregated
 * problem markers.
 *
 * <p>Closes the {@code refactor → compile → fix} loop the agent previously
 * had to drive via per-file {@code get_diagnostics} (incomplete: misses
 * cascading errors in untouched files and project-level errors like broken
 * {@code Require-Bundle} or unresolved Tycho deps) or shelling out to
 * Maven / Gradle (slow, suffers classpath drift between IDE and CLI).</p>
 *
 * <p>Uses {@link IResource#findMarkers} on {@link IMarker#PROBLEM} markers,
 * not AST reconcile (the path {@link GetDiagnosticsTool} takes). The
 * incremental builder writes project-level markers — Java compiler errors,
 * classpath errors, manifest errors — that AST reconcile alone misses.</p>
 */
public class CompileWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CompileWorkspaceTool.class);

    public CompileWorkspaceTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "compile_workspace";
    }

    @Override
    public String getDescription() {
        return """
            Run an incremental Java build over every loaded project (or one
            specific project when projectKey is set) and return aggregated
            problem markers — compiler errors, warnings, and project-level
            errors (broken Require-Bundle, unresolved classpath, manifest
            errors) in one call.

            USAGE:
              compile_workspace()
              compile_workspace(projectKey="core")
              compile_workspace(minSeverity="WARNING")
              compile_workspace(includeTaskMarkers=true)

            Inputs:
            - projectKey — optional. Compile just that project; default is the
              full workspace.
            - minSeverity — "ERROR" (default) or "WARNING". Markers below the
              threshold are dropped from the result.
            - includeTaskMarkers — default false. When true, TODO/FIXME markers
              are included in addition to PROBLEM markers.

            Result:
              { operation, projectsCompiled, errorCount, warningCount,
                diagnostics: [{filePath, line, column, severity, message,
                              sourceProject}] }

            Compilation errors are a normal RESULT (returned in `diagnostics`).
            The tool itself only fails on missing projectKey or aborted build.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("minSeverity", Map.of(
            "type", "string",
            "enum", List.of("ERROR", "WARNING"),
            "description", "Drop diagnostics below this severity. Default: ERROR."));
        properties.put("includeTaskMarkers", Map.of(
            "type", "boolean",
            "description", "Include TODO/FIXME markers (default false)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String minSeverityRaw = getStringParam(arguments, "minSeverity", "ERROR");
        boolean includeTaskMarkers = getBooleanParam(arguments, "includeTaskMarkers", false);

        int minSeverity;
        switch (minSeverityRaw.toUpperCase()) {
            case "ERROR" -> minSeverity = IMarker.SEVERITY_ERROR;
            case "WARNING" -> minSeverity = IMarker.SEVERITY_WARNING;
            default -> {
                return ToolResponse.invalidParameter("minSeverity",
                    "Must be 'ERROR' or 'WARNING'; got '" + minSeverityRaw + "'");
            }
        }

        // AbstractTool.execute already validated `projectKey` if present and
        // wrapped the service in a ScopedJdtService; however, ScopedJdtService
        // delegates allProjects() to the underlying multi-project service, so
        // we need to choose the project set ourselves. When projectKey is set,
        // operate on just that one; otherwise walk every loaded project.
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects.");
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

        try {
            int errorCount = 0;
            int warningCount = 0;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            int compiled = 0;

            for (LoadedProject loaded : projects) {
                IProject project = loaded.javaProject().getProject();
                try {
                    // Pick up any filesystem changes the agent / user made
                    // since the project was loaded — without this, an
                    // incremental build won't see new or edited source files
                    // (Eclipse-linked folders need an explicit refreshLocal
                    // to repopulate the resource tree). We use FULL_BUILD
                    // because INCREMENTAL_BUILD's delta tracking lags fresh
                    // refreshLocal additions in Tycho-test-style headless
                    // Equinox runtimes — and a full build over a workspace
                    // already loaded into memory is fast enough that the
                    // simpler / more reliable mode wins.
                    project.refreshLocal(IResource.DEPTH_INFINITE,
                        new NullProgressMonitor());
                    project.build(IncrementalProjectBuilder.FULL_BUILD,
                        new NullProgressMonitor());
                } catch (Exception e) {
                    log.warn("Build failed on project '{}': {}", loaded.projectKey(), e.getMessage(), e);
                    // Continue collecting markers — partial results are still useful.
                }
                compiled++;

                IMarker[] problems = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
                for (IMarker marker : problems) {
                    int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                    if (severity < minSeverity) continue;
                    Map<String, Object> entry = describeMarker(marker, loaded, service);
                    if (entry != null) {
                        diagnostics.add(entry);
                        if (severity == IMarker.SEVERITY_ERROR) errorCount++;
                        else if (severity == IMarker.SEVERITY_WARNING) warningCount++;
                    }
                }

                if (includeTaskMarkers) {
                    IMarker[] tasks = project.findMarkers(IMarker.TASK, true, IResource.DEPTH_INFINITE);
                    for (IMarker task : tasks) {
                        Map<String, Object> entry = describeMarker(task, loaded, service);
                        if (entry != null) diagnostics.add(entry);
                    }
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "compile_workspace");
            data.put("projectsCompiled", compiled);
            data.put("errorCount", errorCount);
            data.put("warningCount", warningCount);
            data.put("diagnostics", diagnostics);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(diagnostics.size())
                .returnedCount(diagnostics.size())
                .build());
        } catch (Exception e) {
            log.warn("compile_workspace threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static Map<String, Object> describeMarker(IMarker marker,
                                                       LoadedProject loaded,
                                                       IJdtService service) {
        try {
            Map<String, Object> entry = new LinkedHashMap<>();
            IResource resource = marker.getResource();
            if (resource != null && resource.getLocation() != null) {
                IPath location = resource.getLocation();
                entry.put("filePath", service.getPathUtils().formatPath(location.toOSString()));
            }
            int line = marker.getAttribute(IMarker.LINE_NUMBER, -1);
            if (line > 0) entry.put("line", line - 1); // markers are 1-based; tools are 0-based
            int charStart = marker.getAttribute(IMarker.CHAR_START, -1);
            if (charStart >= 0) entry.put("charStart", charStart);
            int charEnd = marker.getAttribute(IMarker.CHAR_END, -1);
            if (charEnd >= 0) entry.put("charEnd", charEnd);
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            entry.put("severity", switch (severity) {
                case IMarker.SEVERITY_ERROR -> "ERROR";
                case IMarker.SEVERITY_WARNING -> "WARNING";
                default -> "INFO";
            });
            String message = marker.getAttribute(IMarker.MESSAGE, "");
            entry.put("message", message);
            entry.put("sourceProject", loaded.projectKey());
            return entry;
        } catch (Exception e) {
            return null;
        }
    }
}
