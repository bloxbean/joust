package org.julclang.state.verifier;

import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.ScriptInfo;
import org.julclang.state.api.OutputClaims;
import org.julclang.state.api.ValueAdapter;
import org.julclang.ledger.Value;
import org.julclang.core.PlutusData;
import org.julclang.ledger.TxInfo;
import org.julclang.ledger.TxOutRef;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.lib.ByteStringLib;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.WithdrawValidator;

import java.math.BigInteger;

/**
 * The JOUST output verifier (ADR-0007 D5): a withdraw-zero script shared by every contract that sends outputs. Its
 * redeemer lists every output claim of the transaction; this checks the output indices strictly increase and exist,
 * so each output is claimed at most once. Each contract's validator then matches its own sends against the claims
 * naming its own input.
 */
@WithdrawValidator
public class OutputVerifier {

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        // Read by cast, not decoded: a Value inside a redeemer record mis-decodes in JuLC (DepositClaim.value).
        OutputClaims oc = (OutputClaims) (Object) redeemer;
        // Only as a withdrawal: never for a certificate, so nobody can deregister the shared credential (ADR-0007 r2).
        boolean rewarding = ctx.scriptInfo() instanceof ScriptInfo.RewardingScript;
        TxInfo tx = ctx.txInfo();
        BigInteger outputs = BigInteger.valueOf(tx.outputs().size());
        BigInteger previous = BigInteger.valueOf(-1);
        boolean ok = rewarding;
        for (var claim : oc.claims()) {
            if (claim.output().compareTo(previous) <= 0 || claim.output().compareTo(outputs) >= 0) {
                ok = false;
            }
            previous = claim.output();
        }
        // Deposits (ADR-0011 D3): by non-decreasing output, each from an input of the transaction, each value canonical,
        // positive and free of reserved-kind tokens, so a receiver adding them can never lose value or gain a thread token.
        BigInteger last = BigInteger.ZERO;
        for (var d : oc.deposits()) {
            if (d.output().compareTo(last) < 0 || d.output().compareTo(outputs) >= 0 || !spends(tx, d.by())
                    || !positive((PlutusData) (Object) d.value())) {
                ok = false;
            }
            last = d.output();
        }
        return ok;
    }

    static boolean spends(TxInfo tx, TxOutRef ref) {
        boolean found = false;
        for (var in : tx.inputs()) {
            if (Builtins.equalsData(in.outRef(), ref)) {
                found = true;
            }
        }
        return found;
    }

    /**
     * A well-formed, non-empty value with only positive amounts and no reserved-kind names under any policy (so an NFT
     * whose name is a 32-byte hash cannot be deposited; send it instead). Well formed as the wire carries it (#85): the
     * runtime writes the registry through cardano-client-lib's canonical CBOR, which orders a policy's token names by their
     * encoding, not the ledger's way, so the value is read with {@code ValueAdapter.fromWire}'s rules (any token order; a
     * repeated name, a zero quantity, an empty token map or policies out of order fail), as the offered assets are (C1).
     */
    static boolean positive(PlutusData v) {
        // Not empty: a zero-value claim would let anyone add deposits (and memos) that carry nothing (review r3, L1).
        boolean ok = !ValueAdapter.isEmpty(ValueAdapter.fromWire((Value) (Object) v));
        PlutusData ps = Builtins.unMapData(v);
        while (!Builtins.nullList(ps)) {
            PlutusData pair = Builtins.headList(ps);
            boolean ada = ByteStringLib.length(Builtins.unBData(Builtins.fstPair(pair))) == 0;
            PlutusData ts = Builtins.unMapData(Builtins.sndPair(pair));
            while (!Builtins.nullList(ts)) {
                PlutusData t = Builtins.headList(ts);
                byte[] n = Builtins.unBData(Builtins.fstPair(t));
                long len = ByteStringLib.length(n);
                boolean reserved = !ada && (len == 32 || (len == 29 && (ByteStringLib.at(n, 0L) == 79L || ByteStringLib.at(n, 0L) == 73L)));
                if (reserved || Builtins.unIData(Builtins.sndPair(t)).compareTo(BigInteger.ZERO) <= 0) {
                    ok = false;
                }
                ts = Builtins.tailList(ts);
            }
            ps = Builtins.tailList(ps);
        }
        return ok;
    }
}
