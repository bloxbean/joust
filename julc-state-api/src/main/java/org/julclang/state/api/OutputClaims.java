package org.julclang.state.api;

import org.julclang.state.api.OutputClaim;
import org.julclang.core.types.JulcList;

/**
 * The redeemer of the JOUST output verifier (ADR-0007 D5): every output claim of the transaction, by strictly
 * increasing output index, so no output is claimed twice.
 */
public record OutputClaims(JulcList<OutputClaim> claims) {}
