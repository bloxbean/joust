package org.julclang.state.api.mpf;

import org.julclang.state.api.mpf.ProofStep;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.MapEntry;

/** One net write: the key's entry before and after, and the MPF proof of its position at this step (ADR-0002 D3). */
public record WriteProof(MapEntry before, MapEntry after, JulcList<ProofStep> proof) {}
