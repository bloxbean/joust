package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** ADR-0020 D5/U6: the spike's report, written to {@code build/spike/<section>.json} with machine and commit. */
final class Report {
    private Report() {}

    static final ObjectMapper JSON = new ObjectMapper();
    static final Path DIR = Path.of("build/spike");

    /** Comparisons per contract.method and outcome class (D4: invocation counts per kind). */
    static final Map<String, Map<String, Integer>> COUNTS = new TreeMap<>();

    static {
        try {
            Files.deleteIfExists(DIR.resolve("mismatches.txt"));   // per test JVM
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static synchronized void comparison(String contract, String method, Outcome p, Outcome j) {
        COUNTS.computeIfAbsent(contract + "." + method, k -> new TreeMap<>())
                .merge(p.kind().name().toLowerCase(), 1, Integer::sum);
    }

    static synchronized void mismatches(List<String> details) {
        try {
            Files.createDirectories(DIR);
            Files.writeString(DIR.resolve("mismatches.txt"), String.join("\n\n", details) + "\n\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ObjectNode header() {
        ObjectNode h = JSON.createObjectNode();
        h.put("commit", System.getProperty("spike.commit", git()));
        h.put("machine", System.getProperty("os.name") + " " + System.getProperty("os.arch") + ", "
                + Runtime.getRuntime().availableProcessors() + " cpus, java " + System.getProperty("java.version"));
        h.put("vm", "julc-vm-java (JulcVm.create), LedgerEvaluationTarget.pv11(PLUTUS_V3), cap "
                + UplcRunner.CAP.cpuSteps() + " cpu / " + UplcRunner.CAP.memoryUnits() + " mem per evaluation");
        return h;
    }

    static synchronized void write(String section, ObjectNode body) {
        try {
            Files.createDirectories(DIR);
            ObjectNode out = header();
            out.setAll(body);
            Files.writeString(DIR.resolve(section + ".json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String git() {
        try {
            var p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").redirectErrorStream(true).start();
            String s = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            var d = new ProcessBuilder("git", "status", "--porcelain").redirectErrorStream(true).start();
            boolean dirty = !new String(d.getInputStream().readAllBytes()).isBlank();
            d.waitFor();
            return s + (dirty ? "+uncommitted" : "");
        } catch (Exception e) {
            return "unknown";
        }
    }
}
