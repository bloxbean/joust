package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.plutus.spec.BigIntPlutusData;
import com.bloxbean.cardano.client.plutus.spec.BytesPlutusData;
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
import java.util.Map;

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

    // ------------------------------------------------------------------ #104 review C1: a component named state

    static final String STATE_FIELD = """
            package org.julclang.state.examples.probe;

            import java.math.BigInteger;
            import org.julclang.state.api.Action;
            import org.julclang.state.api.Ctx;
            import org.julclang.state.api.Init;
            import org.julclang.state.api.Owned;
            import org.julclang.state.api.OwnedDestroy;
            import org.julclang.state.api.OwnedInit;
            import org.julclang.state.api.Shared;
            import org.julclang.state.api.SignedBy;
            import org.julclang.state.api.StateContract;
            import org.julclang.state.api.Payouts;
            import org.julclang.state.api.Payout;
            import org.julclang.core.types.JulcList;

            @StateContract
            public class StateField {
                public record State(BigInteger state) {}

                @Owned
                public record Item(BigInteger state, byte[] owner) {}

                @Init
                public static State init() {
                    return new State(BigInteger.ONE);
                }

                @Action
                public static State increment(State s) {
                    return new State(s.state().add(BigInteger.ONE));
                }

                @OwnedInit
                public static Item make(@Shared State s, Ctx ctx) {
                    return new Item(BigInteger.valueOf(7), ctx.caller());
                }

                @OwnedDestroy
                @SignedBy("owner")
                public static JulcList<Payout> end(Item i, Ctx ctx) {
                    return Payouts.of(i.owner(), ctx.balance());
                }
            }
            """;

    /**
     * #104 review C1: a creation is unwrapped only when it returns a result record (ContractReader's RESULT). A state, or
     * an owned record, with a component named {@code state} is the creation's value itself: the program steps to the
     * whole record, as the validator does.
     */
    @Test
    void aComponentNamedState_isNotUnwrapped() throws Exception {
        var built = Toolchain.build(Map.of("org/julclang/state/examples/probe/StateField.java", STATE_FIELD), Map.of());
        assertTrue(built.ok(), built.diagnostics().toString());
        var programs = built.contracts().get(0).transitions();
        // init: Some(State(1)), not Some(1).
        PlutusData state1 = ConstrPlutusData.of(0, BigIntPlutusData.of(1));
        PlutusData empty = ListPlutusData.builder().build();
        assertEquals("ok:" + ConstrPlutusData.of(0, ConstrPlutusData.of(0, state1), empty).serializeToHex(),
                evaluate(programs.get("init"), NONE, NONE, args(), NONE), "the instance's init steps to its whole State");
        // make (an owned init): Some(Item(7, caller)), not Some(7).
        byte[] caller = fill(28, 0xA1);
        var ctx = new Ctx(caller, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO, BigInteger.ZERO);
        PlutusData item = ConstrPlutusData.of(0, BigIntPlutusData.of(7), BytesPlutusData.of(caller));
        assertEquals("ok:" + ConstrPlutusData.of(0, ConstrPlutusData.of(0, item), empty).serializeToHex(),
                evaluate(programs.get("make"), NONE, state1, args(), data(ctx)), "the owned init steps to its whole Item");
    }
}
