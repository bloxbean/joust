package org.julclang.state.runtime.wasm;

import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.core.types.JulcMap;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * ADR-0020 D3: the record codec of {@link JvmTransitions} (the comparison mode's JVM side), following JuLC's data
 * layout. Decoding is strict (constructor tag and arity checked); encoding mirrors it. Types JuLC treats as ledger types
 * (Value, Address, ...) go through the runtime's {@link PlutusDataAdapter}, the encoding the typed runtime writes on
 * chain. The replays check the round trip: every recorded transaction is rebuilt byte for byte through it.
 */
final class DataCodec {
    private DataCodec() {}

    /** A Data value that does not fit the expected type (an ABI rejection, ADR-0020 D3). */
    static final class AbiError extends IllegalArgumentException {
        AbiError(String m) { super(m); }
    }

    static Class<?> raw(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType p) return (Class<?>) p.getRawType();
        throw new AbiError("unsupported type " + t);
    }

    static Type arg(Type t, int i) {
        if (t instanceof ParameterizedType p) return p.getActualTypeArguments()[i];
        return Object.class;
    }

    static Object decode(PlutusData d, Type t) {
        Class<?> c = raw(t);
        if (PlutusData.class.isAssignableFrom(c)) return d;
        if (c == BigInteger.class) {
            if (d instanceof PlutusData.IntData i) return i.value();
            throw new AbiError("expected an integer, got " + kind(d));
        }
        if (c == byte[].class) {
            if (d instanceof PlutusData.BytesData b) return b.value();
            throw new AbiError("expected bytes, got " + kind(d));
        }
        if (c == boolean.class || c == Boolean.class) {
            var cd = constr(d, -1, 0);
            if (cd.tag() > 1) throw new AbiError("expected a Bool constructor, got tag " + cd.tag());
            return cd.tag() == 1;
        }
        if (c == Optional.class) {
            var cd = constr(d, -1, -1);
            if (cd.tag() == 0 && cd.fields().size() == 1) return Optional.of(decode(cd.fields().get(0), arg(t, 0)));
            if (cd.tag() == 1 && cd.fields().isEmpty()) return Optional.empty();
            throw new AbiError("expected Some/None, got tag " + cd.tag() + " with " + cd.fields().size() + " fields");
        }
        if (c == JulcList.class) {
            if (!(d instanceof PlutusData.ListData l)) throw new AbiError("expected a list, got " + kind(d));
            Object[] out = new Object[l.items().size()];
            for (int i = 0; i < out.length; i++) out[i] = decode(l.items().get(i), arg(t, 0));
            return JulcList.of(out);
        }
        if (c == JulcMap.class) {
            if (!(d instanceof PlutusData.MapData m)) throw new AbiError("expected a map, got " + kind(d));
            JulcMap<Object, Object> out = JulcMap.empty();
            // JulcMap.insert prepends: insert in reverse to keep the Data's order (duplicates kept as JuLC keeps them).
            for (int i = m.entries().size() - 1; i >= 0; i--) {
                var p = m.entries().get(i);
                out = out.insert(decode(p.key(), arg(t, 0)), decode(p.value(), arg(t, 1)));
            }
            return out;
        }
        if (c.isRecord() && !isLedger(c)) return record(d, c, 0);
        if (c.isSealed() && c.isInterface() && !isLedger(c)) {
            var cd = constr(d, -1, -1);
            Class<?>[] variants = c.getPermittedSubclasses();
            if (cd.tag() >= variants.length) throw new AbiError(c.getSimpleName() + ": no variant " + cd.tag());
            return record(d, variants[cd.tag()], cd.tag());
        }
        // Ledger types (Value, Address, Credential, …): as the runtime decodes them.
        try {
            return PlutusDataAdapter.convert(PlutusDataAdapter.toClientLib(d), c);
        } catch (RuntimeException e) {
            throw new AbiError("cannot decode " + c.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static Object record(PlutusData d, Class<?> c, int tag) {
        RecordComponent[] rc = c.getRecordComponents();
        var cd = constr(d, tag, rc.length);
        Object[] values = new Object[rc.length];
        Class<?>[] types = new Class<?>[rc.length];
        for (int i = 0; i < rc.length; i++) {
            values[i] = decode(cd.fields().get(i), rc[i].getGenericType());
            types[i] = rc[i].getType();
        }
        try {
            var k = c.getDeclaredConstructor(types);
            k.setAccessible(true);
            return k.newInstance(values);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) throw r;
            throw new IllegalStateException(e.getCause());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(c + ": " + e, e);
        }
    }

    private static PlutusData.ConstrData constr(PlutusData d, int tag, int arity) {
        if (!(d instanceof PlutusData.ConstrData cd)) throw new AbiError("expected a constructor, got " + kind(d));
        if (tag >= 0 && cd.tag() != tag) throw new AbiError("expected constructor " + tag + ", got " + cd.tag());
        if (arity >= 0 && cd.fields().size() != arity) {
            throw new AbiError("expected " + arity + " fields, got " + cd.fields().size());
        }
        return cd;
    }

    static String kind(PlutusData d) {
        return d == null ? "nothing" : d.getClass().getSimpleName();
    }

    static PlutusData encode(Object v, Type t) {
        Class<?> c = raw(t);
        if (v == null) throw new IllegalArgumentException("null " + c.getSimpleName());
        if (v instanceof PlutusData pd) return pd;
        if (v instanceof BigInteger b) return new PlutusData.IntData(b);
        if (v instanceof byte[] b) return new PlutusData.BytesData(b);
        if (v instanceof Boolean b) return PlutusData.constr(b ? 1 : 0);
        if (v instanceof Optional<?> o) {
            return o.isPresent() ? PlutusData.constr(0, encode(o.get(), arg(t, 0))) : PlutusData.constr(1);
        }
        if (v instanceof JulcList<?> l) {
            var items = new ArrayList<PlutusData>();
            for (long i = 0; i < l.size(); i++) items.add(encode(l.get(i), arg(t, 0)));
            return new PlutusData.ListData(items);
        }
        if (v instanceof JulcMap<?, ?> m) return m.toPlutusData();
        if (v.getClass().isRecord() && !isLedger(v.getClass())) {
            int tag = 0;
            for (Class<?> i : v.getClass().getInterfaces()) {
                if (i.isSealed()) tag = List.of(i.getPermittedSubclasses()).indexOf(v.getClass());
            }
            RecordComponent[] rc = v.getClass().getRecordComponents();
            var fields = new PlutusData[rc.length];
            for (int i = 0; i < rc.length; i++) {
                try {
                    var acc = rc[i].getAccessor();
                    acc.setAccessible(true);
                    fields[i] = encode(acc.invoke(v), rc[i].getGenericType());
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(e);
                }
            }
            return PlutusData.constr(tag, fields);
        }
        return PlutusDataAdapter.fromClientLib(PlutusDataAdapter.convert(v));
    }

    /** JuLC's ledger types keep the runtime's own encoding. */
    static boolean isLedger(Class<?> c) {
        return c.getName().startsWith("org.julclang.ledger.");
    }

}
