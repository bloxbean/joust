package org.julclang.state.toolchain;

import org.julclang.compiler.LibrarySourceResolver;
import org.julclang.compiler.frontend.javac.JavacFrontend;
import org.julclang.state.dsl.v2.JscV2;
import org.junit.jupiter.api.Test;

import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D8 (J7): every {@code std} binding row names an exact Java signature, and JuLC's scoped javac attribution of
 * the on-chain sources on this class path (the pinned julc-stdlib and JOUST's API, as the npm toolchain sees them) must
 * declare exactly that method. The stdlib methods the audited adapters call are checked too. A JuLC upgrade that
 * renames or retypes one fails here, never in a user's contract.
 */
class JscV2BindingTest {

    /** What BytesAdapter calls: narrowing happens only there, after its domain check. */
    static final List<String> ADAPTER_CALLEES = List.of(
            "org.julclang.stdlib.lib.ByteStringLib.slice(byte[],long,long):byte[]",
            "org.julclang.stdlib.lib.ByteStringLib.at(byte[],long):long");

    @Test
    void everyBindingMatchesTheAttributedSource() {
        var pool = new ArrayList<JavacFrontend.Unit>();
        LibrarySourceResolver.scanClasspathSources(Toolchain.class.getClassLoader(), p -> {})
                .values().forEach(l -> pool.add(JavacFrontend.Unit.jar(l.source())));
        var wanted = new ArrayList<String>(JscV2.bindingSignatures());
        wanted.addAll(ADAPTER_CALLEES);
        List<String> missing = JavacFrontend.shared().attribute(List.of(JavacFrontend.Unit.of("package probe; class Probe {}")), pool, a -> {
            var out = new ArrayList<String>();
            for (String sig : wanted) {
                String owner = sig.substring(0, sig.lastIndexOf('.', sig.indexOf('(')));
                String method = sig.substring(owner.length() + 1, sig.indexOf('('));
                TypeElement type = a.elements().getTypeElement(owner);
                if (type == null) {
                    out.add(sig + "  (no class " + owner + ")");
                    continue;
                }
                var found = new TreeSet<String>();
                for (var e : type.getEnclosedElements()) {
                    if (e instanceof ExecutableElement m && m.getSimpleName().contentEquals(method) && m.getModifiers().contains(Modifier.STATIC)
                            && m.getModifiers().contains(Modifier.PUBLIC)) {
                        found.add(owner + "." + method + "(" + m.getParameters().stream().map(p -> p.asType().toString())
                                .collect(Collectors.joining(",")) + "):" + m.getReturnType());
                    }
                }
                if (!found.contains(sig)) out.add(sig + "  (declared: " + found + ")");
            }
            return out;
        });
        assertEquals(List.of(), missing, "std bindings whose Java signature changed");
        System.out.println("[D8] " + wanted.size() + " binding signatures match the attributed sources");
    }
}
