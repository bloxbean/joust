package org.julclang.state.host.conformance;

import java.math.BigInteger;
import java.util.*;
import org.bouncycastle.crypto.digests.Blake2bDigest;

/** Length-bounded CBOR item walker. Offsets always refer to the received bytes, including indefinite containers. */
final class Cbor {
    static final HexFormat HEX = HexFormat.of();
    final byte[] source;
    final int start, end, major;
    final BigInteger argument;
    final int payload;
    final List<Cbor> items;

    private Cbor(byte[] source, int start, int depth) {
        require(depth < 64 && start < source.length);
        this.source = source;
        this.start = start;
        int header = source[start] & 255;
        major = header >>> 5;
        int ai = header & 31;
        int pos = start + 1;
        boolean indefinite = ai == 31;
        require(!indefinite || major >= 2 && major <= 5);
        if (ai < 24 || indefinite) argument = BigInteger.valueOf(indefinite ? -1 : ai);
        else {
            require(ai <= 27);
            int size = 1 << (ai - 24);
            require(size <= source.length - pos);
            argument = new BigInteger(1, source, pos, size);
            pos += size;
        }
        payload = pos;
        if ((major == 2 || major == 3) && !indefinite) {
            int size = argument.intValueExact();
            require(size <= source.length - pos);
            pos += size;
            items = List.of();
        } else if (indefinite || major >= 4 && major <= 6) {
            int count = indefinite ? -1 : major == 6 ? 1 :
                    Math.multiplyExact(argument.intValueExact(), major == 5 ? 2 : 1);
            var children = new ArrayList<Cbor>();
            while (indefinite ? pos < source.length && source[pos] != -1 : children.size() < count) {
                var child = new Cbor(source, pos, depth + 1);
                if (major == 2 || major == 3) require(child.major == major && child.argument.signum() >= 0);
                children.add(child);
                pos = child.end;
            }
            if (indefinite) {
                require(pos < source.length && source[pos] == -1 && (major != 5 || children.size() % 2 == 0));
                pos++;
            }
            items = children;
        } else items = List.of();
        end = pos;
    }

    static Cbor read(byte[] bytes) {
        require(bytes.length <= 1_048_576);
        var node = new Cbor(bytes, 0, 0);
        require(node.end == bytes.length);
        return node;
    }
    static void require(boolean ok) { if (!ok) throw new IllegalArgumentException("malformed CBOR or ledger field"); }
    List<Cbor> array() { require(major == 4); return items; }
    List<Cbor> set() { return major == 6 ? tagged(258).array() : array(); }
    Cbor at(int i) { return array().get(i); }
    Cbor tagged(int tag) { require(major == 6 && argument.intValueExact() == tag); return items.getFirst(); }
    BigInteger integer() {
        require(major <= 1);
        return major == 0 ? argument : argument.negate().subtract(BigInteger.ONE);
    }
    int uint() { require(major == 0); return integer().intValueExact(); }
    String bytes() { require(major == 2 && argument.signum() >= 0); return HEX.formatHex(source, payload, end); }
    String raw() { return HEX.formatHex(source, start, end); }
    Map<Integer,Cbor> fields() {
        require(major == 5);
        var map = new LinkedHashMap<Integer,Cbor>();
        for (int i = 0; i < items.size(); i += 2) require(map.put(items.get(i).uint(), items.get(i+1)) == null);
        return map;
    }
    String digest(int bits) { return digest(bits, source, start, end - start); }
    static String digest(int bits, byte[] value, int start, int size) {
        var hash = new Blake2bDigest(bits);
        hash.update(value, start, size);
        byte[] out = new byte[bits / 8];
        hash.doFinal(out, 0);
        return HEX.formatHex(out);
    }
}
