package org.javalens.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.ltk.core.refactoring.RefactoringCore;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — {@code move_class}: move a Java type to a different
 * package. Updates every {@code import} line and qualified-name reference
 * in the workspace.
 *
 * <p>The type is identified by file + line + column (zero-based). The
 * target package is given by FQN; if it doesn't yet exist in the same
 * source folder as the type, it's created before the move.</p>
 */
public class MoveClassTool extends AbstractRefactoringTool {

    public MoveClassTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "move_class";
    }

    @Override
    public String getDescription() {
        return """
            Move a Java type to a different package. Every import and qualified
            reference across the workspace is updated.

            USAGE:
              move_class(filePath="src/main/java/com/example/Foo.java",
                         line=10, column=14,
                         targetPackage="com.example.api")

            Inputs:
            - filePath / line / column — point anywhere inside the type to move
              (zero-based line/column).
            - targetPackage — FQN of destination package; created if missing.
            - updateReferences (default true) — when false, the file moves but
              external imports keep their old qualified name.

            Conflict (e.g. target package already has a same-named type) →
            REFACTORING_FAILED with no files modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the type to move."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number anywhere inside the type."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("targetPackage", Map.of("type", "string",
            "description", "Fully-qualified destination package (e.g., 'com.example.api')."));
        properties.put("updateReferences", Map.of("type", "boolean",
            "description", "Update import lines and qualified references in callers (default true)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "targetPackage"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetPackage = getStringParam(arguments, "targetPackage");
        boolean updateReferences = arguments != null && arguments.has("updateReferences")
            ? arguments.get("updateReferences").asBoolean(true)
            : true;

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        if (targetPackage == null) {
            return ToolResponse.invalidParameter("targetPackage", "targetPackage is required");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType type = service.getTypeAtPosition(filePath, line, column);
            if (type == null) {
                return ToolResponse.symbolNotFound(
                    "No type at " + filePathStr + ":" + line + ":" + column);
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("filePath",
                    "Type is not from a source compilation unit (binary types can't be moved)");
            }
            if (!(cu.getResource() instanceof IFile file)) {
                return ToolResponse.invalidParameter("filePath",
                    "Compilation unit's resource is not an IFile");
            }
            // The type's enclosing package gives us the source root we should
            // create the target package under.
            IPackageFragment sourcePkg = (IPackageFragment) cu.getParent();
            if (sourcePkg.getElementName().equals(targetPackage)) {
                return ToolResponse.invalidParameter("targetPackage",
                    "Target package equals source package; nothing to move");
            }

            IJavaProject jp = service.getJavaProject();
            IPackageFragment destination = ensurePackage(jp, sourcePkg, targetPackage);
            if (destination == null) {
                return ToolResponse.invalidParameter("targetPackage",
                    "Could not resolve or create target package '" + targetPackage + "'");
            }

            MoveDescriptor descriptor = (MoveDescriptor) RefactoringCore
                .getRefactoringContribution(IJavaRefactorings.MOVE)
                .createDescriptor();
            descriptor.setProject(jp.getProject().getName());
            // Signature is (files, folders, compilationUnits) — pass the CU
            // we're moving in the third slot.
            descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[]{cu});
            descriptor.setDestination(destination);
            descriptor.setUpdateReferences(updateReferences);

            return runRefactoring(service, descriptor, "move_class");

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Resolve {@code targetPackage} on the same source root as {@code sourcePkg}.
     * If the package fragment doesn't exist yet, create an empty one so the
     * MoveDescriptor has a destination.
     */
    private static IPackageFragment ensurePackage(IJavaProject jp,
                                                  IPackageFragment sourcePkg,
                                                  String targetPackage) throws Exception {
        IPackageFragmentRoot sourceRoot = (IPackageFragmentRoot) sourcePkg.getParent();
        if (sourceRoot != null) {
            IPackageFragment existing = sourceRoot.getPackageFragment(targetPackage);
            if (existing != null && existing.exists()) {
                return existing;
            }
            return sourceRoot.createPackageFragment(targetPackage, true, new NullProgressMonitor());
        }
        // Fall back: scan all source roots.
        for (IPackageFragmentRoot root : jp.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) continue;
            IPackageFragment existing = root.getPackageFragment(targetPackage);
            if (existing != null && existing.exists()) return existing;
        }
        return null;
    }
}
