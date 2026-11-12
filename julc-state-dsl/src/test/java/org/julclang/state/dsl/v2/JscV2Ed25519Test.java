package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1b review F2, S2: the Java reference interpreter's Ed25519 ({@link Ed25519}) answers the 783 edge vectors as the
 * ledger's libsodium 1.0.18 recorded them (js/jsc-interpreter/test/fixtures/ed25519-libsodium.json, where the oracle
 * and generator are named), as the JavaScript interpreter's does in its npm test.
 */
class JscV2Ed25519Test {

    @Test
    @SuppressWarnings("unchecked")
    void everyRecordedLibsodiumAnswer() throws Exception {
        var doc = (Map<String, Object>) AbiCheck.Json.parse(Files.readString(Path.of("../js/jsc-interpreter/test/fixtures/ed25519-libsodium.json")));
        var hex = HexFormat.of();
        var wrong = new ArrayList<String>();
        int n = 0;
        for (Object o : (List<Object>) doc.get("vectors")) {
            var v = (Map<String, Object>) o;
            boolean got = Ed25519.verify(hex.parseHex((String) v.get("key")), hex.parseHex((String) v.get("msg")), hex.parseHex((String) v.get("sig")));
            if (got != (Boolean) v.get("valid")) wrong.add((String) v.get("label"));
            n++;
        }
        assertEquals(783, n);
        assertEquals(List.of(), wrong);
    }
}
