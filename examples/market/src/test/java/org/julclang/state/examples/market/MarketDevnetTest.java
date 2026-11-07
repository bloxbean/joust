package org.julclang.state.examples.market;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.common.model.Networks;
import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.ScriptHash;
import org.julclang.state.api.Assets;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.devkit.DevKit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The NFT marketplace on a live Yaci DevKit (ADR-0007): list, buy (seller paid, NFT and royalty sent), cancel. */
@Tag("devnet")
class MarketDevnetTest {
    static final BigInteger ADA = BigInteger.valueOf(1_000_000);
    static final byte[] VAULT_HASH = HexUtil.decodeHexString("7a".repeat(28));

    @BeforeAll
    static void devkit() {
        assumeTrue(DevKit.isReachable(), "Yaci DevKit not reachable");
    }

    @Test
    void listBuyCancel() {
        var runtime = DevKit.runtime();
        var admin = DevKit.fundedAccount(100);
        var seller = DevKit.fundedAccount(100);
        var buyer = DevKit.fundedAccount(300);
        byte[] policy = HexUtil.decodeHexString(DevKit.mintToken(seller, "Ape42", 1));
        byte[] ape = "Ape42".getBytes(StandardCharsets.UTF_8);
        byte[] policy2 = HexUtil.decodeHexString(DevKit.mintToken(seller, "Ape43", 1));
        byte[] ape2 = "Ape43".getBytes(StandardCharsets.UTF_8);
        var vault = new Address(new Credential.ScriptCredential(new ScriptHash(VAULT_HASH)), Optional.empty());
        String vaultAddr = AddressProvider.getEntAddress(com.bloxbean.cardano.client.address.Credential.fromScript(VAULT_HASH),
                Networks.testnet()).toBech32();

        var market = MarketClient.deploy(runtime, admin, BigInteger.valueOf(250), vault, BigInteger.valueOf(500));
        var listing = market.offer(seller, policy, ape, ADA.multiply(BigInteger.valueOf(100)), BigInteger.ZERO,
                Assets.of(policy, ape, BigInteger.ONE));
        assertEquals(BigInteger.ONE, Assets.quantity(listing.fullValue(), policy, ape), "the listing holds the NFT");

        BigInteger sellerBefore = DevKit.balance(seller.enterpriseAddress(), "lovelace");
        BigInteger vaultBefore = DevKit.balance(vaultAddr, "lovelace");
        assertThrows(RequireException.class, () -> listing.buy(buyer, ADA.multiply(BigInteger.valueOf(99)), Assets.none()),
                "underpaid");
        listing.buy(buyer, ADA.multiply(BigInteger.valueOf(100)), Assets.none());
        String unit = HexUtil.encodeHexString(policy) + HexUtil.encodeHexString(ape);
        assertEquals(BigInteger.ONE, DevKit.balance(buyer.enterpriseAddress(), unit), "the buyer has the NFT");
        // 100 ADA - 2.5 % fee - 5 % royalty, plus the listing's deposit.
        BigInteger paid = DevKit.balance(seller.enterpriseAddress(), "lovelace").subtract(sellerBefore);
        assertEquals(ADA.multiply(BigInteger.valueOf(925)).divide(BigInteger.TEN).add(BigInteger.valueOf(MarketClient.SPEC.minAda())), paid);
        assertTrue(DevKit.balance(vaultAddr, "lovelace").subtract(vaultBefore).compareTo(ADA.multiply(BigInteger.valueOf(5))) >= 0,
                "royalty at the vault script");

        // A second listing, withdrawn by its seller even while the market is paused (the escape hatch).
        var second = market.offer(seller, policy2, ape2, ADA.multiply(BigInteger.valueOf(50)), BigInteger.ZERO,
                Assets.of(policy2, ape2, BigInteger.ONE));
        market.setPaused(admin, true);
        assertThrows(RequireException.class, () -> second.buy(buyer, ADA.multiply(BigInteger.valueOf(50)), Assets.none()));
        second.cancel(seller);
        assertEquals(BigInteger.ONE, DevKit.balance(seller.enterpriseAddress(),
                HexUtil.encodeHexString(policy2) + HexUtil.encodeHexString(ape2)));
    }
}
