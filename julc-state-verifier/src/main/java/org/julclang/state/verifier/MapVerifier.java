package org.julclang.state.verifier;

import org.julclang.ledger.ScriptContext;
import org.julclang.state.api.mpf.MapClaims;
import org.julclang.state.api.mpf.MapWitnesses;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.WithdrawValidator;

/**
 * The JOUST map verifier (ADR-0002 D9): a withdraw-zero script shared by every contract with MerkleMap state. A
 * transaction that changes or reads MerkleMaps withdraws 0 from this script, with every map claim of the transaction
 * as the redeemer; this checks the MPF proofs of all of them once. Each contract's validator then only matches the
 * claims of its own maps against what its method read and wrote, and carries no MPF code.
 */
@WithdrawValidator
public class MapVerifier {

    @Entrypoint
    public static boolean validate(MapClaims redeemer, ScriptContext ctx) {
        boolean ok = true;
        for (var claim : redeemer.claims()) {
            if (!MapWitnesses.verify(claim)) {
                ok = false;
            }
        }
        return ok;
    }
}
