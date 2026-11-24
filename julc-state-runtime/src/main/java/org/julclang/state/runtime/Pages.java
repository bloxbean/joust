package org.julclang.state.runtime;

import com.bloxbean.cardano.client.api.exception.ApiException;
import com.bloxbean.cardano.client.api.model.Result;
import com.bloxbean.cardano.client.api.model.Utxo;
import org.julclang.state.runtime.snapshot.ReadFailed;

import java.util.List;

/**
 * Pages through an address's UTxOs. The end of the answer is an empty page, a short page, or a page that starts where
 * the previous one did (a provider that ignores the page number). A failed page, in a scoped call (a hosted one, ADR-0022
 * D6), is a failed read: {@link ReadFailed}, never the end, since absence would decide the build (#145 Codex open item 4).
 * A 404 is an address with no history, holding nothing (Blockfrost). Unscoped, a failed page ends the listing, as it
 * always did, unless the walk reads {@linkplain #toEnd to the listing's end} (#165).
 */
final class Pages {
    private final StateRuntime rt;
    private final String address;
    private final int count;
    private final int limit;
    private final boolean scoped;
    private int page;
    private String lastFirst;
    private boolean done;
    private java.util.function.BiFunction<Integer, String, String> failedPage;
    private String overflow;
    private boolean unanswered;

    /** Up to {@code limit} pages of {@code count} UTxOs at {@code address}. */
    Pages(StateRuntime rt, String address, int count, int limit) {
        this.rt = rt;
        this.address = address;
        this.count = count;
        this.limit = limit;
        this.scoped = rt.callScope() != null;
    }

    /**
     * Reads to the listing's end (#165 Codex rounds 5-7): unscoped, a failed first page ends the listing as an
     * {@linkplain #unanswered unanswered} one, a later failed page is a {@link JoustException} with
     * {@code failedPage(page, response)} (never the end), and asking for a page past the limit while the listing goes
     * on is one with {@code overflow}. Scoped, a failed page stays a {@link ReadFailed}.
     */
    Pages toEnd(java.util.function.BiFunction<Integer, String, String> failedPage, String overflow) {
        this.failedPage = failedPage;
        this.overflow = overflow;
        return this;
    }

    /** The first page failed (unscoped, {@linkplain #toEnd to the end}): nothing listed, or the provider did not answer. */
    boolean unanswered() {
        return unanswered;
    }

    /** The next page, or null at the end of the answer (or of the limit). */
    List<Utxo> next() throws ApiException {
        if (done) return null;
        if (page >= limit) {
            if (overflow != null) throw new JoustException(overflow);
            return null;
        }
        page++;
        Result<List<Utxo>> r;
        try {
            r = rt.backend().getUtxoService().getUtxos(address, count, page);
        } catch (ApiException | RuntimeException e) {
            // A provider that throws (an I/O failure) is a failed page too: scoped, never a partial answer (#145 Codex).
            if (scoped) throw new ReadFailed("utxos", address, TxRunner.rootMessage(e));
            throw e;
        }
        List<Utxo> list = answer(r);
        if (list == null || list.isEmpty()) {
            done = true;
            return null;
        }
        String first = Designation.ref(list.get(0));
        if (first.equals(lastFirst)) {
            done = true;
            return null;
        }
        lastFirst = first;
        if (list.size() < count) done = true;
        return list;
    }

    private List<Utxo> answer(Result<List<Utxo>> r) {
        if (r == null) {
            if (scoped) throw new ReadFailed("utxos", address, "no answer");
            return failed("no answer");
        }
        if (r.isSuccessful()) return r.getValue() == null ? List.of() : r.getValue();
        if (r.code() == 404) return List.of();
        if (scoped) throw new ReadFailed("utxos", address, r.getResponse());
        return failed(r.getResponse());
    }

    private List<Utxo> failed(String response) {
        if (failedPage == null) return null;
        if (page > 1) throw new JoustException(failedPage.apply(page, response));
        unanswered = true;
        return null;
    }
}
