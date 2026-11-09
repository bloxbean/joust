package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.plutus.spec.PlutusScript;

import java.util.List;

/**
 * How a call proves {@code @AuthorizedBy} (ADR-0009 D3): a key that signs, or a script credential that runs for a
 * zero withdrawal. A Plutus governor's redeemer must be an {@code Approvals} list naming this action; {@code signers}
 * are keys the governor itself requires (they become required signers and sign the transaction).
 *
 * @param signers       keys that sign (the key for a key credential; a governor's own signers)
 * @param rewardAddress the script's reward address (script credentials), else null
 * @param script        the governor script, attached to the transaction
 * @param redeemer      its Rewarding redeemer (a record, converted with PlutusDataAdapter)
 */
public record Authorization(List<Account> signers, String rewardAddress, PlutusScript script, Object redeemer) {

    public static Authorization key(Account signer) {
        return new Authorization(List.of(signer), null, null, null);
    }

    public static Authorization script(String rewardAddress, PlutusScript script, Object redeemer, Account... signers) {
        return new Authorization(List.of(signers), rewardAddress, script, redeemer);
    }
}
