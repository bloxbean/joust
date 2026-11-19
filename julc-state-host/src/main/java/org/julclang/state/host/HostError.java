package org.julclang.state.host;

import java.util.List;

/**
 * A failure whose kind and code are decided where it happens (ADR-0022 D9): {@code refusal}, {@code bad},
 * {@code unsupported} or {@code internal}, with a stable code from the protocol document.
 * The bootstrap can report one even when the runtime predates the host seams.
 */
final class HostError extends Error {

    enum Kind {
        REFUSAL("refusal", -32001), BAD("bad", -32602), UNSUPPORTED("unsupported", -32003), INTERNAL("internal", -32603);

        final String wire;
        final int rpcCode;

        Kind(String wire, int rpcCode) {
            this.wire = wire;
            this.rpcCode = rpcCode;
        }
    }

    final Kind kind;
    final String code;
    final List<Object> sites;
    final Long retryAtSlot;

    HostError(Kind kind, String code, String message) {
        this(kind, code, message, List.of(), null);
    }

    HostError(Kind kind, String code, String message, List<Object> sites, Long retryAtSlot) {
        super(message == null ? code : message, null, false, false);
        this.kind = kind;
        this.code = code;
        this.sites = sites == null ? List.of() : List.copyOf(sites);
        this.retryAtSlot = retryAtSlot;
    }

    static HostError bad(String code, String message) {
        return new HostError(Kind.BAD, code, message);
    }

    static HostError refusal(String code, String message) {
        return new HostError(Kind.REFUSAL, code, message);
    }

    static HostError unsupported(String code, String message) {
        return new HostError(Kind.UNSUPPORTED, code, message);
    }

    static HostError internal(String code, String message) {
        return new HostError(Kind.INTERNAL, code, message);
    }

    /** The JSON-RPC code for this error: the kind's, or LSP's RequestCancelled for {@code cancelled}. */
    int rpcCode() {
        return code.equals("cancelled") ? -32800 : kind.rpcCode;
    }
}
