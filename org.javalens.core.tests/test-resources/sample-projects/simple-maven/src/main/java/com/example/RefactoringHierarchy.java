package com.example;

/**
 * Fixture for Sprint 11 Phase E pull_up / push_down tests.
 *
 * <p>Two-class hierarchy purpose-built for refactoring tests:</p>
 * <ul>
 *   <li>{@link RefactoringBase#commonMethod()} — exists only on the base;
 *       can be pushed down to {@link RefactoringDerived}.</li>
 *   <li>{@link RefactoringDerived#uniqueMethod()} — exists only on the
 *       subtype; can be pulled up to {@link RefactoringBase}.</li>
 *   <li>{@link RefactoringDerived#fieldToEncapsulate} — public field with
 *       no existing accessor; usable for encapsulate_field tests.</li>
 * </ul>
 */
class RefactoringBase {
    void commonMethod() {
        // Pushable down to derived — supertype only.
    }
}

class RefactoringDerived extends RefactoringBase {
    public int fieldToEncapsulate;

    void uniqueMethod() {
        // Pullable up to base — subtype only.
    }
}
