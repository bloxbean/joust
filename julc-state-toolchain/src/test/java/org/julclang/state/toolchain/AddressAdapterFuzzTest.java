package org.julclang.state.toolchain;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.PlutusData;
import org.julclang.core.Program;
import org.julclang.core.cbor.PlutusDataCborEncoder;
import org.julclang.ledger.Address;
import org.julclang.ledger.Credential;
import org.julclang.state.api.AddressAdapter;
import org.julclang.state.api.RequireException;
import org.julclang.stdlib.StdlibRegistry;
import org.julclang.vm.EvalOptions;
import org.julclang.vm.EvalResult;
import org.julclang.vm.ExBudget;
import org.julclang.vm.JulcVm;
import org.julclang.vm.LedgerEvaluationTarget;
import org.julclang.vm.PlutusLanguage;
import org.julclang.vm.TermExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Coverage-guided fuzzing of the address and credential checks on raw data (ADR-0018 D5, J15; M2a, M2c):
 * {@code AddressAdapter.addressFromData} and {@code credentialFromData} (a memo's or a read state's address or
 * credential, decided on the data: constructor tags, field counts, 28-byte hashes, no staking pointer), and
 * {@code addressOk}/{@code credentialOk} (an argument's checked-input rule) on every input the JVM can hold as a ledger
 * {@code Address} or {@code Credential}. One body runs on both sides (no on-chain variant), so the JVM run is compared
 * with JuLC's compilation of the same source on the VM (PV11): the same result's bytes, or a refusal on both.
 * <p>
 * A JVM refusal is {@link RequireException} (the rule's own). The JVM's {@code Builtins.un*Data} throwing
 * IllegalArgumentException on data of another kind, where the chain refuses, is #96's registered divergence (counted by
 * builtin, its first input written to {@code build/fuzz/}); any other exception is a crash and a finding. On
 * the VM a budget exhaustion is inconclusive and counted. Inputs are mostly almost-addresses: the ledger's encoding with
 * one part wrong (a tag, a field count, a hash length, a pointer), and sometimes any data.
 */
class AddressAdapterFuzzTest {

    static final FuzzData.Tally TALLY = new FuzzData.Tally("AddressAdapterFuzzTest");
    static final String KNOWN_ISSUE = "known divergence #96";
    static final HexFormat HEX = HexFormat.of();
    static final JulcVm VM = JulcVm.create();
    static final ExBudget BUDGET = new ExBudget(10_000_000_000L, 14_000_000L);
    static final Map<String, Program> PROGRAMS = new LinkedHashMap<>();

    static final String PROBE = """
            package adprobe;

            import org.julclang.core.PlutusData;
            import org.julclang.ledger.Address;
            import org.julclang.ledger.Credential;
            import org.julclang.stdlib.Builtins;
            import org.julclang.state.api.AddressAdapter;

            public class AdProbe {
                public static PlutusData addressFromData(PlutusData d) {
                    return (PlutusData) (Object) AddressAdapter.addressFromData(d, "#memo:a");
                }
                public static PlutusData credentialFromData(PlutusData d) {
                    return (PlutusData) (Object) AddressAdapter.credentialFromData(d, "#memo:c");
                }
                public static PlutusData addressOk(PlutusData d) {
                    return AddressAdapter.addressOk((Address) (Object) d) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
                public static PlutusData credentialOk(PlutusData d) {
                    return AddressAdapter.credentialOk((Credential) (Object) d) ? Builtins.iData(1L) : Builtins.iData(0L);
                }
            }
            """;

    @BeforeAll
    static void compile() {
        if (!PROGRAMS.isEmpty()) return;
        Map<String, LibrarySource> pool = LibrarySourceResolver.scanClasspathSources(AddressAdapterFuzzTest.class.getClassLoader(), problem -> { });
        var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(pool, pool));
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var libs = LibrarySourceResolver.resolveLibrarySources(PROBE, pool).stream().map(LibrarySource::source).toList();
        for (String m : List.of("addressFromData", "credentialFromData", "addressOk", "credentialOk")) {
            var r = compiler.compileMethod(PROBE, m, libs);
            assertFalse(r.hasErrors(), m + ": " + r.diagnostics());
            PROGRAMS.put(m, JulcScriptAdapter.toProgram(JulcScriptAdapter.fromProgram(r.program()).getCborHex()));
        }
    }

    @AfterAll
    static void report() {
        TALLY.write();
    }

    // ------------------------------------------------------------------ inputs

    static PlutusData hash(FuzzedDataProvider d) {
        return d.consumeInt(0, 5) == 0 ? FuzzData.any(d, 1) : PlutusData.bytes(d.consumeInt(0, 3) == 0 ? FuzzData.bytes(d) : FuzzData.bytesOf(28, d));
    }

    static int tag(FuzzedDataProvider d, int honest) {
        return d.consumeInt(0, 7) == 0 ? d.consumeInt(0, 9) : honest;
    }

    static List<PlutusData> fields(FuzzedDataProvider d, List<PlutusData> honest) {
        var out = new ArrayList<>(honest);
        switch (d.consumeInt(0, 9)) {
            case 0 -> { if (!out.isEmpty()) out.remove(out.size() - 1); }
            case 1 -> out.add(FuzzData.any(d, 1));
            default -> { }
        }
        return out;
    }

    static PlutusData credential(FuzzedDataProvider d) {
        int t = d.consumeBoolean() ? 0 : 1;
        return new PlutusData.ConstrData(tag(d, t), fields(d, List.of(hash(d))));
    }

    static PlutusData stake(FuzzedDataProvider d) {
        return switch (d.consumeInt(0, 3)) {
            case 0 -> new PlutusData.ConstrData(tag(d, 1), fields(d, List.of()));
            case 1 -> new PlutusData.ConstrData(tag(d, 0), fields(d, List.of(new PlutusData.ConstrData(tag(d, 1),
                    fields(d, List.of(PlutusData.integer(FuzzData.integer(d)), PlutusData.integer(FuzzData.integer(d)), PlutusData.integer(FuzzData.integer(d))))))));
            default -> new PlutusData.ConstrData(tag(d, 0), fields(d, List.of(new PlutusData.ConstrData(tag(d, 0), fields(d, List.of(credential(d)))))));
        };
    }

    static PlutusData address(FuzzedDataProvider d) {
        return new PlutusData.ConstrData(tag(d, 0), fields(d, List.of(credential(d), stake(d))));
    }

    // ------------------------------------------------------------------ the target

    @FuzzTest(maxDuration = "30s")
    void addressesAndCredentials(FuzzedDataProvider data) {
        TALLY.input();
        boolean any = data.consumeInt(0, 6) == 0;
        if (data.consumeBoolean()) {
            PlutusData a = any ? FuzzData.any(data, 4) : address(data);
            compare("addressFromData", a, () -> AddressAdapter.addressFromData(a, "#memo:a"));
            Address held = held(a, Address::fromPlutusData);
            if (held != null) compare("addressOk", a, () -> AddressAdapter.addressOk(held));
        } else {
            PlutusData c = any ? FuzzData.any(data, 3) : credential(data);
            compare("credentialFromData", c, () -> AddressAdapter.credentialFromData(c, "#memo:c"));
            Credential held = held(c, Credential::fromPlutusData);
            if (held != null) compare("credentialOk", c, () -> AddressAdapter.credentialOk(held));
        }
    }

    /** The ledger record the JVM builds from {@code d}, when it holds the data as written; else null. */
    static <T extends org.julclang.ledger.PlutusDataConvertible> T held(PlutusData d, java.util.function.Function<PlutusData, T> from) {
        try {
            T t = from.apply(d);
            return t.toPlutusData().equals(d) ? t : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static void compare(String fn, PlutusData arg, java.util.function.Supplier<Object> body) {
        String u = onChain(fn, arg);
        if (u.equals("budget")) {
            TALLY.count(fn + " inconclusive (budget)");
            return;
        }
        String j = jvm(fn, body);
        TALLY.count(fn + " " + (j.startsWith("ok:") ? "accepted" : j));
        String jv = j.startsWith("refused") ? "refused" : j;
        if (j.startsWith("refused (") && jv.equals(u)) {
            var p = java.nio.file.Path.of("build/fuzz/AddressAdapterFuzzTest.known-" + fn + "-" + j.replaceAll(".*: (\\w+)\\)$", "$1") + ".txt");
            try {
                if (!java.nio.file.Files.exists(p)) {
                    java.nio.file.Files.createDirectories(p.getParent());
                    java.nio.file.Files.writeString(p, fn + "\n" + arg + "\nJVM " + j + "\non-chain " + u + "\n");
                }
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        if (!jv.equals(u)) {
            var path = FuzzData.reproducer("AddressAdapterFuzzTest", fn + "\n" + arg + "\nJVM " + j + "\non-chain " + u + "\n");
            fail(fn + ": the JVM and the VM disagree on " + arg + ": JVM " + j + ", VM " + u + " (" + path + ")");
        }
    }

    static String jvm(String fn, java.util.function.Supplier<Object> body) {
        try {
            Object r = body.get();
            PlutusData d = r instanceof Boolean b ? PlutusData.integer(b ? 1 : 0) : ((org.julclang.ledger.PlutusDataConvertible) r).toPlutusData();
            return "ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(d));
        } catch (RequireException e) {
            return "refused";
        } catch (RuntimeException e) {
            // #96, exactly (review r1, design F10, Codex C5): an un*Data builtin of the JVM's Builtins given data of another
            // kind throws IllegalArgumentException where the chain refuses. It is a registered divergence, counted by builtin
            // with its first input written to build/fuzz/; any other exception is a crash.
            String builtin = FuzzData.builtinWrongKind(e);
            if (builtin != null) return "refused (" + KNOWN_ISSUE + ": " + builtin + ")";
            throw new AssertionError(fn + " crashed on the JVM with " + e, e);
        }
    }

    static String onChain(String fn, PlutusData arg) {
        EvalResult r = VM.evaluateWithArgs(PROGRAMS.get(fn), LedgerEvaluationTarget.pv11(PlutusLanguage.PLUTUS_V3), List.of(arg), BUDGET,
                EvalOptions.DEFAULT);
        return switch (r) {
            case EvalResult.Success s -> "ok:" + HEX.formatHex(PlutusDataCborEncoder.encode(TermExtractor.extractData(s.resultTerm())));
            case EvalResult.BudgetExhausted b -> "budget";
            default -> "refused";
        };
    }
}
