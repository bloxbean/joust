package org.julclang.state.examples.devkit;

import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0015 M0's oracle is sound: every recorded fixture's unsigned transaction parses and hashes to the recorded
 * transaction hash (the body is what was signed), and every read's result reference resolves to a stored blob; each
 * group holds one recording (its files numbered 1 to n).
 */
class FixturesTest {

    static final Path ROOT = Path.of("../../fixtures/m0");
    static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void fixturesAreConsistent() throws Exception {
        assertTrue(Files.isDirectory(ROOT), "fixtures/m0 is missing");
        int fixtures = 0, reads = 0;
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json") && !p.toString().contains("/blobs/")
                    && !p.getFileName().toString().equals("devkit.json")).toList()) {
                JsonNode d = JSON.readTree(f.toFile());
                Transaction tx = Transaction.deserialize(HexUtil.decodeHexString(d.get("unsignedTx").asText()));
                assertEquals(d.get("txHash").asText(), TransactionUtil.getTxHash(tx), f.toString());
                for (JsonNode r : d.get("reads")) {
                    JsonNode ref = r.path("result").path("$ref");
                    if (ref.isTextual()) assertTrue(Files.exists(ROOT.resolve("blobs").resolve(ref.asText() + ".json")), f + ": " + ref);
                    reads++;
                }
                fixtures++;
            }
        }
        assertTrue(fixtures >= 150, "expected the recorded oracle, found " + fixtures + " fixtures");
        // One recording per group (M2c review r1, F2): a group's files are numbered 1, 2, ... n, each number once. The
        // recorder numbers a run's transactions from 1 and does not clear a group, so a run over an older one leaves both.
        var mixed = new java.util.ArrayList<String>();
        try (Stream<Path> groups = Files.list(ROOT)) {
            for (Path g : groups.filter(p -> Files.isDirectory(p) && !p.getFileName().toString().equals("blobs")).sorted().toList()) {
                var numbers = new java.util.ArrayList<Integer>();
                try (Stream<Path> fs = Files.list(g)) {
                    for (Path f : fs.filter(p -> p.toString().endsWith(".json")).toList()) {
                        String n = f.getFileName().toString();
                        numbers.add(Integer.parseInt(n.substring(0, n.indexOf('-'))));
                    }
                }
                java.util.Collections.sort(numbers);
                for (int i = 0; i < numbers.size(); i++) {
                    if (numbers.get(i) != i + 1) {
                        mixed.add(g.getFileName() + ": " + numbers);
                        break;
                    }
                }
            }
        }
        assertEquals(java.util.List.of(), mixed, "groups that mix recordings (clear a group before recording it again)");
        System.out.println("[fixtures] " + fixtures + " transactions, " + reads + " chain reads");
    }
}
