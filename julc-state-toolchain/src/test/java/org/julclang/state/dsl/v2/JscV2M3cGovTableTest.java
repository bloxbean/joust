package org.julclang.state.dsl.v2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M3c: the ledger types of the script purposes as {@code .jsc} 2 reads them ({@link Gov}) are JuLC's. Each sealed
 * type's constructors are JuLC's records in the ledger's tag order (each built and encoded: its constructor tag is its
 * index), each with JuLC's components, in order, of the types the table maps them to (a {@code PubKeyHash} a Key read
 * through {@code .hash()}, an {@code Optional<ScriptHash>} an Option of a Hash, a {@code JulcMap} a LedgerMap, …); and
 * the JavaScript interpreter's twin ({@code js/jsc-interpreter/src/gov.js}) is the same table. Here, beside JuLC's ledger
 * (the front end does not depend on it), in the front end's package (the table is package-private).
 */
class JscV2M3cGovTableTest {

    static final String LEDGER = "org.julclang.ledger.";

    /** The JuLC class a ledger type name or a constructor ({@code Type.Ctor}) is. */
    static Class<?> julc(String name) throws ClassNotFoundException {
        return Class.forName(LEDGER + name.replace('.', '$'));
    }

    /** The Java type JuLC holds a field of {@code .jsc} type {@code t} as, by how the table says it is held. */
    static String javaType(Gov.Field f) {
        return switch (f.wire()) {
            case HASH -> LEDGER + hashClass(f.type());
            case OPT_HASH -> "java.util.Optional<" + LEDGER + hashClass(((Ty.OptT) f.type()).elem()) + ">";
            case DIRECT -> direct(f.type());
        };
    }

    static String hashClass(Ty t) {
        if (t.equals(Ty.KEY)) return "PubKeyHash";
        if (t.equals(Ty.HASH)) return "ScriptHash";
        if (t.equals(Ty.BYTES)) return "TxId";
        throw new AssertionError("no hash newtype for " + Ty.show(t));
    }

    static String direct(Ty t) {
        if (t.equals(Gov.DATA)) return "org.julclang.core.PlutusData";
        return switch (t) {
            case Ty.Prim p when p.name().equals("Int") -> "java.math.BigInteger";
            case Ty.Ledger l -> LEDGER + l.name();
            case Ty.Gov g -> LEDGER + g.name();
            case Ty.OptT o -> "java.util.Optional<" + direct(o.elem()) + ">";
            case Ty.ListT l -> "org.julclang.core.types.JulcList<" + direct(l.elem()) + ">";
            case Ty.LedgerMapT m -> "org.julclang.core.types.JulcMap<" + direct(m.key()) + ", " + direct(m.val()) + ">";
            default -> throw new AssertionError("no direct Java type for " + Ty.show(t));
        };
    }

    /** A value of a JuLC type, any: enough to build a record and read its constructor tag. */
    static Object dummy(Type t) throws Exception {
        Class<?> c = (Class<?>) (t instanceof ParameterizedType p ? p.getRawType() : t);
        if (c == BigInteger.class) return BigInteger.ONE;
        if (c == byte[].class) return new byte[28];
        if (c == Optional.class) return Optional.empty();
        if (c == org.julclang.core.types.JulcList.class) return org.julclang.core.types.JulcList.empty();
        if (c == org.julclang.core.types.JulcMap.class) return org.julclang.core.types.JulcMap.empty();
        if (c == org.julclang.core.PlutusData.class) return org.julclang.core.PlutusData.integer(0);
        if (c.isSealed()) return dummy(c.getPermittedSubclasses()[0]);
        if (c.isRecord()) {
            RecordComponent[] rc = c.getRecordComponents();
            var types = new Class<?>[rc.length];
            var args = new Object[rc.length];
            for (int i = 0; i < rc.length; i++) {
                types[i] = rc[i].getType();
                args[i] = dummy(rc[i].getGenericType());
            }
            return c.getDeclaredConstructor(types).newInstance(args);
        }
        throw new AssertionError("no dummy for " + t);
    }

    static int tag(Object record) throws Exception {
        var d = (org.julclang.core.PlutusData.ConstrData) record.getClass().getMethod("toPlutusData").invoke(record);
        return d.tag();
    }

    static void sameFields(Gov.Ctor c, Class<?> k) {
        RecordComponent[] rc = k.getRecordComponents();
        assertEquals(c.fields().size(), rc.length, c.javaClass() + "'s components");
        for (int i = 0; i < rc.length; i++) {
            Gov.Field f = c.fields().get(i);
            assertEquals(rc[i].getName(), f.name(), c.javaClass() + " component " + i);
            assertEquals(rc[i].getGenericType().getTypeName(), javaType(f), c.javaClass() + "." + f.name());
        }
    }

    @Test
    void theTable_isJulcsLedgerTypes_inTagOrder() throws Exception {
        int ctors = 0;
        for (var e : Gov.SUMS.entrySet()) {
            Class<?> sealed = julc(e.getKey());
            assertTrue(sealed.isSealed(), e.getKey());
            Class<?>[] permitted = sealed.getPermittedSubclasses();
            assertEquals(permitted.length, e.getValue().size(), e.getKey() + "'s constructors");
            for (Gov.Ctor c : e.getValue()) {
                Class<?> k = julc(c.javaClass());
                assertTrue(sealed.isAssignableFrom(k), c.javaClass());
                sameFields(c, k);
                assertEquals(c.tag(), tag(dummy(k)), c.javaClass() + "'s constructor tag");
                ctors++;
            }
        }
        for (Gov.Ctor c : Gov.RECORDS.values()) {
            Class<?> k = julc(c.javaClass());
            assertTrue(k.isRecord(), c.javaClass());
            sameFields(c, k);
            assertEquals(0, tag(dummy(k)), c.javaClass() + "'s constructor tag");
        }
        System.out.println("[M3c] the ledger table: " + Gov.SUMS.size() + " sealed types (" + ctors + " constructors) and " + Gov.RECORDS.size()
                + " records agree with JuLC's");
    }

    /** The table in gov.js's form: {GOV_SUMS: {type: [[ctor, {name, type}…]]}, GOV_RECORDS: {type: [{name, type}…]}}. */
    static Map<String, Object> javaTable() {
        var sums = new LinkedHashMap<String, Object>();
        for (var e : Gov.SUMS.entrySet()) {
            var cs = new ArrayList<Object>();
            for (Gov.Ctor c : e.getValue()) {
                var row = new ArrayList<Object>(List.of(c.name()));
                for (Gov.Field f : c.fields()) row.add(Map.of("name", f.name(), "type", IrJson.type(f.type())));
                cs.add(row);
            }
            sums.put(e.getKey(), cs);
        }
        var recs = new LinkedHashMap<String, Object>();
        for (Gov.Ctor c : Gov.RECORDS.values()) {
            var fs = new ArrayList<Object>();
            for (Gov.Field f : c.fields()) fs.add(Map.of("name", f.name(), "type", IrJson.type(f.type())));
            recs.put(c.type(), fs);
        }
        return Map.of("GOV_SUMS", sums, "GOV_RECORDS", recs);
    }

    @Test
    void theJavaScriptTwin_isTheSameTable() throws Exception {
        Path gov = Path.of("../js/jsc-interpreter/src/gov.js").toAbsolutePath().normalize();
        var p = new ProcessBuilder("node", "--input-type=module", "-e",
                "const g = await import(" + new ObjectMapper().writeValueAsString(gov.toUri().toString()) + ");"
                        + " console.log(JSON.stringify({ GOV_SUMS: g.GOV_SUMS, GOV_RECORDS: g.GOV_RECORDS }));")
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "node timed out");
        assertEquals(0, p.exitValue(), out);
        var m = new ObjectMapper();
        JsonNode js = m.readTree(out);
        JsonNode java = m.valueToTree(javaTable());
        assertEquals(java, js, "gov.js differs from Gov.java");
    }
}
