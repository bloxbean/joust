package org.julclang.state.examples.market;

import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.TxId;
import org.julclang.ledger.TxInInfo;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Assets;
import org.julclang.state.api.Ctx;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.Transition;
import org.julclang.state.testkit.Attacks;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The NFT marketplace (ADR-0007): listings hold the NFT; a purchase pays the seller, sends the NFT and a royalty. */
class MarketTest {
    static final byte[] ADMIN = StateHarness.fill(28, 0xAD);
    static final byte[] SELLER = StateHarness.fill(28, 0x5E);
    static final byte[] BUYER = StateHarness.fill(28, 0xB0);
    static final byte[] NFT_POLICY = StateHarness.fill(28, 0x4E);
    static final byte[] NFT = "Ape#42".getBytes();
    static final Address VAULT = new Address(new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x7A))),
            Optional.empty());
    static final BigInteger PRICE = BigInteger.valueOf(100_000_000);

    final StateHarness<Market.Config> h = StateHarness.of(MarketClient.SPEC);
    final Market.Config config = new Market.Config(ADMIN, BigInteger.valueOf(250), VAULT, BigInteger.valueOf(500), false);
    final Market.Config paused = new Market.Config(ADMIN, BigInteger.valueOf(250), VAULT, BigInteger.valueOf(500), true);
    final Value nft = Assets.of(NFT_POLICY, NFT, BigInteger.ONE);

    MarketValidator.Holding held(Market.Listing l) {
        return new MarketValidator.Holding(h.instanceId(), l);
    }

    StateHarness.Held listing() {
        return h.heldObject(held(new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE)), BigInteger.ZERO, nft);
    }

    static <R> Transition<R> sold(Market.Sold s) {
        return Transition.full(null, s.payouts(), s.sends(), null, s.accepted());
    }

    @Test
    void offer_takesTheNftIn() {
        Ctx ctx = h.ctx(SELLER, BigInteger.ZERO, BigInteger.ZERO);
        var r = Market.offer(config, NFT_POLICY, NFT, PRICE, ctx, h.createFunds(true, nft));
        var tx = h.createWith(new MarketValidator.Offer(h.seed(), h.instanceId(), NFT_POLICY, NFT, PRICE, ctx, nft.toPlutusData()),
                held(r.state()), ctx, r.accepted(), true, config);
        System.out.println("[offer] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void offer_withAJunkTokenAlongside_rejected() {
        Ctx ctx = h.ctx(SELLER, BigInteger.ZERO, BigInteger.ZERO);
        Value junk = Assets.add(nft, Assets.of(StateHarness.fill(28, 0x11), new byte[]{9}, BigInteger.valueOf(1000)));
        var r = Market.offer(config, NFT_POLICY, NFT, PRICE, ctx, h.createFunds(true, junk));
        // The method accepts only the NFT; a caller that pushes junk in is refused (ADR-0007 r2, I31).
        var tx = h.createWith(new MarketValidator.Offer(h.seed(), h.instanceId(), NFT_POLICY, NFT, PRICE, ctx, junk.toPlutusData()),
                held(r.state()), ctx, junk, true, config);
        h.assertRejected(tx, "received != accepted");
    }

    @Test
    void buy_paysTheSellerAndSendsTheNftAndTheRoyalty() {
        var live = listing();
        Ctx ctx = h.ctx(BUYER, PRICE, live.lovelace());
        var l = new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE);
        var s = Market.buy(l, config, ctx, h.funds(live, Assets.none()));
        assertEquals(2, s.sends().size());
        var tx = h.stepWith(live, new MarketValidator.Buy(ctx, Assets.none().toPlutusData()), new MarketValidator.BurnInstance(),
                sold(s), null, BUYER, ctx, config);
        System.out.println("[buy] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
    }

    @Test
    void buy_underpaid_rejectedOnTheJvmAndOnChain() {
        var live = listing();
        Ctx ctx = h.ctx(BUYER, PRICE.subtract(BigInteger.ONE), live.lovelace());
        var l = new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE);
        assertThrows(RequireException.class, () -> Market.buy(l, config, ctx, h.funds(live, Assets.none())));
        var honest = Market.buy(l, config, h.ctx(BUYER, PRICE, live.lovelace()), h.funds(live, Assets.none()));
        h.assertRejected(h.stepWith(live, new MarketValidator.Buy(ctx, Assets.none().toPlutusData()),
                new MarketValidator.BurnInstance(), sold(honest), null, BUYER, ctx, config), "underpaid");
    }

    @Test
    void royaltyClaimedByASpentVaultContinuation_rejected() {
        var live = listing();
        Ctx ctx = h.ctx(BUYER, PRICE, live.lovelace());
        var l = new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE);
        var s = Market.buy(l, config, ctx, h.funds(live, Assets.none()));
        var tx = h.stepWith(live, new MarketValidator.Buy(ctx, Assets.none().toPlutusData()), new MarketValidator.BurnInstance(),
                sold(s), null, BUYER, ctx, config);
        h.assertAccepted(tx);
        // The vault script is spent in the same transaction: its continuing output could already hold the royalty.
        tx.inputs.add(new TxInInfo(new TxOutRef(new TxId(StateHarness.fill(32, 0x7B)), BigInteger.ZERO),
                new TxOut(VAULT, Value.lovelace(BigInteger.valueOf(900_000_000)), new OutputDatum.NoOutputDatum(), Optional.empty())));
        h.assertRejected(tx, "a send to a spent script's credential (ADR-0007 I30)");
    }

    @Test
    void cancel_escapeHatch_worksWhilePaused() {
        var live = listing();
        Ctx ctx = h.ctx(SELLER, BigInteger.ZERO, live.lovelace());
        var l = new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE);
        var w = Market.cancel(l, ctx, h.funds(live, Assets.none()));
        var tx = h.stepWith(live, new MarketValidator.Cancel(ctx), new MarketValidator.BurnInstance(),
                Transition.full(null, w.payouts(), w.sends(), null, null), null, SELLER, ctx, null);
        System.out.println("[cancel] " + h.assertAccepted(tx) + "  attacks rejected: " + Attacks.assertAllRejected(h, tx));
        assertTrue(tx.referenceInputs.isEmpty());
        assertThrows(RequireException.class, () -> Market.buy(l, paused, h.ctx(BUYER, PRICE, live.lovelace()),
                h.funds(live, Assets.none())));
    }

    @Test
    void cancel_byAStranger_rejected() {
        var live = listing();
        Ctx ctx = h.ctx(BUYER, BigInteger.ZERO, live.lovelace());
        var l = new Market.Listing(SELLER, NFT_POLICY, NFT, PRICE);
        var w = Market.cancel(l, ctx, h.funds(live, Assets.none()));
        h.assertRejected(h.stepWith(live, new MarketValidator.Cancel(ctx), new MarketValidator.BurnInstance(),
                Transition.full(null, w.payouts(), w.sends(), null, null), null, BUYER, ctx, null), "@SignedBy(seller)");
    }
}
