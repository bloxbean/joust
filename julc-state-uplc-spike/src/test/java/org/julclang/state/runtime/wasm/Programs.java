package org.julclang.state.runtime.wasm;

import org.julclang.clientlib.JulcScriptAdapter;
import org.julclang.compiler.CompilerOptions;
import org.julclang.compiler.CompilerTarget;
import org.julclang.compiler.JulcCompiler;
import org.julclang.compiler.LibrarySource;
import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.OptimizationConfiguration;
import org.julclang.compiler.OptimizationLevel;
import org.julclang.core.source.SourceMap;
import org.julclang.state.toolchain.Toolchain;
import org.julclang.stdlib.StdlibRegistry;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0020 D2/D8/D9: the transition programs compiled the way the toolchain compiles them (same pool, same options,
 * same discovery substitution), so the spike can (1) record the exact pool and substitution set in the manifest, (2)
 * check that its rebuild equals the toolchain's programs byte for byte, and (3) build the two non-primary sets: the
 * <b>auxiliary</b> set (a {@code Require} that traces {@code joust:require <message>}, D9) and the <b>diagnostic</b>
 * set (source maps on, which skips JuLC's UPLC optimiser, F23).
 */
final class Programs {
    private Programs() {}

    enum Set { PRIMARY, AUXILIARY, DIAGNOSTIC }

    static final String REQUIRE = "org.julclang.state.api.Require";
    static final String MERKLE_MAPS = "org.julclang.state.api.MerkleMaps";

    /** A compiled set: method → script CBOR hex, the source maps (diagnostic set), and the pool it used. */
    record Compiled(Set set, Map<String, String> programs, Map<String, org.julclang.core.Program> objects,
                    Map<String, SourceMap> sourceMaps, Map<String, String> pool, List<String> substituted,
                    Map<String, Object> options) {}

    private static Map<String, LibrarySource> classpath;

    static synchronized Map<String, LibrarySource> classpathLibraries() {
        if (classpath == null) classpath = LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader());
        return classpath;
    }

    static Map<String, Object> options(Set set) {
        var o = new LinkedHashMap<String, Object>();
        o.put("target", CompilerTarget.PLUTUS_V3_PV11.profileId());
        o.put("profile", OptimizationLevel.DEFAULT_PROFILE_ID);
        o.put("sourceMapEnabled", set == Set.DIAGNOSTIC);
        o.put("uplcOptimiser", set != Set.DIAGNOSTIC);
        o.put("traces", true);
        return o;
    }

    static Compiled compile(String example, Toolchain.Contract c, Set set) throws Exception {
        return compile(Spike.sources(example), c, set);
    }

    static Compiled compile(Map<String, String> sources, Toolchain.Contract c, Set set) throws Exception {
        Map<String, LibrarySource> project = new LinkedHashMap<>();
        for (String text : sources.values()) {
            LibrarySource l = LibrarySourceResolver.librarySource(text);
            if (l != null) project.put(l.fqcn(), l);
        }
        Map<String, LibrarySource> pool = new LinkedHashMap<>(classpathLibraries());
        pool.putAll(project);
        var options = OptimizationConfiguration.apply(new CompilerOptions().setTarget(CompilerTarget.PLUTUS_V3_PV11),
                OptimizationLevel.DEFAULT_PROFILE_ID, null);
        options.setJarLibrarySources(LibrarySourceResolver.jarSourceTexts(pool, classpathLibraries()));
        if (set == Set.DIAGNOSTIC) options.setSourceMapEnabled(true);
        var compiler = new JulcCompiler(StdlibRegistry.defaultRegistry(), options);
        var libs = new ArrayList<LibrarySource>();
        var substituted = new ArrayList<String>();
        for (LibrarySource l : LibrarySourceResolver.resolveLibrarySources(c.transitionsSource(), pool)) {
            String discovery = resource("META-INF/joust/discovery/" + l.fqcn().replace('.', '/') + ".java");
            if (discovery != null) {
                libs.add(LibrarySourceResolver.librarySource(discovery));
                substituted.add(l.fqcn());
            } else if (set == Set.AUXILIARY && l.fqcn().equals(REQUIRE)) {
                libs.add(LibrarySourceResolver.librarySource(taggedRequire(l.source())));
                substituted.add(l.fqcn());
            } else {
                libs.add(l);
            }
        }
        var poolHashes = new LinkedHashMap<String, String>();
        for (LibrarySource l : libs) poolHashes.put(l.fqcn(), sha256(l.source()));
        var programs = new LinkedHashMap<String, String>();
        var maps = new LinkedHashMap<String, SourceMap>();
        var objects = new LinkedHashMap<String, org.julclang.core.Program>();
        for (String m : c.transitions().keySet()) {
            var r = compiler.compileMethod(c.transitionsSource(), m, libs.stream().map(LibrarySource::source).toList());
            if (r.hasErrors()) throw new IllegalStateException(c.name() + "." + m + " (" + set + "): " + r.diagnostics());
            programs.put(m, JulcScriptAdapter.fromProgram(r.program()).getCborHex());
            objects.put(m, r.program());
            if (r.sourceMap() != null) maps.put(m, r.sourceMap());
        }
        return new Compiled(set, programs, objects, maps, poolHashes, substituted, options(set));
    }

    /** The on-chain {@code Require} whose refusal traces {@code joust:require <message>} (D9's auxiliary format). */
    static String taggedRequire(String onchain) {
        String a = "ContextsLib.trace(message);";
        if (!onchain.contains(a)) throw new IllegalStateException("unexpected on-chain Require source");
        return onchain.replace(a, "ContextsLib.trace(\"joust:require \" + message);");
    }

    static String resource(String path) throws Exception {
        try (var in = Toolchain.class.getClassLoader().getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
