package org.julclang.state.examples.wallet;

import co.nstant.in.cbor.model.Array;
import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.examples.bank.BankClient;
import org.julclang.state.examples.counter.CounterClient;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.SubmitException;
import org.julclang.state.runtime.WalletAccount;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Wallet-signed transactions on a live Yaci DevKit (ADR-0013 D1): the runtime prepares for an address-only caller, a
 * simulated CIP-30 wallet signs the CBOR hex (returning only its witnesses, as {@code signTx(cbor, true)} does), and the
 * witnesses are checked, added and submitted.
 */
@Tag("devnet")
class WalletDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    /** What a CIP-30 wallet's {@code signTx(cbor, true)} returns: a witness set with its own vkey witness only. */
    static String cip30SignTx(Account keys, String cborHex) {
        try {
            byte[] hash = HexUtil.decodeHexString(TransactionUtil.getTxHash(HexUtil.decodeHexString(cborHex)));
            byte[] sig = CryptoConfiguration.INSTANCE.getSigningProvider().signExtended(hash,
                    keys.hdKeyPair().getPrivateKey().getKeyData());
            var ws = new TransactionWitnessSet();
            ws.setVkeyWitnesses(List.of(new VkeyWitness(keys.hdKeyPair().getPublicKey().getKeyData(), sig)));
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(ws.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void counterIncrement_preparedSignedSubmitted() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(100);
        var annKeys = DevKit.fundedAccount(100);              // the keys inside Ann's wallet
        var ann = WalletAccount.of(annKeys.baseAddress(), Networks.testnet());
        var counter = CounterClient.deploy(runtime, admin);

        var prepared = Compose.of(runtime, ann).add(() -> counter.increment(ann, BigInteger.TWO)).prepare();
        assertTrue(prepared.requiredSigners().contains(HexUtil.encodeHexString(ann.paymentKeyHash())));
        String witness = cip30SignTx(annKeys, prepared.cbor());
        String txHash = prepared.submit(witness);
        assertEquals(prepared.txHash(), txHash);
        assertEquals(BigInteger.TWO, counter.state().count());

        // Refused before submission: a missing signature, someone else's, a witness set carrying a redeemer.
        var next = Compose.of(runtime, ann).add(() -> counter.increment(ann, BigInteger.ONE)).prepare();
        assertThrows(JoustException.class, next::submit, "no signature");
        assertThrows(JoustException.class, () -> next.submit(cip30SignTx(admin, next.cbor())), "the wrong key");
        assertThrows(JoustException.class, () -> next.submit(cip30SignTx(annKeys, prepared.cbor())), "signs another transaction");
        assertThrows(JoustException.class, () -> next.submit(withRedeemer(cip30SignTx(annKeys, next.cbor()), next)),
                "a witness set adding a redeemer");
        // The page can submit through the wallet instead: the signed CBOR keeps the hash the wallet signed.
        String signed = next.withWitnesses(cip30SignTx(annKeys, next.cbor()));
        assertEquals(next.txHash(), TransactionUtil.getTxHash(HexUtil.decodeHexString(signed)));
        next.submit(cip30SignTx(annKeys, next.cbor()));
        assertEquals(BigInteger.valueOf(3), counter.state().count());

        // A stale input: prepared, then the counter moves on, then submitted.
        var stale = Compose.of(runtime, ann).add(() -> counter.increment(ann, BigInteger.ONE)).prepare();
        var first = Compose.of(runtime, ann).add(() -> counter.increment(ann, BigInteger.TEN)).prepare();
        first.submit(cip30SignTx(annKeys, first.cbor()));
        assertThrows(SubmitException.class, () -> stale.submit(cip30SignTx(annKeys, stale.cbor())), "its inputs are spent");

        // A key held here is never used for a wallet transaction.
        assertThrows(JoustException.class, () -> Compose.of(runtime, ann).add(() -> counter.increment(admin, BigInteger.ONE)).prepare());
        assertThrows(UnsupportedOperationException.class, ann::hdKeyPair);
    }

    @Test
    void bankAccount_openedAndWithdrawnFromAWallet_paidAtItsAddress() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(100);
        var annKeys = DevKit.fundedAccount(200);
        var ann = WalletAccount.of(annKeys.baseAddress(), Networks.testnet());
        var bank = BankClient.deploy(runtime, admin, ADA.multiply(BigInteger.valueOf(50)));

        BankClient.AccountObject[] acct = new BankClient.AccountObject[1];
        var open = Compose.of(runtime, ann).add(() -> acct[0] = bank.open(ann, ADA.multiply(BigInteger.valueOf(30)))).prepare();
        open.submit(cip30SignTx(annKeys, open.cbor()));
        assertEquals(ADA.multiply(BigInteger.valueOf(30)), acct[0].value().balance());

        BigInteger before = DevKit.balance(annKeys.baseAddress(), "lovelace");
        var withdraw = Compose.of(runtime, ann).add(() -> acct[0].withdraw(ann, ADA.multiply(BigInteger.TEN))).prepare();
        withdraw.submit(cip30SignTx(annKeys, withdraw.cbor()));
        assertEquals(ADA.multiply(BigInteger.valueOf(20)), acct[0].value().balance());
        // The payout went to the wallet's own (base) address, which wallets show: it gained 10 ADA less the fee.
        BigInteger gained = DevKit.balance(annKeys.baseAddress(), "lovelace").subtract(before);
        assertTrue(gained.compareTo(ADA.multiply(BigInteger.valueOf(9))) > 0, "gained " + gained);
    }

    /** A witness set with the wallet's signature plus a redeemer: must be refused. */
    static String withRedeemer(String witnessHex, org.julclang.state.runtime.PreparedTx p) {
        try {
            var items = co.nstant.in.cbor.CborDecoder.decode(HexUtil.decodeHexString(witnessHex));
            var ws = TransactionWitnessSet.deserialize((co.nstant.in.cbor.model.Map) items.get(0));
            ws.setRedeemers(p.transaction().getWitnessSet().getRedeemers());
            return HexUtil.encodeHexString(CborSerializationUtil.serialize(ws.serialize()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
