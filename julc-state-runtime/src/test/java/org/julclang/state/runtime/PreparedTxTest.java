package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.spec.TransactionBody;
import com.bloxbean.cardano.client.transaction.spec.TransactionInput;
import com.bloxbean.cardano.client.transaction.spec.TransactionOutput;
import com.bloxbean.cardano.client.transaction.spec.Value;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** PreparedTx witness handling (ADR-0013 D1, review r3): the encodings wallets use, and what is refused. */
class PreparedTxTest {
    final Account wallet = new Account(Networks.testnet());
    final Account other = new Account(Networks.testnet());

    PreparedTx prepared() {
        var body = TransactionBody.builder()
                .inputs(List.of(new TransactionInput("aa".repeat(32), 0)))
                .outputs(List.of(TransactionOutput.builder().address(wallet.baseAddress())
                        .value(Value.builder().coin(BigInteger.valueOf(5_000_000)).build()).build()))
                .fee(BigInteger.valueOf(200_000)).build();
        var tx = Transaction.builder().body(body).build();
        return new PreparedTx(null, tx, List.of(Keys.pkh(wallet)), null, "test");
    }

    static byte[] sign(Account a, PreparedTx p) {
        return CryptoConfiguration.INSTANCE.getSigningProvider().signExtended(HexUtil.decodeHexString(p.txHash()),
                a.hdKeyPair().getPrivateKey().getKeyData());
    }

    static String bstr(byte[] b) {
        String len = b.length < 24 ? String.format("%02x", 0x40 + b.length) : "58" + String.format("%02x", b.length);
        return len + HexUtil.encodeHexString(b);
    }

    /** {0: [ [vkey, sig] ]}, the pair encoded definite or indefinite, the outer array optionally a tag-258 set. */
    static String witnessSet(Account a, byte[] sig, boolean indefinitePair, boolean set) {
        String pair = (indefinitePair ? "9f" : "82") + bstr(a.hdKeyPair().getPublicKey().getKeyData()) + bstr(sig)
                + (indefinitePair ? "ff" : "");
        return "a100" + (set ? "d90102" : "") + "81" + pair;
    }

    @Test
    void acceptsTheEncodingsWalletsUse() {
        var p = prepared();
        byte[] sig = sign(wallet, p);
        for (String ws : List.of(witnessSet(wallet, sig, false, false), witnessSet(wallet, sig, true, false),
                witnessSet(wallet, sig, false, true))) {
            String signed = p.withWitnesses(ws);
            assertEquals(p.txHash(), TransactionUtil.getTxHash(HexUtil.decodeHexString(signed)), ws);
        }
    }

    @Test
    void refusesBadWitnessSets() {
        var p = prepared();
        byte[] sig = sign(wallet, p);
        String good = witnessSet(wallet, sig, false, false);
        assertThrows(JoustException.class, () -> p.withWitnesses("a2" + good.substring(2) + "01" + "80"), "another key");
        assertThrows(JoustException.class, () -> p.withWitnesses(good + "00"), "trailing CBOR");
        assertThrows(JoustException.class, () -> p.withWitnesses(witnessSet(other, sign(other, p), false, false)), "not the signer");
        assertThrows(JoustException.class, () -> p.withWitnesses(witnessSet(wallet, sign(other, p), false, false)), "bad signature");
        assertThrows(JoustException.class, () -> p.withWitnesses("ff"), "not CBOR");
    }

    @Test
    void dropsWitnessesOfKeysThatNeedNotSign() {
        var p = prepared();
        String signed = p.withWitnesses(witnessSet(wallet, sign(wallet, p), false, false), witnessSet(other, sign(other, p), false, false));
        var tx = assertDoesNotThrow(() -> Transaction.deserialize(HexUtil.decodeHexString(signed)));
        assertEquals(1, tx.getWitnessSet().getVkeyWitnesses().size(), "only the required signer's witness, as the fee assumed");
    }
}
