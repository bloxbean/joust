package org.julclang.state.runtime.snapshot;

/**
 * A backend method a restricted backend does not serve (ADR-0022 D6): a chain read outside its vocabulary, a submit or
 * an evaluation. An {@link Error}, like {@link Need}, so no {@code catch (RuntimeException)} site in the runtime or CCL
 * can take it for an empty or failed answer and carry on.
 *
 * @param what the service and method, as {@code <service>.<method>} (for example {@code account.getAccountInformation})
 */
public final class Unsupported extends Error implements org.julclang.state.runtime.CallAbort {
    private final String what;

    public Unsupported(String what) {
        super("unsupported: " + what, null, false, false);
        this.what = what;
    }

    public String what() {
        return what;
    }
}
