package org.julclang.state.runtime;

import com.bloxbean.cardano.client.account.Account;
import com.bloxbean.cardano.client.address.Address;
import com.bloxbean.cardano.client.address.AddressProvider;
import com.bloxbean.cardano.client.address.AddressType;
import com.bloxbean.cardano.client.address.CredentialType;
import com.bloxbean.cardano.client.common.model.Network;
import com.bloxbean.cardano.client.crypto.bip32.HdKeyPair;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.util.HexUtil;

import java.util.Optional;

/**
 * A caller known only by its address: the user of a CIP-30 wallet, whose keys stay in the wallet (ADR-0013 D1).
 * Generated clients take it wherever they take an {@link Account}. It <b>fails closed</b>: every method of
 * {@code Account} is overridden and either answers from the address or throws, so no inherited method can answer for the
 * random key the {@code Account} constructor makes. Transactions for it are built with {@link Compose#prepare()} and
 * signed by the wallet.
 */
public final class WalletAccount extends Account {

    private final Address address;
    private final byte[] paymentKeyHash;
    private final Network network;

    private WalletAccount(Network network, Address address, byte[] paymentKeyHash) {
        super(network);
        this.network = network;
        this.address = address;
        this.paymentKeyHash = paymentKeyHash;
    }

    /**
     * The caller whose wallet address is {@code bech32} (its CIP-30 {@code getChangeAddress()}): a base or enterprise
     * address with a key payment credential, on {@code network}. Script, pointer and Byron addresses are refused.
     */
    public static WalletAccount of(String bech32, Network network) {
        Address a;
        try {
            a = new Address(bech32);
        } catch (Exception e) {
            throw new JoustException("not a Shelley address: " + bech32, e);
        }
        if (a.getAddressType() != AddressType.Base && a.getAddressType() != AddressType.Enterprise) {
            throw new JoustException("a wallet caller needs a base or enterprise address, not " + a.getAddressType());
        }
        if (a.getNetwork() == null || a.getNetwork().getNetworkId() != network.getNetworkId()) {
            throw new JoustException("the wallet address is for another network than the runtime's");
        }
        var cred = AddressProvider.getPaymentCredential(a)
                .orElseThrow(() -> new JoustException("the wallet address has no payment credential"));
        if (cred.getType() != CredentialType.Key) {
            throw new JoustException("a wallet caller needs a key payment credential, not a script");
        }
        return new WalletAccount(network, a, cred.getBytes());
    }

    /** The payment key hash the wallet signs with (28 bytes). */
    public byte[] paymentKeyHash() {
        return paymentKeyHash.clone();
    }

    /** The wallet's address, as given (its funds, fees and change). */
    public String address() {
        return address.toBech32();
    }

    // Account's constructor calls some of these before this class's fields are set: while {@code address} is null they
    // answer as Account does; after construction, never.

    private static UnsupportedOperationException noKeys() {
        return new UnsupportedOperationException("a WalletAccount has no keys: the wallet signs (Compose.prepare, ADR-0013)");
    }

    // ---- answered from the address

    /** The wallet's address (base, or enterprise if that is what the wallet gave): where its funds and change are. */
    @Override public String baseAddress() { if (address == null) return super.baseAddress(); return address.toBech32(); }
    @Override public String baseAddressAsBase16() { if (address == null) return super.baseAddressAsBase16(); return HexUtil.encodeHexString(address.getBytes()); }
    @Override public String changeAddress() { if (address == null) return super.changeAddress(); return address.toBech32(); }
    @Override public Address getBaseAddress() { if (address == null) return super.getBaseAddress(); return address; }
    @Override public Address getEnterpriseAddress() {
        if (address == null) return super.getEnterpriseAddress();
        return AddressProvider.getEntAddress(com.bloxbean.cardano.client.address.Credential.fromKey(paymentKeyHash), network);
    }
    @Override public String enterpriseAddress() { if (address == null) return super.enterpriseAddress(); return getEnterpriseAddress().toBech32(); }
    @Override public String stakeAddress() {
        if (address == null) return super.stakeAddress();
        if (address.getAddressType() != AddressType.Base) throw new UnsupportedOperationException("an enterprise address has no stake key");
        return AddressProvider.getStakeAddress(address).toBech32();
    }
    @Override public String toString() { if (address == null) return super.toString(); return "WalletAccount[" + address.toBech32() + "]"; }

    // ---- keys: none here

    @Override public String mnemonic() { if (address == null) return super.mnemonic(); throw noKeys(); }
    @Override public String getBech32PrivateKey() { if (address == null) return super.getBech32PrivateKey(); throw noKeys(); }
    @Override public byte[] privateKeyBytes() { if (address == null) return super.privateKeyBytes(); throw noKeys(); }
    @Override public byte[] publicKeyBytes() { if (address == null) return super.publicKeyBytes(); throw noKeys(); }
    @Override public HdKeyPair hdKeyPair() { if (address == null) return super.hdKeyPair(); throw noKeys(); }
    @Override public HdKeyPair stakeHdKeyPair() { if (address == null) return super.stakeHdKeyPair(); throw noKeys(); }
    @Override public HdKeyPair changeHdKeyPair() { if (address == null) return super.changeHdKeyPair(); throw noKeys(); }
    @Override public HdKeyPair drepHdKeyPair() { if (address == null) return super.drepHdKeyPair(); throw noKeys(); }
    @Override public String drepId() { if (address == null) return super.drepId(); throw noKeys(); }
    @Override public String legacyDRepId() { if (address == null) return super.legacyDRepId(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.governance.keys.DRepKey drepKey() { if (address == null) return super.drepKey(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.address.Credential drepCredential() { if (address == null) return super.drepCredential(); throw noKeys(); }
    @Override public HdKeyPair committeeColdKeyPair() { if (address == null) return super.committeeColdKeyPair(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.governance.keys.CommitteeColdKey committeeColdKey() { if (address == null) return super.committeeColdKey(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.address.Credential committeeColdCredential() { if (address == null) return super.committeeColdCredential(); throw noKeys(); }
    @Override public HdKeyPair committeeHotKeyPair() { if (address == null) return super.committeeHotKeyPair(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.governance.keys.CommitteeHotKey committeeHotKey() { if (address == null) return super.committeeHotKey(); throw noKeys(); }
    @Override public com.bloxbean.cardano.client.address.Credential committeeHotCredential() { if (address == null) return super.committeeHotCredential(); throw noKeys(); }
    @Override public String sign(String txHex) { throw noKeys(); }
    @Override public Transaction sign(Transaction tx) { if (address == null) return super.sign(tx); throw noKeys(); }
    @Override public Transaction signWithStakeKey(Transaction tx) { if (address == null) return super.signWithStakeKey(tx); throw noKeys(); }
    @Override public Transaction signWithDRepKey(Transaction tx) { if (address == null) return super.signWithDRepKey(tx); throw noKeys(); }
    @Override public Transaction signWithCommitteeColdKey(Transaction tx) { if (address == null) return super.signWithCommitteeColdKey(tx); throw noKeys(); }
    @Override public Transaction signWithCommitteeHotKey(Transaction tx) { if (address == null) return super.signWithCommitteeHotKey(tx); throw noKeys(); }
    @Override public Optional<HdKeyPair> getRootKeyPair() { if (address == null) return super.getRootKeyPair(); throw noKeys(); }
}
