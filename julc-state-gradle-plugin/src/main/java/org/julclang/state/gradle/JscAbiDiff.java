package org.julclang.state.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.options.Option;

/**
 * {@code jscAbiDiff --contract C [--out file]} (ADR-0021 D7a): prints the committed ABI manifest of {@code C} and the one
 * the build computes now, each with its SHA-256 ({@code none} when nothing is committed); {@code --out} also writes them
 * as JSON (VS Code's Accept ABI… reads it from its own storage). Writes nothing into the project.
 */
public abstract class JscAbiDiff extends JavaExec {

    @Input
    @Option(option = "contract", description = "The contract whose manifests to compare")
    public abstract Property<String> getContract();

    @Input
    @Optional
    @Option(option = "out", description = "Also write the diff as JSON to this file")
    public abstract Property<String> getOut();
}
