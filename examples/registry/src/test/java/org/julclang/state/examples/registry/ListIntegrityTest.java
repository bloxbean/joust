package org.julclang.state.examples.registry;

import org.julclang.core.PlutusData;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptPurpose;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.registry.RegistryWorld.Kind;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.julclang.state.examples.registry.RegistryWorld.data;
import static org.julclang.state.examples.registry.RegistryWorld.hex;
import static org.julclang.state.examples.registry.Tamper.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * I47 on the ledger model (ADR-0016 §8): random sequences of inserts, removes, retires, keyed updates and clock
 * advances, over two instances and both keyed types, drawn from the test's reference model (never from the code under
 * test). Honest transactions are randomly mutated (outputs, values, datums, addresses, redeemers, extra inputs,
 * validity bounds, signers, mints). After every accepted transaction, honest or mutant:
 * <ul>
 *   <li>each list's head walk equals the reference sorted set (retired keys marked), with strictly increasing keys;</li>
 *   <li>the 0x4B units held anywhere are exactly the walks' units, each with total quantity 1.</li>
 * </ul>
 * A mutant the ledger model accepts must have the honest operation's effect on the lists. Steps, mutants per step and
 * the seed: {@code -Djoust.i47.steps}, {@code -Djoust.i47.mutants}, {@code -Djoust.i47.seed}.
 */
class ListIntegrityTest {

    static final int STEPS = Integer.getInteger("joust.i47.steps", 1000);
    static final int MUTANTS = Integer.getInteger("joust.i47.mutants", 4);

    /**
     * Why an accepted mutant differs from its honest transaction in a way the ADR does not allow, or null. Allowed
     * (D4b, D10, I3 amended): the relinked anchor holding more lovelace; the retired node's top-up changed while it
     * keeps at least minAda; extra signers; and the change output, which balances them.
     */
    String disallowed(RegistryWorld.Built b, Ledger.Tx t) {
        Ledger.Tx h = b.tx;
        if (!t.inputs.equals(h.inputs) || !t.referenceInputs.equals(h.referenceInputs)) return "other inputs";
        if (!t.mint.equals(h.mint)) return "another mint";
        if (!Map.copyOf(t.redeemers).equals(Map.copyOf(h.redeemers))) return "other redeemers";
        if (!java.util.Objects.equals(t.lower, h.lower) || !java.util.Objects.equals(t.upper, h.upper)) return "other bounds";
        var honestSigners = h.signers.stream().map(RegistryWorld::hex).toList();
        var signers = t.signers.stream().map(RegistryWorld::hex).toList();
        if (!signers.containsAll(honestSigners)) return "a signer removed";
        if (t.outputs.size() != h.outputs.size()) return "other outputs";
        boolean retiring = b.op.equals("retire") || b.op.equals("leave");
        for (int i = 0; i < h.outputs.size(); i++) {
            if (i == b.change) continue;
            TxOut o = t.outputs.get(i);
            TxOut x = h.outputs.get(i);
            if (!o.address().equals(x.address()) || !o.datum().equals(x.datum()) || !o.referenceScript().equals(x.referenceScript())) {
                return "output " + i + " changed beyond its lovelace";
            }
            var oe = new TreeMap<>(Ledger.entries(o.value()));
            var xe = new TreeMap<>(Ledger.entries(x.value()));
            BigInteger ol = o.value().lovelaceOf();
            BigInteger xl = x.value().lovelaceOf();
            oe.remove("");
            xe.remove("");
            if (!oe.equals(xe)) return "output " + i + "'s tokens changed";
            if (ol.equals(xl)) continue;
            if (i == b.anchorOut && ol.compareTo(xl) > 0) continue;
            if (retiring && i == b.nodeOut && ol.compareTo(w.minAda) >= 0) continue;
            return "output " + i + "'s lovelace changed (" + xl + " -> " + ol + ")";
        }
        return null;
    }
    static final long SEED = Long.getLong("joust.i47.seed", 47L);

    /** Key bytes rich in prefixes and in the ADR's ordering vectors (7f/80, ab/ab00, 00). */
    static final byte[] ALPHABET = {0x00, 0x61, 0x62, 0x7f, (byte) 0x80, (byte) 0xab, (byte) 0xff};

    RegistryWorld w;
    Random rnd;
    final List<byte[]> instances = new ArrayList<>();
    final Map<String, Integer> stats = new TreeMap<>();

    void count(String k) {
        stats.merge(k, 1, Integer::sum);
    }

    @Test
    void listsStayIntactUnderRandomOperationsAndMutations() {
        w = new RegistryWorld();
        rnd = new Random(SEED);
        instances.add(w.createInstance());
        instances.add(w.createInstance());
        w.checkLists();
        for (int step = 0; step < STEPS; step++) {
            RegistryWorld.Built b;
            try {
                b = drawOperation();
            } catch (NoOperation e) {
                continue;
            }
            if (b == null) continue;   // a clock advance
            count("op " + b.op);
            boolean applied = false;
            int mutants = rnd.nextDouble() < 0.7 ? 1 + rnd.nextInt(MUTANTS) : 0;
            for (int m = 0; m < mutants && !applied; m++) {
                var mutation = new ArrayList<String>();
                Ledger.Tx t = mutant(b, mutation);
                if (t == null || mutation.isEmpty() || fingerprint(t).equals(fingerprint(b.tx))) continue;   // a no-op
                count("mutants tried");
                Ledger.Result r = w.ledger.submit(t);
                if (r.accepted()) {
                    count("mutants accepted: " + String.join(" + ", mutation));
                    String why = disallowed(b, t);
                    if (why != null) fail("step " + step + ": a mutant of " + b.op + " was accepted with " + mutation + ": " + why);
                    b.effect.run();
                    try {
                        w.checkLists();
                    } catch (AssertionError e) {
                        fail("an accepted mutant (" + mutation + ") of " + b.op + " broke I47: " + e.getMessage());
                    }
                    applied = true;
                } else {
                    count(r.reason().startsWith("script failed") ? "mutants rejected by a script" : "mutants rejected in phase 1");
                }
            }
            if (!applied) {
                Ledger.Result r = w.submit(b);
                if (!r.accepted()) fail("step " + step + ": the honest " + b.op + " was rejected:\n" + r);
                count("honest accepted");
                w.checkLists();
            }
        }
        System.out.println("[I47] seed=" + SEED + " steps=" + STEPS + " mutants/step<=" + MUTANTS);
        stats.forEach((k, v) -> System.out.println("[I47]   " + k + ": " + v));
        int keys = w.model.values().stream().mapToInt(Map::size).sum();
        System.out.println("[I47]   keys at the end (live + retired, 4 lists): " + keys);
        int applied = stats.entrySet().stream().filter(e -> e.getKey().equals("honest accepted")
                || e.getKey().startsWith("mutants accepted")).mapToInt(Map.Entry::getValue).sum();
        assertTrue(applied > STEPS / 3, "a third of the steps applied an operation: " + applied);
        assertTrue(stats.getOrDefault("mutants rejected by a script", 0) > 0);
    }

    // ------------------------------------------------------------------ operations, from the reference model

    static final class NoOperation extends RuntimeException {
        NoOperation() {
            super(null, null, false, false);
        }
    }

    byte[] party() {
        return PARTIES.get(rnd.nextInt(PARTIES.size()));
    }

    byte[] randomName() {
        int len = rnd.nextDouble() < 0.1 ? 64 : 1 + rnd.nextInt(3);
        byte[] k = new byte[len];
        for (int i = 0; i < len; i++) k[i] = ALPHABET[rnd.nextInt(ALPHABET.length)];
        return k;
    }

    /** A live (or retired) key of a list, or null. */
    byte[] someKey(RegistryWorld.ListId l, boolean retired) {
        var ks = w.model.get(l).entrySet().stream().filter(e -> e.getValue() == retired).map(Map.Entry::getKey).toList();
        return ks.isEmpty() ? null : RegistryWorld.hex(ks.get(rnd.nextInt(ks.size())));
    }

    RegistryWorld.Built drawOperation() {
        byte[] inst = instances.get(rnd.nextInt(instances.size()));
        var entries = new RegistryWorld.ListId(hex(inst), Kind.ENTRY);
        var ballots = new RegistryWorld.ListId(hex(inst), Kind.BALLOT);
        double p = rnd.nextDouble();
        if (p < 0.08) {
            w.ledger.advance(rnd.nextInt(4) * 60_000L + rnd.nextInt(60) * 1_000L);
            count("clock advances");
            return null;
        }
        if (p < 0.38) {
            byte[] name = randomName();
            if (w.model.get(entries).containsKey(hex(name))) throw new NoOperation();
            if (rnd.nextDouble() < 0.15) return w.grant(inst, name, party());
            // Stakes of at least 2 ADA: a payout output below the ledger minimum (~1.06 ADA here) could never be paid.
            return w.claim(inst, party(), name, ada(2 + rnd.nextInt(4)));
        }
        if (p < 0.48) {
            byte[] voter = party();
            if (w.model.get(ballots).containsKey(hex(voter))) throw new NoOperation();
            return w.cast(inst, voter, BigInteger.valueOf(rnd.nextInt(10)));
        }
        if (p < 0.66) {
            byte[] name = someKey(entries, false);
            if (name == null) throw new NoOperation();
            return w.release(inst, name);
        }
        if (p < 0.74) {
            boolean ballot = rnd.nextBoolean();
            byte[] key = someKey(ballot ? ballots : entries, false);
            if (key == null) throw new NoOperation();
            return w.retire(inst, ballot ? Kind.BALLOT : Kind.ENTRY, key, rnd.nextDouble() < 0.2 ? ada(1) : BigInteger.ZERO);
        }
        if (p < 0.82) {
            byte[] voter = someKey(ballots, false);
            if (voter == null) throw new NoOperation();
            return w.change(inst, voter, BigInteger.valueOf(rnd.nextInt(10)));
        }
        byte[] name = someKey(entries, false);
        if (name == null) throw new NoOperation();
        var e = RegistryWorld.entryOf(w.node(inst, Kind.ENTRY, name).datum());
        double q = rnd.nextDouble();
        if (q < 0.4) return w.upgrade(inst, name, e.owner(), ada(2), BigInteger.valueOf(rnd.nextInt(4)));
        // Withdraw all of a small stake, or 2 ADA of a larger one: never leave a stake under 2 ADA to pay out later.
        if (q < 0.7 && e.stake().compareTo(ada(2)) >= 0) {
            return w.withdraw(inst, name, e.owner(), e.stake().compareTo(ada(4)) < 0 ? e.stake() : ada(2));
        }
        var gs = new ArrayList<byte[]>();
        for (byte[] g : e.guardians()) gs.add(g);
        return w.recover(inst, name, party(), gs);
    }

    // ------------------------------------------------------------------ mutations

    /** 1 or 2 random mutations of {@code b}'s transaction, rebalanced; null if it cannot be balanced. */
    Ledger.Tx mutant(RegistryWorld.Built b, List<String> names) {
        Ledger.Tx t = b.tx.copy();
        int change = b.change;
        int n = 1 + (rnd.nextDouble() < 0.3 ? 1 : 0);
        try {
            for (int i = 0; i < n; i++) change = mutateOnce(b, t, change, names);
        } catch (RuntimeException e) {
            return null;   // a combination this generator cannot express (e.g. a mutation of an already mutated datum)
        }
        return w.rebalance(t, change) ? t : null;
    }

    List<Integer> stateOutputs(Ledger.Tx t, int change) {
        var out = new ArrayList<Integer>();
        for (int i = 0; i < t.outputs.size(); i++) if (i != change && t.outputs.get(i).address().credential().equals(w.state.credential())) out.add(i);
        return out;
    }

    <T> T pick(List<T> xs) {
        return xs.get(rnd.nextInt(xs.size()));
    }

    /** One mutation; returns the change output's index afterwards. */
    int mutateOnce(RegistryWorld.Built b, Ledger.Tx t, int change, List<String> names) {
        var outs = stateOutputs(t, change);
        int kind = rnd.nextInt(13);
        if (outs.isEmpty() && kind < 6) kind = 6 + rnd.nextInt(7);
        switch (kind) {
            case 0 -> {
                int i = pick(outs);
                BigInteger d = pick(List.of(BigInteger.ONE, BigInteger.ONE.negate(), ada(1), ada(1).negate()));
                boolean retiring = b.op.equals("retire") || b.op.equals("leave");
                String where = i == b.anchorOut ? "the anchor" : i == b.nodeOut ? (retiring ? "the retired node" : "the node")
                        : "another output";
                names.add("lovelace " + (d.signum() > 0 ? "+" : "-") + " on " + where);
                mapOutput(t, i, o -> plusLovelace(o, d));
            }
            case 1, 2 -> {
                int i = pick(outs);
                if (!(t.outputs.get(i).datum() instanceof OutputDatum.OutputDatumInline d)) break;
                PlutusData changed = leaf(d.datum());
                if (changed.equals(d.datum())) break;
                names.add("datum leaf");
                mapOutput(t, i, o -> withDatum(o, changed));
            }
            case 3 -> {
                int i = pick(outs);
                names.add("address");
                mapOutput(t, i, o -> rnd.nextBoolean() ? withStake(o) : withAddress(o, StateHarness.pkhAddress(party())));
            }
            case 4 -> {
                int i = pick(outs);
                names.add(rnd.nextBoolean() ? "reference script" : "datum hash");
                mapOutput(t, i, names.get(names.size() - 1).equals("datum hash") ? Tamper::withDatumHash : Tamper::withRefScript);
            }
            case 5 -> {
                int i = pick(outs);
                var tokens = Ledger.entries(t.outputs.get(i).value()).get(hex(w.policy));
                if (tokens == null) break;
                String name = pick(new ArrayList<>(tokens.keySet()));
                names.add("token moved to the change");
                mapOutput(t, i, o -> plusToken(o, w.policy, RegistryWorld.hex(name), BigInteger.ONE.negate()));
            }
            case 6 -> {
                names.add("extra mint");
                byte[] name = rnd.nextBoolean() && !t.mint.isEmpty()
                        ? RegistryWorld.hex(pick(new ArrayList<>(t.mint.values().iterator().next().keySet())))
                        : RegistryWorld.keyIdOfType(pick(instances), rnd.nextBoolean() ? "Entry" : "Ballot", randomName());
                t.mint(w.policy, name, rnd.nextBoolean() ? BigInteger.ONE : BigInteger.ONE.negate());
                if (t.mint.isEmpty()) t.redeemers.remove(w.mint());
                else if (!t.redeemers.containsKey(w.mint())) t.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()));
            }
            case 7 -> {
                var ps = new ArrayList<>(t.redeemers.keySet());
                if (ps.isEmpty()) break;
                ScriptPurpose p = pick(ps);
                PlutusData changed = leaf(t.redeemers.get(p));
                if (changed.equals(t.redeemers.get(p))) break;
                names.add("redeemer leaf");
                t.redeemers.put(p, changed);
            }
            case 8 -> {
                var spends = t.redeemers.keySet().stream().filter(p -> p instanceof ScriptPurpose.Spending).toList();
                if (spends.isEmpty()) break;
                ScriptPurpose p = pick(spends);
                PlutusData other = data(otherRedeemer(t));
                if (other.equals(t.redeemers.get(p))) break;
                names.add("spend redeemer swapped");
                t.redeemers.put(p, other);
            }
            case 9 -> {
                var others = w.ledger.at(w.state).stream().map(Map.Entry::getKey).filter(r -> !t.inputs.contains(r)).toList();
                if (others.isEmpty()) break;
                TxOutRef x = pick(others);
                names.add("extra input with Link");
                t.inputs.add(x);
                t.redeemers.put(RegistryWorld.spend(x), data(new RegistryValidator.Link()));
                t.outputs.add(0, w.ledger.utxo(x));
                change++;
            }
            case 10 -> {
                if (t.lower == null && t.upper == null) break;
                names.add("validity");
                switch (rnd.nextInt(4)) {
                    case 0 -> t.lower = t.lower == null ? null : t.lower.subtract(BigInteger.valueOf(RegistryWorld.C));
                    case 1 -> t.upper = t.upper == null ? null : t.upper.add(BigInteger.valueOf(1_000));
                    case 2 -> t.upper = null;
                    default -> t.lower = null;
                }
            }
            case 11 -> {
                if (!t.signers.isEmpty() && rnd.nextBoolean()) {
                    names.add("a required signer dropped (its witness kept)");
                    t.signers.remove(rnd.nextInt(t.signers.size()));
                } else {
                    names.add("an extra signer");
                    t.signers.add(party());
                }
            }
            default -> {
                // Another anchor: a random state UTxO in place of the structural operation's anchor.
                if (b.anchor == null || !t.inputs.contains(b.anchor)) break;
                var others = w.ledger.at(w.state).stream().filter(e -> !t.inputs.contains(e.getKey()))
                        .filter(e -> e.getValue().datum() instanceof OutputDatum.OutputDatumInline d
                                && d.datum() instanceof PlutusData.ConstrData c && c.fields().size() >= 3)
                        .map(Map.Entry::getKey).toList();
                if (others.isEmpty()) break;
                TxOutRef x = pick(others);
                names.add("another anchor");
                t.inputs.set(t.inputs.indexOf(b.anchor), x);
                t.redeemers.put(RegistryWorld.spend(x), t.redeemers.remove(RegistryWorld.spend(b.anchor)));
                PlutusData mr = t.redeemers.get(w.mint());
                int field = b.op.equals("release") ? 0 : 1;
                t.redeemers.put(w.mint(), field(mr, field, data(x)));
                if (b.anchorOut >= 0 && t.outputs.get(b.anchorOut).datum() instanceof OutputDatum.OutputDatumInline) {
                    var xd = (PlutusData.ConstrData) ((OutputDatum.OutputDatumInline) w.ledger.utxo(x).datum()).datum();
                    var old = datum(t.outputs.get(b.anchorOut));
                    t.outputs.set(b.anchorOut, new TxOut(w.ledger.utxo(x).address(), w.ledger.utxo(x).value(),
                            new OutputDatum.OutputDatumInline(field(field(xd, 1, old.fields().get(1)), 2, old.fields().get(2))),
                            java.util.Optional.empty()));
                }
            }
        }
        return change;
    }

    /** Everything a transaction says, comparable with equals. */
    static List<Object> fingerprint(Ledger.Tx t) {
        var outs = new ArrayList<Object>();
        for (TxOut o : t.outputs) outs.add(List.of(o.address(), Ledger.entries(o.value()), o.datum(), o.referenceScript()));
        var signers = t.signers.stream().map(RegistryWorld::hex).sorted().toList();
        var witnesses = t.witnesses.stream().map(RegistryWorld::hex).sorted().distinct().toList();
        return java.util.Arrays.asList(List.copyOf(t.inputs), List.copyOf(t.referenceInputs), outs, t.mint, signers, witnesses, t.lower,
                t.upper, Map.copyOf(t.redeemers), t.fee, Map.copyOf(t.withdrawals));
    }

    Object otherRedeemer(Ledger.Tx t) {
        BigInteger lo = t.lower == null ? w.ledger.slotStart() : t.lower;
        BigInteger hi = t.upper == null ? lo.add(BigInteger.valueOf(RegistryWorld.W)) : t.upper;
        Ctx ctx = new Ctx(party(), BigInteger.ZERO, BigInteger.ZERO, lo, hi);
        return pick(List.of(new RegistryValidator.Link(), new RegistryValidator.Change(BigInteger.ONE),
                new RegistryValidator.Upgrade(BigInteger.ONE, ctx), new RegistryValidator.Withdraw(BigInteger.ONE, ctx),
                new RegistryValidator.Recover(party()), new RegistryValidator.Leave(ctx), new RegistryValidator.Release(ctx),
                new RegistryValidator.Retire(ctx), new RegistryValidator.SetOpen(false)));
    }

    /** {@code d} with one random leaf changed: a byte string grown, shrunk or flipped; an integer moved; a tag moved. */
    PlutusData leaf(PlutusData d) {
        return switch (d) {
            case PlutusData.ConstrData c when c.fields().isEmpty() || rnd.nextInt(5) == 0 ->
                    new PlutusData.ConstrData(Math.max(0, c.tag() + (rnd.nextBoolean() ? 1 : -1)), c.fields());
            case PlutusData.ConstrData c -> {
                int i = rnd.nextInt(c.fields().size());
                var fs = new ArrayList<>(c.fields());
                fs.set(i, leaf(fs.get(i)));
                yield new PlutusData.ConstrData(c.constructorTag(), fs);
            }
            case PlutusData.ListData l when !l.items().isEmpty() -> {
                int i = rnd.nextInt(l.items().size());
                var xs = new ArrayList<>(l.items());
                if (rnd.nextBoolean()) xs.set(i, leaf(xs.get(i)));
                else xs.remove(i);
                yield new PlutusData.ListData(xs);
            }
            case PlutusData.BytesData b -> {
                byte[] v = b.value();
                yield switch (rnd.nextInt(4)) {
                    case 0 -> new PlutusData.BytesData(java.util.Arrays.copyOf(v, v.length + 1));
                    case 1 -> new PlutusData.BytesData(v.length == 0 ? new byte[]{0x61} : java.util.Arrays.copyOf(v, v.length - 1));
                    case 2 -> {
                        byte[] x = v.length == 0 ? new byte[]{0} : v.clone();
                        x[0] ^= 0x01;
                        yield new PlutusData.BytesData(x);
                    }
                    default -> new PlutusData.BytesData(randomName());
                };
            }
            case PlutusData.IntData i -> new PlutusData.IntData(pick(List.of(i.value().add(BigInteger.ONE),
                    i.value().subtract(BigInteger.ONE), i.value().add(BigInteger.valueOf(60_000)), BigInteger.ZERO)));
            default -> d;
        };
    }
}
