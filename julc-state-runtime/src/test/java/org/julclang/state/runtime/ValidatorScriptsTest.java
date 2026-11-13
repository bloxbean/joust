package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c reviews S1/F1, C5, S7: a validator's script is the one beside its class, never another validator's of the
 * same simple name; when its own location can be read it is the only source; when it cannot, a class-path copy is taken
 * only when it can be told apart. Cases A–E are the security review's (r2) table.
 */
class ValidatorScriptsTest {

    static final String RESOURCE = "META-INF/plutus/VaultValidator.plutus.json";
    static final String PORT = "org.x.dsl.VaultValidator", JAVA = "org.x.vault.VaultValidator";

    static String json(String cbor) {
        return "{ \"type\": \"PlutusScriptV3\", \"description\": \"VaultValidator\", \"cborHex\": \"" + cbor + "\" }";
    }

    /** A classes directory holding the script (when {@code cbor} is not null) and the given class files. */
    static Path dir(Path root, String name, String cbor, String... classFiles) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        if (cbor != null) {
            Path r = dir.resolve(RESOURCE);
            Files.createDirectories(r.getParent());
            Files.writeString(r, json(cbor));
        }
        for (String c : classFiles) {
            Path f = dir.resolve(c);
            Files.createDirectories(f.getParent());
            Files.write(f, new byte[]{(byte) 0xCA, (byte) 0xFE});
        }
        return dir;
    }

    static Path jar(Path root, String name, String cbor, String... classFiles) throws Exception {
        Path jar = root.resolve(name);
        try (var out = new JarOutputStream(new FileOutputStream(jar.toFile()))) {
            if (cbor != null) {
                out.putNextEntry(new JarEntry(RESOURCE));
                out.write(json(cbor).getBytes());
            }
            for (String c : classFiles) {
                out.putNextEntry(new JarEntry(c));
                out.write(new byte[]{1});
            }
        }
        return jar;
    }

    static String classFile(String fqcn) {
        return fqcn.replace('.', '/') + ".class";
    }

    static String cbor(String json) {
        var m = java.util.regex.Pattern.compile("\"cborHex\": \"([0-9a-f]+)\"").matcher(json);
        assertTrue(m.find(), json);
        return m.group(1);
    }

    static URL url(Path p) throws Exception {
        return p.toUri().toURL();
    }

    /** A URL no JDK handler can open as a container (a Spring Boot nested jar), whose content this handler serves. */
    static URL nested(String spec, String content) throws Exception {
        return new URL(null, spec, new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL u) {
                return new URLConnection(u) {
                    @Override
                    public void connect() {
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(content.getBytes());
                    }
                };
            }
        });
    }

    /** A loader whose copies of the script are exactly {@code copies}. */
    static ClassLoader copies(URL... copies) {
        return new ClassLoader(null) {
            @Override
            public Enumeration<URL> getResources(String name) {
                return Collections.enumeration(name.equals(RESOURCE) ? List.of(copies) : List.of());
            }
        };
    }

    static void refused(String fragment, Runnable r) {
        var e = assertThrows(JoustException.class, r::run);
        assertTrue(e.getMessage().contains(fragment), e.getMessage());
    }

    @Test
    void theCopyBesideTheClass_winsOverTheFirstOnTheClassPath(@TempDir Path tmp) throws Exception {
        Path javaDir = dir(tmp, "java", "aa01", classFile(JAVA));
        Path port = dir(tmp, "port", "bb02", classFile(PORT));
        // The class path lists the Java example first: a lookup by name would give its script to both.
        try (var loader = new URLClassLoader(new URL[]{url(javaDir), url(port)}, null)) {
            assertEquals(2, Collections.list(loader.getResources(RESOURCE)).size());
            assertEquals("bb02", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", url(port), loader)));
            assertEquals("aa01", cbor(ValidatorScripts.resolve(JAVA, "VaultValidator", url(javaDir), loader)));
        }
    }

    @Test
    void jarAndJarUrlCodeSources_areReadBeside(@TempDir Path tmp) throws Exception {
        Path jar = jar(tmp, "port.jar", "cc03", classFile(PORT));
        Path other = dir(tmp, "java", "aa01", classFile(JAVA));
        try (var loader = new URLClassLoader(new URL[]{url(other), url(jar)}, null)) {
            assertEquals("cc03", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", url(jar), loader)));
            // review S7: a jar: location is read beside too (jar:file:…!/), not through the class path.
            URL jarUrl = new URL("jar:" + url(jar) + "!/");
            assertEquals("cc03", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", jarUrl, loader)));
        }
    }

    @Test
    void aReadableLocationWithoutTheScript_isRefused(@TempDir Path tmp) throws Exception {
        Path foreign = dir(tmp, "java", "aa01", classFile(JAVA));
        try (var loader = new URLClassLoader(new URL[]{url(foreign)}, null)) {
            // B: a readable jar with the class and no script; one foreign copy elsewhere. Refused, not loaded.
            Path b = jar(tmp, "b.jar", null, classFile(PORT));
            refused("holds no META-INF/plutus/VaultValidator.plutus.json", () -> ValidatorScripts.resolve(PORT, "VaultValidator", u(b), loader));
            // B': the same with a classes directory.
            Path b2 = dir(tmp, "b2", null, classFile(PORT));
            refused("holds no", () -> ValidatorScripts.resolve(PORT, "VaultValidator", u(b2), loader));
        }
    }

    @Test
    void twoClassesOfTheName_areRefused_whereverTheScriptIs(@TempDir Path tmp) throws Exception {
        // A2: a flat (shaded) jar holding both classes and one script.
        Path shaded = jar(tmp, "shaded.jar", "dd04", classFile(JAVA), classFile(PORT));
        try (var loader = new URLClassLoader(new URL[]{url(shaded)}, null)) {
            refused("holds 2 classes named VaultValidator", () -> ValidatorScripts.resolve(PORT, "VaultValidator", u(shaded), loader));
        }
        // Codex C5: classes and resources in separate directories, the classes directory holding both validators and
        // the resources directory their one (overwritten) script. Refused, though the script is not beside the class.
        Path classes = dir(tmp, "classes", null, classFile(JAVA), classFile(PORT));
        Path resources = dir(tmp, "resources", "aa01");
        try (var loader = new URLClassLoader(new URL[]{url(classes), url(resources)}, null)) {
            refused("holds 2 classes named VaultValidator", () -> ValidatorScripts.resolve(PORT, "VaultValidator", u(classes), loader));
            refused("holds 2 classes named VaultValidator", () -> ValidatorScripts.resolve(JAVA, "VaultValidator", u(classes), loader));
        }
    }

    @Test
    void unreadableLocations_takeOnlyACopyThatCanBeToldApart(@TempDir Path tmp) throws Exception {
        URL nestedOwn = nested("jar:nested:/app.jar/!BOOT-INF/lib/dsl.jar!/" + RESOURCE, json("ee05"));
        URL nestedForeign = nested("jar:nested:/app.jar/!BOOT-INF/lib/vault.jar!/" + RESOURCE, json("aa01"));
        URL nestedLocation = nested("jar:nested:/app.jar/!BOOT-INF/lib/dsl.jar!/", "");
        Path foreignDir = dir(tmp, "java", "aa01", classFile(JAVA));
        Path ownDir = dir(tmp, "own", "bb02", classFile(PORT));
        URL foreignCopy = url(foreignDir.resolve(RESOURCE)), ownCopy = url(ownDir.resolve(RESOURCE));
        // C: a nested location, two copies: refused.
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", nestedLocation, copies(nestedOwn, nestedForeign)));
        // C': a nested location, only a foreign copy in a readable directory (beside another validator's class): refused.
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", nestedLocation, copies(foreignCopy)));
        // C'': a nested location and its own copy only (unreadable as a container): the documented fallback loads it.
        assertEquals("ee05", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", nestedLocation, copies(nestedOwn))));
        // Issue #73 (security S9): a lone unreadable copy that is not under the class's own location (another nested
        // jar's, say the Java vault's) is refused, not loaded as this validator's script.
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", nestedLocation, copies(nestedForeign)));
        URL nestedPrefix = nested("jar:nested:/app.jar/!BOOT-INF/lib/dsl.jar!/x/" + RESOURCE, json("ee06"));
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", nestedLocation, copies(nestedPrefix)));
        // ...and without a location to compare with, a lone unreadable copy is refused too.
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", null, copies(nestedOwn)));
        // A GraalVM (Web) Image: a placeholder location and the image's one resource: copy, taken unverified there only
        // (#80 review S1; the image builds' checkImageScripts stands behind it).
        URL image = nested("resource:/2!/" + RESOURCE, json("ab07"));
        URL placeholder = new URL("file:/this-is-not-a-real-file.js");
        assertEquals("ab07", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", placeholder, copies(image), true)));
        assertEquals("ab07", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", null, copies(image), true)));
        URL image2 = nested("resource:/3!/" + RESOURCE, json("ab08"));
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", placeholder, copies(image, image2), true));
        // Outside an image a resource: URL is never taken, whatever the location (S1's attack 2).
        for (URL w : new URL[]{null, placeholder, new URL("jar:file:/app.jar!/BOOT-INF/lib/a.jar!/")}) {
            refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", w, copies(image), false));
        }
        assertFalse(ValidatorScripts.inImage(), "a JVM is not an image");
        // Inside one, only the image's exact form: not a resource: URL that names another path, nor another resource.
        for (String spec : new String[]{"resource:/somewhere/else/b.jar/" + RESOURCE, "resource:/2!/x/" + RESOURCE, "resource:2!/" + RESOURCE,
                "resource:/2!/META-INF/plutus/Other.plutus.json"}) {
            URL odd = nested(spec, json("ab09"));
            refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", placeholder, copies(odd), true));
        }
        // A location written without its trailing slash still matches its own copy.
        URL noSlash = nested("jar:nested:/app.jar/!BOOT-INF/lib/dsl.jar!", "");
        assertEquals("ee05", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", noSlash, copies(nestedOwn))));
        // D: a jrt: location, two copies: refused.
        URL jrt = new URL("jrt:/some.module");
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", jrt, copies(nestedOwn, nestedForeign)));
        // ...unless exactly one copy sits beside this validator's own class: then that one.
        assertEquals("bb02", cbor(ValidatorScripts.resolve(PORT, "VaultValidator", jrt, copies(foreignCopy, ownCopy))));
        // E: no code source, one foreign copy (beside another validator's class): refused.
        refused("cannot be told apart", () -> ValidatorScripts.resolve(PORT, "VaultValidator", null, copies(foreignCopy)));
        // No copy at all.
        refused("no compiled script", () -> ValidatorScripts.resolve(PORT, "VaultValidator", null, copies()));
    }

    static URL u(Path p) {
        try {
            return p.toUri().toURL();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
