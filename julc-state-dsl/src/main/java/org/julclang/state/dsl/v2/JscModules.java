package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Where a {@code .jsc} 2 build finds the {@code .jsc} libraries its files import (ADR-0018 D9, M4):
 * {@code import { fee, Line } from "./fees.jsc"}. A module is named by an id, unique in the build: a source's path
 * relative to the source root, {@code '/'}-separated ({@code app/fees.jsc}). A relative specifier ({@code ./x.jsc},
 * {@code ../lib/x.jsc}) is resolved against the importing module's directory and never leaves the root. Other
 * specifiers (an npm library package's {@code joust-lib/}, M4c) are the resolver's.
 */
public interface JscModules {

    /** A module's source: its id in the build, its bare file name, and its text. */
    record Source(String id, String fileName, String text) {}

    /** The module {@code spec} names when imported from the module {@code from} (an id, or null), or null when none. */
    Source resolve(String from, String spec);

    /** No modules: every {@code .jsc} import is refused (a file lowered on its own). */
    JscModules NONE = (from, spec) -> null;

    /** Whether {@code spec} names a {@code .jsc} module: relative ({@code ./}, {@code ../}) and ending in {@code .jsc}. */
    static boolean isRelative(String spec) {
        return (spec.startsWith("./") || spec.startsWith("../")) && spec.endsWith(".jsc");
    }

    /**
     * The id a relative {@code spec} names from the module {@code from}: its directory joined with the specifier, with
     * {@code .} and {@code ..} segments resolved; null when it would leave the root, or holds an empty segment or a
     * backslash.
     */
    static String join(String from, String spec) {
        if (!isRelative(spec) || spec.contains("\\") || spec.contains("//")) return null;
        var parts = new ArrayList<String>();
        if (from != null) {
            String f = from.replace('\\', '/');
            int slash = f.lastIndexOf('/');
            if (slash > 0) parts.addAll(List.of(f.substring(0, slash).split("/")));
        }
        for (String seg : spec.split("/")) {
            if (seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (parts.isEmpty()) return null;
                parts.removeLast();
            } else {
                if (seg.isEmpty()) return null;
                parts.add(seg);
            }
        }
        return String.join("/", parts);
    }

    /**
     * The modules of a build given as texts by path relative to the source root ({@code app/Shop.jsc}; a {@code '\\'}
     * separator is read as {@code '/'}). Relative specifiers only.
     */
    static JscModules of(Map<String, String> files) {
        var byId = new LinkedHashMap<String, String>();
        files.forEach((k, v) -> {
            if (k.endsWith(".jsc")) byId.put(k.replace('\\', '/'), v);
        });
        return (from, spec) -> {
            String id = join(from, spec);
            if (id == null || !byId.containsKey(id)) return null;
            return new Source(id, id.substring(id.lastIndexOf('/') + 1), byId.get(id));
        };
    }
}
