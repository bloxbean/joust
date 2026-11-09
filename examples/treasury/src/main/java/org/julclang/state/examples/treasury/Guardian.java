package org.julclang.state.examples.treasury;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.ScriptInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.WithdrawValidator;
import org.julclang.stdlib.lib.ContextsLib;

/**
 * A minimal governor for {@code @AuthorizedBy} (ADR-0009 D3): a withdraw-zero script that approves whatever its
 * Approvals redeemer lists when its owner key signs. Real governors decide by votes or another contract's state; the
 * JOUST validator only accepts the approval naming itself, its instance and its action. It refuses certificates, so
 * nobody can deregister its credential.
 */
@WithdrawValidator
public class Guardian {

    @Param static byte[] owner;

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        boolean rewarding = ctx.scriptInfo() instanceof ScriptInfo.RewardingScript;
        return rewarding && ContextsLib.signedBy(ctx.txInfo(), owner);
    }
}
