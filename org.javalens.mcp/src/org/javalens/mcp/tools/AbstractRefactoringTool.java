package org.javalens.mcp.tools;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.refactoring.descriptors.JavaRefactoringDescriptor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.PerformChangeOperation;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.javalens.core.IJdtService;
import org.javalens.mcp.models.ResponseMeta;
import org.javalens.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 11 Phase E — base class for the structural-refactoring tools
 * ({@code move_class}, {@code move_package}, {@code pull_up},
 * {@code push_down}, {@code encapsulate_field}).
 *
 * <p>Encapsulates the boilerplate that every JDT/LTK refactoring needs:</p>
 * <ol>
 *   <li>Build the descriptor.</li>
 *   <li>{@link JavaRefactoringDescriptor#createRefactoring} → {@link Refactoring}</li>
 *   <li>{@link Refactoring#checkInitialConditions} — bail with
 *       {@code INVALID_PARAMETER} on FATAL/ERROR severity.</li>
 *   <li>{@link Refactoring#checkFinalConditions} — bail with
 *       {@code REFACTORING_FAILED} on FATAL/ERROR severity (no files modified).</li>
 *   <li>{@link Refactoring#createChange} → {@link Change}</li>
 *   <li>{@link PerformChangeOperation} runs the change against the workspace.</li>
 *   <li>Collect modified compilation units and return a structured success
 *       response with formatted relative paths.</li>
 * </ol>
 *
 * <p>The result contract documented as {@code modifiedFiles} on the success
 * payload is intentionally uniform across all five Phase E tools so an
 * agent can post-process them with the same code path.</p>
 */
public abstract class AbstractRefactoringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AbstractRefactoringTool.class);

    private static volatile boolean jdtManipulationInitialized = false;

    /**
     * One-time JDT-UI preference seeding. Eclipse IDE's
     * {@code org.eclipse.jdt.ui} plugin activator registers default values
     * for {@code importorder} / {@code ondemandthreshold} /
     * {@code staticondemandthreshold} on startup; we don't import that
     * bundle, so in headless RCP runs (and Tycho-surefire test runs) the
     * import-rewrite path used by {@code move_class}, {@code pull_up},
     * {@code push_down}, and {@code encapsulate_field} fetches a null
     * import-order string and NPEs.
     *
     * <p>JDT reads these via {@code JavaManipulation.getPreference(...)},
     * which walks {@code ProjectScope} → {@code InstanceScope} →
     * {@code DefaultScope} against the {@code "org.eclipse.jdt.ui"} preference
     * node specifically — not against any custom node we might pass to
     * {@code JavaManipulation.setPreferenceNodeId(...)}. Writing to the
     * standard JDT-UI node is the fix.</p>
     *
     * <p>Has to be invoked lazily, not from a {@code static {}} block — the
     * preferences subsystem may not be wired before this class first loads,
     * and writes to an unwired store get dropped.</p>
     */
    private static synchronized void initializeJdtManipulation() {
        if (jdtManipulationInitialized) return;
        try {
            // 1. Tell JavaManipulation which preference node to consult.
            //    JavaManipulation.getPreference(...) walks
            //      ProjectScope(fgPreferenceNodeId) → InstanceScope(fgPreferenceNodeId)
            //      → DefaultScope(fgPreferenceNodeId)
            //    where fgPreferenceNodeId comes from setPreferenceNodeId. Eclipse IDE's
            //    org.eclipse.jdt.ui activator normally sets this to "org.eclipse.jdt.ui";
            //    in headless RCP runs (no jdt.ui bundle) it stays null, and getPreference
            //    silently returns null which then NPEs deep inside CodeStyleConfiguration's
            //    ImportRewrite plumbing. Set it ourselves to the standard JDT-UI node so
            //    our writes below are the ones JDT reads.
            //
            //    JavaManipulation.setPreferenceNodeId asserts that the node id hasn't
            //    been set already (or is being cleared); only call it when nothing else
            //    set it first.
            if (org.eclipse.jdt.core.manipulation.JavaManipulation.getPreferenceNodeId() == null) {
                org.eclipse.jdt.core.manipulation.JavaManipulation.setPreferenceNodeId("org.eclipse.jdt.ui");
            }
            // 2. Seed defaults on the same node so the Project → Instance → Default
            //    lookup chain finds something. DefaultScope is the fall-through that
            //    matters; InstanceScope is mirrored for any caller that probes it
            //    directly.
            var defaults = org.eclipse.core.runtime.preferences.DefaultScope.INSTANCE
                .getNode("org.eclipse.jdt.ui");
            defaults.put("org.eclipse.jdt.ui.importorder", "java;javax;org;com;");
            defaults.put("org.eclipse.jdt.ui.ondemandthreshold", "99");
            defaults.put("org.eclipse.jdt.ui.staticondemandthreshold", "99");
            var instance = org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                .getNode("org.eclipse.jdt.ui");
            if (instance.get("org.eclipse.jdt.ui.importorder", null) == null) {
                instance.put("org.eclipse.jdt.ui.importorder", "java;javax;org;com;");
            }
            if (instance.get("org.eclipse.jdt.ui.ondemandthreshold", null) == null) {
                instance.put("org.eclipse.jdt.ui.ondemandthreshold", "99");
            }
            if (instance.get("org.eclipse.jdt.ui.staticondemandthreshold", null) == null) {
                instance.put("org.eclipse.jdt.ui.staticondemandthreshold", "99");
            }
            // 3. Wire up the code-template store. SelfEncapsulateFieldRefactoring's
            //    getter/setter generation calls JavaManipulation.getCodeTemplateStore();
            //    Eclipse JDT.UI sets this on plugin startup. In headless RCP nothing
            //    does, so ProjectTemplateStore.fInstanceStore stays null and the
            //    encapsulate-field codegen NPEs inside getTemplateData(). A minimal
            //    TemplateStoreCore backed by the InstanceScope JDT-UI node is
            //    enough — load() pulls in any contributed templates if the registry
            //    is wired and otherwise leaves the store empty (codegen falls back
            //    to default templates).
            if (org.eclipse.jdt.core.manipulation.JavaManipulation.getCodeTemplateStore() == null) {
                try {
                    var templateStore = new org.eclipse.text.templates.TemplateStoreCore(
                        org.eclipse.core.runtime.preferences.InstanceScope.INSTANCE
                            .getNode("org.eclipse.jdt.ui"),
                        "org.eclipse.jdt.ui.text.custom_code_templates");
                    try {
                        templateStore.load();
                    } catch (java.io.IOException e) {
                        // Empty store is acceptable — codegen has built-in fallbacks.
                    }
                    org.eclipse.jdt.core.manipulation.JavaManipulation.setCodeTemplateStore(templateStore);
                } catch (Throwable inner) {
                    log.warn("CodeTemplateStore init failed (encapsulate_field codegen may NPE): {}",
                        inner.getMessage(), inner);
                }
            }
            // 4. Install the MembersOrderPreferenceCacheCommon. Eclipse IDE's
            //    org.eclipse.jdt.ui activator does this on startup; in headless
            //    RCP nothing does, so the cache singleton's fPreferences field
            //    stays null and any internal-JDT call path that touches it
            //    (CodeStyleConfiguration's import-rewrite path, the structural
            //    refactoring processors used by pull_up / push_down /
            //    encapsulate_field) NPEs. install() reads the InstanceScope /
            //    DefaultScope nodes for the id we just set above and caches them.
            try {
                var plugin = org.eclipse.jdt.internal.core.manipulation.JavaManipulationPlugin.getDefault();
                if (plugin != null) {
                    plugin.getMembersOrderPreferenceCacheCommon().install();
                }
            } catch (Throwable inner) {
                log.warn("MembersOrderPreferenceCacheCommon.install() failed (refactorings may NPE): {}",
                    inner.getMessage(), inner);
            }
        } catch (Throwable t) {
            log.warn("JDT manipulation init failed (refactorings will likely error): {}", t.getMessage(), t);
        } finally {
            jdtManipulationInitialized = true;
        }
    }

    protected AbstractRefactoringTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    /**
     * Run the refactoring described by {@code descriptor} and return a
     * structured response. Subclasses that have a {@link JavaRefactoringDescriptor}
     * with public setters call this entry point.
     *
     * @param service          live IJdtService (already non-null per AbstractTool contract)
     * @param descriptor       fully-configured JDT refactoring descriptor
     * @param operationLabel   human-readable label included in the response
     *                         (e.g. {@code "move_class"}); also used for log lines
     */
    protected ToolResponse runRefactoring(IJdtService service,
                                          JavaRefactoringDescriptor descriptor,
                                          String operationLabel) {
        initializeJdtManipulation();
        try {
            // 1. Build the refactoring object from the descriptor.
            RefactoringStatus status = new RefactoringStatus();
            Refactoring refactoring = descriptor.createRefactoring(status);
            if (refactoring == null) {
                String reason = status.hasError()
                    ? formatStatus(status)
                    : "JDT could not instantiate refactoring '" + operationLabel + "'";
                return ToolResponse.invalidParameter(operationLabel, reason);
            }
            if (status.hasError()) {
                return ToolResponse.invalidParameter(operationLabel, formatStatus(status));
            }
            return runRefactoring(service, refactoring, operationLabel);
        } catch (Exception e) {
            log.warn("Refactoring '{}' threw unexpectedly: {}", operationLabel, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Run a pre-built {@link Refactoring} and return a structured response.
     * Subclasses that configure their refactoring via internal JDT processor
     * classes (Phase E pull_up / push_down / encapsulate_field — see
     * {@code docs/upgrade-checklist.md}) build the {@link Refactoring}
     * directly and call this entry point.
     */
    protected ToolResponse runRefactoring(IJdtService service,
                                          Refactoring refactoring,
                                          String operationLabel) {
        initializeJdtManipulation();
        try {
            // 2. Initial conditions — checks the inputs themselves.
            RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initial.hasFatalError()) {
                return ToolResponse.invalidParameter(operationLabel, formatStatus(initial));
            }

            // 3. Final conditions — checks the workspace impact (resolves all
            //    references, looks for conflicts, etc.). Failures here mean
            //    the refactoring is unsafe; nothing has been modified yet.
            RefactoringStatus finalStatus = refactoring.checkFinalConditions(new NullProgressMonitor());
            if (finalStatus.hasFatalError() || finalStatus.hasError()) {
                return refactoringFailed(operationLabel, finalStatus);
            }

            // 4. Compute the workspace change.
            Change change = refactoring.createChange(new NullProgressMonitor());
            if (change == null) {
                return refactoringFailed(operationLabel,
                    new RefactoringStatus() {{
                        addFatalError("createChange() returned null");
                    }});
            }

            // 4b. Initialise the change's validation state. LTK's
            //     PerformChangeOperation calls Change.isValid() during
            //     execution; without initializeValidationData(), TextFileChange
            //     (and friends) throw "has not been initialialized". Eclipse's
            //     refactoring wizard infrastructure does this implicitly via
            //     CreateChangeOperation; the headless path doesn't.
            change.initializeValidationData(new NullProgressMonitor());

            // 5. Perform the change. PerformChangeOperation handles undo
            //    history, validation, and cleanup. If the change throws,
            //    nothing partial is left behind by JDT's own contract.
            PerformChangeOperation perform = new PerformChangeOperation(change);
            perform.run(new NullProgressMonitor());

            RefactoringStatus validation = perform.getValidationStatus();
            if (validation != null && (validation.hasFatalError() || validation.hasError())) {
                return refactoringFailed(operationLabel, validation);
            }

            // 6. Collect modified compilation units (best-effort: walks the
            //    Change tree, picking out CompilationUnitChange descendants).
            List<Map<String, Object>> modifiedFiles = describeModifiedUnits(change, service);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", operationLabel);
            data.put("modifiedFiles", modifiedFiles);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(modifiedFiles.size())
                .returnedCount(modifiedFiles.size())
                .build());

        } catch (Exception e) {
            log.warn("Refactoring '{}' threw unexpectedly: {}", operationLabel, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Format an LTK {@link RefactoringStatus} into a single human-readable
     * string. We prefer the most-severe entry's message and fall back to a
     * compact list when there are multiple distinct messages.
     */
    protected static String formatStatus(RefactoringStatus status) {
        if (status == null || status.isOK()) {
            return "OK";
        }
        StringBuilder sb = new StringBuilder();
        sb.append(status.getMessageMatchingSeverity(status.getSeverity()));
        var entries = status.getEntries();
        if (entries.length > 1) {
            sb.append(" (");
            for (int i = 0; i < entries.length; i++) {
                if (i > 0) sb.append("; ");
                sb.append(entries[i].getMessage());
            }
            sb.append(")");
        }
        return sb.toString();
    }

    private ToolResponse refactoringFailed(String operationLabel, RefactoringStatus status) {
        String detail = formatStatus(status);
        return ToolResponse.error(
            "REFACTORING_FAILED",
            operationLabel + " failed (" + severityName(status.getSeverity()) + "): " + detail,
            "Inspect the conflict description and either adjust the input or fix the workspace state. "
                + "No files were modified."
        );
    }

    private static String severityName(int severity) {
        return switch (severity) {
            case RefactoringStatus.OK       -> "OK";
            case RefactoringStatus.INFO     -> "INFO";
            case RefactoringStatus.WARNING  -> "WARNING";
            case RefactoringStatus.ERROR    -> "ERROR";
            case RefactoringStatus.FATAL    -> "FATAL";
            default                         -> "UNKNOWN(" + severity + ")";
        };
    }

    /**
     * Walk the {@link Change} tree (JDT-LTK CompositeChange / TextFileChange /
     * etc.) collecting compilation units that were touched. Each entry has
     * {@code filePath} (formatted relative to project root via
     * {@link IJdtService#getPathUtils()}) and {@code summary} carrying the
     * change's name.
     */
    private List<Map<String, Object>> describeModifiedUnits(Change change, IJdtService service) {
        List<Map<String, Object>> out = new ArrayList<>();
        collectCompilationUnitChanges(change, out, service);
        return out;
    }

    private static void collectCompilationUnitChanges(Change change,
                                                      List<Map<String, Object>> out,
                                                      IJdtService service) {
        if (change == null) return;
        Object modified = change.getModifiedElement();
        if (modified instanceof ICompilationUnit cu) {
            Map<String, Object> entry = new LinkedHashMap<>();
            try {
                java.nio.file.Path absolute = cu.getResource().getLocation().toFile().toPath();
                entry.put("filePath", service.getPathUtils().formatPath(absolute));
            } catch (Exception ignore) {
                entry.put("filePath", String.valueOf(cu.getElementName()));
            }
            entry.put("summary", change.getName());
            out.add(entry);
        } else if (modified instanceof IJavaElement element) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("element", element.getElementName());
            entry.put("summary", change.getName());
            out.add(entry);
        }
        // Composite changes have children: recurse.
        if (change instanceof org.eclipse.ltk.core.refactoring.CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                collectCompilationUnitChanges(child, out, service);
            }
        }
    }
}
