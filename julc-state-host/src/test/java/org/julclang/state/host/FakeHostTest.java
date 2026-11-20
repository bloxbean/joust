package org.julclang.state.host;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.julclang.state.host.conformance.FakeHost;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.julclang.state.host.Wire.*;

class FakeHostTest {
    private Process start(String session) throws Exception {
        var argv = new java.util.ArrayList<>(java.util.List.of(System.getProperty("joust.host.java"),"-cp",
                System.getProperty("joust.host.clientKitClassPath"),FakeHost.class.getName(),"--stdio"));
        if (!session.equals("bank")) argv.add(RecordedSessionsTest.SESSIONS.resolve(session+".json").toString());
        return new ProcessBuilder(argv).start();
    }

    @Test void classifierCarriesTheShippedInputs() throws Exception {
        try (var jar = new java.util.jar.JarFile(System.getProperty("joust.host.clientKit"))) {
            for (String group : java.util.List.of("verifier","sessions")) {
                try (var files = java.nio.file.Files.list(HostVectorsTest.DIRECTORY.resolve(group))) {
                    for (var f : files.toList()) {
                        var entry = jar.getJarEntry(group+"/"+f.getFileName());
                        assertNotNull(entry,f::toString);
                        try (var in = jar.getInputStream(entry)) {
                            assertArrayEquals(java.nio.file.Files.readAllBytes(f),in.readAllBytes());
                        }
                    }
                }
            }
            assertNull(jar.getJarEntry("org/julclang/state/host/Session.class"));
        }
    }

    @Test @org.junit.jupiter.api.Timeout(30) void replaysBothSessionsWithClientChosenIds() throws Exception {
        for (String name : java.util.List.of("bank","keyed")) {
            var p = start(name);
            try {
                var reader = new Frames.Reader(p.getInputStream());
                var writer = new Frames.Writer(p.getOutputStream());
                for (var f : JSON.readTree(RecordedSessionsTest.SESSIONS.resolve(name+".json").toFile()).required("frames")) {
                    if (f.has("client")) {
                        var msg = (ObjectNode)f.required("client");
                        if (msg.has("method") && msg.has("id")) msg.put("id","client-"+msg.get("id").asText());
                        if (msg.path("method").asText().equals("$/cancelRequest"))
                            msg.withObject("/params").put("id","client-"+msg.at("/params/id").asText());
                        assertTrue(writer.write(JSON.writeValueAsBytes(msg)));
                    } else {
                        var expected = (ObjectNode)f.required("host");
                        if (!expected.has("method")) expected.put("id","client-"+expected.get("id").asText());
                        assertEquals(expected, JSON.readTree(reader.next()));
                    }
                }
                assertTrue(p.waitFor(3,TimeUnit.SECONDS));
                assertEquals(0,p.exitValue());
            } finally { p.destroyForcibly(); }
        }
    }

    @Test void rejectsAnAnswerToAnotherReverseRequest() throws Exception {
        var p = start("bank");
        try {
            var writer = new Frames.Writer(p.getOutputStream());
            var reader = new Frames.Reader(p.getInputStream());
            for (var f : JSON.readTree(RecordedSessionsTest.SESSIONS.resolve("bank.json").toFile()).required("frames")) {
                if (f.has("host")) {
                    assertEquals(f.required("host"),JSON.readTree(reader.next()));
                    continue;
                }
                var msg = (ObjectNode)f.required("client");
                if (msg.has("result")) {
                    msg.put("id","unknown.reverse");
                    assertTrue(writer.write(JSON.writeValueAsBytes(msg)));
                    break;
                }
                assertTrue(writer.write(JSON.writeValueAsBytes(msg)));
            }
            assertTrue(p.waitFor(3,TimeUnit.SECONDS));
            assertNotEquals(0,p.exitValue());
            assertTrue(new String(p.getErrorStream().readAllBytes(),StandardCharsets.UTF_8).contains("reverse answer"));
        } finally { p.destroyForcibly(); }
    }

    @Test void rejectsWrongParamsFramingAndPartialEof() throws Exception {
        var first = JSON.readTree(RecordedSessionsTest.SESSIONS.resolve("keyed.json").toFile()).at("/frames/0/client");
        ((ObjectNode)first.at("/params/network")).put("networkId",1);
        byte[] json = JSON.writeValueAsBytes(first);
        for (String bad : java.util.List.of("Content-Length: "+json.length+"\r\n\r\n"+new String(json, StandardCharsets.UTF_8),
                "Content-Length: 4194305\r\n\r\n","X-extra: 1\r\n\r\n","Content-Length: 7\r\n\r\n{")) {
            var p = start("keyed");
            try {
                p.getOutputStream().write(bad.getBytes(StandardCharsets.UTF_8));
                p.getOutputStream().close();
                assertTrue(p.waitFor(4,TimeUnit.SECONDS));
                assertNotEquals(0,p.exitValue());
                assertEquals(0,p.getInputStream().readAllBytes().length);
                assertFalse(new String(p.getErrorStream().readAllBytes(),StandardCharsets.UTF_8).isBlank());
            } finally { p.destroyForcibly(); }
        }
    }
}
