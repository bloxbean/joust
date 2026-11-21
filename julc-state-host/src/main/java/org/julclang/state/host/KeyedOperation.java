package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static org.julclang.state.host.Wire.*;

/** The client's persisted intent. Attempts contain only transactions handed out for submission or export. */
record KeyedOperation(Identity identity, Map<String, Long> attempts) {
    record Identity(String designated, String contract, String method, String instance, String key) {}
    static HostError mismatch() { return HostError.bad("operation", "operation differs from the call or its attempt history"); }

    static KeyedOperation parse(JsonNode o) {
        try {
            fields(o, "designated", "contract", "method", "instance", "key", "attempts");
            String ref = str(o, "designated");
            if (!ref.matches("[0-9a-f]{64}#(0|[1-9][0-9]*)") || ref.length()>75 || Long.parseLong(ref.substring(65))>Integer.MAX_VALUE || !o.path("attempts").isArray()) throw mismatch();
            String instance = nullable(o, "instance", "[0-9a-f]{64}");
            String key = nullable(o, "key", "(?:[0-9a-f]{2})+");
            if (key != null && instance == null) throw mismatch();
            var attempts = new LinkedHashMap<String, Long>();
            for (var a : o.get("attempts")) {
                fields(a, "txHash", "validTo");
                String hash = str(a, "txHash");
                if (!hash.matches("[0-9a-f]{64}") || attempts.put(hash, number(a, "validTo", 1)) != null) throw mismatch();
            }
            return new KeyedOperation(new Identity(ref, str(o,"contract"), str(o,"method"), instance, key), Map.copyOf(attempts));
        } catch (HostError malformed) { throw mismatch(); }
    }
    private static String nullable(JsonNode o, String field, String pattern) {
        if (o.path(field).isNull()) return null;
        String value = str(o, field);
        if (!value.matches(pattern)) throw mismatch();
        return value;
    }
    KeyedOperation handedOut(String hash, long to) {
        var history = new HashMap<>(attempts);
        history.put(hash,to);
        return new KeyedOperation(identity,Map.copyOf(history));
    }
    static KeyedOperation fromCall(JsonNode call) {
        if (!call.hasNonNull("designated")) throw HostError.refusal("needsDesignated", "choose and persist a designated wallet input");
        var op = parse(call.path("operation"));
        var requested = new Identity(str(call,"designated"), str(call,"contract"), str(call,"method"),
                call.path("instance").asText(null), call.path("object").asText(null));
        if (!requested.equals(op.identity)) throw mismatch();
        return op;
    }
}
