package org.julclang.state.api;

import org.julclang.state.api.OutputClaim;
import org.julclang.core.types.JulcList;
import org.julclang.state.api.DepositClaim;

/**
 * The redeemer of the JOUST output verifier (ADR-0007 D5): every send claim of the transaction, by strictly increasing
 * output index, so no output is claimed twice; and every deposit claim (ADR-0011 D3), by non-decreasing output index
 * (an output may receive several deposits).
 */
public record OutputClaims(JulcList<OutputClaim> claims, JulcList<DepositClaim> deposits) {}
