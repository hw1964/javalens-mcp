package org.javalens.mcp.tools.build;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.javalens.core.IJdtService;
import org.javalens.core.LoadedProject;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 13 (v1.7.0) — Ring 3 build/dep: {@code add_dependency}.
 *
 * <p>Adds a new {@code <dependency>} entry to a project's {@code pom.xml}.
 * Maven-only for v1.7.0; Gradle support arrives in v1.8.x.</p>
 *
 * <p>Mutation is text-level (not DOM round-trip) so the user's existing
 * formatting and comments survive. The tool refuses to add a duplicate
 * (same {@code groupId}+{@code artifactId} already declared).</p>
 */
public class AddDependencyTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AddDependencyTool.class);

    public AddDependencyTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "add_dependency";
    }

    @Override
    public String getDescription() {
        return """
            Add a new <dependency> to a Maven project's pom.xml. Refuses if
            a dependency with the same groupId+artifactId already exists
            (use update_dependency to bump versions).

            USAGE:
              add_dependency(groupId="org.apache.commons",
                             artifactId="commons-lang3", version="3.14.0")
              add_dependency(projectKey="core",
                             groupId="org.junit.jupiter",
                             artifactId="junit-jupiter-api",
                             version="5.10.0", scope="test")

            Inputs:
            - projectKey — optional. Defaults to the workspace's primary project.
            - groupId / artifactId / version — required.
            - scope — compile (default) | test | provided | runtime | system | import.

            Result:
              { operation, projectKey, pomPath, added, modifiedFiles }

            Maven projects only in v1.7.0; Gradle path arrives in v1.8.x.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("groupId", Map.of("type", "string"));
        properties.put("artifactId", Map.of("type", "string"));
        properties.put("version", Map.of("type", "string"));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("compile", "test", "provided", "runtime", "system", "import")));
        schema.put("properties", properties);
        schema.put("required", List.of("groupId", "artifactId", "version"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "groupId");
        if (missing != null) return missing;
        missing = requireParam(arguments, "artifactId");
        if (missing != null) return missing;
        missing = requireParam(arguments, "version");
        if (missing != null) return missing;

        String groupId = getStringParam(arguments, "groupId");
        String artifactId = getStringParam(arguments, "artifactId");
        String version = getStringParam(arguments, "version");
        String scope = getStringParam(arguments, "scope", "compile");

        if (isBlank(groupId) || isBlank(artifactId) || isBlank(version)) {
            return ToolResponse.invalidParameter("groupId/artifactId/version",
                "All three must be non-empty.");
        }

        try {
            LoadedProject project = pickProject(service, arguments);
            if (project == null) {
                return ToolResponse.invalidParameter("projectKey",
                    "No loaded project. Use list_projects.");
            }

            Path pom = MavenPomSupport.locatePom(project);
            if (pom == null) {
                return ToolResponse.invalidParameter("projectKey",
                    "Project '" + project.projectKey() + "' is not a Maven project "
                        + "(no pom.xml at project root). Gradle support arrives in v1.8.x.");
            }

            List<MavenPomSupport.DeclaredDep> existing = MavenPomSupport.readDependencies(pom);
            if (MavenPomSupport.hasDependency(existing, groupId, artifactId)) {
                return ToolResponse.invalidParameter("groupId/artifactId",
                    "Dependency " + groupId + ":" + artifactId
                        + " is already declared in " + pom + ". "
                        + "Use update_dependency to change the version.");
            }

            String original = Files.readString(pom, StandardCharsets.UTF_8);
            String updated = insertDependency(original, groupId, artifactId, version, scope);
            if (updated == null) {
                return ToolResponse.invalidParameter("projectKey",
                    "Could not locate <project> closing tag in " + pom);
            }
            Files.writeString(pom, updated, StandardCharsets.UTF_8);

            try {
                project.javaProject().getProject().refreshLocal(
                    IResource.DEPTH_INFINITE, new NullProgressMonitor());
            } catch (Exception ignore) {}

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "add_dependency");
            data.put("projectKey", project.projectKey());
            data.put("pomPath", service.getPathUtils().formatPath(pom));
            Map<String, String> added = new LinkedHashMap<>();
            added.put("groupId", groupId);
            added.put("artifactId", artifactId);
            added.put("version", version);
            added.put("scope", scope);
            data.put("added", added);

            List<Map<String, Object>> modifiedFiles = List.of(Map.of(
                "filePath", data.get("pomPath"),
                "summary", "added dependency " + groupId + ":" + artifactId + ":" + version));
            data.put("modifiedFiles", modifiedFiles);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(1)
                .returnedCount(1)
                .build());
        } catch (Exception e) {
            log.warn("add_dependency failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Insert a new dependency snippet into the pom XML text, preserving
     * existing formatting. Strategy: find {@code </dependencies>} and
     * insert the snippet before it. If absent, find {@code </project>}
     * and insert a fresh {@code <dependencies>} block.
     *
     * @return the new pom content, or {@code null} when neither closing
     *         tag can be located.
     */
    static String insertDependency(String pom, String groupId, String artifactId,
                                   String version, String scope) {
        String snippet = String.format("""

                <dependency>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>%s</version>%s
                </dependency>""",
            groupId, artifactId, version,
            scope == null || "compile".equalsIgnoreCase(scope)
                ? ""
                : "\n                    <scope>" + scope + "</scope>");

        int closingDeps = pom.lastIndexOf("</dependencies>");
        if (closingDeps >= 0) {
            return pom.substring(0, closingDeps).stripTrailing() + "\n        "
                + snippet.stripLeading() + "\n    " + pom.substring(closingDeps);
        }

        int closingProject = pom.lastIndexOf("</project>");
        if (closingProject >= 0) {
            String block = """
                    <dependencies>
                        <dependency>
                            <groupId>%s</groupId>
                            <artifactId>%s</artifactId>
                            <version>%s</version>%s
                        </dependency>
                    </dependencies>

                """.formatted(groupId, artifactId, version,
                    scope == null || "compile".equalsIgnoreCase(scope)
                        ? ""
                        : "\n                            <scope>" + scope + "</scope>");
            return pom.substring(0, closingProject) + block + pom.substring(closingProject);
        }
        return null;
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
