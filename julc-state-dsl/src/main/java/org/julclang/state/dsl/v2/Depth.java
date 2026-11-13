package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.v2.Syntax.*;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The nesting cap on the tree the parser built (ADR-0018 J18, review S2). Every later pass (the checker, the emitter,
 * the interpreter, and javac and JuLC on the generated Java) recurses along this tree, so its depth is what bounds
 * them. The parser's own recursion is capped as it reads ({@link Parser#MAX_DEPTH}); chains it reads in a loop and
 * does not balance ({@code a / b / c}, {@code o ?? d}, {@code a == b == c}, {@code xs.f().g()}, {@code m[a][b]}) nest a
 * level per link, which only this measure sees. Each expression and each block is a level; statements and else-if
 * branches are not. The walk is iterative, so it is itself bounded by nothing but the file's size.
 */
final class Depth {
    private Depth() {}

    /** A node, its depth, and the fn whose body it is in (null in a method). */
    private record Item(Object node, int depth, String fn) {}

    static void check(File f) {
        var stack = new ArrayDeque<Item>();
        List<Item> roots = new ArrayList<>();
        for (Fn fn : f.fns()) roots.add(new Item(fn.body(), 0, fn.name()));
        for (Method m : f.contract().methods()) roots.add(new Item(m.body(), 0, null));
        for (Fn fn : f.contract().fns()) roots.add(new Item(fn.body(), 0, fn.name()));
        // M2b (#84 review C5): invariants are roots too.
        for (Syntax.InvariantDecl inv : f.contract().invariants()) roots.add(new Item(inv.body(), 0, null));
        for (int k = roots.size() - 1; k >= 0; k--) stack.push(roots.get(k));
        while (!stack.isEmpty()) {
            Item it = stack.pop();
            int d = it.node() instanceof Expr || it.node() instanceof Block ? it.depth() + 1 : it.depth();
            if (d > Parser.MAX_DEPTH) {
                Pos p = it.node() instanceof Expr e ? e.pos() : ((Block) it.node()).pos();
                throw p.error("JSC0150", "this expression nests deeper than " + Parser.MAX_DEPTH + " levels: a chain of /, %, ??, ==, field"
                        + " reads, method calls or [ ] counts a level per link, as does each branch of an else-if chain used as a value"
                        + " that stays inline (at most 8 branches, or one that calls its own fn); name a part with const or move it into"
                        + " a fn (ADR-0018 J18)");
            }
            List<Object> kids = children(it.node());
            // A short else-if chain used as a value lowers to nested conditionals: a level per branch.
            boolean nested = it.node() instanceof IfExpr ie && Checker.plainChain(ie, it.fn());
            // Pushed in reverse, so the walk is in source order and the first node over the cap is reported.
            for (int k = kids.size() - 1; k >= 0; k--) {
                if (kids.get(k) != null) stack.push(new Item(kids.get(k), nested ? d + Math.min(k / 2, kids.size() / 2 - 1) : d, it.fn()));
            }
        }
    }

    /** Whether {@code root} holds a call of {@code fn} (a work list: J18). */
    static boolean calls(Object root, String fn) {
        var work = new ArrayDeque<Object>();
        work.push(root);
        while (!work.isEmpty()) {
            Object n = work.pop();
            if (n instanceof Call c && c.fn().equals(fn)) return true;
            for (Object k : children(n)) if (k != null) work.push(k);
        }
        return false;
    }

    private static List<Object> children(Object n) {
        var out = new ArrayList<Object>();
        switch (n) {
            case Block b -> {
                out.addAll(b.stmts());
                out.add(b.result());
            }
            case Const c -> out.add(c.value());
            case Syntax.Require r -> out.add(r.cond());
            case Pay p -> {
                out.add(p.to());
                out.add(p.amount());
            }
            case For f -> {
                out.add(f.list());
                out.add(f.body());
            }
            case IfStmt i -> {
                for (Branch b : i.branches()) {
                    out.add(b.cond());
                    out.add(b.then());
                }
                out.add(i.otherwise());
            }
            case MatchStmt m -> out.add(m.match());
            // M2's effect statements (#84 review C5: emit's event, and send/mint/accept's arguments, were not walked).
            case Syntax.Send s -> {
                out.add(s.to());
                out.add(s.value());
            }
            case Syntax.Mint mt -> {
                out.add(mt.name());
                out.add(mt.qty());
            }
            case Syntax.Accept a -> out.add(a.value());
            case Syntax.Emit em -> out.add(em.event());
            case Field e -> out.add(e.target());
            case Call e -> out.addAll(e.args());
            case MethodCall e -> {
                out.add(e.target());
                out.addAll(e.args());
            }
            case Lambda e -> out.add(e.body());
            case ObjLit e -> {
                out.add(e.spread());
                for (FieldInit fi : e.fields()) out.add(fi.value());
            }
            case ListLit e -> {
                out.addAll(e.items());
                out.add(e.rest());
            }
            case Unary e -> out.add(e.operand());
            case Binary e -> {
                out.add(e.left());
                out.add(e.right());
            }
            case Cond e -> {
                out.add(e.cond());
                out.add(e.then());
                out.add(e.otherwise());
            }
            case IfExpr e -> {
                for (Branch b : e.branches()) {
                    out.add(b.cond());
                    out.add(b.then());
                }
                out.add(e.otherwise());
            }
            case BlockExpr e -> out.add(e.block());
            case Match e -> {
                out.add(e.scrutinee());
                for (Arm a : e.arms()) out.add(a.body());
            }
            case Index e -> {
                out.add(e.target());
                out.add(e.key());
            }
            default -> {
            }
        }
        return out;
    }
}
