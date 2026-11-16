package org.julclang.state.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0017 D3: joust new (I6, I9, I12). */
class ScaffoldTest {

    static final Versions RELEASE = new Versions("0.1.0-pre1-rc8");
    static final Versions SNAPSHOT = new Versions("0.1.0-pre1-abc1234-SNAPSHOT");

    @TempDir
    Path tmp;

    FakeSys sys() {
        return new FakeSys(tmp);
    }

    int run(FakeSys s, Versions v, String... args) {
        return new JoustMain(s, v).run(args);
    }

    static List<String> tree(Path dir) throws IOException {
        List<String> files = new ArrayList<>();
        try (Stream<Path> s = Files.walk(dir)) {
            s.filter(Files::isRegularFile).forEach(p -> files.add(dir.relativize(p).toString()));
        }
        return files.stream().sorted().toList();
    }

    @Test
    void gradleJavaByDefault() throws IOException {
        FakeSys s = sys();
        assertThat(run(s, RELEASE, "new", "My App")).isZero();
        Path d = tmp.resolve("My App");
        assertThat(tree(d)).containsExactly(".gitignore", "README.md", "build.gradle", "gradle.properties",
                "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
                "gradlew", "settings.gradle", "src/main/java/app/Counter.java", "src/main/java/app/CounterMain.java",
                "src/main/java/app/Devnet.java", "src/test/java/app/CounterTest.java");
        String build = Files.readString(d.resolve("build.gradle"));
        assertThat(build).contains("id 'org.julclang.joust' version '0.1.0-pre1-rc8'")
                .contains("mainClass = 'app.CounterMain'").contains(Scaffold.RELEASES).doesNotContain(Scaffold.SNAPSHOTS)
                .doesNotContain("@@");
        String settings = Files.readString(d.resolve("settings.gradle"));
        assertThat(settings).contains("rootProject.name = 'My-App'").contains("exclusiveContent")
                .contains("includeGroupByRegex 'org\\\\.julclang(\\\\..*)?'").doesNotContain("gradlePluginPortal")
                .doesNotContain("@@");
        assertThat(Files.readString(d.resolve("gradle/wrapper/gradle-wrapper.properties")))
                .contains("distributionSha256Sum=");
        assertThat(Files.getPosixFilePermissions(d.resolve("gradlew"))).contains(PosixFilePermission.OWNER_EXECUTE);
        assertThat(Files.readString(d.resolve(".gitignore"))).contains(".joust/");
        for (String f : tree(d)) {
            if (!f.endsWith(".jar")) assertThat(Files.readString(d.resolve(f))).as(f).doesNotContain("@@");
        }
        assertThat(s.outText()).contains("./gradlew build").doesNotContain("Note:");   // JDK 25 found (FakeSys)
    }

    @Test
    void gradleJscInAPackage() throws IOException {
        FakeSys s = sys();
        assertThat(run(s, RELEASE, "new", "app", "-l", "jsc", "-p", "org.example.counter")).isZero();
        Path d = tmp.resolve("app");
        assertThat(tree(d)).contains("src/main/jsc/org/example/counter/Counter.jsc", "src/main/jsc/org/example/counter/Counter.abi.json",
                "src/main/java/org/example/counter/CounterMain.java").doesNotContain("src/main/java/org/example/counter/Counter.java");
        // ADR-0018 D14: .jsc 2 (a preview), its ABI manifest beside it, for this package.
        assertThat(Files.readString(d.resolve("src/main/jsc/org/example/counter/Counter.jsc")))
                .startsWith("jsc 1\npackage org.example.counter\n");
        assertThat(Files.readString(d.resolve("src/main/jsc/org/example/counter/Counter.abi.json")))
                .contains("\"contract\": \"org.example.counter.Counter\"").doesNotContain("@@");
        assertThat(Files.readString(d.resolve("README.md"))).contains("acceptJscAbi").contains("a language preview").doesNotContain("@@");
        assertThat(s.outText()).contains("Note: .jsc is a language preview");
        assertThat(Files.readString(d.resolve("build.gradle"))).contains("mainClass = 'org.example.counter.CounterMain'");
        assertThat(Files.readString(d.resolve("README.md"))).contains("src/main/jsc/org/example/counter/Counter.jsc");
    }

    @Test
    void aSnapshotCliUsesTheSnapshotsRepository() throws IOException {
        assertThat(run(sys(), SNAPSHOT, "new", "snap")).isZero();
        assertThat(Files.readString(tmp.resolve("snap/build.gradle")))
                .contains("version '0.1.0-pre1-abc1234-SNAPSHOT'").contains(Scaffold.SNAPSHOTS);
        assertThat(Files.readString(tmp.resolve("snap/settings.gradle"))).contains(Scaffold.SNAPSHOTS);
    }

    @Test
    void ciRepositoryIsTheOnlySourceOfJoustsOwnModules() throws IOException {
        String repo = tmp.resolve("staging").toUri().toString();
        assertThat(run(sys(), SNAPSHOT, "new", "ci", "--joust-repository", repo)).isZero();
        String settings = Files.readString(tmp.resolve("ci/settings.gradle"));
        assertThat(settings).contains(repo).doesNotContain(Scaffold.RELEASES);
        String build = Files.readString(tmp.resolve("ci/build.gradle"));
        assertThat(build).contains("forRepository { maven { url = uri('" + repo + "') } }")
                .contains("includeModuleByRegex 'org\\\\.julclang', 'julc-state-.*'");
    }

    @Test
    void nothingIsWrittenUnlessEveryCheckPasses() throws IOException {
        Files.writeString(Files.createDirectories(tmp.resolve("full")).resolve("x"), "");
        for (String[] args : List.of(new String[]{"new"}, new String[]{"new", "full"}, new String[]{"new", "-x"},
                new String[]{"new", "a", "-t", "react"}, new String[]{"new", "a", "-l", "kotlin"},
                new String[]{"new", "a", "-p", "1app"}, new String[]{"new", "a", "-p", "app-x"},
                new String[]{"new", "a", "-p", "class"}, new String[]{"new", "a", "-p", "a..b"},
                new String[]{"new", "a", "-t", "node", "-p", "x"},
                new String[]{"new", "a", "--joust-repository", "https://evil.example/repo"},
                new String[]{"new", "a", "--joust-repository", "file:/x'+({->println('INJECTED')}())+'"},
                new String[]{"new", "a", "--joust-repository", "file:/x') } } maven { url = uri('https://evil"},
                new String[]{"new", "a", "--joust-repository", "file://host/share/repo"},
                new String[]{"new", "a", "-p", "_"}, new String[]{"new", "a", "-p", "a$b"},
                new String[]{"new", "a", "-l", "jsc", "-p", "app.$demo"}, new String[]{"new", "a", "-p", "org._"},
                new String[]{"new", "a", "-t", "node", "--create-joust", "create-joust@latest"},
                new String[]{"new", "a", "-t", "node", "--create-joust", "relative.tgz"},
                new String[]{"new", "a", "--create-joust", "create-joust@1.0.0"},
                new String[]{"new", "a", "--nope"})) {
            FakeSys s = sys();
            assertThat(run(s, RELEASE, args)).as(String.join(" ", args)).isEqualTo(JoustMain.USAGE);
            assertThat(s.runs).isEmpty();
        }
        assertThat(tmp.resolve("a")).doesNotExist();
        assertThat(tree(tmp.resolve("full"))).containsExactly("x");
    }

    // ---- npm templates (I12)

    Path tools() throws IOException {
        Path bin = Files.createDirectories(tmp.resolve("tools"));
        for (String t : List.of("npm", "node")) {
            Path p = Files.writeString(bin.resolve(t), "#!/bin/sh\n");
            Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        return bin;
    }

    /** A gzipped tar of {@code entries} (name -> content; null content = directory; "L:" prefix = symlink). */
    static byte[] tgz(java.util.Map<String, String> entries) throws IOException {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var gz = new java.util.zip.GZIPOutputStream(bytes)) {
            for (var e : entries.entrySet()) {
                byte[] data = e.getValue() == null || e.getValue().startsWith("L:") ? new byte[0]
                        : e.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] h = new byte[512];
                byte[] name = e.getKey().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                System.arraycopy(name, 0, h, 0, name.length);
                put(h, 100, String.format("%07o", 0644));
                put(h, 124, String.format("%011o", data.length));
                h[156] = (byte) (e.getValue() == null ? '5' : e.getValue().startsWith("L:") ? '2'
                        : e.getKey().startsWith("PAXG") ? 'g' : '0');
                if (e.getValue() != null && e.getValue().startsWith("L:")) put(h, 157, e.getValue().substring(2));
                put(h, 257, "ustar");
                gz.write(h);
                gz.write(data);
                gz.write(new byte[(512 - data.length % 512) % 512]);
            }
            gz.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    static void put(byte[] h, int off, String s) {
        byte[] b = s.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, off, b.length);
    }

    static java.util.Map<String, String> goodPackage() {
        var m = new java.util.LinkedHashMap<String, String>();
        m.put("package/package.json", "{\"name\": \"create-joust\", \"bin\": {\"create-joust\": \"bin/create-joust.js\"}}");
        m.put("package/bin/create-joust.js", "console.log('hi')");
        return m;
    }

    @Test
    void npmTemplatesFetchWithNpmAndRunWithTheResolvedNode() throws IOException {
        Path bin = tools();
        Path hostileTmp = Files.createDirectories(tmp.resolve("hostile-tmp"));
        FakeSys s = sys();
        s.cwd = Files.createDirectories(tmp.resolve("somewhere"));
        s.env.put("PATH", "." + java.io.File.pathSeparator + bin);
        s.env.put("TMPDIR", hostileTmp.toString());
        s.env.put("JOUST_DEFERRED", "/x");
        byte[] tarball = tgz(goodPackage());
        List<String> seen = new ArrayList<>();
        s.onRun = r -> {
            try {
                if (r.command().get(1).equals("pack")) {
                    seen.add(r.dir().toString());
                    seen.add(Files.readString(r.dir().resolve("package.json")));
                    seen.add(PosixFilePermissions.toString(Files.getPosixFilePermissions(r.dir())));
                    Files.write(r.dir().resolve("create-joust-0.1.0-pre1-rc8.tgz"), tarball);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        };
        assertThat(run(s, RELEASE, "new", "web-app", "-t", "web")).isZero();
        assertThat(s.runs).hasSize(2);
        Path private_ = Path.of(seen.get(0));
        assertThat(private_.getParent()).isEqualTo(hostileTmp);           // under $TMPDIR (S22)
        assertThat(seen.get(1)).contains("\"private\": true");          // its own package.json: npm stops there
        assertThat(seen.get(2)).isEqualTo("rwx------");
        FakeSys.Run pack = s.runs.get(0);
        assertThat(pack.command()).containsExactly(bin.resolve("npm").toString(), "pack", "--workspaces=false", "--loglevel=warn",
                "create-joust@0.1.0-pre1-rc8");
        assertThat(pack.command()).doesNotContain("--prefix");           // keeps the user's global npmrc (S21)
        assertThat(pack.env().get("PATH")).isEqualTo(bin.toString());
        assertThat(pack.env()).doesNotContainKey("JOUST_DEFERRED");
        FakeSys.Run create = s.runs.get(1);
        assertThat(create.command().get(0)).isEqualTo(bin.resolve("node").toString());
        assertThat(create.command().get(1)).endsWith(private_.getFileName() + "/x/package/bin/create-joust.js");
        assertThat(create.command().subList(2, 7)).containsExactly(s.cwd.resolve("web-app").toString(),
                "--template", "web", "--lang", "jsc");
        assertThat(create.dir()).isEqualTo(private_);
        assertThat(private_).doesNotExist();                              // removed afterwards
    }

    @Test
    void hostileTarballsAreRefused() throws IOException {
        Path bin = tools();
        for (var bad : List.of(java.util.Map.of("package/../../evil.js", "x"), java.util.Map.of("/etc/evil", "x"),
                java.util.Map.of("other/file", "x"), java.util.Map.of("package/link", "L:/etc/passwd"),
                java.util.Map.of("package/big", "a".repeat((int) Tarball.MAX_BYTES + 1)))) {
            var entries = new java.util.LinkedHashMap<>(goodPackage());
            entries.putAll(bad);
            Path tgzFile = Files.write(tmp.resolve("bad.tgz"), tgz(entries));
            FakeSys s = sys();
            s.env.put("PATH", bin.toString());
            assertThat(run(s, RELEASE, "new", "n", "-t", "node", "--create-joust", tgzFile.toString()))
                    .as(bad.keySet().toString()).isEqualTo(JoustMain.USAGE);
            assertThat(s.runs).as(bad.keySet().toString()).isEmpty();
            assertThat(s.errText()).contains("tarball");
        }
        assertThat(tmp.resolve("evil.js")).doesNotExist();
    }

    @Test
    void aSnapshotCliNeedsAnExplicitCreateJoust() throws IOException {
        Path bin = tools();
        FakeSys s = sys();
        s.env.put("PATH", bin.toString());
        assertThat(run(s, SNAPSHOT, "new", "n", "-t", "node")).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("development build").contains("--create-joust");
        assertThat(s.runs).isEmpty();

        FakeSys t = sys();
        t.env.put("PATH", bin.toString());
        Path tgzFile = Files.write(tmp.resolve("create-joust-1.tgz"), tgz(goodPackage()));
        assertThat(run(t, SNAPSHOT, "new", "n", "-t", "node", "-l", "java", "--create-joust", tgzFile.toString())).isZero();
        assertThat(t.runs).hasSize(1);                                    // a local tarball: no npm at all
        assertThat(t.runs.get(0).command().get(0)).isEqualTo(bin.resolve("node").toString());
        assertThat(t.runs.get(0).command()).contains("--lang", "java");
    }

    @Test
    void npmTemplatesNeedNode() {
        FakeSys s = sys();
        s.env.put("PATH", "");
        assertThat(run(s, RELEASE, "new", "n", "-t", "node")).isEqualTo(1);
        assertThat(s.errText()).contains("needs Node.js 22+").contains("npm create joust");
    }

    @Test
    void aMissingOrOldJdkIsPointedOut() {
        FakeSys s = sys();
        s.javaVersionOutput = "openjdk version \"21.0.2\" 2024-01-16";
        assertThat(run(s, RELEASE, "new", "old")).isZero();
        assertThat(s.outText()).contains("needs JDK 25 (found Java 21)");
        FakeSys n = sys();
        n.javaVersionOutput = null;
        assertThat(run(n, RELEASE, "new", "none")).isZero();
        assertThat(n.outText()).contains("no java found");
    }

    @Test
    void theVersionComesFromTheBuild() {
        assertThat(Versions.load().cli()).isEqualTo(System.getProperty("joust.version"));
        assertThat(Set.of(Scaffold.TEMPLATES.toArray())).containsExactlyInAnyOrder("gradle", "node", "web");
    }

    @Test
    void groovyStringsAreEscaped() {
        assertThat(Scaffold.groovy("a'b\\c")).isEqualTo("a\\'b\\\\c");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Scaffold.groovy("a\nb"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void tarballBudgets() throws IOException {
        Path bin = tools();
        // a directory entry with content, and pax records that add up beyond the stream budget, are refused
        var dirWithContent = new java.util.LinkedHashMap<>(goodPackage());
        dirWithContent.put("package/pad/", null);
        byte[] t = tgz(dirWithContent);
        // patch the size field of the directory entry (the third header) to a non-zero value
        var raw = new java.io.ByteArrayOutputStream();
        try (var in = new java.util.zip.GZIPInputStream(new java.io.ByteArrayInputStream(t))) {
            in.transferTo(raw);
        }
        byte[] tar = raw.toByteArray();
        int dirHeader = 512 * 4;   // package.json header + 1 data block, js header + 1 data block
        System.arraycopy(String.format("%011o", 512).getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, tar,
                dirHeader + 124, 11);
        Path bad = tmp.resolve("dir.tgz");
        try (var out = new java.util.zip.GZIPOutputStream(Files.newOutputStream(bad))) {
            out.write(tar);
        }
        FakeSys s = sys();
        s.env.put("PATH", bin.toString());
        assertThat(run(s, RELEASE, "new", "n", "-t", "node", "--create-joust", bad.toString())).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("directory entry with content");

        var many = new java.util.LinkedHashMap<>(goodPackage());
        for (int i = 0; i < 9; i++) many.put("package/f" + i, "a".repeat(600_000));   // 5.4 MB, each file under 1 MiB
        Path big = Files.write(tmp.resolve("many.tgz"), tgz(many));
        FakeSys m = sys();
        m.env.put("PATH", bin.toString());
        assertThat(run(m, RELEASE, "new", "m", "-t", "node", "--create-joust", big.toString())).isEqualTo(JoustMain.USAGE);
        assertThat(m.errText()).contains("tarball");
        assertThat(m.runs).isEmpty();
    }

    @Test
    void npmsJavaScriptEntryPointRunsUnderTheResolvedNode() throws IOException {
        Path lib = Files.createDirectories(tmp.resolve("lib/node_modules/npm/bin"));
        Path cli = Files.writeString(lib.resolve("npm-cli.js"), "#!/usr/bin/env node\n");
        Path bin = Files.createDirectories(tmp.resolve("bin2"));
        Path npm = Files.createSymbolicLink(bin.resolve("npm"), cli);
        Path node = bin.resolve("node");
        assertThat(Scaffold.packCommand(node, npm, "create-joust@1.0.0")).containsExactly(node.toString(),
                cli.toRealPath().toString(), "pack", "--workspaces=false", "--loglevel=warn", "create-joust@1.0.0");
    }

    @Test
    void metadataAloneCannotExceedTheStreamBudget() throws IOException {
        Path bin = tools();
        var entries = new java.util.LinkedHashMap<String, String>();
        for (int i = 0; i < 65; i++) entries.put("PAXG" + i, "x".repeat(64 * 1024));   // 65 global pax records, 4.2 MB
        entries.putAll(goodPackage());
        Path tgzFile = Files.write(tmp.resolve("pax.tgz"), tgz(entries));
        FakeSys s = sys();
        s.env.put("PATH", bin.toString());
        assertThat(run(s, RELEASE, "new", "p", "-t", "node", "--create-joust", tgzFile.toString())).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("decompresses to more than");
        assertThat(s.runs).isEmpty();
    }

    @Test
    void underscorePrefixedSegmentsAreJavaAndJsc() throws IOException {
        for (String l : List.of("java", "jsc")) {
            FakeSys s = sys();
            assertThat(run(s, RELEASE, "new", "u-" + l, "-l", l, "-p", "org._internal")).as(l).isZero();
        }
        assertThat(Files.readString(tmp.resolve("u-jsc/src/main/jsc/org/_internal/Counter.jsc")))
                .startsWith("jsc 1\npackage org._internal\n");
    }
}
