package org.julclang.state.toolchain;

import org.julclang.state.dsl.v2.Interpreter.BigMapV;
import org.julclang.state.dsl.v2.Interpreter.Bytes;
import org.julclang.state.dsl.v2.Interpreter.MapV;
import org.julclang.state.dsl.v2.Interpreter.Opt;
import org.julclang.state.dsl.v2.Interpreter.Rec;
import org.julclang.state.testkit.StateHarness;
import org.julclang.vm.EvalResult;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.julclang.state.toolchain.FourWay.*;
import static org.julclang.state.toolchain.JscV2M1aFourWayTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1a measurements for the report: execution units of the generated validators for the new constructs (each
 * call accepted on-chain), and the on-chain cost of a Map's ingress check as a function of its size.
 */
class JscV2M1aMeasureTest {

    static long[] units(StateHarness.Verdict v) {
        long cpu = 0, mem = 0;
        for (EvalResult r : v.results().values()) {
            var c = switch (r) {
                case EvalResult.Success s -> s.consumed();
                case EvalResult.Failure f -> f.consumed();
                case EvalResult.BudgetExhausted b -> b.consumed();
            };
            cpu += c.cpuSteps();
            mem += c.memoryUnits();
        }
        return new long[]{cpu, mem};
    }

    static void measure(FourWay f, String label, String method, Rec state, List<Object> args, org.julclang.state.dsl.v2.Interpreter.Tx t,
                        byte[] signer) throws Exception {
        assertNotEquals("refused", f.check(method, state, args, t, signer), label);
        long[] u = units(f.lastVerdict);
        System.out.printf("[M1a budget] %-48s cpu %,15d  mem %,10d%n", f.name + " " + label, u[0], u[1]);
    }

    static MapV limits(int n) {
        var m = new LinkedHashMap<Bytes, Object>();
        for (int i = 0; i < n; i++) {
            byte[] k = new byte[28];
            k[0] = (byte) (i + 1);
            m.put(Bytes.of(k), BigInteger.valueOf(1_000 + i));
        }
        return MapV.of(m);
    }

    @Test
    void ledgerAndShelf() throws Exception {
        JscV2M1aFourWayTest.build();
        var f = LEGS.get("Ledger");
        Map<Bytes, Object> holdings = new LinkedHashMap<>();
        holdings.put(b(ANN), n(40));
        holdings.put(b(BEN), n(10));
        MapV lim = map(b(ANN), n(100), b(BEN), n(50));
        Rec s = ledger(OPEN, lim, holdings, 50);
        for (int size : List.of(0, 2, 10)) measure(f, "init (limits: " + size + " entries)", "init", null, List.of(limits(size)), at(OWNER, 0, 0), OWNER);
        measure(f, "pause (enum ==, Paused)", "pause", s, List.of(utf8("audit")), null, OWNER);
        measure(f, "close (statement match)", "close", s, List.of(n(9)), at(OWNER, 0, 0), OWNER);
        measure(f, "setLimit (Map.set, new key)", "setLimit", s, List.of(b(CAT), n(5)), null, OWNER);
        measure(f, "setLimit (Map.delete)", "setLimit", s, List.of(b(ANN), n(0)), null, OWNER);
        measure(f, "mint (Map.get, BigMap read and write)", "mint", s, List.of(b(ANN), n(10)), null, OWNER);
        measure(f, "transfer (two BigMap writes)", "transfer", s, List.of(b(CAT), n(25)), at(ANN, 0, 0), ANN);
        measure(f, "settle (isEmpty, match with pays)", "settle", ledger(rec("Closed", n(7), b(BEN)), lim, Map.of(), 0), List.of(),
                at(OWNER, 0, 10_000_001), OWNER);
        var sh = LEGS.get("Shelf");
        Rec empty = shelf(MapV.EMPTY, Map.of(), Opt.NONE);
        Rec item = item("title", rec("Disc", n(30)), "a", "b");
        measure(sh, "put (Map of records, Option of an enum)", "put", empty, List.of(utf8("id"), item), null, OWNER);
        Rec one = sh.next("put", empty, List.of(utf8("id"), item), null);
        measure(sh, "remove (match on Map.get)", "remove", one, List.of(utf8("id")), null, OWNER);
        measure(sh, "note (BigMap<Bytes, Bytes>)", "note", one, List.of(utf8("k"), utf8("v")), at(ANN, 0, 0), ANN);
    }
}
