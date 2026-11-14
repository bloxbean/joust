package org.julclang.state.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLConnection;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ADR-0018 D9 (M4b; review r2), downstream class loading: a contract built with {@code java:} libraries runs, on the JVM,
 * the classes compiled from the libraries' certified on-chain sources. The build records the SHA-256 of each one's class
 * file in a resource per contract ({@code META-INF/joust/java-libraries/<contract>.json}), beside the contract's client.
 * The client, generated knowing which libraries the contract was built with, calls {@link #verify} when it loads, and the
 * contract refuses to run unless:
 * <ul>
 *   <li>the client, the validator and the contract class come from one code source (a byte-identical copy of the validator
 *       ahead on the class path moves no check; security S1);</li>
 *   <li>that code source holds the contract's record (none is a refusal, never "nothing to check": review F2, S1), and the
 *       record lists every library the client names;</li>
 *   <li>each recorded class, as the client's loader resolves it, is defined by bytes that hash to the recorded value, read
 *       through the code source's URL (directories, jars, {@code jar:} and {@code nested:} locations alike; review F1);</li>
 *   <li>no recorded class has a versioned variant in a multi-release jar, which a class loader may define instead of the
 *       base entry (Codex C4).</li>
 * </ul>
 * An application jar that ships a class of the same name first on the class path (the same constants, another body) is
 * refused: the JVM-side computation (transitions, views, prepared steps) would otherwise run code nobody certified.
 * On-chain safety never depends on this: the validator was compiled from the certified sources. A contract built without
 * {@code java:} libraries never calls this.
 */
public final class JavaLibraryCheck {

    private JavaLibraryCheck() {}

    /** Where a contract's record of its library classes is, in its client's code source. */
    static String record(String contract) {
        return "META-INF/joust/java-libraries/" + contract + ".json";
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<Class<?>, Boolean> VERIFIED = new ConcurrentHashMap<>();

    /** Thrown when a library class's defining bytes are not the recorded ones, or cannot be checked. */
    public static final class Mismatch extends IllegalStateException {
        public Mismatch(String message) {
            super(message);
        }
    }

    /**
     * Checks the {@code java:} library classes of {@code contract} (its fully qualified name), built with {@code libraries}
     * (their class names), for its {@code client} and {@code validator}. Throws {@link Mismatch} on any failure.
     */
    public static void verify(Class<?> client, Class<?> validator, String contract, List<String> libraries) {
        if (VERIFIED.containsKey(client)) return;
        URL home = location(client);
        if (home == null) throw new Mismatch(contract + "'s client has no code source: its java: library classes cannot be checked (ADR-0018 D9)");
        Class<?> contractClass;
        try {
            contractClass = Class.forName(contract, false, client.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new Mismatch(contract + " is not on the class path beside its client (ADR-0018 D9)");
        }
        for (Class<?> c : List.of(validator, contractClass)) {
            if (!same(home, location(c))) {
                throw new Mismatch(c.getName() + " was loaded from " + location(c) + ", not from " + home + " where its client is: the contract's"
                        + " classes come from one place, or its java: library check means nothing; the contract refuses to run (ADR-0018 D9)");
            }
        }
        byte[] text;
        try {
            text = read(home, record(contract));
        } catch (IOException e) {
            throw new Mismatch("the record of " + contract + "'s java: library classes cannot be read at " + home + ": " + e);
        }
        if (text == null) {
            throw new Mismatch(contract + " was built with the java: libraries " + libraries + ", but " + record(contract) + " is not beside its"
                    + " client at " + home + " (a jar that dropped it?): its library classes cannot be checked, so it refuses to run (ADR-0018 D9)");
        }
        JsonNode classes;
        try {
            classes = JSON.readTree(text).path("classes");
        } catch (IOException e) {
            throw new Mismatch("the record of " + contract + "'s java: library classes is not JSON: " + e.getMessage());
        }
        for (String lib : libraries) {
            if (!classes.has(lib)) throw new Mismatch(contract + "'s record of its java: library classes does not list " + lib + " (ADR-0018 D9)");
        }
        var names = classes.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            check(name, classes.path(name).asText(), client.getClassLoader(), contract);
        }
        VERIFIED.put(client, Boolean.TRUE);
    }

    private static void check(String name, String want, ClassLoader loader, String contract) {
        Class<?> c;
        try {
            c = Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            throw new Mismatch(name + ", a class of a java: library " + contract + " was built with, is not on the class path (ADR-0018 D9)");
        }
        URL where = location(c);
        String entry = name.replace('.', '/') + ".class";
        byte[] bytes;
        try {
            bytes = where == null ? null : read(where, entry);
            if (bytes != null && versioned(where, entry)) {
                throw new Mismatch(name + " (a java: library class of " + contract + ") has a versioned variant in the multi-release jar " + where
                        + ": a class loader may define that one instead of the recorded bytes; the contract refuses to run (ADR-0018 D9)");
            }
        } catch (IOException e) {
            bytes = null;
        }
        if (bytes == null) {
            throw new Mismatch(name + " (a java: library class of " + contract + ") has no readable code source (" + where + "): its bytes"
                    + " cannot be checked against the build's, so the contract refuses to run (ADR-0018 D9)");
        }
        String got = sha256(bytes);
        if (!got.equals(want)) {
            throw new Mismatch(name + " (a java: library class of " + contract + ") was loaded from " + where
                    + " with other bytes than the build compiled from the library's certified sources (SHA-256 " + got + ", built "
                    + want + "): another jar supplies this class; the contract refuses to run (ADR-0018 D9)");
        }
    }

    static URL location(Class<?> c) {
        var pd = c.getProtectionDomain();
        return pd != null && pd.getCodeSource() != null ? pd.getCodeSource().getLocation() : null;
    }

    static boolean same(URL a, URL b) {
        return a != null && b != null && a.toString().equals(b.toString());
    }

    /**
     * The root of a code source, as a URL under which an entry's path is appended: a directory or a {@code jar:…!/} location
     * as it is, a jar file (or a {@code nested:} jar) wrapped as {@code jar:<location>!/}.
     */
    static String root(URL codeSource) {
        String s = codeSource.toString();
        return s.endsWith("/") ? s : "jar:" + s + "!/";
    }

    /** An entry of a code source, through its URL handler (no file system needed); null when there is none. */
    static byte[] read(URL codeSource, String entry) throws IOException {
        URL url;
        try {
            url = URI.create(root(codeSource) + entry).toURL();
        } catch (IllegalArgumentException | java.net.MalformedURLException e) {
            throw new IOException("no URL for " + entry + " under " + codeSource + ": " + e.getMessage(), e);
        }
        URLConnection conn = url.openConnection();
        conn.setUseCaches(false);
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        } catch (FileNotFoundException | java.nio.file.NoSuchFileException e) {
            return null;
        }
    }

    /** Whether a jar code source holds a {@code META-INF/versions/<n>/} variant of {@code entry} for this runtime. */
    static boolean versioned(URL codeSource, String entry) throws IOException {
        if (root(codeSource).startsWith("file:")) return false;   // a directory: no multi-release
        for (int n = 9; n <= Runtime.version().feature(); n++) {
            if (read(codeSource, "META-INF/versions/" + n + "/" + entry) != null) return true;
        }
        return false;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
