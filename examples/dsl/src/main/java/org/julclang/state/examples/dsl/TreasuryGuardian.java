package org.julclang.state.examples.dsl;

import org.julclang.core.PlutusData;
import org.julclang.ledger.ScriptContext;
import org.julclang.ledger.ScriptInfo;
import org.julclang.stdlib.annotation.Entrypoint;
import org.julclang.stdlib.annotation.Param;
import org.julclang.stdlib.annotation.WithdrawValidator;
import org.julclang.stdlib.lib.ContextsLib;

/**
 * A copy of examples/treasury's Guardian, for the treasury port's copied tests (#84 review F7): with it here, examples/dsl
 * needs no class path holding examples/treasury, whose TreasuryValidator shares its script's resource name with the
 * port's. Named apart from it (the copied tests' {@code Guardian} becomes {@code TreasuryGuardian}), so no class path
 * holding both examples (the toolchain's tests) has two scripts of one name either.
 * <p>
 * A minimal governor for {@code @AuthorizedBy} (ADR-0009 D3): a withdraw-zero script that approves whatever its
 * Approvals redeemer lists when its owner key signs. Real governors decide by votes or another contract's state; the
 * JOUST validator only accepts the approval naming itself, its instance and its action. It refuses certificates, so
 * nobody can deregister its credential.
 */
@WithdrawValidator
public class TreasuryGuardian {

    @Param static byte[] owner;

    @Entrypoint
    public static boolean validate(PlutusData redeemer, ScriptContext ctx) {
        boolean rewarding = ctx.scriptInfo() instanceof ScriptInfo.RewardingScript;
        return rewarding && ContextsLib.signedBy(ctx.txInfo(), owner);
    }
}
