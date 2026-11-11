package @@package@@;

import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CounterTest {

    static final byte[] ALICE = StateHarness.fill(28, 0x11);
    static final byte[] BOB = StateHarness.fill(28, 0x22);

    final StateHarness<Counter.State> h = StateHarness.of(CounterClient.SPEC);

    @Test
    void plainJava() {
        var s0 = Counter.init(h.ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO));
        var s1 = Counter.increment(s0, BigInteger.valueOf(5));
        assertEquals(BigInteger.valueOf(5), Counter.count(s1));
        var e = assertThrows(RequireException.class, () -> Counter.increment(s1, BigInteger.ZERO));
        assertEquals("increment must be positive", e.getMessage());
    }

    @Test
    void incrementOnChain() {
        var live = h.live(new Counter.State(BigInteger.valueOf(3), ALICE), BigInteger.ZERO);
        var next = Counter.increment(live.state(), BigInteger.TEN);
        var tx = h.act(live, new CounterValidator.Increment(BigInteger.TEN), next, null, null);
        System.out.println(h.assertAccepted(tx));
        Attacks.assertAllRejected(h, tx);
    }

    @Test
    void resetByStrangerRejectedOnChain() {
        var live = h.live(new Counter.State(BigInteger.valueOf(42), ALICE), BigInteger.ZERO);
        h.assertRejected(h.act(live, new CounterValidator.Reset(), Counter.reset(live.state()), BOB, null), "@OnlyOwner");
    }
}
