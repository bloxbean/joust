package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * How a contract's effects are laid out in the generated Java and the ABI (ADR-0018 D4, M2), decided once from the IR
 * so the {@link JavaEmitter} and the ABI manifest ({@link Abi}) agree:
 * <ul>
 *   <li>a method takes the {@code Funds} parameter when it reads {@code funds}, sends, mints, burns or accepts;</li>
 *   <li>a payable method with {@code Funds} takes native assets (its redeemer carries {@code assetsIn});</li>
 *   <li>an action with effects returns {@code Outcome} (state, then the kinds its record holds), or {@code Received} when
 *       it takes native assets; each record holds every kind any of its actions produces, in the order payouts, sends,
 *       mint, accepted, events, so one record type serves them all (as the Java examples' records do);</li>
 *   <li>a destroy returns its payouts, or {@code Settled} (payouts, sends, mint, events) when a destroy of the contract
 *       sends, mints, burns or emits;</li>
 *   <li>a method whose result record has {@code events} (M2b) carries them last in its redeemer (ADR-0009 D5), empty when
 *       it emits none.</li>
 * </ul>
 * A contract whose only effect is {@code pay} keeps M1's layout exactly ({@code Outcome(state, payouts)} and
 * {@code JulcList<Payout>}), so its script does not change.
 */
final class Effects {
    private Effects() {}

    /** The record components of each kind, in their order. */
    static final List<String> ORDER = List.of("pay", "send", "mint", "accept", "emit");

    static String component(String kind) {
        return switch (kind) {
            case "pay" -> "payouts";
            case "send" -> "sends";
            case "mint" -> "mint";
            case "emit" -> "events";
            default -> "accepted";
        };
    }

    /** The effect kinds a method's body reaches, and whether it reads funds. */
    static JavaEmitter.Free usage(Ir.Method m) {
        return new JavaEmitter.Free().block(m.body());
    }

    static Set<String> kinds(Ir.Method m) {
        return usage(m).kinds;
    }

    /** Whether the method is lowered with {@code Funds} (D4). Views never are. */
    static boolean takesFunds(Ir.Method m) {
        if (m.kind() == Ir.Kind.VIEW) return false;
        var u = usage(m);
        return u.funds || u.kinds.contains("send") || u.kinds.contains("mint") || u.kinds.contains("accept");
    }

    static boolean payable(Ir.Method m) {
        return m.mods().stream().anyMatch(x -> x.name().equals("payable"));
    }

    /** A payable method with {@code Funds}: the redeemer carries the native assets offered ({@code assetsIn}, ADR-0007 r2). */
    static boolean takesAssets(Ir.Method m) {
        return payable(m) && takesFunds(m);
    }

    /**
     * The record an action returns ({@code Outcome}, {@code Received}, {@code Stepped}), or null when it returns the state.
     * A batched action never returns events (ADR-0009: not on {@code @Batched}): when a direct action's {@code Outcome}
     * would hold events, a batched action with effects (it can only pay) returns {@code Stepped(state, payouts)} instead
     * (#84 review F1, C4). Otherwise it shares {@code Outcome}, so earlier lowerings are unchanged.
     */
    static String actionRecord(Ir.Program p, Ir.Method m) {
        if (kinds(m).isEmpty()) return null;
        if (takesAssets(m)) return "Received";
        return Abi.batched(m) && directEvents(p) ? "Stepped" : "Outcome";
    }

    /** Whether a direct (not batched) action returning {@code Outcome} emits: its record then holds events. */
    private static boolean directEvents(Ir.Program p) {
        for (Ir.Method m : p.methods()) {
            if (m.kind() == Ir.Kind.ACTION && !Abi.batched(m) && !takesAssets(m) && kinds(m).contains("emit")) return true;
        }
        return false;
    }

    /** The kinds a record holds: every kind any of its methods produces, in {@link #ORDER}. */
    static List<String> recordKinds(Ir.Program p, String record) {
        var all = new TreeSet<String>();
        for (Ir.Method m : p.methods()) {
            if (m.kind() == Ir.Kind.ACTION && record.equals(actionRecord(p, m))) all.addAll(kinds(m));
            if (m.kind() == Ir.Kind.DESTROY && record.equals("Settled")) all.addAll(kinds(m));
        }
        if (record.equals("Received")) all.add("accept");
        var out = new ArrayList<String>();
        for (String k : ORDER) if (all.contains(k)) out.add(k);
        return out;
    }

    /** Whether the destroys return {@code Settled} (some destroy sends, mints or burns), else their payouts. */
    static boolean settled(Ir.Program p) {
        for (Ir.Method m : p.methods()) {
            if (m.kind() != Ir.Kind.DESTROY) continue;
            var k = kinds(m);
            if (k.contains("send") || k.contains("mint") || k.contains("emit")) return true;
        }
        return false;
    }

    /**
     * Whether the method takes the params (M2b, ADR-0009 D1): an init whenever the contract declares them (the creation
     * is made under the script applied to them), any other method when it reads them. Directly after the state, first in
     * an init.
     */
    static boolean takesParams(Ir.Program p, Ir.Method m) {
        if (p.params() == null) return false;
        return m.kind() == Ir.Kind.INIT || usage(m).params;
    }

    /** Whether the method's result carries events (M2b): its redeemer then carries them last (ADR-0009 D5). */
    static boolean events(Ir.Program p, Ir.Method m) {
        return switch (m.kind()) {
            case ACTION -> actionRecord(p, m) != null && recordKinds(p, actionRecord(p, m)).contains("emit");
            case DESTROY -> settled(p) && recordKinds(p, "Settled").contains("emit");
            default -> false;
        };
    }

    /** Whether some result record has a {@code mint} component: the mint validator then has {@code ByAction} (ADR-0007 D4). */
    static boolean mints(Ir.Program p) {
        for (String r : List.of("Outcome", "Received")) if (recordKinds(p, r).contains("mint")) return true;
        return settled(p) && recordKinds(p, "Settled").contains("mint");
    }
}
