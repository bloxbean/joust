package org.julclang.state.examples.registry;

import org.julclang.core.types.JulcList;
import org.julclang.state.examples.registry.RegistryWorld.Kind;
import org.julclang.state.examples.registry.widest.WidestClient;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.HexFormat;

import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0016 M1 gates: the applied scripts of the registry and of the widest accepted contract fit the runtime's
 * 15,000 bytes (StateContractHandle.scriptFor), and an insert and a remove at the size caps (64-byte keys, datums at
 * the envelope limit) stay within half of the per-transaction CPU and memory limits, summed over every script.
 */
class SizeAndBudgetTest {

    /** The runtime's limit for an applied script (F10). */
    static final int SCRIPT_LIMIT = 15_000;
    /** Per-transaction limits: CPU steps; memory before (14,000,000) and since (16,500,000) the Plomin raise. */
    static final long MAX_CPU = 10_000_000_000L;
    static final long MAX_MEM_OLD = 14_000_000L;
    static final long MAX_MEM = 16_500_000L;

    static int size(org.julclang.state.runtime.ContractSpec<?> spec) {
        return HexFormat.of().parseHex(StateContractHandle.scriptFor(spec, null).getCborHex()).length;
    }

    @Test
    void appliedScriptsFit() {
        int registry = size(RegistryClient.SPEC);
        int widest = size(WidestClient.SPEC);
        System.out.println("[size] RegistryValidator applied script: " + registry + " bytes (limit " + SCRIPT_LIMIT + ")");
        System.out.println("[size] WidestValidator applied script: " + widest + " bytes (limit " + SCRIPT_LIMIT + ")");
        assertTrue(registry <= SCRIPT_LIMIT);
        assertTrue(widest <= SCRIPT_LIMIT);
    }

    /** A claim of {@code name} whose datum is as large as the envelope allows (most guardians that fit). */
    static RegistryWorld.Built largest(RegistryWorld w, byte[] inst, byte[] name) {
        RegistryWorld.Built best = null;
        for (int n = 1; n <= 10; n++) {
            JulcList<byte[]> gs = JulcList.empty();
            for (int i = n; i >= 1; i--) gs = gs.prepend(StateHarness.fill(28, 0x20 + i));
            var t = w.claim(inst, PARTIES.get(0), name, ada(3), BigInteger.ONE, gs, BigInteger.ONE);
            if (!w.ledger.check(t.tx).accepted()) break;
            best = t;
        }
        return best;
    }

    static void report(String what, Ledger.Result r) {
        System.out.println("[budget] " + what + ": cpu=" + r.cpu() + " (" + pct(r.cpu(), MAX_CPU) + " of " + MAX_CPU + "), mem="
                + r.mem() + " (" + pct(r.mem(), MAX_MEM_OLD) + " of " + MAX_MEM_OLD + ", " + pct(r.mem(), MAX_MEM) + " of "
                + MAX_MEM + ")");
        r.scripts().forEach((k, v) -> System.out.println("[budget]     " + k + ": cpu=" + v.budgetConsumed().cpuSteps() + " mem="
                + v.budgetConsumed().memoryUnits()));
        assertTrue(r.cpu() <= MAX_CPU / 2, what + ": CPU over half the per-transaction limit");
        assertTrue(r.mem() <= MAX_MEM_OLD / 2, what + ": memory over half the per-transaction limit");
    }

    static String pct(long v, long of) {
        return String.format("%.2f%%", 100.0 * v / of);
    }

    @Test
    void budgetsAtTheSizeCaps() {
        var w = new RegistryWorld();
        byte[] inst = w.createInstance();
        byte[] k1 = StateHarness.fill(64, 0x41);
        byte[] k2 = StateHarness.fill(64, 0x42);
        byte[] k3 = StateHarness.fill(64, 0x43);
        w.accept(largest(w, inst, k1));
        w.accept(largest(w, inst, k2));
        var insert = largest(w, inst, k3);   // anchored at k2, both at the envelope limit
        int datum = org.julclang.core.cbor.PlutusDataCborEncoder.encode(Tamper.datum(insert.tx.outputs.get(insert.nodeOut))).length;
        System.out.println("[budget] node datum at the cap: " + datum + " bytes (maxDatumBytes 384, next padded to 64 bytes)");
        report("insert of a 64-byte key after a 64-byte key, both datums at the cap", w.accept(insert));
        report("remove of a 64-byte key whose predecessor has a 64-byte key, both datums at the cap",
                w.accept(w.release(inst, k2)));
        report("retire of a node at the cap", w.accept(w.retire(inst, Kind.ENTRY, k3, BigInteger.ZERO)));
        report("keyed action (upgrade) on a node at the cap", w.accept(w.upgrade(inst, k1, PARTIES.get(0), ada(2))));
        report("instance creation with two heads", w.ledger.check(w.createInstanceTx().tx));
    }
}
