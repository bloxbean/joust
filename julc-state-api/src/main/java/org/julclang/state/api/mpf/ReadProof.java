package org.julclang.state.api.mpf;

import org.julclang.state.api.mpf.ProofStep;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;

/** A read-set entry and its MPF proof against the map's pre-call root (ADR-0002 D3). */
public record ReadProof(MapEntry entry, JulcList<ProofStep> proof) {}
