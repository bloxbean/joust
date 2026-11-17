package org.julclang.state.processor;

import org.julclang.state.processor.gen.ClientGenerator;
import org.julclang.state.processor.gen.DescriptionWriter;
import org.julclang.state.processor.gen.TransitionsGenerator;
import org.julclang.state.processor.gen.ValidatorGenerator;
import org.julclang.state.processor.ir.ContractIR;

import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.TypeElement;
import java.util.Optional;

/**
 * JOUST's code generation for one {@code @StateContract} type, as one entry point for every host: the annotation
 * processor (Gradle) and the toolchain (ADR-0015 D2: JuLC's embedded javac in Node). Both run exactly this, so their
 * outputs can only differ through their inputs (I1).
 */
public final class JoustGenerators {

    private JoustGenerators() {
    }

    /** What to generate. */
    public record Options(boolean traces, boolean javaClient, boolean transitions) {
        public static Options of(ProcessingEnvironment env) {
            return new Options(Boolean.parseBoolean(env.getOptions().getOrDefault("joust.traces", "false")),
                    !Boolean.parseBoolean(env.getOptions().getOrDefault("joust.jsMode", "false")),
                    Boolean.parseBoolean(env.getOptions().getOrDefault("joust.transitions", "false")));
        }
    }

    /** The generated sources of one contract. {@code clientSource} and {@code transitions} may be null. */
    public record Generated(String packageName, String contractName, String validatorName, String validatorSource,
                            String clientName, String clientSource, String descriptionJson,
                            TransitionsGenerator.Transitions transitions) {
        public String validatorFqcn() {
            return packageName + "." + validatorName;
        }
    }

    /**
     * Reads {@code type} and generates its sources; empty when the reader reported errors (through the environment's
     * messager). Descriptions are generated here; a host that cannot produce one reports the exception.
     */
    public static Optional<Generated> generate(ProcessingEnvironment env, TypeElement type, Options options) {
        return new ContractReader(env).read(type).map(ir -> generate(ir, type, env, options));
    }

    static Generated generate(ContractIR ir, TypeElement type, ProcessingEnvironment env, Options options) {
        return new Generated(ir.packageName(), ir.name(), ir.validatorName(),
                ValidatorGenerator.generate(ir, options.traces()),
                ir.clientName(), options.javaClient() ? ClientGenerator.generate(ir) : null,
                DescriptionWriter.generate(ir, type, env),
                options.transitions() ? TransitionsGenerator.generate(ir, type, env) : null);
    }
}
