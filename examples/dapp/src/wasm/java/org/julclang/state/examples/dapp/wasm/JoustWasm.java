package org.julclang.state.examples.dapp.wasm;

import org.graalvm.webimage.api.JS;
import org.graalvm.webimage.api.JSObject;
import org.graalvm.webimage.api.JSString;
import org.julclang.state.api.RequireException;
import org.julclang.state.examples.bank.BankPortable;
import org.julclang.state.examples.counter.CounterPortable;

import java.util.HexFormat;
import java.util.function.Function;

/**
 * The dApp's contracts in the browser (ADR-0013 D4, experimental): a GraalVM Web Image of the contracts' pure
 * transitions ({@code <Name>Portable}, generated with {@code -Ajoust.portable=true}). The same Java methods that run on
 * the JVM and, compiled by JuLC, on chain, here compute the next state in the page.
 * <p>
 * Installs {@code globalThis.joustWasm.step(contract, method, target, shared, args, ctx)}: hex CBOR in, and
 * {@code "ok:<hex CBOR of Constr 0 [next, payouts]>"}, {@code "require:<message>"} (the contract refused) or
 * {@code "error:<message>"} out. Then calls {@code globalThis.onJoustWasm(api)}.
 */
public final class JoustWasm {

    private JoustWasm() {
    }

    public static void main(String[] args) {
        install(argv -> JSString.of(step(text(argv, 0), text(argv, 1), text(argv, 2), text(argv, 3), text(argv, 4), text(argv, 5))),
                JSString.of(names(CounterPortable.METHODS)), JSString.of(names(BankPortable.METHODS)));
    }

    /** One transition; never throws (Java exceptions reach JavaScript only as opaque proxies). */
    static String step(String contract, String method, String target, String shared, String args, String ctx) {
        try {
            HexFormat hex = HexFormat.of();
            byte[] t = hex.parseHex(target);
            byte[] s = shared.isEmpty() ? new byte[0] : hex.parseHex(shared);
            byte[] a = hex.parseHex(args);
            byte[] c = ctx.isEmpty() ? new byte[0] : hex.parseHex(ctx);
            byte[] out = switch (contract) {
                case "Counter" -> CounterPortable.step(method, t, s, a, c);
                case "Bank" -> BankPortable.step(method, t, s, a, c);
                default -> throw new IllegalArgumentException("no contract " + contract);
            };
            return "ok:" + hex.formatHex(out);
        } catch (RequireException e) {
            return "require:" + e.getMessage();
        } catch (RuntimeException e) {
            // getMessage, not toString: string formatting of arbitrary objects would grow the image a lot.
            return "error:" + e.getClass().getName() + ": " + e.getMessage();
        } catch (StackOverflowError e) {   // deeply nested CBOR: the decoder recurses
            return "error:nested too deeply";
        }
    }

    private static String names(String[] methods) {
        var sb = new StringBuilder();
        for (String m : methods) sb.append(sb.isEmpty() ? "" : ",").append(m);
        return sb.toString();
    }

    private static String text(JSObject argv, int i) {
        Object v = argv.get(i);
        return v instanceof JSString s ? s.asString() : "";
    }

    @JS(args = {"fn", "counter", "bank"}, value = """
            const api = {
              contracts: { Counter: String(counter).split(','), Bank: String(bank).split(',') },
              step: (contract, method, target, shared, args, ctx) =>
                String(fn([String(contract), String(method), String(target), String(shared ?? ''), String(args), String(ctx ?? '')])),
            };
            // Fixed once installed: a later script cannot swap the contracts' code (an earlier one could patch anything).
            Object.freeze(api.contracts);
            Object.defineProperty(globalThis, 'joustWasm', { value: Object.freeze(api), writable: false, configurable: false });
            if (typeof globalThis.onJoustWasm === 'function') setTimeout(() => globalThis.onJoustWasm(api), 0);
            """)
    private static native void install(Function<JSObject, JSString> fn, JSString counter, JSString bank);
}
