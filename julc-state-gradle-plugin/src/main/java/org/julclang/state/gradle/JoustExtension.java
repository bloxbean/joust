package org.julclang.state.gradle;

import org.gradle.api.provider.Property;

/** {@code joust { }}: the plugin's one switch. */
public abstract class JoustExtension {

    /** Add {@code cardano-client-backend-blockfrost} (Yaci Store on DevKit, or Blockfrost). Default {@code true}. */
    public abstract Property<Boolean> getBlockfrost();
}
