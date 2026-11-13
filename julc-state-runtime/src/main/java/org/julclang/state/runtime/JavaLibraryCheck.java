package org.julclang.state.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-0018 D9 (M4b), downstream class loading: a contract built with {@code java:} libraries runs, on the JVM, the classes
 * compiled from the libraries' certified on-chain sources; the build records the SHA-256 of each one's class file
 * ({@code META-INF/joust/java-libraries.json}, beside the contract's validator). When a contract is loaded (its
 * {@link ContractSpec}), each such class, as the contract's own class loader resolves it, is checked: the bytes at the
 * code source that defined it must hash to the recorded value, or the contract refuses to run. An application jar that
 * ships a class of the same name first on the class path (the same constants, another body) is refused: the JVM-side
 * computation (transitions, views, prepared steps) would otherwise run code nobody certified. On-chain safety never
 * depends on this: the validator was compiled from the certified sources.
 */
public final class JavaLibraryCheck {

    private JavaLibraryCheck() {}

    static final String MANIFEST = "META-INF/joust/java-libraries.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Class<?>, Boolean> VERIFIED = new ConcurrentHashMap<>();

    /** Thrown when a library class's defining bytes are not the recorded ones. */
    public static final class Mismatch extends IllegalStateException {
        public Mismatch(String message) {
            super(message);
        }
    }

    /**
     * Checks every library class the manifest beside {@code validator} (in its code source) records; nothing when there is
     * none (a contract without {@code java:} libraries). Throws {@link Mismatch} when a class's defining bytes differ, or
     * cannot be read from where it was defined.
     */
    public static void verify(Class<?> validator) {
        if (validator == null || VERIFIED.containsKey(validator)) return;
        JsonNode manifest = manifest(validator);
        if (manifest != null) {
            var classes = manifest.path("classes");
            var names = classes.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                String want = classes.path(name).asText();
                Class<?> c;
                try {
                    c = Class.forName(name, false, validator.getClassLoader());
                } catch (ClassNotFoundException e) {
                    throw new Mismatch(name + ", a class of a java: library " + validator.getName() + " was built with, is not on the class path");
                }
                byte[] bytes = definingBytes(c);
                if (bytes == null) {
                    throw new Mismatch(name + " (a java: library class of " + validator.getName() + ") has no readable code source: its bytes"
                            + " cannot be checked against the build's, so the contract refuses to run (ADR-0018 D9)");
                }
                String got = sha256(bytes);
                if (!got.equals(want)) {
                    throw new Mismatch(name + " (a java: library class of " + validator.getName() + ") was loaded from " + location(c)
                            + " with other bytes than the build compiled from the library's certified sources (SHA-256 " + got + ", built "
                            + want + "): another jar supplies this class; the contract refuses to run (ADR-0018 D9)");
                }
            }
        }
        VERIFIED.put(validator, Boolean.TRUE);
    }

    /** The manifest in {@code validator}'s own code source, or null. */
    static JsonNode manifest(Class<?> validator) {
        URL where = location(validator);
        if (where == null) return null;
        try {
            byte[] text = read(where, MANIFEST);
            return text == null ? null : JSON.readTree(text);
        } catch (IOException e) {
            throw new Mismatch("the java: library manifest beside " + validator.getName() + " cannot be read: " + e.getMessage());
        }
    }

    static URL location(Class<?> c) {
        var pd = c.getProtectionDomain();
        return pd != null && pd.getCodeSource() != null ? pd.getCodeSource().getLocation() : null;
    }

    /** The class file of {@code c} at the code source that defined it. */
    static byte[] definingBytes(Class<?> c) {
        URL where = location(c);
        if (where == null) return null;
        try {
            return read(where, c.getName().replace('.', '/') + ".class");
        } catch (IOException e) {
            return null;
        }
    }

    /** An entry of a code source: a directory or a jar. */
    static byte[] read(URL codeSource, String entry) throws IOException {
        Path p;
        try {
            p = Path.of(codeSource.toURI());
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
        if (Files.isDirectory(p)) {
            Path f = p.resolve(entry);
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        }
        if (!Files.isRegularFile(p)) return null;
        try (var jar = new java.util.jar.JarFile(p.toFile())) {
            var e = jar.getJarEntry(entry);
            if (e == null) return null;
            try (InputStream in = jar.getInputStream(e)) {
                return in.readAllBytes();
            }
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
