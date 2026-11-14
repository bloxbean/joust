package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The joins of the .jsc 2 integration (#98) that only a build of several files shows: an imported contract whose
 * types use a library's (JSC0746), names a Java library and an imported contract both take (JSC0744), and an imported
 * .jsc library over a Java library's records (review C1).
 */
class JscV2IntegrationTest {

    static final String LIB = """
            jsc 1
            library app

            type Line = { to: Key, bps: Int }
            enum Tier { Basic, Gold { since: Int } }

            fn fee(amount: Int, bps: Int): Int = amount * bps / 10_000
            """;

    /** A contract Feed whose {@code members} are given, importing app/lib.jsc. */
    static String feed(String members) {
        return "jsc 1\npackage app\n\nimport { Line, Tier, fee } from \"./lib.jsc\"\n\ncontract Feed {\n" + members + "\n}\n";
    }

    static final String SHOP = """
            jsc 1
            package app

            import contract { Feed } from "./Feed.jsc"

            contract Shop {
              params { feed: Instance<Feed> }
              state { n: Int }

              init() {
                return { n: 0 }
              }

              action look(f: reads params.feed) open {
                return { ...state, n: n + 1 }
              }
            }
            """;

    static JscV2.Lowered build(String feed) {
        var fs = new LinkedHashMap<String, String>();
        fs.put("app/lib.jsc", LIB);
        fs.put("app/Feed.jsc", feed);
        fs.put("app/Shop.jsc", SHOP);
        return JscV2.lower(SHOP, "Shop.jsc", "app/Shop.jsc", JscModules.of(fs));
    }

    static final String INIT = "  init() {\n    return { owner: caller }\n  }\n  action set() only owner {\n    return state\n  }\n";

    /**
     * #98 review S6, F5: each shape of a library type in an imported contract's foreign view is refused (JSC0746), and each
     * of those contracts builds on its own; a library type only in a method's parameters and results is accepted.
     */
    @Test
    void anImportedContractUsingALibrarysTypeInItsView_isRefused_andOnlyThat() {
        var refused = new LinkedHashMap<String, String>();
        refused.put("state List<Line>", "  state { owner: Key, lines: List<Line> }\n  init() {\n    return { owner: caller, lines: [] }\n  }\n");
        refused.put("state Option<Tier>", "  state { owner: Key, tier: Option<Tier> }\n  init() {\n    return { owner: caller, tier: None }\n  }\n");
        refused.put("state Map<Key, Line>", "  state { owner: Key, m: Map<Key, Line> }\n  init() {\n    return { owner: caller, m: Map.empty() }\n  }\n");
        refused.put("a record wrapping Line", "  type Wrap = { l: Line }\n  state { owner: Key }\n" + INIT);
        refused.put("a variant holding Line", "  enum Box { Empty, Full { l: Line } }\n  state { owner: Key }\n" + INIT);
        refused.put("receives Line", "  state { owner: Key, n: Int }\n  init() {\n    return { owner: caller, n: 0 }\n  }\n"
                + "  action take() receives Line {\n    return { ...state, n: n + funds.deposits.length }\n  }\n");
        refused.put("an event carrying Line", "  state { owner: Key }\n  event Added { l: Line }\n  init() {\n    return { owner: caller }\n  }\n"
                + "  action add(l: Line) only owner {\n    emit(Added { l })\n    return state\n  }\n");
        for (var e : refused.entrySet()) {
            String f = feed(e.getValue());
            var fs = new LinkedHashMap<String, String>(Map.of("app/lib.jsc", LIB, "app/Feed.jsc", f));
            assertDoesNotThrow(() -> JscV2.lower(f, "Feed.jsc", "app/Feed.jsc", JscModules.of(fs)), e.getKey() + ": Feed builds on its own");
            var x = assertThrows(JscException.class, () -> build(f), e.getKey());
            assertEquals("JSC0746", x.code, e.getKey() + ": " + x.getMessage());
            assertTrue(x.getMessage().contains("its state, its declared types, its events or its memos"), x.getMessage());
        }
        // A library type only in a method's parameter and a view's result: not in the foreign view, so accepted.
        String args = feed("  state { owner: Key, total: Int }\n  init() {\n    return { owner: caller, total: 0 }\n  }\n"
                + "  action add(l: Line) only owner {\n    return { ...state, total: total + fee(100, l.bps) }\n  }\n"
                + "  view sample(): Line = Line { to: owner, bps: 1 }\n");
        assertDoesNotThrow(() -> build(args));
    }

    /** #98 review C2: an imported contract and a Java library of the same simple name: JSC0744, not uncompilable Java. */
    @Test
    void anImportedContractAndAJavaLibraryOfOneSimpleName_areRefused() {
        String l = "jsc 1\npackage data\n\ncontract L {\n  state { owner: Key, n: Int }\n  init() {\n    return { owner: caller, n: 0 }\n  }\n}\n";
        String shop = """
                jsc 1
                package app

                import contract { L } from "./L.jsc"
                import { twice } from "java:com.acme.L"

                contract Shop {
                  params { feed: Instance<L> }
                  state { n: Int }

                  init() {
                    return { n: 0 }
                  }

                  action look(f: reads params.feed) open {
                    return { ...state, n: f.n + twice(1) }
                  }
                }
                """;
        var fs = new LinkedHashMap<String, String>(Map.of("app/L.jsc", l, "app/Shop.jsc", shop));
        var x = assertThrows(JscException.class, () -> JscV2.lower(shop, "Shop.jsc", "app/Shop.jsc", JscModules.of(fs), Examples.JAVA));
        assertEquals("JSC0744", x.code, x.getMessage());
        assertTrue(x.getMessage().contains("the imported contract data.L"), x.getMessage());
    }

    /**
     * #98 review C1: a .jsc library comparing a Java library's verified records, imported by a contract: the contract
     * lowers (its library's message sites included), as the library does on its own.
     */
    @Test
    void aJscLibraryOverJavaRecords_lowersInTheContractThatImportsIt() {
        String wrapper = """
                jsc 1
                library app

                import { mk, P } from "java:com.acme.L"

                fn same(): Bool = mk(#01, 1) == mk(#01, 1)
                """;
        String c = """
                jsc 1
                package app

                import { same } from "./wrapper.jsc"

                contract C {
                  state { owner: Key }

                  init() {
                    require(same(), "the records agree")
                    return { owner: caller }
                  }
                }
                """;
        var fs = new LinkedHashMap<String, String>(Map.of("app/wrapper.jsc", wrapper, "app/C.jsc", c));
        assertDoesNotThrow(() -> JscV2.lower(wrapper, "wrapper.jsc", "app/wrapper.jsc", JscModules.of(fs), Examples.JAVA));
        var lowered = JscV2.lower(c, "C.jsc", "app/C.jsc", JscModules.of(fs), Examples.JAVA);
        assertTrue(lowered.java().contains("same()"), lowered.java());
        assertTrue(lowered.messages().stream().anyMatch(s -> s.message().equals("the records agree")), lowered.messages().toString());
    }
}
