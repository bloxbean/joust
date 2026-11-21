package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.Client.*;
import static org.julclang.state.host.ClientVectors.*;

/** Capture frames at the real transport boundary, including reverse IDs, errors and terminal notifications. */
class RecordedSessionsTest {
    static final Path SESSIONS = HOST.resolve("sessions");
    static class Recorder extends Client {
        final ArrayNode frames = JSON.createArrayNode();
        Recorder(Path entry) throws Exception { super(new Handshake(List.of(entry))); }
        @Override void send(ObjectNode message) throws Exception {
            super.send(message);
            frames.addObject().set("client",message.deepCopy());
        }
        @Override JsonNode next() throws Exception {
            var frame = super.next();
            frames.addObject().set("host",frame.deepCopy());
            return frame;
        }
    }
    static Function<JsonNode,JsonNode> answers(JsonNode v) {
        var answers = new HashMap<JsonNode,JsonNode>();
        for (var p : v.required("reads")) answers.put(p.required("request"),p.required("answer"));
        return q -> Objects.requireNonNull(answers.get(q),q::toString);
    }
    static ObjectNode record(String name) throws Exception {
        boolean bank = name.equals("bank");
        var first = recording(bank ? "Bank-publish-1" : name.equals("maps") ? "token-003-mint" : "registry-019-release");
        var params = first.required("params");
        String contract = (bank ? params : params.required("call")).required("contract").textValue();
        var entry = M1bTest.entry(Class.forName(contract+"Client"));
        try (var c = new Recorder(entry)) {
            var hello = init(entry);
            for (String k : List.of("clock","network","limits","time")) hello.set(k,first.required(k));
            if (bank) {
                var bad = hello.deepCopy();
                bad.withObject("/build").put("digest","0".repeat(64));
                error("bad","stageMismatch",c.rpc("initialize",bad));
            }
            if (name.equals("maps")) hello.put("restoring",true);
            success(c.rpc("initialize",hello));
            success(c.rpc("describe",obj().put("contract",contract)));
            if (name.equals("maps")) maps(c,first);
            else if (!bank) keyed(c,first);
            else bank(c,first,contract);
            success(c.rpc("shutdown",obj()));
            c.send(obj().put("method","exit"));
            assertEquals("exit",c.exits.poll(3, java.util.concurrent.TimeUnit.SECONDS));

            // Portable fixture identity. Only the two build-dependent identity fields are replaced.
            String digest = hello.at("/build/digest").textValue();
            for (var f : c.frames) {
                if ("initialize".equals(f.at("/client/method").textValue())) {
                    var build = (ObjectNode)f.at("/client/params/build");
                    build.put("snapshot","/snapshots/"+name);
                    if (digest.equals(build.get("digest").textValue())) build.put("digest","1".repeat(64));
                }
                if (f.at("/host/result").has("fingerprint"))
                    ((ObjectNode)f.at("/host/result")).put("fingerprint","1".repeat(64));
            }
            return obj().set("frames",c.frames);
        }
    }

    private static void maps(Recorder c, JsonNode recording) throws Exception {
        var p = recording.required("params");
        long now = p.required("now").asLong();
        var previous = recording("token-002-mint").required("expected");
        var restore = obj().put("complete",true);
        restore.putArray("transactions").addObject().put("state","prepared").set("cbor",previous.required("cbor"));
        var ids = success(c.rpc("restore",restore)).required("reservations");
        error("refusal","mapsStale",c.rpc("prepare",p));
        var settled = obj().put("reservation",ids.get(0).asText()).put("status","confirmed").put("slot",now-3).put("tip",now);
        settled.set("txHash",previous.required("txHash"));
        assertEquals("confirmed",success(c.rpc("settled",settled)).required("state").asText());
        error("refusal","mapsStale",c.rpc("prepare",p));
        success(c.rpc("reset",obj().put("now",now)));
        var next = success(c.rpc("prepare",p,answers(recording))).required("prepared");
        assertEquals(recording.at("/expected/cbor"),next.required("cbor"));
        settled.put("status","discarded").remove("slot");
        error("bad","conflict",c.rpc("settled",settled));
        error("refusal","mapsStale",c.rpc("prepare",p));
        settled.set("reservation",next.required("reservation"));
        settled.set("txHash",next.required("txHash"));
        success(c.rpc("settled",settled));
        success(c.rpc("reset",obj().put("now",now)));
        assertEquals(next.required("cbor"),success(c.rpc("prepare",p,answers(recording))).at("/prepared/cbor"));
    }

    private static void keyed(Recorder c, JsonNode recording) throws Exception {
        ObjectNode p = recording.required("params").deepCopy();
        var call = p.withObject("/call");
        var answers = answers(recording);
        String designated = call.remove("designated").textValue();
        error("refusal","needsDesignated",c.rpc("prepare",p));
        call.put("designated",designated);
        call.putArray("pageLimits").add(0).add(900);
        var cooldown = c.rpc("prepare",p,answers);
        error("refusal","cooldown",cooldown);
        assertEquals(recording.at("/expected/validFrom"),cooldown.at("/error/data/retryAtSlot"));
        call.putArray("pageLimits").add(60).add(900);
        var result = success(c.rpc("prepare",p,answers));
        var effects = result.at("/predicted/objects");
        assertEquals(Set.of("updated","removed"),Set.of(effects.get(0).required("effect").asText(),effects.get(1).required("effect").asText()));
        var built = result.required("prepared");
        assertTrue(built.required("validFrom").asLong()>p.required("now").asLong());
        // A live attempt the record does not list yet (awaiting approval) holds the operation: pending (final review F-K3).
        error("refusal","pending",c.rpc("prepare",p,answers));
        var attempts = call.withObject("/operation").withArray("attempts");
        attempts.addObject().setAll(((ObjectNode)built).deepCopy().retain("txHash","validTo"));
        error("refusal","pending",c.rpc("prepare",p,answers));
        error("bad","conflict",c.rpc("settled",obj().put("status","discarded").put("tip",p.required("now").asLong())
                .put("reservation",str(built,"reservation")).put("txHash",str(built,"txHash"))));
        secondInstance(c,recording,p);
        error("refusal","mayHaveLanded",c.rpc("prepare",p,q -> {
            ObjectNode answer = answers.apply(q).deepCopy();
            assertEquals(call.get("address"),q.get("address"));
            answer.putArray("value");
            return answer;
        }));
    }

    // Two instances at one script address, each with an Entry named "other" after "name".
    // Give the second operation its own wallet input while the first reservation is live.
    private static void secondInstance(Recorder c, JsonNode recording, ObjectNode first) throws Exception {
        ObjectNode params = first.deepCopy();
        var call = params.withObject("/call");
        String original = str(call,"instance"), instance = "ab".repeat(32), tx = "12".repeat(32);
        call.put("designated",tx+"#2");
        RegistryRecording.operation(call);
        var replies = new HashMap<JsonNode,JsonNode>();
        var copies = new ArrayList<ObjectNode>();
        var type = org.julclang.state.examples.registry.RegistryClient.SPEC.keyed().stream()
                .filter(t -> t.name().equals("Entry")).findFirst().orElseThrow();
        var hex = HexFormat.of();
        for (var exchange : recording.required("reads")) {
            var q = exchange.required("request");
            ObjectNode answer = exchange.required("answer").deepCopy();
            replies.put(q,answer);
            if (!q.path("kind").asText().equals("output") || answer.at("/value/reference_script_hash").isTextual()) continue;
            ObjectNode clone = answer.required("value").deepCopy();
            int index = clone.required("output_index").intValue();
            clone.put("tx_hash",tx);
            if (index < 2) {
                String key = index == 0 ? "6e616d65" : str(call,"object");
                retag(clone,original,instance,type,key);
            }
            copies.add(clone);
            replies.put(obj().put("kind","output").put("ref",tx+"#"+index),answer.deepCopy().set("value",clone));
        }
        for (var entry : replies.entrySet()) {
            if (!entry.getKey().path("kind").asText().equals("utxos")) continue;
            var values = (ArrayNode)entry.getValue().required("value");
            var headUnit = hex.formatHex(type.keyId(hex.parseHex(original),new byte[0]));
            for (var u : values.deepCopy()) {
                if (!u.path("amount").findValuesAsText("unit").stream().anyMatch(unit -> unit.endsWith(headUnit))) continue;
                ObjectNode head = u.deepCopy();
                head.put("tx_hash",tx).put("output_index",3);
                retag(head,original,instance,type,"");
                values.add(head);
            }
            for (var u : copies) if (u.get("address").equals(entry.getKey().get("address")))
                ((ArrayNode)entry.getValue().required("value")).add(u);
        }
        Function<JsonNode,JsonNode> answer = q -> Objects.requireNonNull(replies.get(q),q::toString);
        error("refusal","pending",c.rpc("prepare",params,answer)); // New designation, same reserved node.
        call.put("instance",instance);
        RegistryRecording.operation(call);
        var listing = obj().put("now",params.required("now").asLong()).put("contract",str(call,"contract"))
                .put("instance",instance).put("limit",20);
        var items = success(c.rpc("objects",listing,answer)).required("items");
        assertEquals(3,java.util.stream.StreamSupport.stream(items.spliterator(),false).filter(i -> i.has("object")).count());
        assertTrue(success(c.rpc("prepare",params,answer)).at("/prepared/txHash").isTextual());
    }

    private static void retag(ObjectNode u,String original,String instance,org.julclang.state.runtime.KeyedType<?> type,String key) {
        var hex = HexFormat.of();
        String datum = str(u,"inline_datum").replace(original,instance);
        u.put("inline_datum",datum).put("data_hash",hex.formatHex(
                com.bloxbean.cardano.client.crypto.Blake2bUtil.blake2bHash256(hex.parseHex(datum))));
        for (var a : u.required("amount")) {
            String unit = str(a,"unit");
            if (!unit.equals("lovelace")) ((ObjectNode)a).put("unit",unit.substring(0,56)+
                    hex.formatHex(type.keyId(hex.parseHex(instance),hex.parseHex(key))));
        }
    }

    private static void bank(Recorder c, JsonNode publication, String contract) throws Exception {
        var p = publication.required("params");
        var q = obj().put("contract",contract).set("now",p.required("now"));
        var absent = answers(publication);
        success(c.rpc("deployment",q,absent));
        success(c.rpc("prerequisites",q,absent));
        var init = recording("Bank-init");
        ObjectNode premature = init.required("params").deepCopy();
        premature.set("now",p.required("now"));
        error("refusal","prerequisite",c.rpc("prepare",premature,absent));
        var holder = success(c.rpc("publish",p,absent)).required("prepared");
        success(c.rpc("settled",obj().put("status","confirmed").put("reservation",holder.required("reservation").asText())
                .put("txHash",holder.required("txHash").asText()).put("slot",p.required("now").asLong())
                .put("tip",init.at("/params/now").asLong())));
        var built = success(c.rpc("prepare",init.required("params"),answers(init))).required("prepared");
        var later = recording("owned-action");
        var now = later.at("/params/now").asLong();
        var query = obj().put("contract",contract).put("now",now);
        var view = query.deepCopy().put("instance",built.required("instance").asText()).putNull("view");
        view.putObject("args");
        success(c.rpc("view",view,answers(later)));
        query.put("limit",10);
        success(c.rpc("instances",query,answers(later)));
        int id = c.request("instances",query);
        var read = c.next();
        assertEquals("chain/read",read.required("method").textValue());
        c.send(obj().put("method","$/cancelRequest").set("params",obj().put("id",id)));
        error("internal","cancelled",c.next());
        c.send(obj().put("id",read.required("id").asText()).set("result",answers(later).apply(read.required("params"))));
        // D10 permits a brief draining period before another forward call.
        for (var t : Thread.getAllStackTraces().keySet())
            if (t.getName().equals("joust-call-"+id)) t.join(2000);
    }

    @Test void liveSessionsMatchTheShippedScripts() throws Exception {
        for (String name : List.of("bank","keyed","maps")) {
            var recorded = record(name);
            var expected = JSON.readTree(SESSIONS.resolve(name+".json").toFile());
            // Installed artifact version strings vary by release and package metadata.
            for (int i=0;i<recorded.required("frames").size();i++) {
                var r = recorded.at("/frames/"+i+"/host/result");
                if (r.has("versions")) ((ObjectNode)r).set("versions",expected.at("/frames/"+i+"/host/result/versions"));
            }
            assertEquals(expected,JSON.readTree(JSON.writeValueAsBytes(recorded)));
        }
    }
    /**
     * DC4: the Bank recording cut at a point where the client must not speak, then one transport fault of the host's.
     * Every fault but one comes while describe is outstanding; the reverse request comes after shutdown's response,
     * where no call can run (the client may still send exit).
     */
    static Map<String,ObjectNode> violations(JsonNode bank) {
        var frames = bank.required("frames");
        int describe = -1, shutdown = -1, initialized = -1;
        for (int i=0;i<frames.size();i++) {
            var f = frames.get(i);
            if ("describe".equals(f.at("/client/method").textValue())) describe = i;
            if ("shutdown".equals(f.at("/client/method").textValue())) shutdown = i+1;
            if (initialized<0 && f.at("/host").has("result") && "initialize".equals(frames.get(i-1).at("/client/method").textValue())) initialized = i;
        }
        var read = frames.get(describe+3).required("host"); // deployment's first chain/read
        var out = new LinkedHashMap<String,ObjectNode>();
        out.put("unsolicited-response",cut(frames,describe,obj().put("violation","unsolicitedResponse")
                .set("frame",obj().put("jsonrpc","2.0").put("id","unsolicited").set("result",obj()))));
        out.put("duplicate-response",cut(frames,describe,obj().put("violation","duplicateResponse").put("again",true)
                .set("frame",frames.get(initialized).required("host"))));
        out.put("stray-bytes",cut(frames,describe,obj().put("violation","strayBytes").put("raw","stray output\n")));
        out.put("malformed-frame",cut(frames,describe,obj().put("violation","malformedFrame").put("raw","Content-Length: 9\r\n\r\n{\"id\":3,}")));
        out.put("oversized-frame",cut(frames,describe,obj().put("violation","oversizedFrame").put("raw","Content-Length: 4194305\r\n\r\n")));
        out.put("eof-during-call",cut(frames,describe,obj().put("violation","eofDuringCall").put("eof",true)));
        var reverse = obj().put("violation","reverseOutsideCall");
        reverse.set("frame",obj().put("jsonrpc","2.0").put("id","99.1").put("method","chain/read").set("params",read.required("params")));
        reverse.putArray("tolerate").add(frames.get(shutdown+1).required("client"));
        out.put("reverse-outside-call",cut(frames,shutdown,reverse));
        return out;
    }
    private static ObjectNode cut(JsonNode frames,int last,ObjectNode violation) {
        var session = obj();
        var kept = session.putArray("frames");
        for (int i=0;i<=last;i++) kept.add(frames.get(i));
        kept.add(violation);
        return session;
    }
    @Test void violationSessionsFollowTheBankRecording() throws Exception {
        var bank = JSON.readTree(SESSIONS.resolve("bank.json").toFile());
        var derived = violations(bank);
        try (var files = Files.list(SESSIONS)) {
            assertEquals(new TreeSet<>(derived.keySet().stream().map(n -> "violation-"+n+".json").toList()),
                    new TreeSet<>(files.map(f -> f.getFileName().toString()).filter(n -> n.startsWith("violation-")).toList()));
        }
        for (var e : derived.entrySet())
            assertEquals(JSON.readTree(SESSIONS.resolve("violation-"+e.getKey()+".json").toFile()),JSON.readTree(JSON.writeValueAsBytes(e.getValue())),e.getKey());
    }
    public static void main(String[] args) throws Exception {
        for (String name : List.of("bank","keyed","maps")) write(SESSIONS.resolve(name+".json"),record(name));
        for (var e : violations(JSON.readTree(SESSIONS.resolve("bank.json").toFile())).entrySet())
            write(SESSIONS.resolve("violation-"+e.getKey()+".json"),e.getValue());
    }
}
