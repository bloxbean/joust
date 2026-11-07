package org.julclang.state.processor.gen;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.regex.Pattern;

/** The JOUST map verifier's script hash, read from its compiled output on the processor's class path (ADR-0002 D9). */
final class MapVerifierInfo {

    private MapVerifierInfo() {}

    static String hashHex() {
        return hashHex("MapVerifier");
    }

    /** The hash of a JOUST verifier compiled into julc-state-verifier ({@code MapVerifier}, {@code OutputVerifier}). */
    static String hashHex(String verifier) {
        String RESOURCE = "META-INF/plutus/" + verifier + ".plutus.json";
        try (InputStream in = MapVerifierInfo.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException(RESOURCE + " is not on the JOUST processor's class path");
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var m = Pattern.compile("\"hash\"\\s*:\\s*\"([0-9a-fA-F]{56})\"").matcher(json);
            if (!m.find()) throw new IllegalStateException(RESOURCE + " has no script hash");
            return m.group(1).toLowerCase();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + RESOURCE, e);
        }
    }

    /** The hash as a JuLC {@code byte[]} literal. */
    static String hashLiteral() {
        return hashLiteral("MapVerifier");
    }

    static String hashLiteral(String verifier) {
        byte[] b = HexFormat.of().parseHex(hashHex(verifier));
        var sb = new StringBuilder("new byte[]{");
        for (int i = 0; i < b.length; i++) sb.append(i == 0 ? "" : ", ").append(b[i]);
        return sb.append('}').toString();
    }
}
