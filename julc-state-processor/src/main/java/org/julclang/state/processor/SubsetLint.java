package org.julclang.state.processor;

import com.sun.source.tree.BinaryTree;
import com.sun.source.tree.CompoundAssignmentTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.UnaryTree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TaskEvent;
import com.sun.source.util.TaskListener;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import java.util.HashSet;
import java.util.Set;

/**
 * Rejects code whose JVM and on-chain meanings differ, in a {@code @StateContract} class: the same method runs in
 * both places, so they must agree (ADR-0001 D2, F12).
 * <p>
 * JuLC compiles {@code int}/{@code long} arithmetic as unbounded integers and {@code intValue()}/{@code valueOf} as
 * the identity, while the JVM wraps at 32/64 bits; {@code float}/{@code double} have no on-chain meaning. So contract
 * code computes in {@code BigInteger}. Two passes:
 * <ol>
 *   <li>syntactic, while processing: fixed-width primitive types written out, and narrowing calls;</li>
 *   <li>typed, after javac attributes the class: any arithmetic expression or local (including {@code var}) whose
 *       type is a fixed-width primitive, e.g. {@code BigInteger.valueOf(2147483647 + 1)}.</li>
 * </ol>
 * Comparisons of {@code int} results ({@code a.compareTo(b) >= 0}) and plain literals as arguments stay allowed.
 * Identity equality on arrays is rejected by JuLC itself (JULC0087).
 */
final class SubsetLint {

    private static final Set<TypeKind> FIXED_WIDTH = Set.of(
            TypeKind.INT, TypeKind.LONG, TypeKind.SHORT, TypeKind.CHAR, TypeKind.BYTE, TypeKind.FLOAT, TypeKind.DOUBLE);

    /** Written-out types rejected by the syntactic pass; {@code byte} is fine there, since {@code byte[]} is a bytestring. */
    private static final Set<TypeKind> FIXED_WIDTH_WRITTEN = Set.of(
            TypeKind.INT, TypeKind.LONG, TypeKind.SHORT, TypeKind.CHAR, TypeKind.FLOAT, TypeKind.DOUBLE);

    private static final Set<Tree.Kind> ARITHMETIC = Set.of(
            Tree.Kind.PLUS, Tree.Kind.MINUS, Tree.Kind.MULTIPLY, Tree.Kind.DIVIDE, Tree.Kind.REMAINDER,
            Tree.Kind.LEFT_SHIFT, Tree.Kind.RIGHT_SHIFT, Tree.Kind.UNSIGNED_RIGHT_SHIFT,
            Tree.Kind.AND, Tree.Kind.OR, Tree.Kind.XOR, Tree.Kind.UNARY_MINUS, Tree.Kind.UNARY_PLUS,
            Tree.Kind.BITWISE_COMPLEMENT, Tree.Kind.PREFIX_INCREMENT, Tree.Kind.PREFIX_DECREMENT,
            Tree.Kind.POSTFIX_INCREMENT, Tree.Kind.POSTFIX_DECREMENT);

    private static final Set<String> NARROWING_CALLS = Set.of(
            "intValue", "longValue", "shortValue", "byteValue", "intValueExact", "longValueExact", "shortValueExact",
            "byteValueExact", "doubleValue", "floatValue");

    private final ProcessingEnvironment env;
    private final Trees trees;
    private final Set<String> pending = new HashSet<>();
    /** Trees of the context javac compiles in after processing (taken in the final round); see {@link #finalRound}. */
    private volatile Trees finalTrees;

    SubsetLint(ProcessingEnvironment env) {
        this.env = env;
        Trees t;
        try {
            t = Trees.instance(env);
            JavacTask task = JavacTask.instance(env);
            task.addTaskListener(new TaskListener() {
                @Override
                public void finished(TaskEvent e) {
                    if (e.getKind() == TaskEvent.Kind.ANALYZE && e.getTypeElement() != null
                            && pending.remove(e.getTypeElement().getQualifiedName().toString())) {
                        // After annotation processing javac works in a fresh context: use the task's Trees and the
                        // event's own compilation unit, not what was captured during processing.
                        Trees typedTrees = finalTrees != null ? finalTrees : Trees.instance(task);
                        typed(typedTrees, e.getCompilationUnit(), e.getTypeElement().getSimpleName().toString());
                    }
                }
            });
        } catch (IllegalArgumentException | IllegalStateException notJavac) {
            t = null;   // not javac (e.g. ECJ): skip the lint; JuLC still checks its own subset
        }
        this.trees = t;
    }

    /**
     * Called in the last processing round. javac makes a new context per round, and the phases after processing run in
     * the last one; diagnostics printed through an earlier round's Trees are lost.
     */
    void finalRound() {
        if (trees == null) return;
        try {
            finalTrees = Trees.instance(env);
        } catch (IllegalArgumentException ignored) {
            // not javac
        }
    }

    /** Syntactic pass now; schedules the typed pass for when javac has attributed {@code type}. */
    boolean check(TypeElement type) {
        if (trees == null) return true;
        TreePath path = trees.getPath(type);
        if (path == null) return true;
        pending.add(type.getQualifiedName().toString());
        boolean[] clean = {true};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitPrimitiveType(PrimitiveTypeTree node, Void unused) {
                if (FIXED_WIDTH_WRITTEN.contains(node.getPrimitiveTypeKind())) {
                    clean[0] = false;
                    report(node, getCurrentPath(), "'" + node + "' in a @StateContract: JuLC treats it as an unbounded"
                            + " integer on-chain while the JVM wraps or rounds it, so the two runs could disagree."
                            + " Use BigInteger");
                }
                return super.visitPrimitiveType(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree select
                        && NARROWING_CALLS.contains(select.getIdentifier().toString())) {
                    clean[0] = false;
                    report(node, getCurrentPath(), select.getIdentifier() + "() in a @StateContract: it narrows on the"
                            + " JVM but is the identity on-chain, so the two runs could disagree. Stay in BigInteger");
                }
                return super.visitMethodInvocation(node, unused);
            }
        }.scan(path, null);
        return clean[0];
    }

    /** Typed pass: fixed-width arithmetic and fixed-width locals, wherever their types come from. */
    private void typed(Trees trees, com.sun.source.tree.CompilationUnitTree unit, String className) {
        for (Tree decl : unit.getTypeDecls()) {
            if (decl instanceof com.sun.source.tree.ClassTree ct && ct.getSimpleName().contentEquals(className)) {
                typedScan(trees, new TreePath(new TreePath(unit), decl));
            }
        }
    }

    private void typedScan(Trees trees, TreePath path) {
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitBinary(BinaryTree node, Void unused) {
                arithmetic(node);
                return super.visitBinary(node, unused);
            }

            @Override
            public Void visitUnary(UnaryTree node, Void unused) {
                arithmetic(node);
                return super.visitUnary(node, unused);
            }

            @Override
            public Void visitCompoundAssignment(CompoundAssignmentTree node, Void unused) {
                TypeMirror t = typeOf(node);
                if (t != null && FIXED_WIDTH.contains(t.getKind())) {
                    reportTyped(trees, node, getCurrentPath(), "fixed-width arithmetic (" + t + ") in a @StateContract: the JVM"
                            + " wraps, on-chain does not. Use BigInteger");
                }
                return super.visitCompoundAssignment(node, unused);
            }

            @Override
            public Void visitVariable(VariableTree node, Void unused) {
                TypeMirror t = typeOf(node);
                if (t != null && FIXED_WIDTH.contains(t.getKind())) {
                    // After attribution javac replaces `var` with the inferred type, so every fixed-width variable is
                    // reported here, written or inferred (written ones are also caught by the syntactic pass).
                    reportTyped(trees, node, getCurrentPath(), "'" + node.getName() + "' is " + t + " in a"
                            + " @StateContract (inferred or declared); fixed-width numbers diverge between the JVM and"
                            + " on-chain. Use BigInteger");
                }
                return super.visitVariable(node, unused);
            }

            private void arithmetic(ExpressionTree node) {
                if (!ARITHMETIC.contains(node.getKind())) return;
                TypeMirror t = typeOf(node);
                if (t != null && FIXED_WIDTH.contains(t.getKind())) {
                    reportTyped(trees, node, getCurrentPath(), "fixed-width arithmetic (" + t + ") in a @StateContract: the JVM"
                            + " wraps at " + (t.getKind() == TypeKind.LONG ? "64" : "32") + " bits, on-chain integers"
                            + " are unbounded, so the two runs could disagree. Compute in BigInteger");
                }
            }

            /** Type of the node being visited (the scanner's current path). */
            private TypeMirror typeOf(Tree node) {
                return getCurrentPath().getLeaf() == node ? trees.getTypeMirror(getCurrentPath()) : null;
            }
        }.scan(path, null);
    }

    private void report(Tree node, TreePath at, String message) {
        report(trees, node, at, message);
    }

    private static void report(Trees trees, Tree node, TreePath at, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, message, node, at.getCompilationUnit());
    }

    private static void reportTyped(Trees trees, Tree node, TreePath at, String message) {
        trees.printMessage(Diagnostic.Kind.ERROR, message, node, at.getCompilationUnit());
    }
}
