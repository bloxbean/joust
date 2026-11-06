package org.julclang.state.runtime;

import com.bloxbean.cardano.client.crypto.Blake2bUtil;
import com.bloxbean.cardano.client.crypto.config.CryptoConfiguration;
import org.julclang.stdlib.CryptoProvider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * JuLC's JVM crypto ({@code Builtins.blake2b_256} and friends) backed by cardano-client-lib and the JDK, so contract
 * code that hashes (MerkleMap, ADR-0002) runs on the JVM. Installed by {@link StateRuntime}.
 */
public final class CclCryptoProvider implements CryptoProvider {

    public byte[] sha2_256(byte[] data) { return digest("SHA-256", data); }
    public byte[] blake2b_256(byte[] data) { return Blake2bUtil.blake2bHash256(data); }
    public byte[] blake2b_224(byte[] data) { return Blake2bUtil.blake2bHash224(data); }
    public byte[] sha3_256(byte[] data) { return digest("SHA3-256", data); }

    public byte[] keccak_256(byte[] data) {
        throw new UnsupportedOperationException("keccak_256 is not available in the JOUST runtime's JVM crypto");
    }

    public boolean verifyEd25519Signature(byte[] pubKey, byte[] msg, byte[] sig) {
        return CryptoConfiguration.INSTANCE.getSigningProvider().verify(sig, msg, pubKey);
    }

    private static byte[] digest(String algorithm, byte[] data) {
        try {
            return MessageDigest.getInstance(algorithm).digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(algorithm + " unavailable", e);
        }
    }
}
