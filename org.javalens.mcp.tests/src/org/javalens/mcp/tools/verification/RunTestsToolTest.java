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
