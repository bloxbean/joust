package org.julclang.state.examples.registry;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0016 D11: the registry's description gives a JavaScript client everything a keyed call needs, and agrees with the
 * generated validator (constructor tags by its permits order) and the Java client (typeIds, tags, bounds).
 */
class RegistryDescriptionTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String RESOURCE = "META-INF/joust/org.julclang.state.examples.registry.Registry.json";

    static JsonNode description() throws Exception {
        try (InputStream in = RegistryDescriptionTest.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            return JSON.readTree(in);
        }
    }

    static int indexIn(Class<?> sealed, String simple) {
        var permits = sealed.getPermittedSubclasses();
        for (int i = 0; i < permits.length; i++) if (permits[i].getSimpleName().equals(simple)) return i;
        throw new AssertionError(simple + " is not a permitted subclass of " + sealed.getSimpleName());
    }

    @Test
    void keyedTypesAgreeWithTheValidatorAndTheJavaClient() throws Exception {
        JsonNode x = description().get("x-joust");
        var types = List.of(RegistryClient.BALLOT_TYPE, RegistryClient.ENTRY_TYPE);
        assertEquals(2, x.get("keyed").size());
        for (int i = 0; i < types.size(); i++) {
            var t = types.get(i);
            JsonNode k = x.get("keyed").get(i);
            assertEquals(t.name(), k.get("simple").asText());
            assertEquals(HexFormat.of().formatHex(t.typeId()), k.get("typeId").asText());
            assertEquals(t.headTag(), k.get("headTag").asInt());
            assertEquals(t.nodeTag(), k.get("nodeTag").asInt());
            assertEquals(t.retiredTag(), k.get("retiredTag").asInt());
            assertEquals(indexIn(RegistryValidator.Holding.class, "Head_" + t.name()), t.headTag());
            assertEquals(indexIn(RegistryValidator.Holding.class, "Keyed_" + t.name()), t.nodeTag());
            assertEquals(indexIn(RegistryValidator.Holding.class, "Retired_" + t.name()), t.retiredTag());
            assertEquals(t.keyIndex(), k.get("keyIndex").asInt());
            assertEquals(t.maxKeyBytes(), k.get("maxKeyBytes").asInt());
            assertEquals(t.signedKey(), k.get("signedKey").asBoolean());
            assertEquals(t.cooldownMs(), k.get("linkCooldownMs").asLong());
            assertEquals(t.windowMs(), k.get("linkWindowMs").asLong());
            assertEquals(indexIn(RegistryValidator.Calls.class, "Link"), k.get("link").get("index").asInt());
        }
        assertTrue(x.get("keyed").get(0).get("remove").isNull(), "Ballot has no removing exit");
        assertEquals(indexIn(RegistryValidator.Lifecycle.class, "Remove_Entry"), x.get("keyed").get(1).get("remove").get("index").asInt());
        assertTrue(x.get("tokens").has("keyed"));
    }

    @Test
    void everyKeyedCallIsDescribedWithItsKind() throws Exception {
        JsonNode x = description().get("x-joust");
        var kinds = new ArrayList<String>();
        for (JsonNode c : x.get("creations")) kinds.add(c.get("name").asText() + ":" + c.get("kind").asText());
        for (JsonNode a : x.get("actions")) kinds.add(a.get("name").asText() + ":" + a.get("kind").asText());
        assertEquals(List.of("init:init", "cast:keyedInit", "claim:keyedInit", "grant:keyedInit", "setOpen:action",
                "change:keyedAction", "upgrade:keyedAction", "withdraw:keyedAction", "recover:keyedAction", "leave:keyedRetire",
                "release:keyedRemove", "retire:keyedRetire"), kinds);
        for (JsonNode c : x.get("creations")) {
            if (!c.get("kind").asText().equals("keyedInit")) continue;
            assertEquals(indexIn(RegistryValidator.Lifecycle.class, "Insert_" + c.get("name").asText()), c.get("redeemer").get("index").asInt());
            assertEquals("instance", c.get("recipe").get("redeemerFields").get(0).asText());
            assertEquals("anchor", c.get("recipe").get("redeemerFields").get(1).asText());
        }
        for (JsonNode a : x.get("actions")) {
            String name = a.get("name").asText();
            String variant = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            assertEquals(indexIn(RegistryValidator.Calls.class, variant), a.get("redeemer").get("index").asInt(), name);
            JsonNode burn = a.get("recipe").get("burn");
            switch (a.get("kind").asText()) {
                case "keyedRemove" -> assertEquals("Remove_Entry", burn.get("constructor").asText());
                case "keyedRetire", "keyedAction", "action" -> assertTrue(burn == null || burn.isNull(), name);
                default -> fail(name);
            }
        }
        // The spend validator's datums include every list datum.
        var datums = new ArrayList<String>();
        for (JsonNode v : description().get("validators")) {
            if (v.get("title").asText().endsWith(".spend")) v.get("datum").get("schema").get("anyOf").forEach(d -> datums.add(d.get("$ref").asText()));
        }
        assertEquals(7, datums.size());
        assertTrue(datums.stream().anyMatch(d -> d.endsWith("RegistryValidator.Retired_Entry")));
    }

    /**
     * The page verifier's unit tests (js/state-client/test/keyed.test.js) run on a copy of this description: it must be
     * the generated one (regenerate the copy with the command in the message).
     */
    @Test
    void theJavaScriptTestsCopyIsCurrent() throws Exception {
        Path copy = Path.of("../../js/state-client/test/fixtures/registry.description.json");
        assertTrue(Files.exists(copy), copy + " is missing");
        assertEquals(description(), JSON.readTree(copy.toFile()),
                "js/state-client/test/fixtures/registry.description.json is stale: copy examples/registry/build/classes/java/main/"
                        + RESOURCE);
    }
}
