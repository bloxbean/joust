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
                if (f.at("/host/result").has("fingerprint"))
                    ((ObjectNode)f.at("/host/result")).put("fingerprint","1".repeat(64));
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
    public static void main(String[] args) throws Exception {
        for (String name : List.of("bank","keyed")) write(SESSIONS.resolve(name+".json"),record(name));
    }
}
