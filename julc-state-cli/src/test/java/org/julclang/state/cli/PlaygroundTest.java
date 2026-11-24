package org.julclang.state.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** ADR-0024 D3, Q1: joust playground in a Gradle build runs this joust's own @julclang/joust from a per-user cache. */
class PlaygroundTest {

    static final Versions RELEASE = new Versions("0.1.0-pre1-rc8");
    static final Versions SNAPSHOT = new Versions("0.1.0-pre2-abc1234-SNAPSHOT");

    @TempDir
    Path tmp;
    Path bin, node, npm, home, project;

    @BeforeEach
    void tools() throws IOException {
        bin = Files.createDirectories(tmp.resolve("tools"));
        node = JoustMainTest.exe(bin.resolve("node"));
        npm = JoustMainTest.exe(bin.resolve("npm"));
        home = tmp.resolve("cache");
        project = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(project.resolve("settings.gradle"), "");
        Files.writeString(project.resolve("build.gradle"), "");
        JoustMainTest.exe(project.resolve("gradlew"));
    }

    FakeSys sys(Path cwd) {
        FakeSys s = new FakeSys(cwd);
        s.env.put("PATH", bin.toString());
        s.env.put(Playground.HOME_ENV, home.toString());
        return s;
    }

    /** npm's install, faked: writes the package into its working directory (the private install directory). */
    static int fakeInstall(FakeSys.Run r, String version) {
        if (!r.command().contains("install")) return 0;
        try {
            Path pkg = Files.createDirectories(r.dir().resolve("node_modules/@julclang/joust/bin"));
            Files.writeString(pkg.resolve("joust.js"), "");
            Files.writeString(pkg.getParent().resolve("package.json"), "{\"name\": \"@julclang/joust\", \"version\": \"" + version + "\"}");
            return 0;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void theFirstRunInstallsThisVersionOnceThenRunsTheCachedPackageInTheProject() throws IOException {
        FakeSys s = sys(project);
        s.onRun = r -> fakeInstall(r, "0.1.0-pre1-rc8");
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground", "--no-open", "--port", "0"})).isZero();
        assertThat(s.runs).hasSize(2);
        var install = s.runs.get(0);
        assertThat(install.command()).startsWith(npm.toString(), "install", "--workspaces=false")
                .contains("--ignore-scripts", "--save-exact").endsWith("@julclang/joust@0.1.0-pre1-rc8");
        assertThat(install.dir().getParent()).isEqualTo(home);
        assertThat(install.dir().getFileName().toString()).startsWith(".install-");
        assertThat(Files.exists(install.dir())).as("the private install directory is renamed into place").isFalse();
        Path cached = home.resolve("0.1.0-pre1-rc8");
        assertThat(cached.resolve(Playground.COMPLETE)).exists();
        var launch = s.runs.get(1);
        assertThat(launch.command()).containsExactly(node.toString(),
                Playground.bin(cached).toString(), "playground", "--no-open", "--port", "0");
        assertThat(launch.dir()).isEqualTo(project);
        // The second run uses the cache: nothing is installed.
        FakeSys again = sys(project);
        assertThat(new JoustMain(again, RELEASE).run(new String[]{"playground"})).isZero();
        assertThat(again.runs).hasSize(1);
        assertThat(again.runs.get(0).command().get(1)).isEqualTo(Playground.bin(cached).toString());
    }

    @Test
    void aSubdirectoryRunsInTheNearestGradleProjectDirectory() throws IOException {
        Path sub = Files.createDirectories(project.resolve("contracts"));
        Files.writeString(sub.resolve("build.gradle"), "");
        Path deeper = Files.createDirectories(sub.resolve("src/main"));
        FakeSys s = sys(deeper);
        s.onRun = r -> fakeInstall(r, "0.1.0-pre1-rc8");
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isZero();
        assertThat(s.runs.get(1).dir()).isEqualTo(sub);
    }

    @Test
    void nodeIsAStatedPrerequisite() throws IOException {
        Files.delete(node);
        FakeSys s = sys(project);
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("needs Node.js 22+");
        assertThat(s.runs).isEmpty();
    }

    @Test
    void anOldNodeIsRefusedBeforeAnything() {
        FakeSys s = sys(project);
        s.nodeVersionOutput = "v20.18.1\n";
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("needs Node.js 22+").contains("version 20");
        assertThat(s.runs).isEmpty();
        FakeSys unknown = sys(project);
        unknown.nodeVersionOutput = null;
        assertThat(new JoustMain(unknown, RELEASE).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(unknown.errText()).contains("cannot tell the version");
    }

    @Test
    void aDevelopmentBuildNeedsTarballsAndNeverFetchesAnotherVersion() {
        FakeSys s = sys(project);
        assertThat(new JoustMain(s, SNAPSHOT).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("development build").contains(Playground.TARBALLS_ENV);
        assertThat(s.runs).isEmpty();
    }

    @Test
    void tarballsMustBeAbsoluteTgzFiles() throws IOException {
        FakeSys s = sys(project);
        s.env.put(Playground.TARBALLS_ENV, "joust.tgz");
        assertThat(new JoustMain(s, SNAPSHOT).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("is not an absolute path to a .tgz file");
        Path a = Files.writeString(tmp.resolve("a.tgz"), "");
        Path b = Files.writeString(tmp.resolve("b.tgz"), "");
        FakeSys ok = sys(project);
        ok.env.put(Playground.TARBALLS_ENV, a + File.pathSeparator + b);
        ok.onRun = r -> fakeInstall(r, "0.1.0-pre1-snapshot");
        assertThat(new JoustMain(ok, SNAPSHOT).run(new String[]{"playground"})).isZero();
        assertThat(ok.runs.get(0).command()).endsWith(a.toString(), b.toString());
        assertThat(home.resolve("0.1.0-pre2-abc1234-SNAPSHOT").resolve(Playground.COMPLETE)).exists();
    }

    @Test
    void anotherVersionFromTheRegistryIsRefusedAndNotCached() {
        FakeSys s = sys(project);
        s.onRun = r -> fakeInstall(r, "9.9.9");
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("installed @julclang/joust 9.9.9, not 0.1.0-pre1-rc8");
        assertThat(home.resolve("0.1.0-pre1-rc8")).doesNotExist();
        assertThat(s.runs).hasSize(1);
    }

    @Test
    void aFailedInstallLeavesNothingBehind() throws IOException {
        FakeSys s = sys(project);
        s.onRun = r -> 1;
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isEqualTo(1);
        assertThat(s.errText()).contains("failed (1)");
        try (var left = Files.list(home)) {
            assertThat(left.toList()).isEmpty();
        }
    }

    @Test
    void aConcurrentInstallThatWonIsUsed() {
        FakeSys s = sys(project);
        Path cached = home.resolve("0.1.0-pre1-rc8");
        s.onRun = r -> {
            if (!r.command().contains("install")) return 0;
            fakeInstall(r, "0.1.0-pre1-rc8");
            // Meanwhile another joust playground completed the same install.
            try {
                Files.createDirectories(Playground.bin(cached).getParent());
                Files.writeString(Playground.bin(cached), "");
                Files.writeString(cached.resolve(Playground.COMPLETE), "0.1.0-pre1-rc8\n");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return 0;
        };
        assertThat(new JoustMain(s, RELEASE).run(new String[]{"playground"})).isZero();
        assertThat(s.runs.get(1).command().get(1)).isEqualTo(Playground.bin(cached).toString());
        try (var left = Files.list(home)) {
            assertThat(left.map(p -> p.getFileName().toString()).toList()).containsExactly("0.1.0-pre1-rc8");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void helpNamesThePlayground() {
        FakeSys s = sys(project);
        new JoustMain(s, RELEASE).run(new String[]{"help"});
        assertThat(s.outText()).contains("playground").contains("~/.joust/playground/<version>");
        FakeSys other = sys(project);
        assertThat(new JoustMain(other, RELEASE).run(new String[]{"deploy"})).isEqualTo(JoustMain.USAGE);
        FakeSys unknown = sys(project);
        new JoustMain(unknown, RELEASE).run(new String[]{"lint"});
        assertThat(unknown.errText()).contains("build, test, run and playground");
    }

    /** With a real node and npm: a local tarball installed into the cache with its integrity recorded, then run. */
    @Test
    void aLocalTarballIsInstalledByNpmAndRun() throws Exception {
        Optional<Path> realNode = Sys.system().which("node");
        Optional<Path> realNpm = Sys.system().which("npm");
        assumeTrue(realNode.isPresent() && realNpm.isPresent(), "needs node and npm on PATH");
        Path src = Files.createDirectories(tmp.resolve("pkg/bin"));
        Files.writeString(src.getParent().resolve("package.json"), "{\"name\": \"@julclang/joust\", \"version\": \"0.0.1-test\","
                + " \"type\": \"module\", \"bin\": {\"joust\": \"bin/joust.js\"}}");
        Files.writeString(src.resolve("joust.js"), "import { writeFileSync } from 'node:fs';\n"
                + "writeFileSync('ran.json', JSON.stringify({ args: process.argv.slice(2), cwd: process.cwd() }));\n");
        Path packs = Files.createDirectories(tmp.resolve("packs"));
        Process p = new ProcessBuilder(realNpm.get().toString(), "pack", "--ignore-scripts", "--pack-destination", packs.toString(),
                src.getParent().toString()).redirectErrorStream(true).redirectOutput(tmp.resolve("pack.log").toFile()).start();
        assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();
        assertThat(p.exitValue()).as(Files.readString(tmp.resolve("pack.log"))).isZero();
        Path tgz = packs.resolve("julclang-joust-0.0.1-test.tgz");
        assertThat(tgz).exists();

        FakeSys s = new FakeSys(project);
        s.env.putAll(System.getenv());
        s.env.put(Playground.HOME_ENV, home.toString());
        s.env.put(Playground.TARBALLS_ENV, tgz.toString());
        s.nodeVersionOutput = null;
        s.realIn = new File("/dev/null");
        s.realOut = tmp.resolve("out.log").toFile();
        s.realErr = tmp.resolve("err.log").toFile();
        // node --version through the real node (FakeSys.capture is canned)
        Process v = new ProcessBuilder(realNode.get().toString(), "--version").start();
        s.nodeVersionOutput = new String(v.getInputStream().readAllBytes());
        assumeTrue(s.nodeVersionOutput.matches("(?s)v(2[2-9]|[3-9]\\d).*"), "needs Node.js 22+");
        int code = new JoustMain(s, SNAPSHOT).run(new String[]{"playground", "--no-open"});
        assertThat(code).as(Files.readString(tmp.resolve("err.log"))).isZero();
        Path cached = home.resolve(SNAPSHOT.cli());
        assertThat(Files.readString(cached.resolve("package-lock.json"))).contains("\"integrity\": \"sha512-");
        assertThat(Files.readString(project.resolve("ran.json")))
                .contains("[\"playground\",\"--no-open\"]").contains(project.toRealPath().toString());
        assertThat(Files.getPosixFilePermissions(home)).isEqualTo(PosixFilePermissions.fromString("rwx------"));
    }
}
