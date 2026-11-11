package org.julclang.state.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/** The options of {@code joust new} (ADR-0017 D3). */
@Command(name = "new", description = "Create a JOUST project.")
final class NewOptions {

    @Parameters(index = "0", arity = "0..1", paramLabel = "<dir>", description = "The directory to create (new or empty).")
    String dir;

    @Option(names = {"-t", "--template"}, paramLabel = "<template>",
            description = "gradle (default): a Java/Gradle project; node: a Node script; web: a browser page.")
    String template;

    @Option(names = {"-l", "--lang"}, paramLabel = "<lang>",
            description = "The contract language: java (default for gradle) or jsc (default for node and web).")
    String lang;

    @Option(names = {"-p", "--package"}, paramLabel = "<package>",
            description = "The Java package of a Gradle project (default: app).")
    String pkg;

    @Option(names = "--create-joust", hidden = true, paramLabel = "<spec>",
            description = "create-joust@<exact version> or an absolute path to a .tgz (development builds, CI).")
    String createJoust;

    @Option(names = "--joust-repository", hidden = true, paramLabel = "<url>",
            description = "The exclusive source of JOUST's own modules in the project (CI).")
    String joustRepository;

    Scaffold.Request request() {
        return new Scaffold.Request(dir, template, lang, pkg, createJoust, joustRepository);
    }
}
