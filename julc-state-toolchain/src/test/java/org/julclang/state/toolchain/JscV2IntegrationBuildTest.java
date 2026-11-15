package org.julclang.state.toolchain;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * #98 review S4 (the .jsc 2 integration, M2c × M4): a receiving action's memo, and a send's datum, of a library's
 * record or enum: the decoders and encoders name the library's types through its class, so the Java compiles and the
 * contract builds (it was a javac error: "cannot find symbol class Line").
 */
class JscV2IntegrationBuildTest {

    static final String LIB = """
            jsc 1
            library t

            type Line = { to: Key, bps: Int }
            enum Tier { Basic, Gold { since: Int } }
            """;

    static Toolchain.Result build(String contract) {
        var files = new LinkedHashMap<String, String>();
        files.put("t/lib.jsc", LIB);
        files.put("t/P.jsc", contract);
        return Toolchain.build(files, Map.of());
    }

    static String receiving(String memo) {
        return "jsc 1\npackage t\n\nimport { Line, Tier } from \"./lib.jsc\"\n\ncontract P {\n  state { owner: Key, n: Int }\n\n"
                + "  init() {\n    return { owner: caller, n: 0 }\n  }\n\n"
                + "  action take() receives " + memo + " {\n    return { ...state, n: n + funds.deposits.length }\n  }\n\n"
                + "  destroy close() only owner {\n    pay(owner, balance)\n  }\n}\n";
    }

    @Test
    void aMemoOfALibraryRecordOrEnum_builds() {
        for (String memo : new String[]{"Line", "Tier"}) {
            var r = build(receiving(memo));
            assertTrue(r.ok(), memo + ": " + r.diagnostics());
        }
    }

    @Test
    void aSendDatumOfALibraryRecordOrEnum_builds() {
        String c = """
                jsc 1
                package t

                import { Line, Tier } from "./lib.jsc"

                contract P {
                  state { owner: Key, n: Int }

                  init() {
                    return { owner: caller, n: 0 }
                  }

                  action give(to: Key) only owner {
                    send(wallet(to), Value.lovelace(2_000_000), Line { to, bps: 5 })
                    send(wallet(to), Value.lovelace(2_000_000), Gold { since: 3 })
                    return state
                  }

                  destroy close() only owner {
                    pay(owner, balance)
                  }
                }
                """;
        var r = build(c);
        assertTrue(r.ok(), r.diagnostics().toString());
    }
}
