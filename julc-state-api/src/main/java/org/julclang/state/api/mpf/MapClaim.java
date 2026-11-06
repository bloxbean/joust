package org.julclang.state.api.mpf;

import org.julclang.core.types.JulcList;
import org.julclang.ledger.TxOutRef;
import org.julclang.state.api.mpf.ReadProof;
import org.julclang.state.api.mpf.WriteProof;

import java.math.BigInteger;

/**
 * What the map verifier proves for one MerkleMap of one instance in a transaction (ADR-0002 D9): the reads are in the
 * map with {@code oldRoot}, and applying the writes in order to it gives {@code newRoot}. The contract's validator finds
 * its claim by {@code owner} (its state input, or the seed when creating) and {@code field} (the map's index).
 */
public record MapClaim(TxOutRef owner, BigInteger field, byte[] oldRoot, JulcList<ReadProof> reads,
                       JulcList<WriteProof> writes, byte[] newRoot) {}
