package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData;
import com.bloxbean.cardano.client.plutus.spec.ListPlutusData;
import com.bloxbean.cardano.client.plutus.spec.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.DepositClaim;
import org.julclang.state.api.Funds;
import org.julclang.state.examples.market.Market;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue #81: the transition program of a creation that returns a result record ({@code @OwnedInit} or {@code @Init}
 * returning {@code (state, accepted)}) gives the record's state as the next state and what it accepted, as the validator
 * reads the same method ({@code result.state()}, and {@code result.accepted()} against the offered assets, I31). Before
 * the fix it wrapped the whole record as the state and reported nothing accepted. examples/market's {@code offer} takes
 * the NFT it lists.
 */
class CreationAcceptsTransitionTest {

    static byte[] fill(int n, int b) {
        byte[] x = new byte[n];
        Arrays.fill(x, (byte) b);
        return x;
    }

    /** An asset-aware step: Constr 0 [Some(next), payouts, sends, mint, accepted, deposits], as the runtime decodes it. */
    static String step(Object next, Value accepted) {
        PlutusData empty = ListPlutusData.builder().build();
        return ConstrPlutusData.of(0, ConstrPlutusData.of(0, data(next)), empty, empty, data(Assets.none()), data(accepted), empty)
                .serializeToHex();
    }

    @Test
    void marketsOffer_givesTheListingAndTheAcceptedNft() throws Exception {
        var programs = transitions("market");
        String offer = programs.get("offer");
        assertNotNull(offer, programs.keySet().toString());
        byte[] seller = fill(28, 0xA1), policy = fill(28, 0x5E), name = "nft".getBytes();
        var market = new Market.Config(fill(28, 0xAD), BigInteger.valueOf(200), wallet(fill(28, 0x77)), BigInteger.valueOf(300), false);
        var nft = Assets.of(policy, name, BigInteger.ONE);
        var funds = new Funds(fill(28, 0x01), new byte[0], nft, Assets.none(), JulcList.<DepositClaim>empty());
        var ctx = new Ctx(seller, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        var price = BigInteger.valueOf(5_000_000);
        var listed = Market.offer(market, policy, name, price, ctx, funds);
        assertEquals(nft, listed.accepted(), "the JVM method accepts the NFT");
        String want = "ok:" + step(listed.state(), listed.accepted());
        // OfferArgs(joustFunds, nftPolicy, nftName, price); the shared argument is the market's state.
        PlutusData marketData = data(market);
        assertEquals(want, evaluate(offer, NONE, marketData, args(funds, policy, name, price), data(ctx)),
                "the transition program's step is the JVM's: the listing, and the NFT it accepted (#81)");
        // A price under the floor is refused by both.
        assertEquals("refused", evaluate(offer, NONE, marketData, args(funds, policy, name, BigInteger.ONE), data(ctx)));
    }

    static org.julclang.ledger.Address wallet(byte[] key) {
        return new org.julclang.ledger.Address(new org.julclang.ledger.Credential.PubKeyCredential(new org.julclang.ledger.PubKeyHash(key)),
                java.util.Optional.empty());
    }
}
