package org.julclang.state.host;

import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.CallAbort;
import org.julclang.state.runtime.JoustException;
import org.julclang.state.runtime.JscMessages;
import org.julclang.state.runtime.NotYetException;
import org.julclang.state.runtime.snapshot.Need;
import org.julclang.state.runtime.snapshot.ReadFailed;
import org.julclang.state.runtime.snapshot.Unsupported;
import org.julclang.state.serve.BadRequest;
import org.julclang.state.serve.JsonValues;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ADR-0022 D9 at the host's boundary: a call's failure becomes one {@link HostError}, by the <b>types</b> in its cause
 * chain, never by its message text. The serving adapter passes the runtime's {@link CallAbort} errors on as themselves
 * (F2), so the host's own errors and the backend's read failures arrive intact:
 * <ul>
 *   <li>{@link HostError}: as decided where it was raised (a read check, a cancellation, the evaluator);</li>
 *   <li>{@link ReadFailed}: {@code internal}, {@code readFailed}, with the client's message;</li>
 *   <li>{@link Unsupported}: {@code unsupported}, {@code read:<what>};</li>
 *   <li>{@link Need}: never expected on the host (its backend asks the client); {@code internal}, {@code need};</li>
 *   <li>a contract's refusal ({@link RequireException}, with a {@code .jsc} contract's sites as values): {@code refusal},
 *       {@code require}; a keyed cooldown ({@link NotYetException}): {@code refusal}, {@code cooldown}, {@code retryAtSlot};</li>
 *   <li>a bad request or argument ({@link BadRequest}, {@link JsonValues.BadValue}): {@code bad}, {@code request};</li>
 *   <li>the runtime's own refusal ({@link JoustException}): {@code refusal}, {@code runtime};</li>
 *   <li>a {@link StackOverflowError}: {@code internal}, {@code tooDeep}; anything else {@code internal}, {@code internal}.</li>
 * </ul>
 */
final class Errors {

    private Errors() {}

    static HostError classify(Throwable t) {
        // Control events and read failures first: they decide the call's end wherever they surface (D10: a control
        // code wins over readFailed, which the latch already ensures).
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof HostError h) return h;
            if (c instanceof org.julclang.state.runtime.ReservedInputException) return HostError.refusal("pending", c.getMessage());
            if (c instanceof StackOverflowError) return HostError.internal("tooDeep", "nested too deeply");
            if (c instanceof ReadFailed r) return HostError.internal("readFailed", r.reason());
            if (c instanceof Unsupported u) return HostError.unsupported("read:" + u.what(), u.getMessage());
            if (c instanceof Need n) return HostError.internal("need", "the host's backend asked for a snapshot read: " + n.getMessage());
            if (c instanceof CallAbort && c.getCause() == null) return HostError.internal("internal", c.getClass().getSimpleName());
            if (c.getCause() == c) break;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof NotYetException n) {
                return new HostError(HostError.Kind.REFUSAL, "cooldown", n.getMessage(), List.of(), n.prepareAt());
            }
            if (c instanceof JscMessages.Refusal r) {
                return new HostError(HostError.Kind.REFUSAL, "require", r.getMessage(), sites(r), null);
            }
            if (c instanceof org.julclang.state.runtime.CallScope.Refusal r) return HostError.refusal(r.code(), r.getMessage());
            if (c instanceof RequireException r) return HostError.refusal("require", r.getMessage());
            if (c instanceof BadRequest || c instanceof JsonValues.BadValue) return HostError.bad("request", c.getMessage());
            if (c.getCause() == c) break;
        }
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof JoustException j) return HostError.refusal("runtime", j.getMessage());
            if (c.getCause() == c) break;
        }
        return HostError.internal("internal", t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + shortened(t.getMessage())));
    }

    private static List<Object> sites(JscMessages.Refusal r) {
        var out = new ArrayList<Object>();
        for (JscMessages.Site s : r.sites()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("file", s.file());
            m.put("line", s.line());
            m.put("col", s.col());
            m.put("kind", s.kind());
            m.put("in", s.in());
            m.put("what", s.what());
            out.add(m);
        }
        return out;
    }

    private static String shortened(String s) {
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
