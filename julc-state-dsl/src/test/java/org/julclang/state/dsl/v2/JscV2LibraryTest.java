package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D9 (M4): {@code .jsc} libraries. A file headed {@code library <package>} holds types, enums and fns; a
 * contract or another library imports them by relative path ({@code import { fee } from "./fees.jsc"}); a library lowers
 * to a JuLC {@code @OnchainLibrary} class (its capitalised file name), which Java contracts call too. The declarations of a
 * build share one namespace; a library's names are seen only where imported; import cycles are refused; an error in a
 * library is reported at the import that brings it in.
 */
class JscV2LibraryTest {

    static final String FEES = """
            jsc 2
            library app

            import { blake2b_224 } from "std/crypto"

            type Line = { to: Key, bps: Int }
            enum Tier { Basic, Gold { since: Int } }

            fn fee(amount: Int, bps: Int): Int = amount * bps / 10_000
            fn rate(t: Tier): Int = match t { Basic => 100, Gold { since } => since > 9 ? 25 : 50 }
            fn hidden(): Int = 7
            fn tag(b: Bytes): Bytes = blake2b_224(b)
            """;

    static String contract(String imports, String members) {
        return "jsc 2\npackage app\n" + imports + "\ncontract Shop {\n  state { owner: Key, price: Int, tier: Tier }\n"
                + "  init(price: Int) { return { owner: caller, price, tier: Basic } }\n" + members + "\n}\n";
    }

    static Map<String, String> build(String... kv) {
        var m = new LinkedHashMap<String, String>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    static JscV2.Lowered lower(Map<String, String> files, String id) {
        String file = id.substring(id.lastIndexOf('/') + 1);
        return JscV2.lower(files.get(id), file, id, JscModules.of(files));
    }

    static JscException refused(Map<String, String> files, String id, String code) {
        var e = assertThrows(JscException.class, () -> lower(files, id));
        assertEquals(code, e.code, e.line + ":" + e.col + ": " + e.getMessage());
        assertTrue(e.line > 0 && e.col > 0, "located");
        return e;
    }

    static final String IMPORT = "import { Tier, Line, fee, rate } from \"./fees.jsc\"";

    @Test
    void aLibrary_lowersToAnOnchainLibraryClass_namedByItsFile() {
        var files = build("app/fees.jsc", FEES);
        var l = lower(files, "app/fees.jsc");
        assertTrue(l.java().contains("@OnchainLibrary\npublic class Fees {"), l.java());
        assertTrue(l.java().contains("public static BigInteger fee(BigInteger amount, BigInteger bps)"), l.java());
        assertTrue(l.java().contains("public record Line(byte[] to, BigInteger bps) {}"), l.java());
        assertTrue(l.java().contains("public sealed interface Tier permits Basic, Gold {}"), l.java());
        assertEquals("app/Fees.java", l.javaPath());
        assertNull(l.abiJson(), "a library is not deployed: it has no ABI manifest");
        assertNull(l.irJson(), "each importing contract's IR carries the library");
        assertEquals("app/Fees.java", JscV2.outputPath(FEES, "fees.jsc"));
        assertTrue(JscV2.isLibrary(FEES));
    }

    @Test
    void aContract_importsLibraryTypesAndFns_qualifiedInTheJava() {
        var files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT,
                "  action buy() payable { require(received >= price + fee(price, rate(tier)), \"pay\"); return state }\n"
                        + "  action move(t: Tier, l: Line) open { require(l.bps > 0, \"bps\"); return { ...state, tier: t } }\n"
                        + "  action promote() open { return { ...state, tier: Gold { since: 1 } } }"));
        var l = lower(files, "app/Shop.jsc");
        assertTrue(l.java().contains("import app.Fees;"), l.java());
        assertTrue(l.java().contains("public record State(byte[] owner, BigInteger price, Fees.Tier tier) {}"), l.java());
        assertTrue(l.java().contains("Fees.fee(") && l.java().contains("Fees.rate("), l.java());
        assertTrue(l.java().contains("new Fees.Gold(") && l.java().contains("new Fees.Basic()"), l.java());
        // The ABI states the library's types the encoded roots reach, with the library that declares them.
        assertTrue(l.abiJson().contains("\"Tier\": { \"kind\": \"library\", \"library\": \"app.Fees\""), l.abiJson());
        assertTrue(l.abiJson().contains("\"Line\": { \"kind\": \"library\", \"library\": \"app.Fees\""), l.abiJson());
        // The IR (version 2) carries the library's declarations for the interpreters.
        assertTrue(l.irJson().contains("\"ir\": 2,") && l.irJson().contains("\"library\": \"app.Fees\""), l.irJson());
        var p = IrJson.read(l.irJson());
        assertEquals(1, p.libraries().size());
        assertNotNull(p.fn("rate"));
        assertEquals(l.irJson(), IrJson.write(p, IrJson.sourceHashOf(l.irJson())), "lossless");
    }

    @Test
    void theInterpreter_evaluatesLibraryFns() {
        var files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT,
                "  view due(): Int = price + fee(price, rate(tier))"));
        var in = JscV2.interpreter(files.get("app/Shop.jsc"), "Shop.jsc", "app/Shop.jsc", JscModules.of(files), Interpreter.JDK_HASHES);
        var s = new Interpreter.Rec("State", List.of(new Interpreter.Bytes(new byte[28]), BigInteger.valueOf(10_000), new Interpreter.Rec("Basic", List.of())));
        assertEquals(BigInteger.valueOf(10_100), in.call("due", s, List.of(), null).value());
        var g = new Interpreter.Rec("State", List.of(new Interpreter.Bytes(new byte[28]), BigInteger.valueOf(10_000),
                new Interpreter.Rec("Gold", List.of(BigInteger.TEN))));
        assertEquals(BigInteger.valueOf(10_025), in.call("due", g, List.of(), null).value());
    }

    @Test
    void aLibrarysDeclarations_areSeenOnlyWhereImported() {
        // A fn the contract did not import.
        var files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  view h(): Int = hidden()"));
        var e = refused(files, "app/Shop.jsc", "JSC0710");
        assertTrue(e.getMessage().contains("'hidden' is declared in app/fees.jsc: import { hidden }"), e.getMessage());
        // A type the contract did not import, by name; and a variant whose enum it did not import.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract("import { Tier } from \"./fees.jsc\"", "  view x(l: Line): Int = 1"));
        e = refused(files, "app/Shop.jsc", "JSC0710");
        assertTrue(e.getMessage().contains("'Line' is declared in app/fees.jsc"), e.getMessage());
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", "jsc 2\npackage app\nimport { fee } from \"./fees.jsc\"\ncontract Shop {\n"
                + "  state { n: Int }\n  init() { return { n: 0 } }\n  view v(): Bool = Basic == Basic\n}\n");
        e = refused(files, "app/Shop.jsc", "JSC0710");
        assertTrue(e.getMessage().contains("the variant Basic of Tier"), e.getMessage());
        // Values of a type that is not imported still flow: a library fn returns one, and its fields are read.
        files = build("app/fees.jsc", FEES + "fn mk(n: Int): Line = Line { to: asKey(#" + "00".repeat(28) + "), bps: n }\n",
                "app/Shop.jsc", "jsc 2\npackage app\nimport { mk } from \"./fees.jsc\"\ncontract Shop {\n"
                        + "  state { n: Int }\n  init() { return { n: mk(3).bps } }\n}\n");
        assertTrue(lower(files, "app/Shop.jsc").java().contains("Fees.mk("));
        // A binding may take the name of a library declaration the file does not import (it hides nothing in scope),
        // not of one it imports.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  view h(hidden: Int): Int = hidden"));
        lower(files, "app/Shop.jsc");
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  view h(fee: Int): Int = fee"));
        refused(files, "app/Shop.jsc", "JSC0222");
        // std imports are per file: the library's import of blake2b_224 is not the contract's.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  view h(b: Bytes): Bytes = blake2b_224(b)"));
        refused(files, "app/Shop.jsc", "JSC0704");
    }

    @Test
    void oneNamespacePerBuild_andLibraryClasses() {
        // A contract type with the name of a library type.
        var files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "") .replace("contract Shop {", "type Line = { x: Int }\ncontract Shop {"));
        var e = refused(files, "app/Shop.jsc", "JSC0709");
        assertTrue(e.getMessage().contains("also declared in app/fees.jsc"), e.getMessage());
        // A contract fn with the name of a library fn, imported or not.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  fn hidden(): Int = 1"));
        refused(files, "app/Shop.jsc", "JSC0709");
        // A declaration with the name of a library's class.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "").replace("contract Shop {", "type Fees = { x: Int }\ncontract Shop {"));
        refused(files, "app/Shop.jsc", "JSC0709");
        // Two libraries that are one class (the same package and file name).
        files = build("app/fees.jsc", FEES, "lib/fees.jsc", "jsc 2\nlibrary app\nfn two(): Int = 2\n", "app/Shop.jsc",
                contract(IMPORT + "\nimport { two } from \"../lib/fees.jsc\"", ""));
        e = refused(files, "app/Shop.jsc", "JSC0709");
        assertTrue(e.getMessage().contains("both the library app.Fees"), e.getMessage());
        // A library's file name is its class: it must make one.
        files = build("app/fee-math.jsc", "jsc 2\nlibrary app\nfn two(): Int = 2\n", "app/Shop.jsc",
                contract("import { two } from \"./fee-math.jsc\"", "").replace("tier: Tier", "n: Int").replace("tier: Basic", "n: two()"));
        e = refused(files, "app/Shop.jsc", "JSC0712");
        assertTrue(e.getMessage().contains("error[JSC0705]"), e.getMessage());
    }

    @Test
    void imports_resolveRelativePaths_refuseCyclesContractsAndOutsideTheRoot() {
        // A missing module, a bare specifier, a path out of the sources.
        var files = build("app/Shop.jsc", contract("import { Tier } from \"./nope.jsc\"", ""));
        refused(files, "app/Shop.jsc", "JSC0706");
        files = build("app/Shop.jsc", contract("import { Tier } from \"fees\"", ""));
        refused(files, "app/Shop.jsc", "JSC0706");
        files = build("fees.jsc", FEES, "app/Shop.jsc", contract("import { Tier } from \"../../fees.jsc\"", ""));
        refused(files, "app/Shop.jsc", "JSC0706");
        // Up a directory, inside the root.
        files = build("lib/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT.replace("./fees.jsc", "../lib/fees.jsc"), ""));
        assertTrue(lower(files, "app/Shop.jsc").java().contains("import app.Fees;"));
        // A cycle.
        files = build("app/a.jsc", "jsc 2\nlibrary app\nimport { b } from \"./b.jsc\"\nfn a(): Int = 1\n",
                "app/b.jsc", "jsc 2\nlibrary app\nimport { a } from \"./a.jsc\"\nfn b(): Int = 2\n",
                "app/Shop.jsc", "jsc 2\npackage app\nimport { a } from \"./a.jsc\"\ncontract Shop {\n  state { n: Int }\n  init() { return { n: a() } }\n}\n");
        var e = refused(files, "app/Shop.jsc", "JSC0707");
        assertTrue(e.getMessage().contains("app/a.jsc -> app/b.jsc -> app/a.jsc"), e.getMessage());
        // A contract is not a library.
        files = build("app/Other.jsc", contract("", "").replace("tier: Tier", "n: Int").replace("tier: Basic", "n: 0").replace("Shop", "Other"),
                "app/Shop.jsc", contract("import { Tier } from \"./Other.jsc\"", ""));
        var x = refused(files, "app/Shop.jsc", "JSC0712");
        assertTrue(x.getMessage().contains("error[JSC0708]") && x.getMessage().contains("is a contract, not a library"), x.getMessage());
        // A name the library does not declare.
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract("import { Tier, nothing } from \"./fees.jsc\"", ""));
        refused(files, "app/Shop.jsc", "JSC0708");
        // A file lowered on its own imports no libraries.
        assertEquals("JSC0706", assertThrows(JscException.class, () -> JscV2.lower(contract(IMPORT, ""), "Shop.jsc")).code);
        // A library holds no contract.
        var lib = assertThrows(JscException.class, () -> JscV2.lower("jsc 2\nlibrary app\ncontract X {\n state { n: Int }\n init() { return { n: 0 } }\n}\n", "x.jsc"));
        assertEquals("JSC0711", lib.code);
    }

    @Test
    void anErrorInALibrary_isReportedAtTheImport_namingTheLibrarysPosition() {
        var files = build("app/fees.jsc", FEES.replace("amount * bps / 10_000", "amount * bps / true"), "app/Shop.jsc", contract(IMPORT, ""));
        var e = refused(files, "app/Shop.jsc", "JSC0712");
        assertEquals(3, e.line, "the import line of Shop.jsc");
        assertTrue(e.getMessage().startsWith("in the imported library app/fees.jsc:9:"), e.getMessage());
        // D11 through library calls: a library fn that divides by its parameter, called with a time value, is refused at
        // the call, in the importing contract (the library alone builds).
        files = build("app/fees.jsc", FEES + "fn per(total: Int, n: Int): Int = total / n\n", "app/Shop.jsc", contract(IMPORT.replace("rate }", "rate, per }"),
                "  action late() open { return { ...state, price: per(100, validFrom) } }"));
        e = refused(files, "app/Shop.jsc", "JSC0712");
        assertTrue(e.getMessage().contains("error[JSC05") && e.getMessage().contains("called with a time value"), e.getMessage());
        lower(build("app/fees.jsc", FEES + "fn per(total: Int, n: Int): Int = total / n\n"), "app/fees.jsc");
        // A method of the contract named like a library variant is refused, as for its own (its redeemer's class).
        files = build("app/fees.jsc", FEES, "app/Shop.jsc", contract(IMPORT, "  action gold() open { return state }"));
        refused(files, "app/Shop.jsc", "JSC0203");
    }

    @Test
    void theMessageMap_namesALibrarysSitesWithItsFile() {
        var files = build("app/fees.jsc", FEES + "fn checked(n: Int): Int {\n  require(n >= 0, \"never negative\")\n  n\n}\n",
                "app/Shop.jsc", contract(IMPORT.replace("rate }", "rate, checked }"), "  action set(p: Int) open { return { ...state, price: checked(p) } }"));
        var l = lower(files, "app/Shop.jsc");
        assertTrue(l.sourceMapJson().contains("\"never negative\": [{ \"line\": 14, \"col\": 3, \"kind\": \"require\", \"in\": \"checked\", \"file\": \"fees.jsc\" }]"),
                l.sourceMapJson());
        assertEquals(List.of("fees.jsc:14:3 (a require in checked)"), l.explain("never negative"));
    }

    @Test
    void jscMain_lowersADirectoryWithItsLibraries(@TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("src/app"));
        Files.writeString(src.resolve("fees.jsc"), FEES);
        Files.writeString(src.resolve("Shop.jsc"), contract(IMPORT, ""));
        var err = new ByteArrayOutputStream();
        PrintStream old = System.err;
        int code;
        try {
            System.setErr(new PrintStream(err, true));
            code = JscMain.run(new String[]{dir.resolve("src").toString(), dir.resolve("out").toString(), "--accept-abi"});
        } finally {
            System.setErr(old);
        }
        assertEquals(0, code, err.toString());
        assertTrue(Files.readString(dir.resolve("out/app/Fees.java")).contains("@OnchainLibrary"));
        assertTrue(Files.readString(dir.resolve("out/app/Shop.java")).contains("import app.Fees;"));
        assertTrue(Files.exists(src.resolve("Shop.abi.json")) && !Files.exists(src.resolve("Fees.abi.json")) && !Files.exists(src.resolve("fees.abi.json")));
        assertFalse(Files.exists(dir.resolve("out/app/Fees.ir.json")));
        assertTrue(Files.exists(dir.resolve("out/app/Fees.jscmap.json")));
    }
}
