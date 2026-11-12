package org.julclang.state.examples.dsl;

import org.julclang.state.api.Ctx;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.runtime.InMemoryNodeStore;
import org.julclang.state.runtime.MerkleSupport;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.julclang.state.testkit.TxModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D16, J13: the v1 examples migrated by {@code joust migrate} ({@code src/main/jsc/.../migrated}, checked to be
 * migrate's output by {@code MigrateTest}) behave as their v1 originals. Both are built here by the same Gradle build;
 * random calls must give the same next state (the map's contents included) or both refuse. The migrated validators
 * accept the honest transactions and reject the attack suite. Jar is not migrated: its batched action waits for M3.
 */
class MigratedTest {
    static final byte[][] KEYS = {StateHarness.fill(28, 0x0A), StateHarness.fill(28, 0xA1), StateHarness.fill(28, 0xB1)};
    static final HexFormat HEX = HexFormat.of();

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    static String run(Supplier<Object> call) {
        try {
            return String.valueOf(call.get());
        } catch (RuntimeException e) {
            return "refused";
        }
    }

    // ------------------------------------------------------------------ Guards: the arms' laziness survives migration

    @Test
    void guards_sameOutcomes_onTheJvm_andTheMigratedValidatorAcceptsThem() {
        for (int d = -120; d <= 120; d++) {
            var v1 = new Guards.State(KEYS[0], BigInteger.valueOf(d * 7L));
            var v2 = new org.julclang.state.examples.dsl.migrated.GuardsV2.State(KEYS[0], BigInteger.valueOf(d * 7L));
            BigInteger arg = BigInteger.valueOf(d);
            assertEquals(run(() -> Guards.set(v1, arg).n()), run(() -> org.julclang.state.examples.dsl.migrated.GuardsV2.set(v2, arg).n()), "set " + d);
            assertEquals(run(() -> Guards.check(v1, arg).n()), run(() -> org.julclang.state.examples.dsl.migrated.GuardsV2.check(v2, arg).n()), "check " + d);
        }
        var h = StateHarness.of(org.julclang.state.examples.dsl.migrated.GuardsV2Client.SPEC);
        var s = new org.julclang.state.examples.dsl.migrated.GuardsV2.State(KEYS[0], BigInteger.TEN);
        var live = h.live(s, BigInteger.ZERO);
        var zero = h.act(live, new org.julclang.state.examples.dsl.migrated.GuardsV2Validator.Set(BigInteger.ZERO),
                org.julclang.state.examples.dsl.migrated.GuardsV2.set(s, BigInteger.ZERO), null, null);
        System.out.println("[migrated guards set 0] " + h.assertAccepted(zero) + "  attacks rejected: " + Attacks.assertAllRejected(h, zero));
        h.assertAccepted(h.act(live, new org.julclang.state.examples.dsl.migrated.GuardsV2Validator.Set(BigInteger.valueOf(4)),
                new org.julclang.state.examples.dsl.migrated.GuardsV2.State(KEYS[0], BigInteger.valueOf(25)), null, null));
        h.assertRejected(h.act(live, new org.julclang.state.examples.dsl.migrated.GuardsV2Validator.Check(BigInteger.valueOf(200)),
                new org.julclang.state.examples.dsl.migrated.GuardsV2.State(KEYS[0], BigInteger.valueOf(200)), null, null), "100 / 200 > 1 is false");
    }

    // ------------------------------------------------------------------ Points: Map<Key, Int> became BigMap<Key, Int>

    final InMemoryNodeStore store = new InMemoryNodeStore();

    /** A call run with map reads served from the store; the next state as the runtime stores it, or "refused". */
    <S extends Record> Object call(StateHarness<S> h, Class<S> type, S current, Supplier<S> method) {
        var ms = new MerkleSupport(store);
        S next;
        try {
            next = ms.run(method);
        } catch (RuntimeException e) {
            return "refused";
        }
        return ms.prepare(h.policy(), type, current, next, StateHarness.LIVE_REF);
    }

    /** A stored state, field by field (the map as its root: equal contents, equal root). */
    static String describe(Object stored) {
        if (stored instanceof String s) return s;
        var p = (MerkleSupport.Prepared<?>) stored;
        var r = (Record) p.stored();
        var out = new StringBuilder();
        for (var c : r.getClass().getRecordComponents()) {
            try {
                Object v = c.getAccessor().invoke(r);
                out.append(c.getName()).append('=').append(v instanceof byte[] b ? HEX.formatHex(b)
                        : v instanceof org.julclang.state.api.MerkleMap m ? HEX.formatHex(m.root()) : v).append(' ');
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            }
        }
        return out.toString();
    }

    @Test
    void points_sameNextStates_onRandomCalls_andTheMigratedValidatorAcceptsThem() {
        var h1 = StateHarness.of(PointsClient.SPEC);
        var h2 = StateHarness.of(org.julclang.state.examples.dsl.migrated.PointsV2Client.SPEC);
        Ctx c0 = h1.ctx(KEYS[0], BigInteger.ZERO, BigInteger.ZERO);
        Points.State a = new MerkleSupport(store).prepareCreation(h1.policy(), Points.State.class, Points.init(c0), h1.seed()).stored();
        var b = new MerkleSupport(store).prepareCreation(h2.policy(), org.julclang.state.examples.dsl.migrated.PointsV2.State.class,
                org.julclang.state.examples.dsl.migrated.PointsV2.init(c0), h2.seed()).stored();
        var rnd = new Random(7);
        int calls = 0, refused = 0, onChain = 0;
        for (int i = 0; i < 400; i++) {
            byte[] caller = KEYS[rnd.nextInt(3)], to = KEYS[rnd.nextInt(3)];
            BigInteger n = BigInteger.valueOf(rnd.nextInt(12) - 1);
            Ctx ctx = h1.ctx(caller, BigInteger.ZERO, BigInteger.ZERO);
            Points.State sa = a;
            var sb = b;
            Object ra, rb;
            Object redeemer;
            switch (rnd.nextInt(3)) {
                case 0 -> {
                    ra = call(h1, Points.State.class, sa, () -> Points.award(sa, to, n));
                    rb = call(h2, org.julclang.state.examples.dsl.migrated.PointsV2.State.class, sb, () -> org.julclang.state.examples.dsl.migrated.PointsV2.award(sb, to, n));
                    redeemer = new org.julclang.state.examples.dsl.migrated.PointsV2Validator.Award(to, n);
                    caller = KEYS[0];   // only admin
                }
                case 1 -> {
                    ra = call(h1, Points.State.class, sa, () -> Points.spend(sa, n, ctx));
                    rb = call(h2, org.julclang.state.examples.dsl.migrated.PointsV2.State.class, sb, () -> org.julclang.state.examples.dsl.migrated.PointsV2.spend(sb, n, ctx));
                    redeemer = new org.julclang.state.examples.dsl.migrated.PointsV2Validator.Spend(n, ctx);
                }
                default -> {
                    ra = call(h1, Points.State.class, sa, () -> Points.give(sa, to, n, ctx));
                    rb = call(h2, org.julclang.state.examples.dsl.migrated.PointsV2.State.class, sb, () -> org.julclang.state.examples.dsl.migrated.PointsV2.give(sb, to, n, ctx));
                    redeemer = new org.julclang.state.examples.dsl.migrated.PointsV2Validator.Give(to, n, ctx);
                }
            }
            assertEquals(describe(ra), describe(rb), "call " + i);
            calls++;
            if (ra instanceof String) {
                refused++;
                continue;
            }
            @SuppressWarnings("unchecked")
            var pb = (MerkleSupport.Prepared<org.julclang.state.examples.dsl.migrated.PointsV2.State>) rb;
            if (i % 40 == 1) {
                // The migrated validator accepts the call it computed, with the map witnesses the runtime builds.
                TxModel tx = h2.withClaims(h2.act(h2.live(sb, BigInteger.ZERO), redeemer, pb.stored(), caller,
                        redeemer instanceof org.julclang.state.examples.dsl.migrated.PointsV2Validator.Award ? null : ctx), pb.claims());
                h2.assertAccepted(tx);
                onChain++;
            }
            @SuppressWarnings("unchecked")
            var pa = (MerkleSupport.Prepared<Points.State>) ra;
            a = pa.stored();
            b = pb.stored();
        }
        System.out.println("[migrated points] " + calls + " calls, " + refused + " refused by both, " + onChain
                + " accepted by the migrated validator: the same next states as v1");
        assertTrue(refused > 20 && refused < calls - 50, "refused " + refused);
        assertTrue(onChain >= 5);
        // Close: only when every point is spent, as in v1.
        Ctx close = h2.ctx(KEYS[0], BigInteger.ZERO, BigInteger.valueOf(org.julclang.state.examples.dsl.migrated.PointsV2Client.SPEC.minAda()));
        var sb = b;
        assertThrows(RuntimeException.class, () -> new MerkleSupport(store).run(() -> org.julclang.state.examples.dsl.migrated.PointsV2.close(sb, close)));
        var empty = new org.julclang.state.examples.dsl.migrated.PointsV2.State(KEYS[0], BigInteger.ZERO, MerkleMaps.empty());
        var out = org.julclang.state.examples.dsl.migrated.PointsV2.close(empty, close);
        assertEquals(1, out.size());
        assertEquals(BigInteger.valueOf(org.julclang.state.examples.dsl.migrated.PointsV2Client.SPEC.minAda()), out.get(0).lovelace());
    }
}
