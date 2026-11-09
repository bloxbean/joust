package org.julclang.state.api.mpf;

import java.math.BigInteger;

/**
 * One step of a Merkle Patricia Forestry proof, in the Plutus encoding of Aiken's
 * {@code aiken-lang/merkle-patricia-forestry} (and cardano-client-lib's {@code MpfTrie.getProofPlutusData}):
 * {@code Branch = Constr 0 [skip, neighbors]}, {@code Fork = Constr 1 [skip, Neighbor]},
 * {@code Leaf = Constr 2 [skip, keyHash, valueHash]}.
 */
public sealed interface ProofStep permits ProofStep.Branch, ProofStep.Fork, ProofStep.Leaf {

    /** A branch node: the hashes of the sparse-merkle neighbours along the path (4 x 32 bytes). */
    record Branch(BigInteger skip, byte[] neighbors) implements ProofStep {}

    /** A branch with exactly one other child: that child. */
    record Fork(BigInteger skip, Neighbor neighbor) implements ProofStep {}

    /** A branch with exactly one other child, which is a leaf: its key hash and value hash. */
    record Leaf(BigInteger skip, byte[] key, byte[] value) implements ProofStep {}

    /** The other child of a fork: its nibble, its remaining path prefix (one byte per nibble), its root. */
    record Neighbor(BigInteger nibble, byte[] prefix, byte[] root) {}
}
