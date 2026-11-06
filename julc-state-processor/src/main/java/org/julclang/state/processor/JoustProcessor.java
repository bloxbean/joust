package org.julclang.state.processor;

import org.julclang.state.processor.gen.ClientGenerator;
import org.julclang.state.processor.gen.ValidatorGenerator;
import org.julclang.state.processor.ir.ContractIR;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;

/**
 * Generates {@code <Name>Validator} and {@code <Name>Client} for every {@code @StateContract} class (ADR-0001).
 * <p>
 * Runs in round 1. JuLC's annotation processor then compiles the generated {@code @MultiValidator} in round 2,
 * together with the contract class it calls (which must be {@code @OnchainLibrary}).
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
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latestSupported();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
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
        write(ir.packageName() + "." + ir.validatorName(), ValidatorGenerator.generate(ir), origin);
        write(ir.packageName() + "." + ir.clientName(), ClientGenerator.generate(ir), origin);
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
