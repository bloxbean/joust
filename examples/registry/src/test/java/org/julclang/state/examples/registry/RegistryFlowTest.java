package org.julclang.state.examples.registry;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every keyed operation of the registry, accepted on the ledger model, with I47 checked after each. */
class RegistryFlowTest {

    @Test
    void everyOperation() {
        var w = new RegistryWorld();
        byte[] inst = w.createInstance();
        byte[] alice = PARTIES.get(0);
        byte[] bob = PARTIES.get(1);
        System.out.println("[flow] cast: " + w.accept(w.cast(inst, alice, BigInteger.TWO)));
        System.out.println("[flow] claim: " + w.accept(w.claim(inst, alice, new byte[]{0x61, 0x62}, ada(5))));
        System.out.println("[flow] claim before it: " + w.accept(w.claim(inst, alice, new byte[]{0x61}, ada(5))));
        System.out.println("[flow] grant: " + w.accept(w.grant(inst, new byte[]{0x7a}, bob)));
        System.out.println("[flow] change: " + w.accept(w.change(inst, alice, BigInteger.ONE)));
        System.out.println("[flow] upgrade: " + w.accept(w.upgrade(inst, new byte[]{0x61}, alice, ada(1))));
        System.out.println("[flow] withdraw: " + w.accept(w.withdraw(inst, new byte[]{0x61}, alice, ada(2))));
        System.out.println("[flow] recover: " + w.accept(w.recover(inst, new byte[]{0x7a}, alice, java.util.List.of(bob))));
        System.out.println("[flow] release: " + w.accept(w.release(inst, new byte[]{0x61, 0x62})));
        System.out.println("[flow] retire: " + w.accept(w.retire(inst, RegistryWorld.Kind.ENTRY, new byte[]{0x61}, BigInteger.ZERO)));
        System.out.println("[flow] leave: " + w.accept(w.retire(inst, RegistryWorld.Kind.BALLOT, alice, BigInteger.ZERO)));
    }

    /** M1 scope: the Java client and the description leave keyed calls out until M2/M3 (ADR-0016 §7). */
    @Test
    void theClientAndDescriptionOmitKeyedCallsForNow() throws Exception {
        assertThrows(UnsupportedOperationException.class, () -> RegistryClient.deploy(null, null));
        String json;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/joust/org.julclang.state.examples.registry.Registry.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8).replaceAll("\\s", "");
        }
        assertTrue(json.contains("\"keyed\""), "the description names the keyed types");
        assertFalse(json.contains("\"name\":\"cast\""), "no keyed creation is described yet");
        assertFalse(json.contains("\"name\":\"upgrade\""), "no keyed action is described yet");
        assertTrue(json.contains("\"name\":\"setOpen\""), "the instance's own calls are described");
    }
}
