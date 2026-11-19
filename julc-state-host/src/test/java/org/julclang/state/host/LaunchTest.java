package org.julclang.state.host;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.*;
import static org.julclang.state.host.Wire.*;
import static org.julclang.state.host.Client.*;
import static org.junit.jupiter.api.Assertions.*;

class LaunchTest {
    @TempDir Path root;
    private static String hash(String text) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }
    @Test void orderedUtf8FingerprintAndSnapshotBoundary() throws Exception {
        var a = Files.createDirectory(root.resolve("zero"));
        var b = Files.createDirectory(root.resolve("one"));
        Files.writeString(a.resolve("😀"), "late");
        Files.writeString(a.resolve("\ue000"), "early");
        Files.writeString(b.resolve("A"), "next");
        String lines = hash("early") + " 0 dir \ue000\n" + hash("late") + " 0 dir 😀\n" + hash("next") + " 1 dir A\n";
        assertEquals(hash(lines), Launch.scan(root, List.of(a,b)).fingerprint());
        assertNotEquals(hash(lines), Launch.scan(root, List.of(b,a)).fingerprint());
        assertEquals("stageMismatch", assertThrows(HostError.class, () -> Launch.scan(a,List.of(b))).code);
    }
    @Test void overlappingResourcesFollowTheFingerprintedOrder() throws Exception {
        String resource = "META-INF/joust/example.Counter.json";
        var entries = new ArrayList<Path>();
        for (String data : List.of("first", "second")) {
            Path jar = root.resolve(data + ".jar");
            entries.add(jar);
            try (var out = new JarOutputStream(Files.newOutputStream(jar))) {
                out.putNextEntry(new JarEntry(resource));
                out.write(data.getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        var first = Launch.scan(root, entries);
        var second = Launch.scan(root, entries.reversed());
        assertEquals(List.of(resource), first.descriptions());
        assertEquals(first.descriptions(), second.descriptions());
        assertNotEquals(first.fingerprint(), second.fingerprint());
        for (var order : List.of(entries, entries.reversed())) {
            var urls = new java.net.URL[]{order.get(0).toUri().toURL(), order.get(1).toUri().toURL()};
            try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
                 var in = loader.getResourceAsStream(resource)) {
                assertEquals(order.getFirst().getFileName().toString(), new String(in.readAllBytes(), StandardCharsets.UTF_8) + ".jar");
            }
        }
    }
    @Test void jarClassPathRefusedAtInitialize() throws Exception {
        var m = new Manifest();
        m.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        m.getMainAttributes().put(Attributes.Name.CLASS_PATH, "other.jar");
        Path jar = root.resolve("consumer.jar");
        try (var ignored = new JarOutputStream(Files.newOutputStream(jar), m)) {}
        var p = init();
        p.withObject("/build").put("snapshot", root.toString());
        try (var c = new Client(new Handshake(List.of(jar)))) {
            var reply = c.rpc("initialize", p);
            error("bad", "stageMismatch", reply);
            assertTrue(reply.at("/error/message").asText().contains("manifest Class-Path"));
        }
    }
    @Test void versionIsReadFromActualRuntime() throws Exception {
        var f = root.resolve("RuntimeVersion.java");
        Files.writeString(f, """
            package org.julclang.state.runtime;
            class StateRuntime {}
            public class RuntimeVersion { public static final String VERSION = "other"; }
            """);
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",root.toString(),f.toString()));
        try (var loader = new URLClassLoader(new java.net.URL[]{root.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            assertEquals("runtimeVersion", assertThrows(HostError.class, () -> Launch.runtimeVersion(loader)).code);
        }
    }
    @Test void ownedCallsAreAdvertised() throws Exception {
        Path bank = Path.of(org.julclang.state.examples.bank.BankClient.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        try (var c = new Client(new Handshake(List.of(bank)))) {
            String name = success(c.rpc("initialize", init(bank))).at("/contracts/0/name").asText();
            var calls = success(c.rpc("describe", obj().put("contract", name))).required("calls");
            var seen = new HashSet<String>();
            for (var method : calls) {
                String kind = method.required("kind").asText();
                if (!kind.startsWith("owned")) continue;
                seen.add(kind);
                assertTrue(method.required("served").booleanValue());
            }
            assertEquals(Set.of("ownedAction", "ownedInit", "ownedDestroy"), seen);
        }
    }
    @Test void methodPoliciesConstrainThePlainCallMatrix() {
        var spec = org.julclang.state.examples.counter.CounterClient.SPEC;
        var c = new Contracts.Contract("Counter", obj(), spec, null, "", null);
        assertNull(c.code("action", obj()));
        assertEquals("keyed", c.code("keyedRemove", obj()));
        assertNull(c.code("view", obj()));
        var m = obj();
        m.putObject("policies").putObject("quorum").put("threshold", 2);
        assertNull(c.code("action", m));
        m.withObject("/policies").putArray("authorizedBy").add("owner");
        assertEquals("authorizedBy", c.code("action", m));
        for (String policy : List.of("batched", "into", "admits", "receives")) {
            m = obj();
            m.putObject("policies").put(policy, true);
            assertEquals("unverifiable", c.code("action", m), policy);
        }
        assertEquals("unverifiable", c.code("init", obj().put("needsService", true)));
    }
    @Test void negotiationOptInDigestAndIdentity() throws Exception {
        try (var c = session()) {
            var p = init();
            p.withObject("/build").put("digest", "wrong");
            error("bad", "stageMismatch", c.rpc("initialize",p));
            p = init();
            p.putArray("protocol").add(2);
            error("unsupported","protocol",c.rpc("initialize",p));
            p.withArray("/protocol").add(1);
            p.withObject("/network").put("networkId",1);
            error("bad","network",c.rpc("initialize",p));
            p.withObject("/network").put("mainnet",true);
            var result = success(c.rpc("initialize",p));
            assertEquals(1,result.get("protocol").asInt());
            assertEquals(p.at("/build/digest"), result.get("fingerprint"));
            assertEquals(p.get("network"),result.get("network"));
            assertTrue(result.at("/versions/build").isNull());
            assertEquals(HostVersion.RUNTIME,result.at("/versions/engine/joust").asText());
            var desc = success(c.rpc("describe",obj().put("contract",result.at("/contracts/0/name").asText())));
            assertEquals("Counter",desc.at("/description/x-joust/contract").asText());
            assertTrue(desc.get("messages").isNull());
            assertTrue(desc.get("calls").findValues("served").stream().allMatch(v -> v.booleanValue()));
            assertEquals(List.of("views", "objects", "quorum"), JSON.convertValue(result.required("capabilities"), List.class));
            success(c.rpc("shutdown",obj()));
            error("bad","request",c.rpc("describe",obj()));
        }
    }
}
