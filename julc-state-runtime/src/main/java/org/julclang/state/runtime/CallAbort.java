package org.julclang.state.runtime;

/**
 * Marks an {@link Error} that ends a call and must reach whoever runs the call with its type intact (ADR-0022 D6, D9):
 * a chain read the call lacks ({@link org.julclang.state.runtime.snapshot.Need}), a read that failed
 * ({@link org.julclang.state.runtime.snapshot.ReadFailed}) or that the backend does not serve
 * ({@link org.julclang.state.runtime.snapshot.Unsupported}), or a host's own control event (a cancellation, a deadline).
 * <p>
 * Implementations are {@code Error}s, so the runtime's and CCL's {@code catch (RuntimeException)} and
 * {@code catch (Exception)} sites cannot swallow them; code that catches {@code Throwable} (a keyed operation's series,
 * an adapter unwrapping reflection) passes them on as they are, or, where an attempt was already handed out and may have
 * landed, as the cause of the pending outcome.
 */
public interface CallAbort {
}
