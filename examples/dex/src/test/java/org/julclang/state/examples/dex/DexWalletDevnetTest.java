package org.julclang.state.examples.dex;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.api.model.Amount;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import com.bloxbean.cardano.client.function.helper.SignerProviders;
import com.bloxbean.cardano.client.quicktx.QuickTxBuilder;
import com.bloxbean.cardano.client.quicktx.Tx;
import com.bloxbean.cardano.client.transaction.spec.TransactionWitnessSet;
import com.bloxbean.cardano.client.transaction.spec.VkeyWitness;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.common.cbor.CborSerializationUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.state.api.Assets;
import org.julclang.state.examples.devkit.DevKit;
import org.julclang.state.runtime.Compose;
import org.julclang.state.runtime.PreparedTx;
import org.julclang.state.runtime.WalletAccount;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Issue #152 on a live Yaci DevKit: wallets pay native assets into the Dex through {@code Compose.prepare}, sign as a
 * CIP-30 wallet would, and the node accepts each transaction. Two layouts: the tokens on a UTxO the fee selection
 * passes over (an ADA-only UTxO beside it), which built a negative token change before the fix; and a wallet whose only
 * UTxO holds ADA and tokens, swapping tokens for an ADA payout to itself, so the funding pick and the asset collection
 * meet on one UTxO (ADR-0015 §13.8's double count).
 */
@Tag("devnet")
class DexWalletDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

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

    static String submit(Account keys, PreparedTx prepared) {
        String txHash = prepared.submit(cip30SignTx(keys, prepared.cbor()));
        assertEquals(prepared.txHash(), txHash);
        return txHash;
    }

    static int utxoCount(String address) throws Exception {
        var r = DevKit.backend().getUtxoService().getUtxos(address, 100, 1);
        return r.isSuccessful() && r.getValue() != null ? r.getValue().size() : 0;
    }

    @Test
    void walletsPayTokensIntoTheDexFromEitherLayout() throws Exception {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(200);
        var lpKeys = DevKit.fundedAccount(500);
        byte[] name = "M152".getBytes(StandardCharsets.UTF_8);
        byte[] policy = HexUtil.decodeHexString(DevKit.mintToken(lpKeys, "M152", 1_000_000));
        String unit = HexUtil.encodeHexString(policy) + HexUtil.encodeHexString(name);
        var dex = DexClient.deploy(runtime, admin, policy, name);

        // Split: the minted tokens sit with the first funding; an ADA-only UTxO beside them is what QuickTx's fee
        // selection prefers (the layout that built a negative token change, #152).
        DevKit.topUp(lpKeys.baseAddress(), 300);
        var lp = WalletAccount.of(lpKeys.baseAddress(), Networks.testnet());
        var tokens = Assets.of(policy, name, BigInteger.valueOf(100_000));
        submit(lpKeys, Compose.of(runtime, lp)
                .add(() -> dex.addLiquidity(lp, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)), tokens))
                .prepare());
        assertEquals(BigInteger.valueOf(100_000), Assets.quantity(dex.instance().value(), policy, name));
        assertEquals(ADA.multiply(BigInteger.valueOf(100)), dex.state().lpSupply());

        // One UTxO with ADA and tokens, a payout to the wallet: the funding pick is the asset input (ADR-0015 §13.8).
        var traderKeys = DevKit.fundedAccount(60);
        var r = new QuickTxBuilder(DevKit.backend()).compose(new Tx()
                        .payToAddress(traderKeys.baseAddress(), List.of(Amount.ada(20), new Amount(unit, BigInteger.valueOf(50_000))))
                        .from(lpKeys.baseAddress()))
                .withSigner(SignerProviders.signerFrom(lpKeys)).completeAndWait(Duration.ofSeconds(60));
        assertTrue(r.isSuccessful(), () -> "token transfer: " + r.getResponse());
        // Consolidated into one UTxO: everything paid to itself, the change merged with the output.
        var all = DevKit.backend().getUtxoService().getUtxos(traderKeys.baseAddress(), 100, 1).getValue();
        var c = new QuickTxBuilder(DevKit.backend()).compose(new Tx()
                        .collectFrom(all)
                        .payToAddress(traderKeys.baseAddress(), List.of(Amount.ada(70), new Amount(unit, BigInteger.valueOf(50_000))))
                        .from(traderKeys.baseAddress()))
                .mergeOutputs(true)
                .withSigner(SignerProviders.signerFrom(traderKeys)).completeAndWait(Duration.ofSeconds(60));
        assertTrue(c.isSuccessful(), () -> "consolidation: " + c.getResponse());
        Thread.sleep(1500);
        assertEquals(1, utxoCount(traderKeys.baseAddress()), "the trader's wallet is one UTxO");
        var trader = WalletAccount.of(traderKeys.baseAddress(), Networks.testnet());
        BigInteger before = DevKit.balance(traderKeys.baseAddress(), "lovelace");
        BigInteger sell = BigInteger.valueOf(10_000);
        BigInteger out = Dex.amountOut(sell, BigInteger.valueOf(100_000), ADA.multiply(BigInteger.valueOf(100)));
        submit(traderKeys, Compose.of(runtime, trader)
                .add(() -> dex.swapTokenForAda(trader, sell, BigInteger.ONE, BigInteger.ZERO, Assets.of(policy, name, sell)))
                .prepare());
        Thread.sleep(1500);
        assertEquals(BigInteger.valueOf(40_000), DevKit.balance(traderKeys.baseAddress(), unit));
        assertTrue(DevKit.balance(traderKeys.baseAddress(), "lovelace").compareTo(before.add(out).subtract(ADA)) > 0,
                "paid about " + out + " lovelace less the fee");
        assertEquals(BigInteger.valueOf(110_000), Assets.quantity(dex.instance().value(), policy, name));

        // Split again: an ADA-only UTxO beside the token UTxO, the same swap.
        DevKit.topUp(traderKeys.baseAddress(), 40);
        submit(traderKeys, Compose.of(runtime, trader)
                .add(() -> dex.swapTokenForAda(trader, sell, BigInteger.ONE, BigInteger.ZERO, Assets.of(policy, name, sell)))
                .prepare());
        Thread.sleep(1500);
        assertEquals(BigInteger.valueOf(30_000), DevKit.balance(traderKeys.baseAddress(), unit));
    }
}
