package org.javalens.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.javalens.core.JdtServiceImpl;
import org.javalens.mcp.fixtures.TestProjectHelper;
import org.javalens.mcp.models.ErrorInfo;
import org.javalens.mcp.models.ToolResponse;
import org.javalens.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 12 (v1.6.0) — {@code run_tests} validation + (deferred) happy-path
 * coverage.
 *
 * <p>The three happy-path tests are {@code @Disabled} for v1.6.0 because
 * Tycho-surefire's headless test runtime doesn't compile our sample-project
 * fixtures (the forked test JVM needs the fixture's compiled classes on
 * disk, and Tycho's test stage doesn't run javac on
 * {@code test-resources/sample-projects/.../src/test/java}). Production
 * usage works (manager → real workspace → real test classpath); validation
 * tests below cover the input layer. Full happy-path coverage lands in
 * v1.6.1 with a fixture-build pipeline. See
 * {@code docs/upgrade-checklist.md}.</p>
 */
class RunTestsToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RunTestsTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new RunTestsTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("validation: missing scope returns INVALID_PARAMETER")
    void validation_missingScope() {
        ObjectNode args = objectMapper.createObjectNode();
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
    }

    @Test
    @DisplayName("validation: scope.kind unknown returns INVALID_PARAMETER")
    void validation_unknownScopeKind() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "totally-bogus");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
    }

    @Test
    @DisplayName("validation: kind=package without packageName is INVALID_PARAMETER")
    void validation_packageMissingPackageName() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
    }

    // ========== Schema documentation contract tests (v1.7.1 / bug #3) ==========
    // Pin the documented input-combination rules so accidental edits to the
    // description block surface as test failures.

    @Test
    @DisplayName("schema doc spells out {typeName, methodName} combination for kind=method")
    void schema_methodScopeDescription_mentionsTypeNameMethodName() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("{typeName, methodName}"),
            "Description must explicitly document the {typeName, methodName} combination; got:\n" + desc);
    }

    @Test
    @DisplayName("schema doc spells out {filePath, line, column} combination for kind=method")
    void schema_methodScopeDescription_mentionsFilePathLineColumn() {
        String desc = tool.getDescription();
        assertTrue(desc.contains("{filePath, line, column}"),
            "Description must explicitly document the {filePath, line, column} combination; got:\n" + desc);
    }

    @Test
    @DisplayName("schema doc explicitly rejects {filePath, methodName} combination (the bugs.md #3 misleading hint)")
    void schema_methodScopeDescription_doesNotImplyFilePathMethodName() {
        String desc = tool.getDescription();
        // The literal phrase "{filePath, methodName}" may appear once if we
        // explicitly mention "NOT valid". Make sure it ONLY appears in a
        // negative context.
        if (desc.contains("{filePath, methodName}")) {
            assertTrue(desc.contains("NOT a valid") || desc.contains("not a valid")
                || desc.contains("NOT valid")  || desc.contains("not valid"),
                "If {filePath, methodName} appears, it MUST be in a negative context "
                  + "(\"NOT a valid combination\"); got:\n" + desc);
        }
    }

    @Test
    @DisplayName("methodName field description documents the typeName pairing rule")
    void schema_methodNameField_documentsTypeNamePairing() {
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> root = (java.util.Map<String, Object>) tool.getInputSchema();
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props = (java.util.Map<String, Object>) root.get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> scope = (java.util.Map<String, Object>) props.get("scope");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> scopeProps = (java.util.Map<String, Object>) scope.get("properties");
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> methodNameProp = (java.util.Map<String, Object>) scopeProps.get("methodName");

        String methodNameDesc = (String) methodNameProp.get("description");
        assertTrue(methodNameDesc.contains("typeName"),
            "methodName description must mention typeName as the pairing field; got: " + methodNameDesc);
    }

    // ========== Bug #1 dispatch tests (v1.7.1) ==========
    // Plain Maven / Gradle projects no longer hit the JDT-LTK OSGi launcher
    // (which NPEd on Bundle.getHeaders). Instead they short-circuit to
    // INVALID_PARAMETER with an actionable workaround.

    @Test
    @DisplayName("Bug #1: plain Maven project returns INVALID_PARAMETER with mvn-test workaround, not the OSGi NPE")
    void runTests_plainMaven_returnsInvalidParameterNotNpe() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "method");
        scope.put("typeName", "com.example.SampleTest");
        scope.put("methodName", "testAddition");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(),
            "plain Maven (BuildSystem.MAVEN) should not invoke the launcher path");
        assertEquals(ErrorInfo.INVALID_PARAMETER, r.getError().getCode(),
            "expected INVALID_PARAMETER; got: " + r.getError());
        String msg = r.getError().getMessage();
        assertTrue(msg.contains("mvn test"),
            "error message should suggest `mvn test` as the workaround; got: " + msg);
        assertTrue(msg.contains("v1.8.0"),
            "error message should point at the v1.8.0 roadmap; got: " + msg);
    }

    @Test
    @DisplayName("Bug #1: plain Maven error message does NOT bubble Bundle.getHeaders NPE (catches regressions)")
    void runTests_plainMaven_doesNotBubbleBundleHeadersNpe() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "method");
        scope.put("typeName", "com.example.SampleTest");
        scope.put("methodName", "testAddition");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess());
        String msg = r.getError().getMessage();
        assertFalse(msg.contains("Bundle.getHeaders"),
            "error message must not surface the OSGi NPE substring; got: " + msg);
        assertFalse(msg.contains("INTERNAL_ERROR"),
            "error code/message must not reference INTERNAL_ERROR; got: " + r.getError());
    }

    @Test
    @DisplayName("Bug #1: plain Maven short-circuit happens before scope validation (does not require valid scope to fire)")
    void runTests_plainMaven_shortCircuitsEarly_packageScopeStillReturnsBuildSystemError() {
        // Use a valid package scope so we know any failure is from the buildSystem
        // branch, not from a malformed input.
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess());
        String msg = r.getError().getMessage();
        assertTrue(msg.contains("mvn test"),
            "the buildSystem dispatch should fire for package scope too; got: " + msg);
    }

    // The end-to-end scope-validation contract for {filePath, methodName} on a
    // non-Maven project is covered by Layer 3 MCP smoke; the schema docs above
    // already pin the documentation side, and Bug #1's dispatch fires before
    // scope validation for the simple-maven fixture we load here.

    @Test
    @Disabled("v1.6.0 known limitation: JUnit launching from the Tycho-surefire "
        + "test runtime needs a fixture-build pipeline (the sample-project's "
        + "test classes must be compiled on disk for the forked JVM's classpath). "
        + "Production usage via the manager works; happy-path coverage lands in "
        + "v1.6.1. See docs/upgrade-checklist.md.")
    @DisplayName("happy: methodScope on testAddition returns one passed result")
    void happy_methodScope_returnsPassed() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "method");
        scope.put("typeName", "com.example.SampleTest");
        scope.put("methodName", "testAddition");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }

    @Test
    @Disabled("v1.6.0 known limitation — see docs/upgrade-checklist.md.")
    @DisplayName("happy: classScope on SampleTest returns mixed pass/fail results")
    void happy_classScope_returnsMixedResults() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "class");
        scope.put("typeName", "com.example.SampleTest");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }

    @Test
    @Disabled("v1.6.0 known limitation — see docs/upgrade-checklist.md.")
    @DisplayName("happy: packageScope on com.example collects all tests")
    void happy_packageScope_collectsAllTests() {
        ObjectNode args = objectMapper.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example");
        args.put("framework", "junit5");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
    }
}
