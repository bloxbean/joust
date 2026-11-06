package org.julclang.state.examples.counter.generated;

import org.julclang.state.examples.counter.Counter;
import org.julclang.core.PlutusData;
import org.julclang.core.types.AssetEntry;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.*;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.MultiValidator;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.Purpose;
import org.julclang.stdlib.lib.AddressLib;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.lib.ContextsLib;
import org.julclang.stdlib.lib.OutputLib;
import org.julclang.stdlib.lib.ValuesLib;

import java.math.BigInteger;
import java.util.Optional;

/**
 * ============================================================================
 *  GENERATED from {@link Counter} by the (proposed) @StateContract processor.
 *  Do not edit. Hand-written in this prototype to show the exact output.
 * ============================================================================
 *
 * One script = one policy id = one address (JuLC @MultiValidator).
 *
 *  MINT  (Create)            : deploys a new counter instance
 *    - consumes the seed UTxO (one-shot, so the thread token is unique forever)
 *    - mints exactly 1 "STATE" token under this policy, nothing else under it
 *    - caller signed the tx
 *    - the token goes to this script's address with inline datum == Counter.init(caller)
 *      and value == min-ADA + token (no junk tokens)
 *
 *  SPEND (Increment | Reset) : applies a user action
 *    - the spent UTxO carries the thread token (so it's the real state)
 *    - caller signed the tx (this is how "msg.sender" is authenticated)
 *    - next = Counter.<action>(datum, args, caller)   <- the developer's code
 *    - exactly one output carries the thread token, at the same address,
 *      with inline datum == next and value == input value
 */
@MultiValidator
public class CounterValidator {

    /** Seed UTxO that makes this instance's policy id unique. */
    @Param static byte[] seedTxHash;
    @Param static BigInteger seedIndex;

    static final byte[] STATE_TOKEN = "STATE".getBytes();

    // ---- generated redeemers: one variant per @Init / @Action ----------------

    public record Create(byte[] caller) {}

    public sealed interface CounterAction permits Increment, Reset {}
    public record Increment(BigInteger by, byte[] caller) implements CounterAction {}
    public record Reset(byte[] caller) implements CounterAction {}

    // ---- MINT: deploy ---------------------------------------------------------

    @Entrypoint(purpose = Purpose.MINT)
    public static boolean create(Create redeemer, ScriptContext ctx) {
        TxInfo tx = ctx.txInfo();
        ScriptInfo.MintingScript mi = (ScriptInfo.MintingScript) ctx.scriptInfo();
        byte[] policy = (byte[]) (Object) mi.policyId();

        boolean seedSpent = seedConsumed(tx.inputs());
        boolean signed = ContextsLib.signedBy(tx, redeemer.caller());
        boolean mintOk = mintsExactlyOneStateToken(tx.mint(), policy);

        TxOut out = uniqueStateOutput(tx.outputs(), policy);
        boolean atSelf = ByteStringLib.equals(AddressLib.credentialHash(out.address()), policy);

        Counter.State expected = Counter.init(redeemer.caller());          // developer's @Init
        boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), (PlutusData) (Object) expected);
        boolean valueOk = onlyAdaAndToken(out.value(), policy);

        return seedSpent && signed && mintOk && atSelf && datumOk && valueOk;
    }

    // ---- SPEND: actions -------------------------------------------------------

    @Entrypoint(purpose = Purpose.SPEND)
    public static boolean spend(Optional<PlutusData> datumOpt, CounterAction action, ScriptContext ctx) {
        TxInfo tx = ctx.txInfo();
        TxOut ownInput = ContextsLib.findOwnInput(ctx).get().resolved();
        byte[] policy = AddressLib.credentialHash(ownInput.address());

        // Only the UTxO holding the thread token is the real state.
        // (Junk UTxOs sent to this address without the token can't be spent.)
        boolean isState = ValuesLib.assetOf(ownInput.value(), policy, STATE_TOKEN)
                .compareTo(BigInteger.ONE) == 0;
        if (!isState) {
            return false;
        } else {
            Counter.State current = (Counter.State) (Object) datumOpt.get();
            byte[] caller = callerOf(action);
            boolean signed = ContextsLib.signedBy(tx, caller);

            Counter.State next = switch (action) {                       // developer's @Actions
                case Increment a -> Counter.increment(current, a.by(), a.caller());
                case Reset r -> Counter.reset(current, r.caller());
            };

            TxOut out = uniqueStateOutput(tx.outputs(), policy);
            boolean sameAddress = Builtins.equalsData(out.address(), ownInput.address());
            boolean datumOk = Builtins.equalsData(OutputLib.getInlineDatum(out), (PlutusData) (Object) next);
            boolean valueOk = ValuesLib.eq(out.value(), ownInput.value());

            return signed && sameAddress && datumOk && valueOk;
        }
    }

    // ---- generated helpers ----------------------------------------------------

    static byte[] callerOf(CounterAction action) {
        return switch (action) {
            case Increment a -> a.caller();
            case Reset r -> r.caller();
        };
    }

    static boolean seedConsumed(JulcList<TxInInfo> inputs) {
        boolean found = false;
        for (var in : inputs) {
            byte[] h = (byte[]) (Object) in.outRef().txId();
            if (ByteStringLib.equals(h, seedTxHash) && in.outRef().index().compareTo(seedIndex) == 0) {
                found = true;
                break;
            }
        }
        return found;
    }

    /** Under this policy, the tx mints exactly STATE x 1 and nothing else. */
    static boolean mintsExactlyOneStateToken(Value mint, byte[] policy) {
        long entries = 0;
        boolean ok = true;
        JulcList<AssetEntry> flat = ValuesLib.flattenTyped(mint);
        for (var e : flat) {
            if (ByteStringLib.equals(e.policyId(), policy)) {
                entries = entries + 1;
                if (!ByteStringLib.equals(e.tokenName(), STATE_TOKEN) || e.amount().compareTo(BigInteger.ONE) != 0) {
                    ok = false;
                }
            }
        }
        return ok && entries == 1;
    }

    /** Exactly one output carries the thread token; returns it (fails otherwise). */
    static TxOut uniqueStateOutput(JulcList<TxOut> outputs, byte[] policy) {
        JulcList<TxOut> withToken = OutputLib.outputsWithToken(outputs, policy, STATE_TOKEN);
        if (withToken.size() != 1) {
            Builtins.error();
            return withToken.head();
        } else {
            return withToken.head();
        }
    }

    /** Value is exactly {ADA: n, policy.STATE: 1}. Blocks token-dust griefing. */
    static boolean onlyAdaAndToken(Value v, byte[] policy) {
        BigInteger lovelace = ValuesLib.lovelaceOf(v);
        Value expected = ValuesLib.add(
                ValuesLib.singleton(ByteStringLib.empty(), ByteStringLib.empty(), lovelace),
                ValuesLib.singleton(policy, STATE_TOKEN, BigInteger.ONE));
        return ValuesLib.eq(v, expected);
    }
}
