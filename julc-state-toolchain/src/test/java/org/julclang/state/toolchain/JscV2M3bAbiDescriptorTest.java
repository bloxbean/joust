package org.julclang.state.toolchain;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.dsl.v2.AbiCheck;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.julclang.state.toolchain.JscV2M2cGateTest.description;
import static org.junit.jupiter.api.Assertions.*;

/**
 * #112 review C4: the ABI check binds the description's keyed descriptors that clients read, each one needed: the lookup,
 * the three datums' schemas, Link's and Remove_T's purpose, constructor and index. Each mutant changes one descriptor of
 * the port's description, every definition kept, and the unchanged manifest must then disagree.
 */
class JscV2M3bAbiDescriptorTest {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String PKG = "org.julclang.state.examples.dsl";

    static ObjectNode keyed(ObjectNode d, int i) {
        return (ObjectNode) d.get("x-joust").get("keyed").get(i);
    }

    @Test
    void everyKeyedDescriptor_isBound() throws Exception {
        String abi = Files.readString(JscV2M1aFourWayTest.DSL.resolve("Registry.abi.json"));
        String desc = description(PKG + ".Registry");
        assertEquals(List.of(), AbiCheck.compare(abi, desc, PKG, "Registry"), "the port's manifest and description agree");
        String v = "#/definitions/" + PKG + ".RegistryValidator.";
        Map<String, Consumer<ObjectNode>> mutants = new java.util.LinkedHashMap<>();
        mutants.put("Entry's link constructor SetOpen", d -> ((ObjectNode) keyed(d, 1).get("link")).put("constructor", "SetOpen"));
        mutants.put("Entry's link purpose mint", d -> ((ObjectNode) keyed(d, 1).get("link")).put("purpose", "mint"));
        mutants.put("Entry's link index 0", d -> ((ObjectNode) keyed(d, 1).get("link")).put("index", 0));
        mutants.put("Entry's remove constructor BurnInstance", d -> ((ObjectNode) keyed(d, 1).get("remove")).put("constructor", "BurnInstance"));
        mutants.put("Entry's remove purpose spend", d -> ((ObjectNode) keyed(d, 1).get("remove")).put("purpose", "spend"));
        mutants.put("Entry's remove null", d -> keyed(d, 1).putNull("remove"));
        mutants.put("Ballot's remove given", d -> keyed(d, 0).set("remove", keyed(d, 1).get("remove").deepCopy()));
        mutants.put("Entry's node Keyed_Ballot", d -> ((ObjectNode) keyed(d, 1).get("node")).put("$ref", v + "Keyed_Ballot"));
        mutants.put("Entry's head Head_Ballot", d -> ((ObjectNode) keyed(d, 1).get("head")).put("$ref", v + "Head_Ballot"));
        mutants.put("Entry's retired Retired_Ballot", d -> ((ObjectNode) keyed(d, 1).get("retired")).put("$ref", v + "Retired_Ballot"));
        mutants.put("Entry's lookup ballot", d -> keyed(d, 1).put("lookup", "ballot"));
        mutants.put("Entry's lookup missing", d -> keyed(d, 1).remove("lookup"));
        mutants.put("Entry's link an extra field", d -> ((ObjectNode) keyed(d, 1).get("link")).put("extra", 1));
        for (var e : mutants.entrySet()) {
            ObjectNode d = (ObjectNode) JSON.readTree(desc);
            e.getValue().accept(d);
            var problems = AbiCheck.compare(abi, JSON.writeValueAsString(d), PKG, "Registry");
            assertFalse(problems.isEmpty(), e.getKey() + ": the manifest must disagree");
        }
        System.out.println("[M3b descriptors] " + mutants.size() + " descriptor mutants, each refused by the ABI check");
    }
}
