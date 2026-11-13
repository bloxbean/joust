package org.julclang.state.toolchain;

import org.julclang.core.types.JulcList;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.dsl.Pledges;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.julclang.state.toolchain.TransitionParityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M0 on the npm path: {@code .jsc} 2 contracts build through the toolchain, errors keep their {@code .jsc}
 * line and column (D12, J5), and the transition programs the engine runs agree with the generated Java on the JVM,
 * byte for byte, including refusals (J3's transition-program leg).
 */
class JscV2ToolchainTest {

    static final Path DSL = Path.of("../examples/dsl/src/main/jsc/org/julclang/state/examples/dsl");

    static Toolchain.Result build(String... names) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        for (String n : names) files.put("org/julclang/state/examples/dsl/" + n, Files.readString(DSL.resolve(n)));
        return Toolchain.build(files, Map.of());
    }

    @Test
    void jsc2ContractsBuild_onTheNpmPath() throws Exception {
        var r = build("Counter2.jsc", "Pledges.jsc", "Vectors.jsc");
        assertTrue(r.ok(), r.diagnostics().toString());
        var names = r.contracts().stream().map(Toolchain.Contract::name).toList();
        assertTrue(names.contains("Counter2") && names.contains("Pledges") && names.contains("Vectors"), names.toString());
        for (var c : r.contracts()) {
            System.out.println("[jsc2 npm] " + c.name() + ": " + c.scriptSizeBytes() + " bytes, transitions " + c.transitions().keySet());
        }
    }

    /**
     * ADR-0018 D14 (M4c): a .jsc library of an npm package, imported by package specifier, is lowered and compiled with
     * the project, its class linted as a generated library; the same library inside the project builds the same script.
     */
    @Test
    void anNpmPackagesJscLibrary_buildsAsTheProjectsOwnWould() {
        String fees = "jsc 2\nlibrary acme.fees\n\nfn fee(amount: Int, bps: Int): Int = amount * bps / 10_000\n";
        String shop = """
                jsc 2
                package app

                import { fee } from "%s"

                contract Shop {
                  state { owner: Key, price: Int }
                  init(price: Int) { return { owner: caller, price } }
                  action buy() payable {
                    require(received >= price + fee(price, 100), "pay the price and the fee")
                    return state
                  }
                }
                """;
        var fromPackage = Toolchain.build(Map.of("app/Shop.jsc", shop.formatted("@acme/fees/fees.jsc")), Map.of(), Map.of(),
                Map.of("@acme/fees/fees.jsc", fees));
        assertTrue(fromPackage.ok(), fromPackage.diagnostics().toString());
        var uses = fromPackage.generated().get(0).libraries();
        assertEquals("pkg:@acme/fees/fees.jsc", uses.get(0).name());
        assertTrue(uses.get(0).external());
        // The same library in the project's sources (its own package directory): the same script, not pinned.
        var local = Toolchain.build(Map.of("app/Shop.jsc", shop.formatted("../acme/fees.jsc"), "acme/fees.jsc", fees), Map.of());
        assertTrue(local.ok(), local.diagnostics().toString());
        assertEquals(local.contracts().get(0).scriptHash(), fromPackage.contracts().get(0).scriptHash());
        assertFalse(local.generated().stream().filter(g -> g.jscFile().equals("app/Shop.jsc")).findFirst().orElseThrow()
                .libraries().get(0).external());
    }

    /**
     * Review r2 (#82 C7, F2, S2; design P-b, Codex's JavaOnlyProbe): a hand-written Java contract that imports a package's
     * Java library reports it, with the hash of its source closure, so joust.lock pins it; changing the library's body
     * changes that hash, however the contract names it (r3 C16).
     */
    @Test
    void aJavaContractsPackageLibrary_isReportedForTheLock_andNothingElseIsOnThePool() throws Exception {
        String lib = "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class L {\n    public static BigInteger f() { return BigInteger.ONE; }\n}\n";
        String counter = Files.readString(Path.of("../js/create-joust/template/contracts/app/Counter.java"));
        String uses = counter.replace("import java.math.BigInteger;", "import java.math.BigInteger;\nimport lib.L;")
                .replace("return new State(BigInteger.ZERO, ctx.caller());", "return new State(L.f(), ctx.caller());");
        assertNotEquals(counter, uses);
        var one = Toolchain.build(Map.of("app/Counter.java", uses), Map.of(), Map.of("lib/L.java", lib));
        assertTrue(one.ok(), one.diagnostics().toString());
        assertEquals(1, one.libraries().size(), one.libraries().toString());
        var use = one.libraries().get(0);
        assertEquals("java:lib.L", use.name());
        assertEquals(org.julclang.state.dsl.v2.JscV2.LibraryUse.JAVA_SOURCE, use.kind());
        assertTrue(use.external());
        var two = Toolchain.build(Map.of("app/Counter.java", uses), Map.of(), Map.of("lib/L.java", lib.replace("ONE", "TWO")));
        assertTrue(two.ok(), two.diagnostics().toString());
        assertNotEquals(use.sha256(), two.libraries().get(0).sha256(), "a changed body moves the pin");
        assertNotEquals(one.contracts().get(0).scriptHash(), two.contracts().get(0).scriptHash());
        // Review r3 (Codex C16): what the contract reaches is read from javac, not from the text: an import on the package's
        // line and an import with a comment inside it are found and pinned.
        for (String spelling : List.of(
                uses.replace("package app;\n", "package app; ").replace("import lib.L;", ""),
                uses.replace("import lib.L;", "import /* library */ lib.L;"))) {
            assertNotEquals(counter, spelling);
            var r = Toolchain.build(Map.of("app/Counter.java", spelling.contains("package app; ") ? spelling.replace("package app; ",
                    "package app; import lib.L;\n") : spelling), Map.of(), Map.of("lib/L.java", lib));
            assertTrue(r.ok(), r.diagnostics().toString());
            assertEquals(List.of(use), r.libraries());
        }
        // A qualified name without an import reaches the library too: javac finds it (JuLC then asks for an import).
        var qualified = Toolchain.build(Map.of("app/Counter.java", counter.replace("return new State(BigInteger.ZERO, ctx.caller());",
                "return new State(lib.L.f(), ctx.caller());")), Map.of(), Map.of("lib/L.java", lib));
        assertFalse(qualified.ok());
        assertTrue(qualified.diagnostics().stream().anyMatch(d -> d.message().contains("JULC0123")), qualified.diagnostics().toString());
        assertTrue(qualified.diagnostics().stream().noneMatch(d -> d.message().contains("does not exist")), qualified.diagnostics().toString());
        // A contract that imports nothing of a package reports nothing.
        var plain = Toolchain.build(Map.of("app/Counter.java", counter), Map.of(), Map.of("lib/L.java", lib));
        assertTrue(plain.ok(), plain.diagnostics().toString());
        assertEquals(List.of(), plain.libraries());
    }

    /** Review r2 (#82 S6): --accept-abi accepts the ABI manifest only; only --accept-lock writes joust.lock. */
    @Test
    void acceptingTheAbi_neverWritesTheLock(@org.junit.jupiter.api.io.TempDir Path dir) throws Exception {
        Path src = Files.createDirectories(dir.resolve("jsc/app"));
        Files.writeString(src.resolve("C.jsc"), "jsc 2\npackage app\nimport { f } from \"java:lib.L\"\ncontract C {\n  state { n: Int }\n"
                + "  init() { return { n: f() } }\n}\n");
        Path libs = Files.createDirectories(dir.resolve("libs/lib"));
        Files.writeString(libs.resolve("L.java"), "package lib;\nimport java.math.BigInteger;\nimport org.julclang.stdlib.annotation.OnchainLibrary;\n"
                + "@OnchainLibrary\npublic class L {\n    public static BigInteger f() { return BigInteger.ONE; }\n}\n");
        Path lock = dir.resolve("joust.lock");
        String[] base = {dir.resolve("jsc").toString(), dir.resolve("out").toString(), "--java-libraries", dir.resolve("libs").toString(), "--lock", lock.toString()};
        var old = System.err;
        System.setErr(new java.io.PrintStream(new java.io.ByteArrayOutputStream()));
        try {
            String[] acceptAbi = java.util.Arrays.copyOf(base, base.length + 1);
            acceptAbi[base.length] = "--accept-abi";
            assertEquals(1, org.julclang.state.dsl.JscMain.run(acceptAbi), "the library is not pinned");
            assertFalse(Files.exists(lock), "--accept-abi wrote no lock");
            String[] acceptLock = java.util.Arrays.copyOf(acceptAbi, acceptAbi.length + 1);
            acceptLock[acceptAbi.length] = "--accept-lock";
            assertEquals(0, org.julclang.state.dsl.JscMain.run(acceptLock));
            assertTrue(Files.readString(lock).contains("java:lib.L"));
        } finally {
            System.setErr(old);
        }
    }

    @Test
    void errorsKeepTheirJscLineAndColumn() {
        var r = Toolchain.build(Map.of("t/Bad.jsc", """
                jsc 2
                package t
                contract Bad {
                  state { n: Int }
                  init() { return { n: 0 } }
                  action a() { let x = 1
                    return state }
                }
                """), Map.of());
        assertFalse(r.ok());
        var d = r.diagnostics().stream().filter(x -> x.severity() == Toolchain.Severity.ERROR).findFirst().orElseThrow();
        assertEquals("t/Bad.jsc", d.file());
        assertEquals(6, d.line());
        assertEquals(16, d.col());
        assertTrue(d.message().contains("immutable"), d.message());
        assertTrue(d.code() != null && d.code().matches("JSC\\d{4}"), "a stable code on the npm path too (D12, J5): " + d.code());
    }

    /** D12, F13: warnings reach the npm path with their line and column. */
    @Test
    void warningsKeepTheirJscLineAndColumn() {
        var r = Toolchain.build(Map.of("t/Warn.jsc", """
                jsc 2
                package t
                contract Warn {
                  state { owner: Key, last: Int }
                  init() { return { owner: caller, last: 0 } }
                  action seen() { return { ...state, last: validFrom } }
                  destroy close() only owner { pay(owner, max(0, balance - (validTo - last))) }
                }
                """), Map.of());
        assertTrue(r.ok(), r.diagnostics().toString());
        var w = r.diagnostics().stream().filter(x -> x.severity() == Toolchain.Severity.WARNING && "jsc".equals(x.source())).toList();
        assertEquals(2, w.size(), w.toString());
        assertEquals(List.of("t/Warn.jsc", 6L, 44L), List.of(w.get(0).file(), w.get(0).line(), w.get(0).col()));
        assertTrue(w.get(0).message().contains("validity window in last"), w.get(0).message());
        assertEquals(List.of(7L, 43L), List.of(w.get(1).line(), w.get(1).col()));
        assertTrue(w.get(1).message().contains("payout"), w.get(1).message());
        assertTrue(w.stream().allMatch(x -> x.code() != null && x.code().startsWith("JSC9")), w.toString());
    }

    /** Security S3 on the npm path: a file name that would rewrite the contract is refused at 1:1, nothing is built. */
    @Test
    void unsafeFileNamesAreRefused() {
        String gate = "jsc 2\npackage t\ncontract Gate {\n  state { owner: Key }\n  init() { return { owner: caller } }\n"
                + "  action claim(k: Key) {\n    require(false, \"nobody may claim\")\n    return { ...state, owner: k }\n  }\n}\n";
        String name = "t/x\"); if (true) return new State(k); Require.that(true, \".jsc";
        var r = Toolchain.build(Map.of(name, gate), Map.of());
        assertFalse(r.ok());
        assertTrue(r.contracts().isEmpty());
        var d = r.diagnostics().get(0);
        assertEquals(List.of(name, 1L, 1L), List.of(d.file(), d.line(), d.col()));
        assertTrue(d.message().contains("may contain only ASCII letters and digits"), d.message());
        assertTrue(Toolchain.build(Map.of("t/Gate.jsc", gate), Map.of()).ok());
    }

    /**
     * Review F16: a v1 file keeps building under any name (D16: deployed v1 scripts stay rebuildable); the name is
     * escaped, so the attack name of S3 changes nothing: claim is still refused by the transition program.
     */
    @Test
    void v1FilesBuildUnderAnyName_andTheNameCannotChangeTheContract() {
        String v1 = "package t\ncontract Gate {\n  state owner: Key\n  init() { owner = caller }\n"
                + "  action claim(k: Key) {\n    require false \"nobody may claim\"\n    owner = k\n  }\n}\n";
        for (String name : List.of("t/My Gate.jsc", "t/Zähler.jsc", "t/_g.jsc", "t/x\"); if (true) return new State(k); Require.that(true, \".jsc",
                "t/a\\u000a\\u0022 */.jsc")) {
            var r = Toolchain.build(Map.of(name, v1), Map.of());
            assertTrue(r.ok(), name + ": " + r.diagnostics());
            var gate = r.contracts().get(0);
            var state = com.bloxbean.cardano.client.plutus.spec.ConstrPlutusData.of(0, data(fill(0x0A)));
            assertEquals("refused", evaluate(gate.transitions().get("claim"), state, NONE, args(fill(0xEE)), NONE), name);
        }
    }

    @Test
    void pledgesTransitionsMatchTheJvm() throws Exception {
        var pledges = build("Pledges.jsc").contracts().stream().filter(c -> c.name().equals("Pledges")).findFirst().orElseThrow();
        var t = pledges.transitions();
        byte[] owner = fill(0x0A), ann = fill(0xA1), ben = fill(0xB1);
        JulcList<Pledges.Share> shares = JulcList.of(new Pledges.Share(ann, BigInteger.valueOf(6_000)), new Pledges.Share(ben, BigInteger.valueOf(4_000)));
        BigInteger t0 = BigInteger.valueOf(1_800_000_000_000L);
        int checked = 0, refusals = 0;
        for (BigInteger deadline : List.of(t0.subtract(BigInteger.ONE), t0.add(BigInteger.valueOf(600_000)))) {
            for (BigInteger raised : List.of(BigInteger.ZERO, BigInteger.valueOf(20_000_000))) {
                for (Optional<byte[]> last : List.of(Optional.<byte[]>empty(), Optional.of(ben))) {
                    var s = new Pledges.State(owner, BigInteger.valueOf(10_000_000), deadline, raised, shares, new byte[]{1, 2}, last);
                    for (BigInteger received : List.of(BigInteger.valueOf(999_999), BigInteger.valueOf(3_000_000))) {
                        var ctx = new Ctx(ann, received, BigInteger.valueOf(25_000_003), t0, t0.add(BigInteger.valueOf(120_000)));
                        String want = expect(() -> result(Pledges.pledge(s, ctx), null));
                        assertEquals(want, evaluate(t.get("pledge"), data(s), NONE, args(), data(ctx)), "pledge " + deadline + " " + received);
                        checked++;
                        if (want.equals("refused")) refusals++;
                        String d = expect(() -> result(null, Pledges.distribute(s, ctx)));
                        assertEquals(d, evaluate(t.get("distribute"), data(s), NONE, args(), data(ctx)), "distribute " + deadline + " " + raised);
                        checked++;
                        if (d.equals("refused")) refusals++;
                    }
                    // An Option in the redeemer, present (valid or short key) or absent.
                    for (Optional<byte[]> who : List.of(Optional.of(ben), Optional.<byte[]>of(new byte[27]), Optional.<byte[]>empty())) {
                        String want = expect(() -> result(Pledges.setBacker(s, who), null));
                        assertEquals(want, evaluate(t.get("setBacker"), data(s), NONE, args(who), NONE), "setBacker");
                        checked++;
                        if (want.equals("refused")) refusals++;
                    }
                    // Records with bytes compared by value: a distinct allocation of an equal share.
                    for (var from : List.of(new Pledges.Share(ann.clone(), BigInteger.valueOf(6_000)), new Pledges.Share(ann.clone(), BigInteger.ONE))) {
                        byte[] to = fill(0xC1);
                        String want = expect(() -> result(Pledges.reassign(s, from, to), null));
                        assertEquals(want, evaluate(t.get("reassign"), data(s), NONE, args(from, to), NONE), "reassign");
                        checked++;
                        if (want.equals("refused")) refusals++;
                    }
                }
            }
        }
        assertTrue(refusals > 0 && refusals < checked, "accepted and refused calls");
        System.out.println("[jsc2 S2] " + checked + " Pledges transitions identical to the JVM (" + refusals + " refusals)");
    }

    static byte[] fill(int b) {
        byte[] x = new byte[28];
        java.util.Arrays.fill(x, (byte) b);
        return x;
    }
}
