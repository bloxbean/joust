package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * ADR-0021 r8 (M5c): the certifier's descriptions of {@code java:} libraries as JSON and back (the language server's
 * library signature cache: javac's attribution runs once per set of on-chain sources, keyed by the SHA-256 of the bytes
 * read, and the analyses take its descriptions). A fixed key order: the same descriptions give the same bytes.
 */
public final class JavaLibrariesJson {

    private JavaLibrariesJson() {}

    public static String write(Map<String, JavaLibraries.Library> libraries) {
        var o = new StringBuilder("{\"format\":1,\"libraries\":[");
        boolean first = true;
        for (var e : new TreeMap<>(libraries).entrySet()) {
            if (!first) o.append(',');
            first = false;
            library(o, e.getValue());
        }
        return o.append("]}").toString();
    }

    private static void library(StringBuilder o, JavaLibraries.Library l) {
        o.append("{\"fqcn\":");
        str(o, l.fqcn());
        o.append(",\"sourceSha256\":");
        str(o, l.sourceSha256());
        o.append(",\"fns\":[");
        for (int k = 0; k < l.fns().size(); k++) {
            JavaLibraries.Fn f = l.fns().get(k);
            if (k > 0) o.append(',');
            o.append("{\"name\":");
            str(o, f.name());
            o.append(",\"paramNames\":");
            strings(o, f.paramNames());
            o.append(",\"params\":[");
            for (int t = 0; t < f.params().size(); t++) {
                if (t > 0) o.append(',');
                type(o, f.params().get(t));
            }
            o.append("],\"result\":");
            type(o, f.result());
            o.append(",\"refusal\":");
            str(o, f.refusal());
            o.append(",\"file\":");
            str(o, f.file());
            o.append(",\"line\":").append(f.line()).append(",\"col\":").append(f.col()).append('}');
        }
        o.append("],\"records\":[");
        for (int k = 0; k < l.records().size(); k++) {
            JavaLibraries.Rec r = l.records().get(k);
            if (k > 0) o.append(',');
            o.append("{\"binaryName\":");
            str(o, r.binaryName());
            o.append(",\"simpleName\":");
            str(o, r.simpleName());
            o.append(",\"canonicalOwner\":");
            str(o, r.canonicalOwner());
            o.append(",\"importClass\":");
            str(o, r.importClass());
            o.append(",\"sourceName\":");
            str(o, r.sourceName());
            o.append(",\"fieldNames\":");
            strings(o, r.fieldNames());
            o.append(",\"fieldTypes\":[");
            for (int t = 0; t < r.fieldTypes().size(); t++) {
                if (t > 0) o.append(',');
                type(o, r.fieldTypes().get(t));
            }
            o.append("],\"verified\":").append(r.verified()).append(",\"opaqueWhy\":");
            str(o, r.opaqueWhy());
            o.append('}');
        }
        o.append("],\"problems\":");
        strings(o, l.problems());
        o.append(",\"certifiedClasses\":");
        strings(o, l.certifiedClasses());
        o.append(",\"messages\":[");
        for (int k = 0; k < l.messages().size(); k++) {
            JavaLibraries.Message m = l.messages().get(k);
            if (k > 0) o.append(',');
            o.append("{\"text\":");
            str(o, m.text());
            o.append(",\"kind\":");
            str(o, m.kind());
            o.append(",\"file\":");
            str(o, m.file());
            o.append(",\"line\":").append(m.line()).append(",\"in\":");
            str(o, m.in());
            o.append('}');
        }
        o.append("],\"sources\":");
        strings(o, l.sources());
        o.append(",\"classes\":");
        strings(o, l.classes());
        o.append('}');
    }

    private static void type(StringBuilder o, JavaLibraries.JType t) {
        if (t == null) {
            o.append("null");
            return;
        }
        o.append("{\"kind\":");
        str(o, t.kind());
        o.append(",\"elem\":");
        type(o, t.elem());
        o.append(",\"record\":");
        str(o, t.record());
        o.append(",\"why\":");
        str(o, t.why());
        o.append('}');
    }

    private static void strings(StringBuilder o, List<String> xs) {
        o.append('[');
        for (int k = 0; k < xs.size(); k++) {
            if (k > 0) o.append(',');
            str(o, xs.get(k));
        }
        o.append(']');
    }

    private static void str(StringBuilder o, String s) {
        if (s == null) o.append("null");
        else JscLangJson.str(o, s);
    }

    /** The descriptions of {@link #write}'s JSON, by class name. */
    @SuppressWarnings("unchecked")
    public static Map<String, JavaLibraries.Library> read(String json) {
        var root = (Map<String, Object>) AbiCheck.Json.parseStrict(json);
        if (!Integer.valueOf(1).equals(num(root.get("format")))) throw new IllegalArgumentException("not format 1 java: library descriptions");
        var out = new LinkedHashMap<String, JavaLibraries.Library>();
        for (Object o : (List<Object>) root.get("libraries")) {
            var l = (Map<String, Object>) o;
            var fns = new ArrayList<JavaLibraries.Fn>();
            for (Object fo : (List<Object>) l.get("fns")) {
                var f = (Map<String, Object>) fo;
                var params = new ArrayList<JavaLibraries.JType>();
                for (Object t : (List<Object>) f.get("params")) params.add(type(t));
                fns.add(new JavaLibraries.Fn((String) f.get("name"), strings(f.get("paramNames")), List.copyOf(params), type(f.get("result")),
                        (String) f.get("refusal"), (String) f.get("file"), num(f.get("line")), num(f.get("col"))));
            }
            var recs = new ArrayList<JavaLibraries.Rec>();
            for (Object ro : (List<Object>) l.get("records")) {
                var r = (Map<String, Object>) ro;
                var types = new ArrayList<JavaLibraries.JType>();
                for (Object t : (List<Object>) r.get("fieldTypes")) types.add(type(t));
                recs.add(new JavaLibraries.Rec((String) r.get("binaryName"), (String) r.get("simpleName"), (String) r.get("canonicalOwner"),
                        (String) r.get("importClass"), (String) r.get("sourceName"), strings(r.get("fieldNames")), List.copyOf(types),
                        Boolean.TRUE.equals(r.get("verified")), (String) r.get("opaqueWhy")));
            }
            var msgs = new ArrayList<JavaLibraries.Message>();
            for (Object mo : (List<Object>) l.get("messages")) {
                var m = (Map<String, Object>) mo;
                msgs.add(new JavaLibraries.Message((String) m.get("text"), (String) m.get("kind"), (String) m.get("file"), num(m.get("line")),
                        (String) m.get("in")));
            }
            var lib = new JavaLibraries.Library((String) l.get("fqcn"), (String) l.get("sourceSha256"), List.copyOf(fns), List.copyOf(recs),
                    strings(l.get("problems")), strings(l.get("certifiedClasses")), List.copyOf(msgs), strings(l.get("sources")),
                    strings(l.get("classes")));
            out.put(lib.fqcn(), lib);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static JavaLibraries.JType type(Object o) {
        if (o == null) return null;
        var t = (Map<String, Object>) o;
        return new JavaLibraries.JType((String) t.get("kind"), type(t.get("elem")), (String) t.get("record"), (String) t.get("why"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object o) {
        var out = new ArrayList<String>();
        for (Object x : (List<Object>) o) out.add((String) x);
        return List.copyOf(out);
    }

    private static int num(Object o) {
        return o == null ? 0 : ((Number) o).intValue();
    }
}
