package org.julclang.state.cli;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/** This CLI's version: the effective version of the JOUST build that produced it (commit-qualified for snapshots). */
record Versions(String cli) {

    static Versions load() {
        Properties p = new Properties();
        try (InputStream in = Versions.class.getResourceAsStream("/META-INF/joust/cli.properties")) {
            if (in == null) throw new IllegalStateException("META-INF/joust/cli.properties is missing");
            p.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new Versions(p.getProperty("version"));
    }

    boolean snapshot() {
        return cli.endsWith("-SNAPSHOT");
    }
}
