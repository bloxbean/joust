package org.julclang.state.dsl.v2;

import java.util.List;
import java.util.Map;

/**
 * The Java (JuLC) libraries a {@code .jsc} build may import with {@code java:} (ADR-0018 D9, M4b), as their certifier
 * describes them: {@code import { isAllowed } from "java:com.acme.compliance.AllowList"}. The front end never reads Java:
 * a certifier with javac's attribution of the library's on-chain sources (julc-state-toolchain's
 * {@code JavaLibraryCertifier}) gives each public static method's exact signature mapped to {@code .jsc} types, the
 * records those signatures reach, and whether the library's bodies passed certification. No description, no import.
 */
public interface JavaLibraries {

    /** The library {@code fqcn} (an {@code @OnchainLibrary} class), or null when no on-chain sources of it are on the library path. */
    Library describe(String fqcn);

    /** No Java libraries: every {@code java:} import is refused. */
    JavaLibraries NONE = fqcn -> null;

    /** A fixed set, by class name. */
    static JavaLibraries of(Map<String, Library> libraries) {
        return libraries::get;
    }

    /**
     * What certifies library sources: julc-state-toolchain's certifier (javac attribution and certification need JuLC's
     * compiler, which this module does not depend on), found through {@link java.util.ServiceLoader}.
     */
    interface Certifier {
        /** The libraries of {@code sources} (on-chain sources by path), each described and certified. */
        JavaLibraries certify(Map<String, String> sources);
    }

    /**
     * The libraries of {@code sources}, certified by the {@link Certifier} on the class path; {@link #NONE} when there are
     * no sources. Fails when sources are given and no certifier is present (the build would otherwise drop them).
     */
    static JavaLibraries certify(Map<String, String> sources) {
        if (sources.isEmpty()) return NONE;
        for (Certifier c : java.util.ServiceLoader.load(Certifier.class, JavaLibraries.class.getClassLoader())) return c.certify(sources);
        throw new IllegalStateException("java: library sources were given, but no certifier is on the class path"
                + " (julc-state-toolchain provides it)");
    }

    /**
     * A Java type as {@code .jsc} sees it. {@code kind}: {@code Int} ({@code BigInteger}), {@code Bool} ({@code boolean}),
     * {@code Bytes} ({@code byte[]}: a Java signature cannot say Key or Hash), {@code List} ({@code JulcList<T>}),
     * {@code Option} ({@code Optional<T>}), {@code Record} (a record of the library's sources, by {@code record}: its
     * binary name), or {@code Unmappable} with the reason ({@code why}): fixed-width numbers, {@code PlutusData},
     * generics, maps and values, types without a {@code .jsc} counterpart.
     */
    record JType(String kind, JType elem, String record, String why) {
        public static final JType INT = new JType("Int", null, null, null);
        public static final JType BOOL = new JType("Bool", null, null, null);
        public static final JType BYTES = new JType("Bytes", null, null, null);

        public static JType list(JType e) {
            return new JType("List", e, null, null);
        }

        public static JType option(JType e) {
            return new JType("Option", e, null, null);
        }

        public static JType record(String binaryName) {
            return new JType("Record", null, binaryName, null);
        }

        public static JType unmappable(String why) {
            return new JType("Unmappable", null, null, why);
        }

        /** The first reason this type does not map, or null. */
        public String unmappable() {
            if (kind.equals("Unmappable")) return why;
            return elem == null ? null : elem.unmappable();
        }
    }

    /**
     * A public static method: its name, parameter names and types, result type. {@code refusal}: why it cannot be
     * imported (an unmappable parameter or result, an overload), or null.
     */
    record Fn(String name, List<String> paramNames, List<JType> params, JType result, String refusal) {}

    /**
     * A record the library's signatures reach. {@code binaryName} ({@code com.acme.AllowList$Entry}) identifies it;
     * {@code simpleName} is its {@code .jsc} name; {@code canonicalOwner} is what the processor's description names it by,
     * before its simple name ({@code com.acme.AllowList}, or the package of a top-level record); {@code importClass} is
     * the top-level class a generated contract imports, {@code sourceName} how it names the record then
     * ({@code AllowList.Entry}). {@code verified}: its encoding is {@code Constr 0} of its fields in order with every field
     * mapped and no constructor logic, so {@code .jsc} reads its fields (read-only); otherwise it is opaque.
     */
    record Rec(String binaryName, String simpleName, String canonicalOwner, String importClass, String sourceName,
               List<String> fieldNames, List<JType> fieldTypes, boolean verified, String opaqueWhy) {}

    /**
     * A message the library's certified bodies can trace, where it is raised (D12, J20): its text, the source file and
     * line, the method; {@code kind} is {@code require} (a {@code Require.that} literal) or {@code operation} (a D8
     * adapter's fixed message). A contract's message map lists them, so every string its script holds is explained.
     */
    record Message(String text, String kind, String file, int line, String in) {}

    /**
     * A library: its class, the SHA-256 of its on-chain sources (every source certified with it, sorted by path), its
     * functions and records, and the certification problems of its bodies (transitively; empty when certified). A library
     * with problems is refused at the import, with them.
     */
    record Library(String fqcn, String sourceSha256, List<Fn> fns, List<Rec> records, List<String> problems,
                   List<String> certifiedClasses, List<Message> messages) {
        /** A library whose bodies trace no message. */
        public Library(String fqcn, String sourceSha256, List<Fn> fns, List<Rec> records, List<String> problems,
                       List<String> certifiedClasses) {
            this(fqcn, sourceSha256, fns, records, problems, certifiedClasses, List.of());
        }

        public String simpleName() {
            return fqcn.substring(fqcn.lastIndexOf('.') + 1);
        }

        public Rec record(String binaryName) {
            for (Rec r : records) if (r.binaryName().equals(binaryName)) return r;
            return null;
        }
    }
}
