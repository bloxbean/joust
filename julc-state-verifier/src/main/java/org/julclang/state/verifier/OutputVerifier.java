package org.julclang.state.verifier;

import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.ScriptInfo;
import org.julclang.state.api.OutputClaims;
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
    public static boolean validate(OutputClaims redeemer, ScriptContext ctx) {
        // Only as a withdrawal: never for a certificate, so nobody can deregister the shared credential (ADR-0007 r2).
        boolean rewarding = ctx.scriptInfo() instanceof ScriptInfo.RewardingScript;
        BigInteger outputs = BigInteger.valueOf(ctx.txInfo().outputs().size());
        BigInteger previous = BigInteger.valueOf(-1);
        boolean ok = rewarding;
        for (var claim : redeemer.claims()) {
            if (claim.output().compareTo(previous) <= 0 || claim.output().compareTo(outputs) >= 0) {
                ok = false;
            }
            previous = claim.output();
        }
        return ok;
    }
}
