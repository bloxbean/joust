package org.julclang.state.runtime;

/**
 * Slot / POSIX-time conversion for one network, plus the chain tip. The runtime picks slots first and derives the
 * milliseconds the contract sees from them, so the JVM run and the on-chain run agree exactly (ADR-0001 D3).
 */
public interface ChainTime {

    /** POSIX ms at the start of {@code slot}. */
    long slotToPosixMs(long slot);

    /** The last slot starting at or before {@code posixMs}. */
    long posixMsToSlotFloor(long posixMs);

    /** Length of one slot in ms. */
    long slotLengthMs();

    /** The current tip slot. */
    long tipSlot();
}
