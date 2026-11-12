package org.julclang.state.runtime.wasm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0020 D8, U3, U7: the program manifest.
 * <ul>
 *   <li>the spike's rebuild (same pool, options and substitution as the toolchain) gives the toolchain's programs byte
 *       for byte, so the manifest records what was actually compiled;</li>
 *   <li>the CI recompile check: committed manifests ({@code src/test/resources/manifests}) equal a fresh compile
 *       (regenerate with {@code -Dspike.writeManifests=true});</li>
 *   <li>the check before evaluation accepts the real deployments and refuses each negative case;</li>
 *   <li>the discovery variant equals the on-chain source above the marker line.</li>
 * </ul>
 */
class ManifestTest {

    /** The contracts of the recorded corpora (fixtures/m0/dapp and fixtures/m3). */
    static final List<String> CONTRACTS = List.of(
            "counter.Counter", "bank.Bank", "crowdfund.Crowdfund", "dex.Dex", "escrow.Escrow", "fees.Pot", "fees.Stall",
            "guild.Armory", "guild.Guild", "locker.Locker", "locker.Safes", "oracle.Oracle", "oracle.Shop", "pool.Pool",
            "registry.Registry", "staking.StakedVault", "token.Token", "treasury.Treasury", "upgrade.CounterV1",
            "upgrade.CounterV2", "vault.Vault");

    static String fqcn(String s) {
        return "org.julclang.state.examples." + s;
    }

    static final Path COMMITTED = Path.of("src/test/resources/manifests");

    @Test
    void manifestsAreReproducibleAndCheckTheRealDeployments() throws Exception {
        var pinned = Manifest.pinned();
        var body = Report.JSON.createObjectNode();
        ArrayNode rows = body.putArray("contracts");
        boolean write = Boolean.getBoolean("spike.writeManifests");
        List<String> problems = new ArrayList<>();
        for (String s : CONTRACTS) {
            var c = Spike.contract(fqcn(s));
            var primary = Programs.compile(s.split("\\.")[0], c, Programs.Set.PRIMARY);
            // The rebuild is the toolchain's: same programs, byte for byte.
            if (!primary.programs().equals(c.transitions())) problems.add(s + ": rebuild differs from the toolchain's programs");
            ObjectNode m = Manifest.of(c, primary);
            Path file = COMMITTED.resolve(fqcn(s) + ".json");
            String text = Report.JSON.writerWithDefaultPrettyPrinter().writeValueAsString(m) + "\n";
            if (write) {
                Files.createDirectories(COMMITTED);
                Files.writeString(file, text);
            } else if (!Files.exists(file) || !Report.JSON.readTree(file.toFile()).equals(m)) {
                problems.add(s + ": the committed manifest differs from a fresh compile (CI recompile check)");
            }
            var ok = Manifest.check(m, new Manifest.Deployment(c.descriptionJson(), c.scriptCborHex(), c.transitions(),
                    c.scriptHash().isEmpty() ? null : c.scriptHash()), Programs.Set.PRIMARY, pinned);
            if (!ok.isEmpty()) problems.add(s + ": " + ok);
            var row = rows.addObject();
            row.put("contract", s);
            row.put("programs", c.transitions().size());
            row.put("substituted", String.join(",", primary.substituted()));
            row.put("parameterised", c.scriptHash().isEmpty());
        }
        Report.write("manifests", body);
        assertEquals(List.of(), problems);
    }

    @Test
    void theRecordedEngineArtifactsAreCheckedAgainstTodaysManifests() throws Exception {
        // fixtures/m3 holds the scripts and programs the engine ran when the recordings were made.
        var pinned = Manifest.pinned();
        var body = Report.JSON.createObjectNode();
        var rows = body.putObject("m3");
        try (var files = Files.list(Spike.ROOT.resolve("fixtures/m3/contracts"))) {
            for (Path p : files.sorted().toList()) {
                JsonNode x = Spike.read(p);
                String title = Spike.title(x.get("description").asText());
                var c = Spike.contract(title);
                var m = Manifest.of(c, Programs.compile(title.split("\\.")[4], c, Programs.Set.PRIMARY));
                var t = new LinkedHashMap<String, String>();
                x.path("transitions").fields().forEachRemaining(e -> t.put(e.getKey(), e.getValue().asText()));
                var r = Manifest.check(m, new Manifest.Deployment(x.get("description").asText(), x.get("script").asText(), t, null),
                        Programs.Set.PRIMARY, pinned);
                rows.put(title, r.isEmpty() ? "consistent" : String.join("; ", r));
            }
        }
        Report.write("manifests-m3", body);
    }

    @Test
    void theCheckRefusesEachNegativeCase() throws Exception {
        var pinned = Manifest.pinned();
        var v1 = Spike.contract(fqcn("upgrade.CounterV1"));
        var v2 = Spike.contract(fqcn("upgrade.CounterV2"));
        var counter = Spike.contract(fqcn("counter.Counter"));
        var vault = Spike.contract(fqcn("vault.Vault"));
        ObjectNode m = Manifest.of(counter, Programs.compile("counter", counter, Programs.Set.PRIMARY));
        var good = new Manifest.Deployment(counter.descriptionJson(), counter.scriptCborHex(), counter.transitions(), counter.scriptHash());
        assertEquals(List.of(), Manifest.check(m, good, Programs.Set.PRIMARY, pinned));

        Map<String, List<String>> cases = new LinkedHashMap<>();
        // A stale program: CounterV1's programs against CounterV2's manifest.
        ObjectNode m2 = Manifest.of(v2, Programs.compile("upgrade", v2, Programs.Set.PRIMARY));
        cases.put("stale programs (v1 against a v2 deployment)", Manifest.check(m2,
                new Manifest.Deployment(v2.descriptionJson(), v2.scriptCborHex(), v1.transitions(), v2.scriptHash()), Programs.Set.PRIMARY, pinned));
        // Programs bound to another unapplied script.
        cases.put("another unapplied script", Manifest.check(m,
                new Manifest.Deployment(counter.descriptionJson(), vault.scriptCborHex(), counter.transitions(), null), Programs.Set.PRIMARY, pinned));
        // A correct manifest, but the spent credential is another deployment's script.
        cases.put("another deployment's script as the spent credential", Manifest.check(m,
                new Manifest.Deployment(counter.descriptionJson(), counter.scriptCborHex(), counter.transitions(), vault.scriptHash()), Programs.Set.PRIMARY, pinned));
        // A primary manifest substituting Require (allowed only in the auxiliary set).
        var aux = Programs.compile("counter", counter, Programs.Set.AUXILIARY);
        ObjectNode ma = Manifest.of(counter, aux);
        ma.put("set", "primary");
        cases.put("a primary manifest substituting Require", Manifest.check(ma,
                new Manifest.Deployment(counter.descriptionJson(), counter.scriptCborHex(), aux.programs(), counter.scriptHash()), Programs.Set.PRIMARY, pinned));
        // An unexpected override.
        ObjectNode mo = m.deepCopy();
        ((ArrayNode) mo.get("substituted")).add("org.julclang.state.api.Payouts");
        cases.put("an unexpected discovery override", Manifest.check(mo, good, Programs.Set.PRIMARY, pinned));
        // A source-mapped (unoptimised) program offered as primary.
        var diag = Programs.compile("counter", counter, Programs.Set.DIAGNOSTIC);
        ObjectNode md = Manifest.of(counter, diag);
        md.put("set", "primary");
        cases.put("a source-mapped program as primary", Manifest.check(md,
                new Manifest.Deployment(counter.descriptionJson(), counter.scriptCborHex(), diag.programs(), counter.scriptHash()), Programs.Set.PRIMARY, pinned));
        // Programs without traces.
        ObjectNode mt = m.deepCopy();
        ((ObjectNode) mt.get("options")).put("traces", false);
        cases.put("programs without traces", Manifest.check(mt, good, Programs.Set.PRIMARY, pinned));
        // A user-overridden profile.
        ObjectNode mp = m.deepCopy();
        ((ObjectNode) mp.get("options")).put("profile", "pv11-aggressive");
        cases.put("another optimisation profile", Manifest.check(mp, good, Programs.Set.PRIMARY, pinned));
        // An override whose source is not the pinned one.
        ObjectNode mh = m.deepCopy();
        if (mh.get("pool").has(Programs.MERKLE_MAPS)) ((ObjectNode) mh.get("pool")).put(Programs.MERKLE_MAPS, "00");
        ((ArrayNode) mh.get("substituted")).removeAll().add(Programs.MERKLE_MAPS);
        cases.put("an override with another source", Manifest.check(mh, good, Programs.Set.PRIMARY, pinned));
        // Another JuLC version.
        ObjectNode mj = m.deepCopy();
        mj.put("julc", "0.1.0-pre20");
        cases.put("another JuLC version", Manifest.check(mj, good, Programs.Set.PRIMARY, pinned));

        var body = Report.JSON.createObjectNode();
        cases.forEach((k, v) -> body.put(k, String.join("; ", v)));
        Report.write("manifest-negative", body);
        cases.forEach((k, v) -> assertFalse(v.isEmpty(), "not refused: " + k));
        // The auxiliary set is accepted as auxiliary.
        assertEquals(List.of(), Manifest.check(Manifest.of(counter, aux), new Manifest.Deployment(counter.descriptionJson(),
                counter.scriptCborHex(), aux.programs(), counter.scriptHash()), Programs.Set.AUXILIARY, pinned));
    }

    @Test
    void theDiscoveryVariantEqualsTheOnChainSourceAboveTheMarker() throws Exception {
        String onchain = Programs.classpathLibraries().get(Programs.MERKLE_MAPS).source();
        String discovery = Programs.resource("META-INF/joust/discovery/org/julclang/state/api/MerkleMaps.java");
        assertTrue(Manifest.sameAboveMarker(onchain, discovery));
        assertFalse(Manifest.sameAboveMarker(onchain, discovery.replaceFirst("MapEntry hit = ", "MapEntry hit2 = ")));
    }
}
