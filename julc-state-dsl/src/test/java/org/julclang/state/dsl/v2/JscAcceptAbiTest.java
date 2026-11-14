package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ADR-0021 D7a (ADR-0018 D12 as amended), Gradle's side: {@code JscMain --abi-diff} (jscAbiDiff) and the bound
 * {@code --accept-abi=<Contract>@<committed>..<new>} (acceptJscAbi --contract --committed-sha256 --sha256): one contract,
 * refused when the committed manifest or the source changed since the diff.
 */
class JscAcceptAbiTest {

    record Run(int code, String out, String err) {}

    static Run run(String... args) throws Exception {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        PrintStream o = System.out, e = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
        try {
            return new Run(JscMain.run(args), out.toString(), err.toString());
        } finally {
            System.setOut(o);
            System.setErr(e);
        }
    }

    static String contract(String name, String fields) {
        return "jsc 2\npackage app\ncontract " + name + " {\n  state { owner: Key" + fields + " }\n  init() { return { owner: caller"
                + fields.replace(": Int", ": 0") + " } }\n  destroy close() only owner { pay(owner, balance) }\n}\n";
    }

    static String json(Path p, String key) throws Exception {
        var m = java.util.regex.Pattern.compile("\"" + key + "\":\\{\"sha256\":\"([0-9a-f]{64}|none)\"").matcher(Files.readString(p));
        assertTrue(m.find(), Files.readString(p));
        return m.group(1);
    }

    @Test
    void aBoundAcceptanceTakesOneContractWhileBothManifestsAreTheReviewedOnes(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("jsc"), out = dir.resolve("out"), app = src.resolve("app");
        Files.createDirectories(app);
        Files.writeString(app.resolve("A.jsc"), contract("A", ", n: Int"));
        Files.writeString(app.resolve("B.jsc"), contract("B", ", m: Int"));
        Path diff = dir.resolve("diff.json");
        Run d = run("--abi-diff", src.toString(), "A", diff.toString());
        assertEquals(0, d.code(), d.err());
        assertEquals("none", json(diff, "committed"));
        String now = json(diff, "now");
        assertTrue(d.out().contains("acceptJscAbi --contract app.A --committed-sha256 none --sha256 " + now), d.out());
        // A only: B's missing manifest still fails the build and is not written.
        Run a = run(src.toString(), out.toString(), "--accept-abi=A@none.." + now);
        assertEquals(1, a.code());
        assertTrue(Files.exists(app.resolve("A.abi.json")));
        assertFalse(Files.exists(app.resolve("B.abi.json")));
        assertTrue(a.err().contains("error[JSC0003]"), a.err());
        // A changes; the source changes again after the diff: refused.
        Files.writeString(app.resolve("A.jsc"), contract("A", ", n: Int, k: Int"));
        assertEquals(0, run("--abi-diff", src.toString(), "A", diff.toString()).code());
        String h1 = json(diff, "committed"), h2 = json(diff, "now");
        Files.writeString(app.resolve("A.jsc"), contract("A", ", n: Int, j: Int, k: Int"));
        String kept = Files.readString(app.resolve("A.abi.json"));
        Run stale = run(src.toString(), out.toString(), "--accept-abi=A@" + h1 + ".." + h2);
        assertTrue(stale.err().contains("not accepted: the manifest the build computes now"), stale.err());
        assertEquals(kept, Files.readString(app.resolve("A.abi.json")));
        // The committed manifest changes under the review (a git pull): refused.
        assertEquals(0, run("--abi-diff", src.toString(), "A", diff.toString()).code());
        h1 = json(diff, "committed");
        h2 = json(diff, "now");
        Files.writeString(app.resolve("A.abi.json"), kept + "\n");
        Run pulled = run(src.toString(), out.toString(), "--accept-abi=A@" + h1 + ".." + h2);
        assertTrue(pulled.err().contains("not accepted: the committed manifest"), pulled.err());
        // A malformed acceptance is a usage error; an unknown contract accepts nothing.
        assertEquals(2, run(src.toString(), out.toString(), "--accept-abi=A@x..y").code());
        assertTrue(run(src.toString(), out.toString(), "--accept-abi=Nope@none.." + h2).err().contains("no .jsc 2 contract Nope"));
        assertEquals(1, run("--abi-diff", src.toString(), "Nope").code());
    }

    /** S5, F5 on #99: two contracts A in two packages: a simple name is ambiguous, refused; the qualified name picks one. */
    @Test
    void aContractIsItsQualifiedNameAndAnAmbiguousSimpleNameIsRefused(@TempDir Path dir) throws Exception {
        Path src = dir.resolve("jsc"), out = dir.resolve("out");
        Files.createDirectories(src.resolve("app"));
        Files.createDirectories(src.resolve("other"));
        Files.writeString(src.resolve("app/A.jsc"), contract("A", ", n: Int"));
        Files.writeString(src.resolve("other/A.jsc"), contract("A", ", n: Int").replace("package app", "package other"));
        Run amb = run("--abi-diff", src.toString(), "A");
        assertEquals(1, amb.code());
        assertTrue(amb.err().contains("ambiguous: app.A (app/A.jsc), other.A (other/A.jsc)"), amb.err());
        Path diff = dir.resolve("d.json");
        assertEquals(0, run("--abi-diff", src.toString(), "other.A", diff.toString()).code());
        assertTrue(Files.readString(diff).contains("\"contract\":\"other.A\",\"path\":\"other/A.abi.json\""), Files.readString(diff));
        String now = json(diff, "now");
        Run ambiguous = run(src.toString(), out.toString(), "--accept-abi=A@none.." + now);
        assertEquals(1, ambiguous.code());
        assertTrue(ambiguous.err().contains("ambiguous"), ambiguous.err());
        assertFalse(Files.exists(src.resolve("app/A.abi.json")) || Files.exists(src.resolve("other/A.abi.json")), "nothing written");
        run(src.toString(), out.toString(), "--accept-abi=other.A@none.." + now);
        assertTrue(Files.exists(src.resolve("other/A.abi.json")));
        assertFalse(Files.exists(src.resolve("app/A.abi.json")), "the other A is not accepted");
    }
}
