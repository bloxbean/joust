package org.julclang.state.runtime;

/** Test access to the runtime's cooldown refusal (package-private in julc-state-runtime). */
public final class KeyedObjectsAccess {
    private KeyedObjectsAccess() {}

    public static NotYetException notYet(String what, long prepareAt) {
        return KeyedObjects.notYet(what, prepareAt);
    }
}
