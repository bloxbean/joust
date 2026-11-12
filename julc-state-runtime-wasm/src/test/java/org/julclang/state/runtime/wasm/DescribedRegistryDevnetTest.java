package org.julclang.state.runtime.wasm;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Utxo;
import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.backend.api.DefaultProtocolParamsSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultScriptSupplier;
import com.bloxbean.cardano.client.backend.api.DefaultUtxoSupplier;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.julclang.clientlib.eval.JulcTransactionEvaluator;
import org.julclang.clientlib.eval.SlotConfig;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.StateRuntime;
import org.julclang.state.runtime.WalletAccount;
import org.julclang.state.runtime.described.DescribedContract;
import org.julclang.state.runtime.described.UplcTransitions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * ADR-0016 M3 on a live DevKit, JVM: the registry through the described dispatcher (descriptions and transition
 * programs, no contract classes), as the Wasm engine runs it. The instance with its heads, then every keyed call kind
 * prepared for a wallet ({@code Compose.prepare()}): each spends the input the page designated, and is signed outside.
 */
@Tag("devnet")
class DescribedRegistryDevnetTest {

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    static final ObjectMapper JSON = new ObjectMapper();

    /** A DevKit runtime with a page's window limits (ADR-0016 D7): what the engine uses for a wallet's keyed call. */
    static StateRuntime pageRuntime() {
        BackendService backend = DevKit.backend();
        var time = DevKit.chainTime(backend);
        return StateRuntime.builder(backend).network(Networks.testnet()).chainTime(time).ttl(Duration.ofSeconds(20))
                .pageLimits(60, 900)
                .log(msg -> System.out.println("  [joust] " + msg))
                .evaluator(new JulcTransactionEvaluator(new DefaultUtxoSupplier(backend.getUtxoService()),
                        new DefaultProtocolParamsSupplier(backend.getEpochService()),
                        new DefaultScriptSupplier(backend.getScriptService()),
                        new SlotConfig(time.zeroSlot(), time.zeroTimeMs(), time.slotLengthMs())))
                .build();
    }

    /** The largest lovelace-only UTxO of {@code a}: the page's choice of a designated input. */
    static String designate(StateRuntime rt, Account a) throws Exception {
        List<Utxo> us = rt.backend().getUtxoService().getUtxos(a.baseAddress(), 100, 1).getValue();
        return us.stream().filter(u -> u.getAmount().size() == 1)
                .max(java.util.Comparator.comparing((Utxo u) -> u.getAmount().get(0).getQuantity()))
                .map(u -> u.getTxHash() + "#" + u.getOutputIndex()).orElseThrow();
    }

    static String sign(Account keys, String cborHex) {
        try {
            byte[] hash = HexUtil.decodeHexString(TransactionUtil.getTxHash(HexUtil.decodeHexString(cborHex)));
            byte[] sig = CryptoConfiguration.INSTANCE.getSigningProvider().signExtended(hash, keys.hdKeyPair().getPrivateKey().getKeyData());
            var ws = new TransactionWitnessSet();
            ws.setVkeyWitnesses(List.of(new VkeyWitness(keys.hdKeyPair().getPublicKey().getKeyData(), sig)));
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(ws.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean spends(PreparedTx p, String ref) {
        return p.transaction().getBody().getInputs().stream().anyMatch(i -> (i.getTransactionId() + "#" + i.getIndex()).equals(ref));
    }

    static int waits;

    /**
     * Prepares {@code call} for {@code keys}' wallet with a designated input, checks it is spent, signs and submits. A
     * call whose link window starts beyond the page's limit (a cooldown) is refused with the slot to prepare again at:
     * the page waits for it (ADR-0016 D7 step 5, D11).
     */
    static String run(StateRuntime rt, Account keys, Runnable call) throws Exception {
        var wallet = WalletAccount.of(keys.baseAddress(), Networks.testnet());
        String designated = designate(rt, keys);
        PreparedTx p;
        while (true) {
            try {
                p = Compose.of(rt, wallet).designate(designated).add(call).prepare();
                break;
            } catch (RuntimeException e) {
                // The cooldown's slot is structured (ADR-0020 §12.1), never read from the message.
                org.julclang.state.runtime.NotYetException notYet = null;
                for (Throwable c = e; c != null; c = c.getCause()) if (c instanceof org.julclang.state.runtime.NotYetException n) notYet = n;
                if (notYet == null) throw e;
                long slot = notYet.prepareAt();
                long tip = rt.chainTime().tipSlot();
                assertTrue(slot > tip && slot <= tip + 200, "a cooldown's wait: slot " + slot + " at tip " + tip);
                waits++;
                while (rt.chainTime().tipSlot() < slot) Thread.sleep(1000);
            }
        }
        assertTrue(spends(p, designated), "the prepared transaction spends the designated input");
        // A link window may start a little ahead (within the page's start limit): submitted once it has started.
        long from = p.transaction().getBody().getValidityStartInterval();
        while (rt.chainTime().tipSlot() < from) Thread.sleep(500);
        String hash = p.submit(sign(keys, p.cbor()));
        for (int i = 0; i < 60 && !DevKit.backend().getTransactionService().getTransaction(hash).isSuccessful(); i++) Thread.sleep(1000);
        Thread.sleep(2000);   // the index catches up with the outputs
        return hash;
    }

    @Test
    void everyKeyedCallKind_preparedForAWallet() throws Exception {
        var c = DescribedReplayTest.contract("registry");
        var registry = new DescribedContract(JSON.readTree(c.description()), c.script(), UplcTransitions.verified(c.manifest(), c.description(), c.script(), c.transitions()));
        var rt = pageRuntime();
        var admin = DevKit.fundedAccount(300);
        var ann = DevKit.fundedAccount(300);
        DevKit.topUp(ann.baseAddress(), 100);
        var annWallet = WalletAccount.of(ann.baseAddress(), Networks.testnet());
        String annKey = HexUtil.encodeHexString(annWallet.paymentKeyHash());
        var none = JSON.createObjectNode();
        // Wallet calls only use the reference script: a key-holding account publishes it first (ADR-0013; a fresh
        // DevKit has none). Idempotent: one an earlier run published is found and kept. The registry needs no verifier
        // (no MerkleMaps, no sends or deposits).
        assertNotNull(registry.handle(rt).ensureReferenceScript(admin));

        // The instance and its two heads (D3), prepared for the admin's wallet: its seed is the designated input.
        var adminWallet = WalletAccount.of(admin.baseAddress(), Networks.testnet());
        String[] made = new String[1];
        // R-G1: a keyed contract's creation is a keyed call for a wallet: refused without a designated input, and composed.
        var c1 = assertThrows(JoustException.class, () -> Compose.of(rt, adminWallet)
                .add(() -> registry.call(rt, adminWallet, "init", "init", null, null, none, BigInteger.ZERO)).prepare());
        assertTrue(c1.getMessage().contains("designated input"), c1.getMessage());
        var c2 = assertThrows(JoustException.class, () -> Compose.of(rt, adminWallet).designate(designate(rt, admin))
                .add(() -> registry.call(rt, adminWallet, "init", "init", null, null, none, BigInteger.ZERO))
                .add(() -> registry.call(rt, adminWallet, "init", "init", null, null, none, BigInteger.ZERO)).prepare());
        assertTrue(c2.getMessage().contains("on its own"), c2.getMessage());
        run(rt, admin, () -> made[0] = registry.call(rt, adminWallet, "init", "init", null, null, none, BigInteger.ZERO));
        String inst = made[0];
        assertNotNull(inst);
        assertTrue(registry.supported().contains("keyedInit cast"));

        // Prepared for a wallet: a keyed call without a designated input is refused, and so is one in a composition.
        var castArgs = JSON.readTree("{\"voter\":\"" + annKey + "\",\"choice\":\"3\"}");
        var e1 = assertThrows(JoustException.class, () -> Compose.of(rt, annWallet)
                .add(() -> registry.call(rt, annWallet, "keyedInit", "cast", inst, null, castArgs, BigInteger.ZERO)).prepare());
        assertTrue(e1.getMessage().contains("designated input"), e1.getMessage());
        var claim61 = JSON.readTree("{\"name\":\"61\",\"tier\":\"0\",\"guardians\":[\"" + annKey + "\"],\"needed\":\"1\"}");
        var e2 = assertThrows(JoustException.class, () -> Compose.of(rt, annWallet).designate(designate(rt, ann))
                .add(() -> registry.call(rt, annWallet, "keyedInit", "cast", inst, null, castArgs, BigInteger.ZERO))
                .add(() -> registry.call(rt, annWallet, "keyedInit", "claim", inst, null, claim61, BigInteger.valueOf(5_000_000)))
                .prepare());
        assertTrue(e2.getMessage().contains("on its own"), e2.getMessage());

        var choice7 = JSON.readTree("{\"choice\":\"7\"}");
        var tier2 = JSON.readTree("{\"tier\":\"2\"}");
        var amount1 = JSON.readTree("{\"amount\":\"1000000\"}");
        // Every keyed call kind, each prepared with its designated input.
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedInit", "cast", inst, null, castArgs, BigInteger.ZERO));
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedAction", "change", inst, annKey, choice7, BigInteger.ZERO));
        var claim = JSON.readTree("{\"name\":\"6e616d65\",\"tier\":\"1\",\"guardians\":[\"" + annKey + "\"],\"needed\":\"1\"}");
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedInit", "claim", inst, null, claim, BigInteger.valueOf(5_000_000)));
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedAction", "upgrade", inst, "6e616d65", tier2,
                BigInteger.valueOf(2_000_000)));
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedAction", "withdraw", inst, "6e616d65",
                amount1, BigInteger.ZERO));
        var claim2 = JSON.readTree("{\"name\":\"6f74686572\",\"tier\":\"0\",\"guardians\":[\"" + annKey + "\"],\"needed\":\"1\"}");
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedInit", "claim", inst, null, claim2, BigInteger.ZERO));
        // The remove relinks "name" (its predecessor) after its cooldown: run waits for the slot the engine names.
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedRemove", "release", inst, "6f74686572", none, BigInteger.ZERO));
        // R-G8: the admin's @Shared insert (grant), and the guardians' @Quorum keyed action (recover; the caller counts).
        var grant = JSON.readTree("{\"name\":\"67\",\"owner\":\"" + annKey + "\"}");
        run(rt, admin, () -> registry.call(rt, adminWallet, "keyedInit", "grant", inst, null, grant, BigInteger.ZERO));
        var recover = JSON.readTree("{\"newOwner\":\"" + annKey + "\"}");
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedAction", "recover", inst, "6e616d65", recover, BigInteger.ZERO, null, null,
                null, List.of()));
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedRetire", "retire", inst, "6e616d65", none, BigInteger.ZERO));
        run(rt, ann, () -> registry.call(rt, annWallet, "keyedRetire", "leave", inst, annKey, none, BigInteger.ZERO));
        System.out.println("[M3] Registry: every keyed call kind prepared for a wallet through the described dispatcher"
                + " (" + waits + " waits for a cooldown)");
        assertTrue(waits >= 1, "inserting after a fresh node and removing it wait for a cooldown");
    }
}
