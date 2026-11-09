package org.julclang.state.api.mpf;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.mpf.MapClaim;

/** The map verifier's redeemer: every MerkleMap claim of the transaction. */
public record MapClaims(JulcList<MapClaim> claims) {}
