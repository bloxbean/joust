package org.julclang.state.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.options.Option;

import java.util.ArrayList;
import java.util.List;

/**
 * {@code acceptJscAbi} (ADR-0018 D12; ADR-0021 D7a): writes the ABI manifests of {@code src/main/jsc}, accepting new and
 * changed ones. With {@code --contract C --committed-sha256 h1 --sha256 h2} (task options, never properties) it accepts
 * that one contract only, and only while its committed manifest is still {@code h1} ({@code none}: nothing committed)
 * and the manifest the build computes is {@code h2}: the two manifests {@code jscAbiDiff} showed.
 */
public abstract class AcceptJscAbi extends JavaExec {

    @Input
    @Optional
    @Option(option = "contract", description = "The one contract to accept (with --committed-sha256 and --sha256, from jscAbiDiff)")
    public abstract Property<String> getContract();

    @Input
    @Optional
    @Option(option = "committed-sha256", description = "The SHA-256 of the committed manifest that was reviewed (none: nothing committed)")
    public abstract Property<String> getCommittedSha256();

    @Input
    @Optional
    @Option(option = "sha256", description = "The SHA-256 of the new manifest that was reviewed")
    public abstract Property<String> getSha256();

    /** The acceptance argument: {@code --accept-abi}, or the bound {@code --accept-abi=C@h1..h2}. */
    List<String> acceptance() {
        var out = new ArrayList<String>();
        if (!getContract().isPresent()) {
            out.add("--accept-abi");
            return out;
        }
        if (!getCommittedSha256().isPresent() || !getSha256().isPresent()) {
            throw new org.gradle.api.GradleException("acceptJscAbi --contract needs --committed-sha256 and --sha256 (from jscAbiDiff)");
        }
        out.add("--accept-abi=" + getContract().get() + "@" + getCommittedSha256().get() + ".." + getSha256().get());
        return out;
    }
}
