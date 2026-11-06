package org.julclang.state.runtime;

import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.Credential;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.common.model.Network;
import org.julclang.ledger.Address;
import org.julclang.ledger.PubKeyHash;
import org.julclang.ledger.ScriptHash;
import org.julclang.ledger.StakingCredential;

import java.util.Optional;

/** Converts between bech32 addresses and the ledger {@link Address} a script sees (key or script credentials). */
final class LedgerAddresses {
    private LedgerAddresses() {}

    static Address toLedger(String bech32) {
        var a = new com.bloxbean.cardano.client.address.Address(bech32);
        var pay = a.getPaymentCredential().orElseThrow(() -> new JoustException("address has no payment credential"));
        Optional<StakingCredential> stake = a.getDelegationCredential().map(d ->
                new StakingCredential.StakingHash(ledger(d)));
        return new Address(ledger(pay), stake);
    }

    static String toBech32(Address a, Network network) {
        Credential pay = ccl(a.credential());
        if (a.stakingCredential().isEmpty()) return AddressProvider.getEntAddress(pay, network).toBech32();
        if (!(a.stakingCredential().get() instanceof StakingCredential.StakingHash sh)) {
            throw new JoustException("pointer addresses are not supported");
        }
        return AddressProvider.getBaseAddress(pay, ccl(sh.credential()), network).toBech32();
    }

    private static org.julclang.ledger.Credential ledger(Credential c) {
        return c.getType() == CredentialType.Key
                ? new org.julclang.ledger.Credential.PubKeyCredential(new PubKeyHash(c.getBytes()))
                : new org.julclang.ledger.Credential.ScriptCredential(new ScriptHash(c.getBytes()));
    }

    private static Credential ccl(org.julclang.ledger.Credential c) {
        return switch (c) {
            case org.julclang.ledger.Credential.PubKeyCredential pk -> Credential.fromKey(pk.hash().hash());
            case org.julclang.ledger.Credential.ScriptCredential sc -> Credential.fromScript(sc.hash().hash());
        };
    }
}
