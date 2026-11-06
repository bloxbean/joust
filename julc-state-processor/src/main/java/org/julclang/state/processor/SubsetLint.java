package org.julclang.state.processor;

import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.PrimitiveTypeTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeKind;
import javax.tools.Diagnostic;
import java.util.Set;

/**
 * Rejects code whose JVM and on-chain meanings differ, in a {@code @StateContract} class: the same method runs in
 * both places, so they must agree (ADR-0001 D2, review r1 finding "numeric divergence").
 * <p>
 * JuLC compiles {@code int}/{@code long} as unbounded integers and {@code intValue()}/{@code longValue()} as the
 * identity, while the JVM wraps at 32/64 bits; {@code float}/{@code double} have no on-chain meaning at all. Contract
 * code must use {@code BigInteger}. Identity equality on arrays is already rejected by JuLC itself (JULC0087).
 */
final class SubsetLint {

    private static final Set<TypeKind> FORBIDDEN_PRIMITIVES = Set.of(
            TypeKind.INT, TypeKind.LONG, TypeKind.SHORT, TypeKind.CHAR, TypeKind.FLOAT, TypeKind.DOUBLE);

    private static final Set<String> NARROWING_CALLS = Set.of(
            "intValue", "longValue", "shortValue", "byteValue", "intValueExact", "longValueExact", "shortValueExact",
            "byteValueExact", "doubleValue", "floatValue");

    private final ProcessingEnvironment env;
    private final Trees trees;

    SubsetLint(ProcessingEnvironment env) {
        this.env = env;
        Trees t;
        try {
            t = Trees.instance(env);
        } catch (IllegalArgumentException notJavac) {
            t = null;   // not javac (e.g. ECJ): skip the lint, JuLC still checks the subset
        }
        this.trees = t;
    }

    /** Reports every divergent construct in {@code type}; true if there were none. */
    boolean check(TypeElement type) {
        if (trees == null) return true;
        var path = trees.getPath(type);
        if (path == null) return true;
        boolean[] clean = {true};
        new TreePathScanner<Void, Void>() {
            @Override
            public Void visitPrimitiveType(PrimitiveTypeTree node, Void unused) {
                if (FORBIDDEN_PRIMITIVES.contains(node.getPrimitiveTypeKind())) {
                    report(node, "'" + node + "' in a @StateContract: JuLC treats it as an unbounded integer on-chain"
                            + " while the JVM wraps or rounds it, so the two runs could disagree. Use BigInteger");
                }
                return super.visitPrimitiveType(node, unused);
            }

            @Override
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                if (node.getMethodSelect() instanceof MemberSelectTree select
                        && NARROWING_CALLS.contains(select.getIdentifier().toString())) {
                    report(node, select.getIdentifier() + "() in a @StateContract: it narrows on the JVM but is the"
                            + " identity on-chain, so the two runs could disagree. Stay in BigInteger");
                }
                return super.visitMethodInvocation(node, unused);
            }

            private void report(Tree node, String message) {
                clean[0] = false;
                trees.printMessage(Diagnostic.Kind.ERROR, message, node, getCurrentPath().getCompilationUnit());
            }
        }.scan(path, null);
        return clean[0];
    }
}
