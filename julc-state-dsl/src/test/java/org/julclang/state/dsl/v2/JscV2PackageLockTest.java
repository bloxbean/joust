package org.julclang.state.dsl.v2;

import org.julclang.state.dsl.JscException;
import org.julclang.state.dsl.JscMain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D14, J12 (M4c): {@code .jsc} libraries of npm packages, imported by package specifier, and {@code joust.lock}
 * ({@link JscMain#checkLock}), which pins every library from outside the sources by the hash of its on-chain sources.
 */
class JscV2PackageLockTest {

    static final String FEES = """
            jsc 1
            library acme.fees

            import { round } from "./util/round.jsc"

            fn fee(amount: Int, bps: Int): Int = round(amount * bps)
            """;
    static final String ROUND = """
            jsc 1
            library acme.fees.util

            fn round(x: Int): Int = x / 10_000
            """;
    static final String SHOP = """
            jsc 1
            package app

            import { fee } from "@acme/fees/fees.jsc"

            contract Shop {
              state { owner: Key, price: Int }
              init(price: Int) { return { owner: caller, price } }
              action buy() payable {
                require(received >= price + fee(price, 100), "pay the price and the fee")
                return state
              }
            }
            """;

    static JscV2.Lowered lower(String shop, Map<String, String> packages) {
        return JscV2.lower(shop, "Shop.jsc", "app/Shop.jsc", JscModules.of(Map.of("app/Shop.jsc", shop), packages));
    }

    @Test
    void specifiers() {
        assertTrue(JscModules.isPackage("@acme/fees/fees.jsc"));
        assertTrue(JscModules.isPackage("fees/lib/x.jsc"));
        assertFalse(JscModules.isPackage("./fees.jsc"));
        assertFalse(JscModules.isPackage("@acme/fees/../x.jsc"));
        assertFalse(JscModules.isPackage("@acme/fees/x.java"));
        assertEquals("@acme/fees", JscModules.packageOf("pkg:@acme/fees/util/round.jsc"));
        assertEquals("fees", JscModules.packageOf("fees/x.jsc"));
    }

    @Test
    void aPackageLibrary_isImported_itsRelativeImportsStayInThePackage_andItIsExternal() {
        var l = lower(SHOP, Map.of("@acme/fees/fees.jsc", FEES, "@acme/fees/util/round.jsc", ROUND));
        assertTrue(l.java().contains("import acme.fees.Fees;"), l.java());
        var names = l.libraries().stream().map(JscV2.LibraryUse::name).sorted().toList();
        assertEquals(List.of("pkg:@acme/fees/fees.jsc", "pkg:@acme/fees/util/round.jsc"), names);
        assertTrue(l.libraries().stream().allMatch(JscV2.LibraryUse::external));
        // A relative import that would climb out of the package resolves to nothing.
        var e = assertThrows(JscException.class, () -> lower(SHOP, Map.of("@acme/fees/fees.jsc",
                FEES.replace("./util/round.jsc", "../other/round.jsc"), "@acme/other/round.jsc", ROUND)));
        assertEquals("JSC0712", e.code, e.getMessage());
        // No such library in the packages.
        e = assertThrows(JscException.class, () -> lower(SHOP.replace("fees.jsc\"", "nope.jsc\""), Map.of("@acme/fees/fees.jsc", FEES)));
        assertEquals("JSC0706", e.code, e.getMessage());
        assertTrue(e.getMessage().contains("npm packages"), e.getMessage());
    }

    @Test
    void theLock_pinsExternalLibraries_refusesUnpinnedAndChanged(@TempDir Path dir) throws Exception {
        Path lock = dir.resolve("joust.lock");
        var a = List.of(new JscV2.LibraryUse("jsc", "pkg:@acme/fees/fees.jsc", "aa".repeat(32)),
                new JscV2.LibraryUse("jsc", "app/fees.jsc", "cc".repeat(32)),               // the project's own: not pinned
                new JscV2.LibraryUse("java", "java:acme.allow.Limits", "bb".repeat(32)));
        assertTrue(err(() -> JscMain.checkLock(lock, a, false)).contains("JSC0731"));
        assertEquals(0, JscMain.checkLock(lock, a, true));
        String written = Files.readString(lock);
        assertTrue(written.contains("\"lock\": 1"), written);
        assertFalse(written.contains("app/fees.jsc"), written);
        assertEquals(0, JscMain.checkLock(lock, a, false));
        var changed = List.of(a.get(0), new JscV2.LibraryUse("java", "java:acme.allow.Limits", "dd".repeat(32)));
        assertTrue(err(() -> JscMain.checkLock(lock, changed, false)).contains("JSC0732"));
        // No external library and no lock: nothing to pin.
        assertEquals(0, JscMain.checkLock(dir.resolve("none.lock"), List.of(a.get(1)), false));
        // Not a version 1 lock.
        Files.writeString(lock, "{\"lock\": 2, \"libraries\": {}}");
        assertTrue(err(() -> JscMain.checkLock(lock, a, false)).contains("not a version 1 lock"));
    }

    interface Check {
        int run() throws Exception;
    }

    /** The stderr of a failing check (its errors). */
    static String err(Check c) throws Exception {
        var old = System.err;
        var buf = new ByteArrayOutputStream();
        System.setErr(new PrintStream(buf, true));
        int n;
        try {
            n = c.run();
        } finally {
            System.setErr(old);
        }
        assertTrue(n > 0, "the check passed: " + buf);
        return buf.toString();
    }
}
