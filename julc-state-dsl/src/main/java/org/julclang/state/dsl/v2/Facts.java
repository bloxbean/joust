package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * ADR-0021 D5, D6 (M5b): what the checker records for the language core, and only when it is given one (the build gives
 * none, so its checking and lowering are unchanged, L5): the IR each expression was checked to (its type, its taint and,
 * for a name, what it refers to), every binding it made, and, in the recovering mode, the errors of each declaration
 * it checked separately.
 */
final class Facts {
    /** Each checked expression's IR (the last round's, when time fields take several rounds). */
    final IdentityHashMap<Syntax.Expr, Ir.Expr> typed = new IdentityHashMap<>();
    /** Every binding the checker made (parameters, consts, loop, lambda and match variables). */
    final List<Ir.Local> locals = new ArrayList<>();
    /** The recovering checker's errors: one per declaration, method or fn it could not check. */
    final List<JscException> errors = new ArrayList<>();
    /**
     * Names the file uses that the recovering mode cannot check: declarations the parser dropped (an error inside them)
     * and names imported from another file (resolved across files in M5c). A reference to one stops the check of the
     * declaration it is in without an error of its own (no cascade, ADR-0021 L13).
     */
    final Set<String> poisoned = new HashSet<>();
    /** Whether the parser dropped a declaration (so a file-level completeness rule cannot be judged). */
    boolean incomplete;
    /** Whether a name imported from another file was used (the analysis is advisory for it, M5c). */
    final Set<String> unresolvedImports = new HashSet<>();

    void clearRound() {
        typed.clear();
        locals.clear();
    }

    /** Whether a use of {@code name} is not to be judged here (it, or the contract it is qualified by, is poisoned). */
    boolean suppressed(String name) {
        if (incomplete || name == null) return incomplete;
        if (poisoned.contains(name)) return true;
        int dot = name.indexOf('.');
        return dot > 0 && poisoned.contains(name.substring(0, dot));
    }

    /** The code of {@link Poisoned}: never a diagnostic. */
    static final String POISONED = "JSC-POISONED";

    /** Thrown where a poisoned name is used: the declaration's check stops, reporting nothing (code {@link #POISONED}). */
    static JscException poisonedUse(String name) {
        return new JscException(1, 1, POISONED, "unchecked: " + name);
    }

    static boolean isPoisoned(JscException e) {
        return POISONED.equals(e.code);
    }
}
