package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.julclang.state.examples.devkit.Fixtures;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D14 (M4c): the runtime engine evaluates validators without a chain. Each Counter and Bank transaction the
 * dApp's DevKit run recorded is evaluated against its snapshot ({@link Offline#evaluate}): its scripts accept it, and a
 * tampered one (the state's datum replaced) is refused by them. Every mutation of the attack suite ({@link Offline#attacks})
 * is refused. This is the M4 entry gate, checked directly: the offline tests' ledger runs exactly this.
 */
class OfflineTest {

    static final Path FIXTURES = Path.of("../fixtures/m0");

    @Test
    void recordedTransactions_evaluateWithoutAChain_andEveryAttackIsRefused() throws Exception {
        JsonNode clock = DescribedReplayTest.JSON.readTree(FIXTURES.resolve("devkit.json").toFile());
        long zeroSlot = clock.get("zeroSlot").asLong(), zeroTime = clock.get("startTimeMs").asLong(), slot = clock.get("slotLengthMs").asLong();
        int evaluated = 0, scripts = 0, attacks = 0;
        var accepted = new ArrayList<String>();
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            for (Path f : files.sorted().toList()) {
                JsonNode fixture = Fixtures.read(f);
                if (fixture.get("call") == null) continue;
                String tx = fixture.get("unsignedTx").asText();
                String snapshot = Fixtures.snapshot(fixture).toJson();
                var r = Offline.evaluate(new Offline.EvaluateRequest(tx, snapshot, zeroSlot, zeroTime, slot));
                assertTrue(r.ok(), f.getFileName() + ": " + r.error());
                evaluated++;
                scripts += r.redeemers().size();
                if (r.redeemers().isEmpty()) continue;
                var a = Offline.attacks(new Offline.EvaluateRequest(tx, snapshot, zeroSlot, zeroTime, slot), 0);
                assertTrue(a.ok(), f.getFileName() + ": " + a.error());
                for (var x : a.attacks()) {
                    attacks++;
                    if (!x.refused()) accepted.add(f.getFileName() + ": " + x.name() + " " + x.detail());
                }
                // Tampered by hand: the first script output's datum replaced.
                Transaction t = Transaction.deserialize(HexUtil.decodeHexString(tx));
                var out = t.getBody().getOutputs().stream().filter(o -> Offline.scriptAddress(o.getAddress()) && o.getInlineDatum() != null).findFirst();
                if (out.isPresent()) {
                    out.get().setInlineDatum(BigIntPlutusData.of(7));
                    var bad = Offline.evaluate(new Offline.EvaluateRequest(HexUtil.encodeHexString(t.serialize()), snapshot, zeroSlot, zeroTime, slot));
                    assertFalse(bad.ok(), f.getFileName() + ": a replaced datum is accepted");
                }
            }
        }
        assertEquals(List.of(), accepted, "mutations the scripts accept");
        // fixtures/m0/dapp: 25 transactions since the serverless demo was dropped (ADR-0020 §12.3).
        assertTrue(evaluated >= 25 && attacks > 100, evaluated + " evaluated, " + attacks + " attacks");
        System.out.println("[M4 gate] " + evaluated + " recorded transactions evaluated without a chain (" + scripts + " script runs); "
                + attacks + " attacks, every one refused by the scripts");
        // A transaction whose inputs the snapshot lacks is not evaluated (no guess).
        JsonNode any;
        try (var files = Files.list(FIXTURES.resolve("dapp"))) {
            any = Fixtures.read(files.sorted().filter(p -> {
                try {
                    return Fixtures.read(p).get("call") != null;
                } catch (Exception e) {
                    return false;
                }
            }).findFirst().orElseThrow());
        }
        var none = Offline.evaluate(new Offline.EvaluateRequest(any.get("unsignedTx").asText(), "{}", zeroSlot, zeroTime, slot));
        assertFalse(none.ok());
    }

    // ---- range attacks: on every transaction with both bounds, unless the caller says the window is bookkeeping

    /** A body with the window [100, 160) (slots) and a redeemer without Ctx. */
    static Transaction windowed(boolean start) {
        var body = TransactionBody.builder().inputs(new ArrayList<>()).outputs(new ArrayList<>())
                .fee(BigInteger.valueOf(200_000)).ttl(160).validityStartInterval(start ? 100 : 0).build();
        var ws = new TransactionWitnessSet();
        ws.setRedeemers(new ArrayList<>(List.of(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.ZERO).data(BigIntPlutusData.of(1))
                .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build())));
        return Transaction.builder().body(body).witnessSet(ws).build();
    }

    @Test
    void bothBounds_areAttacked_evenWithoutCtx() {
        // A @ValidAfter/@ValidBefore method without Ctx reads tx.validRange directly: its redeemer carries no window, and
        // its bounds are attacked all the same (review M4-D1).
        assertTrue(Offline.rangeAttacked(windowed(true), false));
        assertFalse(Offline.rangeAttacked(windowed(false), false), "a TTL only: no script reads a window");
    }

    @Test
    void theCallersUntimedHint_skipsTheRangeAttacks() {
        // A keyed action or retire whose method reads no time: its attempt's window is bookkeeping (Registry's change).
        assertFalse(Offline.rangeAttacked(windowed(true), true));
    }

    /** Review r2 (#82 S5): only a script's refusal counts as refused; an evaluator or suite error does not. */
    @Test
    void onlyAScriptsRefusal_countsAsRefused() {
        assertTrue(Offline.scriptRefusal("Script evaluation failed for SPEND[0]: the datum is not the state"));
        assertTrue(Offline.scriptRefusal("Budget exhausted for MINT[0]"));
        assertFalse(Offline.scriptRefusal("the snapshot has no output aa#0"));
        assertFalse(Offline.scriptRefusal(null));
    }
}
