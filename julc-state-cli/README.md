# joust (native)

The native JOUST command line (ADR-0017). One command for a new JOUST project, Java or npm:

```sh
joust new my-app                    # a Java/Gradle project: Counter.java, its tests, a DevKit demo
joust new my-app -l jsc             # the same with a .jsc contract
joust new my-app -t node            # what `npm create joust` writes (needs Node.js 22+)
cd my-app && joust build            # in a Gradle project: ./gradlew build; in an npm project: the project's own joust
```

Gradle projects need **JDK 25**. They apply the JOUST Gradle plugin, `org.julclang.joust`, which adds the JOUST and
JuLC processors, the on-chain sources, `.jsc` support and the test settings at one version.

## One `joust`

The npm package `@julclang/joust` installs a command called `joust` too. Both behave the same way:

- `joust new`, `joust version` and `joust help` (and any `<command> --help`) are answered by the binary you ran and
  never run anything from the project;
- every other command in an npm project goes to **the project's own** `@julclang/joust` (from `node_modules`), at the
  project's root, exactly as `npm run` would; the native `joust` never has its own version of an npm command;
- in a Gradle build, `joust build`, `joust test` and `joust run` run the build's `./gradlew`.

Install the native `joust` globally (Homebrew or this archive) and `@julclang/joust` per project. `joust --version`
says which one ran, and in an npm project which `@julclang/joust` the project uses.

The project walk does not enter directories owned by another user; list any you trust in `JOUST_SAFE_DIRS`. In a
container or CI job running as another user than the checkout's owner (root over a mounted workspace, say), set
`JOUST_SAFE_DIRS=<checkout>` or run as the owner.

## Install

```sh
brew install bloxbean/tap/joust
```

or unpack the archive for your platform from the [releases](https://github.com/bloxbean/joust/releases) and put
`joust` on your `PATH`. Check a downloaded archive first:

```sh
shasum -a 256 -c joust-<version>-SHA256SUMS.txt --ignore-missing
gh attestation verify joust-<version>-<platform>.zip -R bloxbean/joust
xattr -d com.apple.quarantine joust      # macOS only: the binary is not notarized
```

Platforms: macOS arm64, Linux x86_64 and arm64. On Windows, use `npm create joust` or WSL.

Docs: https://github.com/bloxbean/joust (Quick start: Java & Gradle).
