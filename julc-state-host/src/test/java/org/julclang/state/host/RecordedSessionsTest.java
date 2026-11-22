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
        var first = recording(bank ? "Bank-publish-1" : "unsupported-keyed");
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
            success(c.rpc("initialize",hello));
            success(c.rpc("describe",obj().put("contract",contract)));
            if (!bank) error("unsupported","keyed",c.rpc("prepare",params));
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
                if (f.at("/host/result").has("fingerprint")) {
                    // The host's answer must be the digest computed for this snapshot before it becomes the portable
                    // identity: a client refuses any other (D13), so a wrong one must not be recorded away (#153 Codex review).
                    assertEquals(digest, f.at("/host/result/fingerprint").textValue(), "the host's fingerprint for "+name);
                    ((ObjectNode)f.at("/host/result")).put("fingerprint","1".repeat(64));
                }
            }
            return obj().set("frames",c.frames);
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
        for (String name : List.of("bank","keyed")) {
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
        // A complete (invalid) header, so a client reading headers refuses it at once, not at the partial-frame
        // deadline the fake host would outwait (#153 Codex re-review).
        out.put("stray-bytes",cut(frames,describe,obj().put("violation","strayBytes").put("raw","stray output\r\n\r\n")));
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
        for (String name : List.of("bank","keyed")) write(SESSIONS.resolve(name+".json"),record(name));
        for (var e : violations(JSON.readTree(SESSIONS.resolve("bank.json").toFile())).entrySet())
            write(SESSIONS.resolve("violation-"+e.getKey()+".json"),e.getValue());
    }
}
