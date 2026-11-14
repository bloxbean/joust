package org.julclang.state.toolchain;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The five-way cases saved at the source level (review r1, design F8; JuLC ADR-063 H4): each {@code .plan} in
 * {@code FiveWayRegressions/} holds a program's {@code .jsc} files, params and calls ({@link PlanCodec}), and replays as
 * that program and those calls whatever version of {@link JscGen} decodes Jazzer's bytes today. A fixed five-way finding
 * is kept here; its Jazzer bytes are only corpus. {@code control-*.plan} are hand-checked controls that keep the replay
 * itself honest.
 */
class FiveWayRegressionTest {

    static final Path DIR = Path.of("src/test/resources/org/julclang/state/toolchain/FiveWayRegressions");

    @BeforeAll
    static void crypto() {
        org.julclang.stdlib.Builtins.setCryptoProvider(new org.julclang.state.runtime.CclCryptoProvider());
    }

    static Stream<Path> plans() throws IOException {
        try (var files = Files.list(DIR)) {
            List<Path> ps = files.filter(p -> p.toString().endsWith(".plan")).sorted().toList();
            assertFalse(ps.isEmpty(), "no saved five-way case in " + DIR);
            return ps.stream();
        }
    }

    /** Writes control-seed-<n>.plan from JscGen's seeded program n (JOUST_WRITE_CONTROL_PLAN=n; a control's maintenance). */
    @org.junit.jupiter.api.Test
    void writeAControlPlan() throws Exception {
        String n = System.getenv("JOUST_WRITE_CONTROL_PLAN");
        org.junit.jupiter.api.Assumptions.assumeTrue(n != null, "JOUST_WRITE_CONTROL_PLAN is not set");
        var plan = JscGen.generate(JscGen.of(new java.util.SplittableRandom(Long.parseLong(n))));
        Files.createDirectories(DIR);
        Files.writeString(DIR.resolve("control-seed-" + n + ".plan"), PlanCodec.write(plan));
    }

    @ParameterizedTest
    @MethodSource("plans")
    void savedCasesAgreeOnAllFiveLegs(Path plan) throws Exception {
        var p = PlanCodec.read(Files.readString(plan));
        assertEquals(PlanCodec.write(p), PlanCodec.write(PlanCodec.read(PlanCodec.write(p))), "the plan reads back as itself");
        FiveWayFuzzTest.run(p);
    }
}
