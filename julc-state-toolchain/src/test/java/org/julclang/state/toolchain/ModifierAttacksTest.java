package org.julclang.state.toolchain;

import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.JscV2M1cGateTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c reviews C6 and F13, on the Java examples (whose descriptions drive the attacks): every method with
 * {@code signedBy}/{@code validAfter} policies receives its modifier attacks, and the attacker is never an authorised
 * party, even when an owner's key is the attacker's seed.
 */
class ModifierAttacksTest {

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    static List<String> names(String contract, Call c) throws Exception {
        var impl = JscV2M1cGateTest.java(contract);
        Run r = run(impl, c);
        assertTrue(r.verdict().accepted(), contract + " " + c.label() + "\n" + r.verdict());
        assertTrue(Attacks.assertAllRejected(impl.h, r.tx()) > 0);
        return Attacks.applicable(impl.h, r.tx()).stream().map(Attacks.Attack::name).toList();
    }

    static void has(List<String> names, String... wanted) {
        for (String w : wanted) assertTrue(names.contains(w), "missing modifier attack '" + w + "' in " + names);
    }

    @Test
    void theJavaExamples_receiveTheModifierAttacks() throws Exception {
        long vaultMin = JscV2M1cGateTest.java("Vault").h.minAda();
        List<Object> vault = List.of(OWNER, PAST, ada(10));
        var withdraw = names("Vault", new Call("withdraw", "action", "withdraw", vault, List.of(ada(3)), OWNER, BigInteger.ZERO, ada(10)));
        has(withdraw, "withdraw: a stranger signs as the caller (only owner)", "withdraw: a window ending before unlockAt (after unlockAt)",
                "withdraw: a window straddling unlockAt (after unlockAt)");
        has(names("Vault", new Call("close", "destroy", "close", vault, List.of(), OWNER, BigInteger.ZERO, ada(10).add(n(vaultMin)))),
                "close: a stranger signs as the caller (only owner)", "close: a window ending before unlockAt (after unlockAt)");
        long cfMin = JscV2M1cGateTest.java("Crowdfund").h.minAda();
        org.julclang.core.types.JulcMap<byte[], BigInteger> none = org.julclang.core.types.JulcMap.empty();
        has(names("Crowdfund", new Call("claim", "destroy", "claim", List.of(OWNER, ada(15), PAST, ada(2), ada(15), none.insert(ANN, ada(15))),
                List.of(), OWNER, BigInteger.ZERO, ada(15).add(n(cfMin)))), "claim: a stranger signs as the caller (only creator)");
        has(names("Crowdfund", new Call("close", "destroy", "close", List.of(OWNER, ada(100), PAST, ada(2), BigInteger.ZERO, none),
                List.of(), OWNER, BigInteger.ZERO, n(cfMin))), "close: a stranger signs as the caller (only creator)");
        long poolMin = JscV2M1cGateTest.java("Pool").h.minAda();
        List<Object> pool = List.of(OWNER, ada(5), n(2), ada(10), ada(1));
        has(names("Pool", new Call("withdraw", "action", "withdraw", pool, List.of(ada(1)), OWNER, BigInteger.ZERO, ada(3))),
                "withdraw: a stranger signs as the caller (only admin)");
        has(names("Pool", new Call("close", "destroy", "close", pool, List.of(), OWNER, BigInteger.ZERO, ada(3).add(n(poolMin)))),
                "close: a stranger signs as the caller (only admin)");
        long tokenMin = JscV2M1cGateTest.java("Token").h.minAda();
        has(names("Token", new Call("close", "destroy", "close", List.of(OWNER, BigInteger.ZERO, Map.of()), List.of(), OWNER, BigInteger.ZERO, n(tokenMin))),
                "close: a stranger signs as the caller (only admin)");
        System.out.println("[F13] modifier attacks skipped so far, by reason: " + Attacks.modifierSkips());
    }

    @Test
    void theAttacker_isNeverAnAuthorisedParty() throws Exception {
        // Codex's repro: the Java vault owned by the attacker key of r2 (28 bytes of 0xE5). The attack must still be one.
        byte[] sentinel = StateHarness.fill(28, 0xE5);
        var withdraw = names("Vault", new Call("withdraw", "action", "withdraw", List.of(sentinel, PAST, ada(10)), List.of(ada(3)), sentinel,
                BigInteger.ZERO, ada(10)));
        has(withdraw, "withdraw: a stranger signs as the caller (only owner)");   // derived past the taken seed, and rejected
    }
}
