package org.julclang.state.processor;

import org.julclang.state.processor.ir.ContractIR;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/**
 * Generates {@code <Name>Validator} and {@code <Name>Client} for every {@code @StateContract} class (ADR-0001).
 * <p>
 * Runs in round 1. JuLC's annotation processor then compiles the generated {@code @MultiValidator} in round 2,
 * together with the contract class it calls (an on-chain library through @StateContract; JuLC ADR-071).
 */
public class JoustProcessor extends AbstractProcessor {

    private ContractReader reader;
    private SubsetLint lint;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.reader = new ContractReader(processingEnv);
        this.lint = new SubsetLint(processingEnv);
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Set.of(ContractReader.STATE_CONTRACT);
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Set.of("joust.traces", "joust.jsMode", "joust.transitions");
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            lint.finalRound();
            return false;
        }
        for (TypeElement annotation : annotations) {
            for (Element element : roundEnv.getElementsAnnotatedWith(annotation)) {
                if (element instanceof TypeElement type) {
                    boolean clean = lint.check(type);
                    reader.read(type).filter(ir -> clean).ifPresent(ir -> generate(ir, type));
                }
            }
        }
        return false;   // JuLC and others may also look at the same elements
    }

    private void generate(ContractIR ir, TypeElement origin) {
        var options = JoustGenerators.Options.of(processingEnv);
        JoustGenerators.Generated g;
        try {
            g = JoustGenerators.generate(ir, origin, processingEnv, options);
        } catch (RuntimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JOUST could not generate " + ir.name() + ": " + e, origin);
            return;
        }
        write(g.validatorFqcn(), g.validatorSource(), origin);
        if (g.clientSource() != null) write(g.packageName() + "." + g.clientName(), g.clientSource(), origin);
        describe(ir, g.descriptionJson(), origin);
    }

    /** {@code META-INF/joust/<package>.<Contract>.json}: the description JavaScript clients use (ADR-0013 D3). */
    private void describe(ContractIR ir, String json, TypeElement origin) {
        String name = "META-INF/joust/" + ir.packageName() + "." + ir.name() + ".json";
        try {
            var file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", name, origin);
            try (Writer w = file.openWriter()) {
                w.write(json);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JOUST could not describe " + ir.name() + ": " + e, origin);
        }
    }

    private void write(String fqcn, String source, TypeElement origin) {
        try {
            var file = processingEnv.getFiler().createSourceFile(fqcn, origin);
            try (Writer w = file.openWriter()) {
                w.write(source);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "JOUST could not write " + fqcn + ": " + e.getMessage(), origin);
        }
    }
}
