package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ExUnits;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import com.bloxbean.cardano.client.plutus.spec.Redeemer;
import com.bloxbean.cardano.client.plutus.spec.RedeemerTag;
import com.bloxbean.cardano.client.transaction.spec.Asset;
import com.bloxbean.cardano.client.transaction.spec.MultiAsset;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.Value;
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

    // ---- range attacks only on the bounds a script binds (as the JVM testkit gates them on a real range)

    static final long ZERO_TIME = 1_700_000_000_000L;
    static final Offline.EvaluateRequest CLOCK = new Offline.EvaluateRequest("", "{}", 0, ZERO_TIME, 1000);

    /** A body with the window [100, 160) (slots), a redeemer, and outputs. */
    static Transaction windowed(PlutusData redeemer, TransactionOutput... outputs) {
        var body = TransactionBody.builder().inputs(new ArrayList<>()).outputs(new ArrayList<>(List.of(outputs)))
                .fee(BigInteger.valueOf(200_000)).ttl(160).validityStartInterval(100).build();
        var ws = new TransactionWitnessSet();
        ws.setRedeemers(new ArrayList<>(List.of(Redeemer.builder().tag(RedeemerTag.Spend).index(BigInteger.ZERO).data(redeemer)
                .exUnits(ExUnits.builder().mem(BigInteger.ONE).steps(BigInteger.ONE).build()).build())));
        return Transaction.builder().body(body).witnessSet(ws).build();
    }
    static BigInteger ms(long slot) { return BigInteger.valueOf(ZERO_TIME + slot * 1000); }
    static ConstrPlutusData constr(long tag, PlutusData... fields) {
        return ConstrPlutusData.builder().alternative(tag).data(ListPlutusData.builder().plutusDataList(new ArrayList<>(List.of(fields))).build()).build();
    }
    /** A keyed node output: a 29-byte 0x4B token at a script address, Keyed_T(instance, next, linkedAt, value). */
    static TransactionOutput keyedNode(BigInteger linkedAt) {
        byte[] name = Offline.fill(29, 0x11);
        name[0] = 0x4B;
        var asset = Asset.builder().name("0x" + HexUtil.encodeHexString(name)).value(BigInteger.ONE).build();
        var value = Value.builder().coin(BigInteger.valueOf(2_000_000))
                .multiAssets(List.of(MultiAsset.builder().policyId(HexUtil.encodeHexString(Offline.fill(28, 0xAB))).assets(List.of(asset)).build())).build();
        String script = AddressProvider.getEntAddress(Credential.fromScript(Offline.fill(28, 0xAB)), Networks.testnet()).toBech32();
        return TransactionOutput.builder().address(script).value(value)
                .inlineDatum(constr(1, BytesPlutusData.of(Offline.fill(32, 1)), BytesPlutusData.of(Offline.fill(1, 2)), BigIntPlutusData.of(linkedAt),
                        BigIntPlutusData.of(7))).build();
    }

    @Test
    void anUntimedKeyedAction_bindsNoBound_soNoRangeAttack() {
        // Registry's change: no Ctx, its node continues with its old linkedAt; the attempt's window is bookkeeping only.
        var tx = windowed(constr(1, BigIntPlutusData.of(2)), keyedNode(ms(40)));
        assertEquals(Offline.Binding.NONE, Offline.binding(tx, CLOCK));
    }

    @Test
    void aCtxOrBatchCarryingTheWindow_bindsBothBounds() {
        var ctx = constr(0, BytesPlutusData.of(Offline.fill(28, 3)), BigIntPlutusData.of(0), BigIntPlutusData.of(0), BigIntPlutusData.of(ms(100)),
                BigIntPlutusData.of(ms(160)));
        assertEquals(Offline.Binding.CTX, Offline.binding(windowed(constr(2, BigIntPlutusData.of(5), ctx)), CLOCK));
        assertEquals(Offline.Binding.CTX, Offline.binding(windowed(constr(3, BigIntPlutusData.of(ms(100)), BigIntPlutusData.of(ms(160)))), CLOCK),
                "a batch's Batch(validFrom, validTo)");
        // A Ctx with another window binds nothing of this one.
        var other = constr(0, BytesPlutusData.of(Offline.fill(28, 3)), BigIntPlutusData.of(0), BigIntPlutusData.of(0), BigIntPlutusData.of(ms(99)),
                BigIntPlutusData.of(ms(160)));
        assertEquals(Offline.Binding.NONE, Offline.binding(windowed(constr(2, other)), CLOCK));
    }

    @Test
    void aLinkWithoutCtx_bindsTheUpperBoundAsLinkedAt() {
        var tx = windowed(constr(0, BytesPlutusData.of(Offline.fill(1, 9))), keyedNode(ms(160)));
        assertEquals(Offline.Binding.LINK, Offline.binding(tx, CLOCK));
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
