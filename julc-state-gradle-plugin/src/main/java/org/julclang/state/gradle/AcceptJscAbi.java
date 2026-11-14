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

    /**
     * The acceptance argument: the bare {@code --accept-abi} only when none of the three options is given; with any of
     * them, all three are required (C1 on #99: a partial set never falls back to accepting every manifest), and the
     * bound {@code --accept-abi=C@h1..h2} is checked here before anything runs.
     */
    List<String> acceptance() {
        boolean c = getContract().isPresent(), h1 = getCommittedSha256().isPresent(), h2 = getSha256().isPresent();
        var out = new ArrayList<String>();
        if (!c && !h1 && !h2) {
            out.add("--accept-abi");
            return out;
        }
        if (!c || !h1 || !h2) {
            throw new org.gradle.api.GradleException("acceptJscAbi: --contract, --committed-sha256 and --sha256 go together (from jscAbiDiff);"
                    + " nothing was accepted. Without all three, run acceptJscAbi with none of them to accept every changed manifest");
        }
        String contract = getContract().get(), committed = getCommittedSha256().get(), now = getSha256().get();
        if (!contract.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*") || !committed.matches("none|[0-9a-f]{64}") || !now.matches("[0-9a-f]{64}")) {
            throw new org.gradle.api.GradleException("acceptJscAbi: --contract is a (qualified) contract name, --committed-sha256 a SHA-256 or"
                    + " none, --sha256 a SHA-256; nothing was accepted");
        }
        out.add("--accept-abi=" + contract + "@" + committed + ".." + now);
        return out;
    }
}
