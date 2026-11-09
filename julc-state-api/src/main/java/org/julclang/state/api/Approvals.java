package org.julclang.state.api;

import org.julclang.state.api.Approval;
import org.julclang.core.types.JulcList;

/**
 * The Rewarding redeemer a Plutus governor script uses to approve JOUST actions (ADR-0009 r2): every approval it
 * grants in this transaction. The governor's own rules decide which approvals it lists; each JOUST validator accepts
 * only the one naming itself, its target and its action.
 */
public record Approvals(JulcList<Approval> approvals) {}
