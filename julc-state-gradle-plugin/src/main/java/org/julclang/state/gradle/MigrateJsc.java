package org.julclang.state.gradle;

import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.JavaExec;
import org.gradle.api.tasks.options.Option;

/**
 * {@code migrateJsc} (ADR-0018 D16): translates every {@code .jsc} v1 file of {@code src/main/jsc} to {@code .jsc} 2 with
 * {@code JscMain --migrate}. By default into {@code build/jsc-migrated/}, changing nothing in {@code src/main/jsc};
 * {@code ./gradlew migrateJsc --in-place} replaces each v1 file and keeps its text beside it as {@code Name.jsc.v1}.
 */
public abstract class MigrateJsc extends JavaExec {

    /** Replace the v1 files (their text kept as {@code Name.jsc.v1}) instead of writing to {@code build/jsc-migrated}. */
    @Input
    @Option(option = "in-place", description = "Replaces each v1 file, keeping its text beside it as Name.jsc.v1")
    public abstract Property<Boolean> getInPlace();

    public MigrateJsc() {
        getInPlace().convention(false);
    }
}
