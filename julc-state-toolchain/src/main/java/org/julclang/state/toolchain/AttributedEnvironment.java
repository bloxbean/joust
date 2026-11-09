package org.julclang.state.toolchain;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;

import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A {@link ProcessingEnvironment} over a model javac has already attributed (JuLC's {@code JavacFrontend.attribute}),
 * so JOUST's generators run outside annotation processing (ADR-0015 D2). Messages are collected with their file and
 * line; there is no {@link Filer}: the toolchain returns what is generated.
 */
final class AttributedEnvironment implements ProcessingEnvironment {

    private final Elements elements;
    private final Types types;
    private final Trees trees;
    private final Map<String, String> options;
    private final List<Toolchain.Diagnostic> diagnostics;

    AttributedEnvironment(Elements elements, Types types, Trees trees, Map<String, String> options,
                          List<Toolchain.Diagnostic> diagnostics) {
        this.elements = elements;
        this.types = types;
        this.trees = trees;
        this.options = Map.copyOf(options);
        this.diagnostics = diagnostics;
    }

    @Override public Map<String, String> getOptions() { return options; }
    @Override public Elements getElementUtils() { return elements; }
    @Override public Types getTypeUtils() { return types; }
    @Override public SourceVersion getSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public Locale getLocale() { return Locale.ROOT; }

    @Override
    public Filer getFiler() {
        throw new UnsupportedOperationException("the toolchain returns generated sources; it has no Filer");
    }

    @Override
    public Messager getMessager() {
        return new Messager() {
            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg) {
                add(kind, msg, null);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e) {
                add(kind, msg, e);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a) {
                add(kind, msg, e);
            }

            @Override
            public void printMessage(Diagnostic.Kind kind, CharSequence msg, Element e, AnnotationMirror a, AnnotationValue v) {
                add(kind, msg, e);
            }
        };
    }

    private void add(Diagnostic.Kind kind, CharSequence msg, Element e) {
        String file = null;
        long line = -1;
        if (e != null) {
            TreePath path = trees.getPath(e);
            if (path != null) {
                CompilationUnitTree unit = path.getCompilationUnit();
                file = unit.getSourceFile().getName();
                long pos = trees.getSourcePositions().getStartPosition(unit, path.getLeaf());
                if (pos >= 0) line = unit.getLineMap().getLineNumber(pos);
            }
        }
        diagnostics.add(new Toolchain.Diagnostic(Toolchain.Severity.of(kind), String.valueOf(msg), file, line, "joust"));
    }
}
