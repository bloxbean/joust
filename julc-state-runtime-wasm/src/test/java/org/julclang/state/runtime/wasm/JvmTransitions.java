package org.julclang.state.runtime.wasm;

import org.julclang.core.PlutusData;
import org.julclang.state.api.MapEntry;
import org.julclang.state.api.MerkleMaps;
import org.julclang.state.api.RequireException;
import org.julclang.state.runtime.described.TransitionFn;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * ADR-0020 D3: the contract's {@code <Name>Transitions} methods run on the JVM (javac's compilation of the same Java
 * entry points the programs are compiled from), behind the {@link TransitionFn} interface: the comparison mode's JVM
 * side. Inputs are decoded with the strict {@link DataCodec}; the step record is encoded back to Data. Map reads: a
 * {@code ReadSource} bound with {@link MerkleMaps#withReads} answers a key outside the read set as absent and reports it
 * as a {@code joust:read <root> <key>} need, as the program's discovery variant does.
 */
final class JvmTransitions implements TransitionFn, ShadowTransitions.Side {

    private final Map<String, Method> methods = new HashMap<>();

    JvmTransitions(Class<?> transitions) {
        for (Method m : transitions.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 4) methods.put(m.getName(), m);
        }
    }

    @Override
    public PlutusData apply(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Outcome o = outcome(method, target, shared, args, ctx);
        if (o.kind() == Outcome.Kind.STEP) return o.step();
        throw new RequireException(o.message());
    }

    @Override
    public Run run(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Outcome o = outcome(method, target, shared, args, ctx);
        return new Run(o.kind() == Outcome.Kind.STEP ? o.step() : null,
                o.kind() == Outcome.Kind.STEP ? null : o.message(), o.traces());
    }

    @Override
    public Outcome outcome(String method, PlutusData target, PlutusData shared, PlutusData args, PlutusData ctx) {
        Method m = methods.get(method);
        if (m == null) throw new IllegalArgumentException("no transition " + method);
        Type[] types = m.getGenericParameterTypes();
        PlutusData[] in = {target, shared, args, ctx};
        Object[] values = new Object[4];
        try {
            for (int i = 0; i < 4; i++) values[i] = unused(in[i], types[i]) ? null : DataCodec.decode(in[i], types[i]);
        } catch (DataCodec.AbiError e) {
            return new Outcome(Outcome.Kind.ABI, null, e.getMessage(), List.of());
        }
        List<String> traces = new ArrayList<>();
        try {
            Object step = MerkleMaps.withReads((root, key) -> {
                // Outside the read set: absent, reported as a need (the program's discovery variant does the same).
                traces.add("joust:read " + HexFormat.of().formatHex(root) + " " + HexFormat.of().formatHex(key));
                return new MapEntry(key, false, new byte[0]);
            }, () -> {
                try {
                    return m.invoke(null, values);
                } catch (InvocationTargetException e) {
                    if (e.getCause() instanceof RuntimeException r) throw r;
                    throw new IllegalStateException(e.getCause());
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            });
            return new Outcome(Outcome.Kind.STEP, DataCodec.encode(step, m.getGenericReturnType()), null, traces);
        } catch (RequireException e) {
            return new Outcome(Outcome.Kind.REQUIRE, null, e.getMessage(), traces);
        } catch (RuntimeException e) {
            return new Outcome(Outcome.Kind.ERROR, null, e.getClass().getSimpleName() + ": " + e.getMessage(), traces);
        }
    }

    /** {@code DescribedContract}'s NONE sentinel for an argument the method does not take. */
    static boolean unused(PlutusData d, Type t) {
        if (!(d instanceof PlutusData.ConstrData c) || c.tag() != 0 || !c.fields().isEmpty()) return false;
        Class<?> r = DataCodec.raw(t);
        return r.isRecord() && r.getRecordComponents().length > 0;
    }
}
