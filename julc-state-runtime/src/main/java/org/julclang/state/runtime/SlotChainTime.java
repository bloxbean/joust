package org.julclang.state.runtime;

import com.bloxbean.cardano.client.backend.api.BackendService;

/**
 * Linear slot clock (one era: constant slot length since {@code zeroSlot}) with the tip from a backend. Fits Yaci
 * DevKit and every network whose relevant history uses one slot length; pass the Shelley start for mainnet/testnets.
 */
public final class SlotChainTime implements ChainTime {

    private final long zeroSlot;
    private final long zeroTimeMs;
    private final long slotLengthMs;
    private final BackendService backend;

    public SlotChainTime(long zeroSlot, long zeroTimeMs, long slotLengthMs, BackendService backend) {
        if (slotLengthMs <= 0) throw new IllegalArgumentException("slotLengthMs must be positive");
        this.zeroSlot = zeroSlot;
        this.zeroTimeMs = zeroTimeMs;
        this.slotLengthMs = slotLengthMs;
        this.backend = backend;
    }

    public long zeroSlot() {
        return zeroSlot;
    }

    public long zeroTimeMs() {
        return zeroTimeMs;
    }

    @Override
    public long slotLengthMs() {
        return slotLengthMs;
    }

    @Override
    public long slotToPosixMs(long slot) {
        // Exact: an absurd slot throws instead of wrapping into a time in the past (R-E2).
        return Math.addExact(zeroTimeMs, Math.multiplyExact(Math.subtractExact(slot, zeroSlot), slotLengthMs));
    }

    @Override
    public long posixMsToSlotFloor(long posixMs) {
        return zeroSlot + Math.floorDiv(posixMs - zeroTimeMs, slotLengthMs);
    }

    @Override
    public long tipSlot() {
        try {
            var r = backend.getBlockService().getLatestBlock();
            if (!r.isSuccessful() || r.getValue() == null) {
                throw new JoustException("cannot read the chain tip: " + r.getResponse());
            }
            return r.getValue().getSlot();
        } catch (JoustException e) {
            throw e;
        } catch (Exception e) {
            throw new JoustException("cannot read the chain tip", e);
        }
    }
}
