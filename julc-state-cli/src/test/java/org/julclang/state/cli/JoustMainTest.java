package org.julclang.state.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-0017 D4, D9: detection, delegation, informational commands (I1–I5, I11). */
class JoustMainTest {

    static final Versions RELEASE = new Versions("0.1.0-pre1-rc8");

    @TempDir
    Path tmp;
    Path bin;
    Path node;

    @BeforeEach
    void tools() throws IOException {
        bin = Files.createDirectories(tmp.resolve("tools"));
        node = exe(bin.resolve("node"));
    }

    static Path exe(Path p) throws IOException {
        Files.writeString(p, "#!/bin/sh\nexit 0\n");
        Files.setPosixFilePermissions(p, PosixFilePermissions.fromString("rwxr-xr-x"));
        return p;
    }

    FakeSys sys(Path cwd) {
        FakeSys s = new FakeSys(cwd);
        s.env.put("PATH", ".:" + File.pathSeparator + "relative/bin" + File.pathSeparator + bin);
        return s;
    }

    int run(FakeSys s, String... args) {
        return new JoustMain(s, RELEASE).run(args);
    }

    /** An npm project with its own @julclang/joust; returns the root. */
    Path npmProject(String name, String version) throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Files.writeString(root.resolve("package.json"), "{\"dependencies\": {\"@julclang/joust\": \"" + version + "\"}}");
        Path pkg = Files.createDirectories(root.resolve("node_modules/@julclang/joust/bin"));
        Files.writeString(pkg.getParent().resolve("package.json"),
                "{\"name\": \"@julclang/joust\", \"version\": \"" + version + "\", \"bin\": {\"joust\": \"bin/joust.js\"}}");
        Files.writeString(pkg.resolve("joust.js"), "process.exit(1)\n");
        Files.createDirectories(root.resolve("contracts/app"));
        return root;
    }

    // ---- D9: informational commands never run anything (I4)

    @Test
    void informationalCommandsRunNothingInAnNpmProject() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        for (String[] args : List.of(new String[]{}, new String[]{"--version"}, new String[]{"-v"}, new String[]{"-V"},
                new String[]{"version"}, new String[]{"help"}, new String[]{"-h"}, new String[]{"--help"},
                new String[]{"help", "deploy"}, new String[]{"deploy", "--help"}, new String[]{"build", "-h"},
                new String[]{"deploy", "--network", "preview", "--help"})) {
            FakeSys s = sys(root.resolve("contracts"));
            assertThat(run(s, args)).as(String.join(" ", args)).isZero();
            assertThat(s.runs).as(String.join(" ", args)).isEmpty();
        }
    }

    @Test
    void helpAfterDoubleDashIsAnArgument() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        FakeSys s = sys(root);
        run(s, "build", "--", "--help");
        assertThat(s.runs).hasSize(1);
        assertThat(s.runs.get(0).command()).endsWith("build", "--", "--help");
    }

    @Test
    void versionNamesBothCLIs() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        FakeSys s = sys(root);
        run(s, "--version");
        assertThat(s.outText()).isEqualTo("joust 0.1.0-pre1-rc8 (native)\nproject: @julclang/joust 0.1.0-pre1-rc7 (npm)\n");
    }

    @Test
    void versionPrintsOnlyAValidVersion() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        Files.writeString(root.resolve("node_modules/@julclang/joust/package.json"),
                "{\"version\": \"1.0.0\\u001b]0;pwned\\u0007\", \"bin\": {\"joust\": \"bin/joust.js\"}}");
        FakeSys s = sys(root);
        run(s, "version");
        assertThat(s.outText()).contains("project: @julclang/joust unknown (npm)").doesNotContain("\u001b");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aFifoOrHugeManifestIsAbsentAndNeverBlocks() throws Exception {
        Path fifo = Files.createDirectories(tmp.resolve("fifo"));
        assumeTrue(new ProcessBuilder("mkfifo", fifo.resolve("package.json").toString()).start().waitFor() == 0);
        FakeSys s = sys(fifo);
        assertThat(run(s, "--version")).isZero();
        assertThat(s.outText()).isEqualTo("joust 0.1.0-pre1-rc8 (native)\n");
        assertThat(run(s, "build")).isEqualTo(JoustMain.USAGE);   // no project: the FIFO was not read

        Path huge = Files.createDirectories(tmp.resolve("huge"));
        Files.writeString(huge.resolve("package.json"), "{\"dependencies\": {\"@julclang/joust\": \"1\"}, \"x\": \""
                + "a".repeat(Json.MAX_BYTES) + "\"}");
        FakeSys h = sys(huge);
        assertThat(run(h, "build")).isEqualTo(JoustMain.USAGE);
        assertThat(h.runs).isEmpty();
    }

    @Test
    void unknownOptionIsAUsageError() {
        FakeSys s = sys(tmp);
        assertThat(run(s, "--nope")).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("native joust");
    }

    // ---- D4: npm projects (I1, I2, I3)

    @Test
    void npmCommandsGoToTheProjectsOwnCliAtTheRoot() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        FakeSys s = sys(Files.createDirectories(root.resolve("contracts/app")));
        assertThat(run(s, "deploy", "--network", "preview", "rel/path")).isZero();
        assertThat(s.runs).hasSize(1);
        FakeSys.Run r = s.runs.get(0);
        assertThat(r.command()).containsExactly(node.toString(),
                root.resolve("node_modules/@julclang/joust/bin/joust.js").toRealPath().toString(),
                "deploy", "--network", "preview", "rel/path");
        assertThat(r.dir()).isEqualTo(root);
        assertThat(r.env().get("PATH")).isEqualTo(bin.toString());   // relative and empty entries dropped (I3)
    }

    @Test
    void theExitCodeIsTheDelegates() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        FakeSys s = sys(root);
        s.onRun = r -> 7;
        assertThat(run(s, "build")).isEqualTo(7);
    }

    @Test
    void noInstalledPackageMeansNpmInstallNeverAFallback() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("bare"));
        Files.writeString(root.resolve("joust.config.json"), "{}");
        FakeSys s = sys(root);
        assertThat(run(s, "build")).isEqualTo(1);
        assertThat(s.runs).isEmpty();
        assertThat(s.errText()).contains("is an npm project; run npm install first");
    }

    @Test
    void hoistedPackagesAreFound() throws IOException {
        Path ws = npmProject("ws", "0.1.0-pre1-rc7");
        Path member = Files.createDirectories(ws.resolve("packages/member"));
        Files.writeString(member.resolve("joust.config.json"), "{}");
        FakeSys s = sys(member);
        run(s, "build");
        assertThat(s.runs.get(0).command().get(1)).startsWith(ws.resolve("node_modules").toRealPath().toString());
        assertThat(s.runs.get(0).dir()).isEqualTo(member);
    }

    @Test
    void aBinSymlinkedOutOfItsPackageIsRefused() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        Path js = root.resolve("node_modules/@julclang/joust/bin/joust.js");
        Files.delete(js);
        Files.createSymbolicLink(js, Files.writeString(tmp.resolve("evil.js"), "boom"));
        FakeSys s = sys(root);
        assertThat(run(s, "build")).isEqualTo(1);
        assertThat(s.runs).isEmpty();
    }

    @Test
    void nodeComesOnlyFromAbsolutePathEntries() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        exe(Files.createDirectories(root.resolve("relative/bin")).resolve("node"));
        exe(root.resolve("node"));
        FakeSys s = sys(root);
        s.env.put("PATH", ".:relative/bin");                 // only relative entries: no node
        assertThat(run(s, "build")).isEqualTo(1);
        assertThat(s.runs).isEmpty();
        assertThat(s.errText()).contains("needs Node.js");
    }

    // ---- D4: detection (I5)

    @Test
    void npmBeatsGradleInOneDirectoryAndTheNearestMarkerWins() throws IOException {
        Path root = npmProject("app", "0.1.0-pre1-rc7");
        Files.writeString(root.resolve("build.gradle"), "");
        FakeSys s = sys(root);
        run(s, "build");
        assertThat(s.runs.get(0).command().get(0)).isEqualTo(node.toString());

        Path gradleAbove = Files.createDirectories(tmp.resolve("g"));
        Files.writeString(gradleAbove.resolve("settings.gradle"), "");
        Path nested = npmProject("g/web", "0.1.0-pre1-rc7");
        FakeSys n = sys(nested);
        run(n, "build");
        assertThat(n.runs.get(0).dir()).isEqualTo(nested);
    }

    @Test
    void aGradleSubprojectRunsTheBuildsWrapperFromItsOwnDirectory() throws IOException {
        Path build = Files.createDirectories(tmp.resolve("multi"));
        Files.writeString(build.resolve("settings.gradle"), "include 'sub'");
        Path gradlew = exe(build.resolve("gradlew"));
        Path sub = Files.createDirectories(build.resolve("sub"));
        Files.writeString(sub.resolve("build.gradle"), "");
        FakeSys s = sys(sub);
        assertThat(run(s, "build", "--info")).isZero();
        assertThat(s.runs.get(0).command()).containsExactly(gradlew.toString(), "build", "--info");
        assertThat(s.runs.get(0).dir()).isEqualTo(sub);
    }

    @Test
    void gradleBuildsExplainNpmCommandsAndNeedAWrapper() throws IOException {
        Path build = Files.createDirectories(tmp.resolve("nowrapper"));
        Files.writeString(build.resolve("settings.gradle"), "");
        FakeSys s = sys(build);
        assertThat(run(s, "deploy")).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("is a command of npm projects");
        assertThat(run(s, "clean")).isEqualTo(JoustMain.USAGE);
        assertThat(run(s, "test")).isEqualTo(1);
        assertThat(s.errText()).contains("no executable Gradle wrapper").contains("never runs a gradle from PATH");
        assertThat(s.runs).isEmpty();
    }

    @Test
    void anotherUsersDirectoryIsNotEnteredUnlessTrusted() throws IOException {
        Path foreign = npmProject("shared", "0.1.0-pre1-rc7");
        Path mine = Files.createDirectories(foreign.resolve("mine"));
        FakeSys s = sys(mine);
        s.owners = p -> Optional.of(p.startsWith(mine) ? "me" : "mallory");
        assertThat(run(s, "build")).isEqualTo(JoustMain.USAGE);
        assertThat(s.runs).isEmpty();
        assertThat(s.errText()).contains("did not look in " + foreign).contains("JOUST_SAFE_DIRS");

        FakeSys t = sys(mine);
        t.owners = s.owners;
        t.env.put("JOUST_SAFE_DIRS", foreign.toString());
        run(t, "build");
        assertThat(t.runs).hasSize(1);
    }

    @Test
    void noProjectIsExplained() {
        FakeSys s = sys(tmp);
        assertThat(run(s, "build")).isEqualTo(JoustMain.USAGE);
        assertThat(s.errText()).contains("needs a project").contains("joust new");
    }

    // ---- I2 with real processes: argv, cwd, stdin, both streams and the exit code

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void delegationIsFaithful() throws Exception {
        Optional<Path> realNode = Sys.system().which("node");
        assumeTrue(realNode.isPresent(), "node on PATH");
        Path root = npmProject("real", "0.1.0-pre1-rc7");
        Files.writeString(root.resolve("node_modules/@julclang/joust/bin/joust.js"), """
                const fs = require('fs');
                const input = fs.readFileSync(0, 'utf8');
                console.log(JSON.stringify({ argv: process.argv.slice(2), cwd: process.cwd(), input,
                  deferred: process.env.JOUST_DEFERRED ?? null }));
                console.error('to stderr');
                process.exit(7);
                """);
        FakeSys s = new FakeSys(Files.createDirectories(root.resolve("contracts/app")));
        s.env.put("PATH", realNode.get().getParent().toString());
        s.env.put("JOUST_DEFERRED", "/elsewhere");
        s.realIn = Files.writeString(tmp.resolve("in"), "stdin text").toFile();
        s.realOut = tmp.resolve("out").toFile();
        s.realErr = tmp.resolve("err").toFile();
        int code = new JoustMain(s, RELEASE).run(new String[]{"deploy", "a b", "--x=1", "ünï"});
        assertThat(code).isEqualTo(7);
        assertThat(Files.readString(s.realOut.toPath()).trim()).isEqualTo(
                "{\"argv\":[\"deploy\",\"a b\",\"--x=1\",\"ünï\"],\"cwd\":\"" + root.toRealPath()
                        + "\",\"input\":\"stdin text\",\"deferred\":null}");
        assertThat(Files.readString(s.realErr.toPath())).isEqualTo("to stderr\n");
    }

    @Test
    void malformedOrNumericManifestFieldsAreNotMarkersOrBins() throws IOException {
        Path d = Files.createDirectories(tmp.resolve("num"));
        Files.writeString(d.resolve("package.json"), "{\"dependencies\": {\"@julclang/joust\": +}}");
        Files.writeString(d.resolve("settings.gradle"), "");
        exe(d.resolve("gradlew"));
        FakeSys s = sys(d);
        run(s, "build");
        assertThat(s.runs.get(0).command().get(0)).endsWith("gradlew");   // Gradle, not npm: the JSON is malformed

        Path root = npmProject("numbin", "0.1.0-pre1-rc7");
        Files.writeString(root.resolve("node_modules/@julclang/joust/package.json"), "{\"bin\": {\"joust\": 0}}");
        Files.writeString(root.resolve("node_modules/@julclang/joust/0"), "x");
        FakeSys n = sys(root);
        assertThat(run(n, "build")).isEqualTo(1);
        assertThat(n.runs).isEmpty();
        assertThat(Json.parse("[1, -2.5e3, 0]")).isEqualTo(List.of(new java.math.BigDecimal("1"),
                new java.math.BigDecimal("-2.5e3"), new java.math.BigDecimal("0")));
        for (String bad : List.of("+1", "01", "1.", ".5", "1e", "--1")) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> Json.parse(bad)).as(bad)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void theFilesystemRootIsNeverAProject() {
        FakeSys s = sys(Path.of("/"));
        assertThat(new Locator(s).locate().kind()).isEqualTo(Locator.Kind.NONE);
        assertThat(Locator.isRoot(Path.of("/"))).isTrue();
    }

    @Test
    void uidsAndSafeDirsByRealPath() throws IOException {
        Path foreign = npmProject("shared2", "0.1.0-pre1-rc7");
        Path mine = Files.createDirectories(foreign.resolve("mine"));
        FakeSys t = sys(mine);
        t.owners = p -> Optional.of(p.startsWith(mine) ? "me" : "1001");
        Path link = Files.createSymbolicLink(tmp.resolve("alias"), foreign);
        t.env.put("JOUST_SAFE_DIRS", link.toString());           // listed through a symlink: still trusted
        run(t, "build");
        assertThat(t.runs).hasSize(1);
        assertThat(Sys.system().user()).matches("[0-9]*");        // a uid (empty where there is none)
    }

    @Test
    void helpForAProjectOnlyCommandPointsToTheProjectsCli() throws IOException {
        Path root = npmProject("hint", "0.1.0-pre1-rc7");
        FakeSys s = sys(root);
        assertThat(run(s, "frobnicate", "--help")).isZero();
        assertThat(s.outText()).contains("npx joust frobnicate --help");
        assertThat(s.runs).isEmpty();
    }
}
