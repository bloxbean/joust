package org.julclang.state.api.mpf;

import org.julclang.state.api.mpf.ProofStep;

import org.julclang.core.types.JulcList;
import org.julclang.stdlib.Builtins;
import org.julclang.stdlib.annotation.OnchainLibrary;
import org.julclang.stdlib.lib.ByteStringLib;

import java.math.BigInteger;

/**
 * Merkle Patricia Forestry roots and updates, compatible with Aiken's {@code aiken-lang/merkle-patricia-forestry} and
 * cardano-client-lib's {@code MpfTrie} (blake2b-256, radix 16, keys and values hashed). A port of
 * cardano-client-lib's {@code WireProof}, in JuLC's subset: it runs on-chain and on the JVM alike.
 * <p>
 * A trie is identified by its root; the empty trie's root is 32 zero bytes ({@link #emptyRoot()}). Membership
 * ({@link #has}, {@link #miss}) and updates ({@link #insert}, {@link #delete}, {@link #update}) take a proof of the
 * key's current position, as {@code MpfTrie.getProofPlutusData} returns it.
 */
@OnchainLibrary
public class Mpf {

    /**
     * Roots of empty subtrees of 2, 4 and 8 slots: blake2b_256 of two empty roots, of two NULL2, of two NULL4
     * ({@code MpfLiteralsTest} recomputes them). Literals, so no step recomputes them.
     */
    static final byte[] NULL2 = new byte[]{14, -71, 35, -80, -53, -46, 77, -11, 68, 1, -39, -104, 83, 31, -18, -83, 53, -92, 122, -103, -12, -34, -19, 32, 93, -28, -81, -127, 18, 15, -105, 97};
    static final byte[] NULL4 = new byte[]{-123, -64, -102, -7, 41, 73, 42, -121, 30, 79, -82, 50, -39, -43, -61, 110, 53, 36, 113, -51, 101, -101, -51, -74, 29, -32, -113, 23, 34, -84, -61, -79};
    static final byte[] NULL8 = new byte[]{-78, 45, -15, -95, 38, -75, -70, 78, 51, -63, 111, -42, 21, 117, 7, 97, 14, 85, -1, -50, 32, -38, -25, -84, 68, -54, -31, 104, -92, 99, 97, 42};

    /** The root of the empty trie. */
    public static byte[] emptyRoot() {
        return ByteStringLib.integerToByteString(true, 32, BigInteger.ZERO);
    }

    /** True if {@code key -> value} is in the trie with {@code root}. */
    public static boolean has(byte[] root, byte[] key, byte[] value, JulcList<ProofStep> proof) {
        return ByteStringLib.equals(including(key, value, proof), root);
    }

    /** True if {@code key} is absent from the trie with {@code root}. */
    public static boolean miss(byte[] root, byte[] key, JulcList<ProofStep> proof) {
        return ByteStringLib.equals(excluding(key, proof), root);
    }

    /** The root after inserting {@code key -> value} (absent before); fails if the proof does not show it absent. */
    public static byte[] insert(byte[] root, byte[] key, byte[] value, JulcList<ProofStep> proof) {
        if (!miss(root, key, proof)) {
            Builtins.error();
            return root;
        } else {
            return including(key, value, proof);
        }
    }

    /** The root after deleting {@code key -> value}; fails if the proof does not show it present with that value. */
    public static byte[] delete(byte[] root, byte[] key, byte[] value, JulcList<ProofStep> proof) {
        if (!has(root, key, value, proof)) {
            Builtins.error();
            return root;
        } else {
            return excluding(key, proof);
        }
    }

    /** The root after changing {@code key} from {@code oldValue} to {@code newValue}. */
    public static byte[] update(byte[] root, byte[] key, JulcList<ProofStep> proof, byte[] oldValue, byte[] newValue) {
        if (!has(root, key, oldValue, proof)) {
            Builtins.error();
            return root;
        } else {
            return including(key, newValue, proof);
        }
    }

    /** The root of a trie holding {@code key -> value} at the position the proof describes. */
    public static byte[] including(byte[] key, byte[] value, JulcList<ProofStep> proof) {
        byte[] path = Builtins.blake2b_256(key);
        return doIncluding(path, Builtins.blake2b_256(value), BigInteger.ZERO, proof);
    }

    /** The root of the trie the proof describes without {@code key}. */
    public static byte[] excluding(byte[] key, JulcList<ProofStep> proof) {
        byte[] path = Builtins.blake2b_256(key);
        return proof.isEmpty() ? emptyRoot() : doExcluding(path, BigInteger.ZERO, proof);
    }

    static byte[] doIncluding(byte[] path, byte[] valueHash, BigInteger cursor, JulcList<ProofStep> steps) {
        if (steps.isEmpty()) {
            return leafCommit(path, cursor, valueHash);
        } else {
            ProofStep step = steps.head();
            BigInteger next = checkedNext(step, cursor);
            byte[] child = doIncluding(path, valueHash, next.add(BigInteger.ONE), steps.tail());
            return rollUp(path, step, cursor, next, child);
        }
    }

    static byte[] doExcluding(byte[] path, BigInteger cursor, JulcList<ProofStep> steps) {
        if (steps.isEmpty()) {
            return emptyRoot();
        } else {
            ProofStep step = steps.head();
            boolean last = steps.tail().isEmpty();
            BigInteger next = checkedNext(step, cursor);
            if (last) {
                return lastExcluding(path, step, cursor, next);
            } else {
                byte[] child = doExcluding(path, next.add(BigInteger.ONE), steps.tail());
                return rollUp(path, step, cursor, next, child);
            }
        }
    }

    /** The last step of an exclusion proof: the path ends beside a fork child or a leaf, or under a branch. */
    static byte[] lastExcluding(byte[] path, ProofStep step, BigInteger cursor, BigInteger next) {
        return switch (step) {
            case ProofStep.Fork f -> {
                // The neighbour must not sit under the queried path's own nibble.
                if (f.neighbor().nibble().compareTo(nibble(path, next)) == 0) {
                    Builtins.error();
                    yield path;
                } else {
                    byte[] neighborPrefix = ByteStringLib.cons(f.neighbor().nibble().longValue(), f.neighbor().prefix());
                    byte[] prefix = f.skip().compareTo(BigInteger.ZERO) == 0
                            ? neighborPrefix
                            : Builtins.appendByteString(nibbles(path, cursor, cursor.add(f.skip())), neighborPrefix);
                    yield combine(prefix, f.neighbor().root());
                }
            }
            case ProofStep.Leaf l -> {
                checkLeafNeighbor(path, l.key(), next);
                yield leafCommit(l.key(), cursor, l.value());
            }
            case ProofStep.Branch b -> rollUp(path, step, cursor, next, emptyRoot());
        };
    }

    static byte[] rollUp(byte[] path, ProofStep step, BigInteger cursor, BigInteger next, byte[] child) {
        return switch (step) {
            case ProofStep.Branch b -> combine(nibbles(path, cursor, next),
                    merkle16(nibble(path, next), child, ByteStringLib.slice(b.neighbors(), 0, 32),
                            ByteStringLib.slice(b.neighbors(), 32, 32), ByteStringLib.slice(b.neighbors(), 64, 32),
                            ByteStringLib.slice(b.neighbors(), 96, 32)));
            case ProofStep.Fork f -> doFork(path, cursor, next, child, f.neighbor().nibble(),
                    combine(f.neighbor().prefix(), f.neighbor().root()));
            case ProofStep.Leaf l -> leafNeighbor(path, l, cursor, next, child);
        };
    }

    static byte[] leafNeighbor(byte[] path, ProofStep.Leaf l, BigInteger cursor, BigInteger next, byte[] child) {
        checkLeafNeighbor(path, l.key(), next);
        byte[] neighborHash = leafCommit(l.key(), next.add(BigInteger.ONE), l.value());
        return doFork(path, cursor, next, child, nibble(l.key(), next), neighborHash);
    }

    static byte[] doFork(byte[] path, BigInteger cursor, BigInteger next, byte[] child, BigInteger neighborNibble,
                         byte[] neighborHash) {
        BigInteger branch = nibble(path, next);
        if (branch.compareTo(neighborNibble) == 0) {
            Builtins.error();
            return child;
        } else {
            return combine(nibbles(path, cursor, next), sparseMerkle16(branch, child, neighborNibble, neighborHash));
        }
    }

    /** The leaf neighbour shares the path up to {@code next}, and differs at {@code next}. */
    static void checkLeafNeighbor(byte[] path, byte[] keyHash, BigInteger next) {
        boolean ok = ByteStringLib.length(keyHash) == 32
                && ByteStringLib.equals(nibbles(path, BigInteger.ZERO, next), nibbles(keyHash, BigInteger.ZERO, next))
                && nibble(keyHash, next).compareTo(nibble(path, next)) != 0;
        if (!ok) {
            Builtins.error();
        }
    }

    /** Commitment of a leaf: its remaining path from {@code cursor} (MPF suffix encoding) and its value hash. */
    static byte[] leafCommit(byte[] path, BigInteger cursor, byte[] valueHash) {
        BigInteger half = cursor.divide(BigInteger.TWO);
        byte[] suffix = cursor.remainder(BigInteger.TWO).compareTo(BigInteger.ZERO) == 0
                ? ByteStringLib.cons(255L, ByteStringLib.drop(path, half.longValue()))
                : ByteStringLib.cons(0L, ByteStringLib.cons(nibble(path, cursor).longValue(),
                        ByteStringLib.drop(path, half.add(BigInteger.ONE).longValue())));
        return Builtins.blake2b_256(Builtins.appendByteString(suffix, valueHash));
    }

    static byte[] merkle16(BigInteger branch, byte[] root, byte[] n8, byte[] n4, byte[] n2, byte[] n1) {
        BigInteger eight = BigInteger.valueOf(8);
        return branch.compareTo(BigInteger.valueOf(7)) <= 0
                ? combine(merkle8(branch, root, n4, n2, n1), n8)
                : combine(n8, merkle8(branch.subtract(eight), root, n4, n2, n1));
    }

    static byte[] merkle8(BigInteger branch, byte[] root, byte[] n4, byte[] n2, byte[] n1) {
        BigInteger four = BigInteger.valueOf(4);
        return branch.compareTo(BigInteger.valueOf(3)) <= 0
                ? combine(merkle4(branch, root, n2, n1), n4)
                : combine(n4, merkle4(branch.subtract(four), root, n2, n1));
    }

    static byte[] merkle4(BigInteger branch, byte[] root, byte[] n2, byte[] n1) {
        if (branch.compareTo(BigInteger.ONE) <= 0) {
            return combine(branch.compareTo(BigInteger.ZERO) == 0 ? combine(root, n1) : combine(n1, root), n2);
        } else {
            return combine(n2, branch.compareTo(BigInteger.TWO) == 0 ? combine(root, n1) : combine(n1, root));
        }
    }

    static byte[] sparseMerkle16(BigInteger me, byte[] meHash, BigInteger neighbor, byte[] neighborHash) {
        BigInteger seven = BigInteger.valueOf(7);
        BigInteger eight = BigInteger.valueOf(8);
        if (me.compareTo(seven) <= 0) {
            if (neighbor.compareTo(seven) <= 0) {
                return combine(sparseMerkle8(me, meHash, neighbor, neighborHash), NULL8);
            } else {
                return combine(merkle8(me, meHash, NULL4, NULL2, emptyRoot()),
                        merkle8(neighbor.subtract(eight), neighborHash, NULL4, NULL2, emptyRoot()));
            }
        } else {
            if (neighbor.compareTo(eight) >= 0) {
                return combine(NULL8, sparseMerkle8(me.subtract(eight), meHash, neighbor.subtract(eight), neighborHash));
            } else {
                return combine(merkle8(neighbor, neighborHash, NULL4, NULL2, emptyRoot()),
                        merkle8(me.subtract(eight), meHash, NULL4, NULL2, emptyRoot()));
            }
        }
    }

    static byte[] sparseMerkle8(BigInteger me, byte[] meHash, BigInteger neighbor, byte[] neighborHash) {
        BigInteger three = BigInteger.valueOf(3);
        BigInteger four = BigInteger.valueOf(4);
        if (me.compareTo(three) <= 0) {
            if (neighbor.compareTo(three) <= 0) {
                return combine(sparseMerkle4(me, meHash, neighbor, neighborHash), NULL4);
            } else {
                return combine(merkle4(me, meHash, NULL2, emptyRoot()),
                        merkle4(neighbor.subtract(four), neighborHash, NULL2, emptyRoot()));
            }
        } else {
            if (neighbor.compareTo(four) >= 0) {
                return combine(NULL4, sparseMerkle4(me.subtract(four), meHash, neighbor.subtract(four), neighborHash));
            } else {
                return combine(merkle4(neighbor, neighborHash, NULL2, emptyRoot()),
                        merkle4(me.subtract(four), meHash, NULL2, emptyRoot()));
            }
        }
    }

    static byte[] sparseMerkle4(BigInteger me, byte[] meHash, BigInteger neighbor, byte[] neighborHash) {
        if (me.compareTo(BigInteger.ONE) <= 0) {
            if (neighbor.compareTo(BigInteger.ONE) <= 0) {
                return combine(combineMe(me, meHash, neighborHash), NULL2);
            } else {
                return combine(combineMe(me, meHash, emptyRoot()), combineMe(neighbor, neighborHash, emptyRoot()));
            }
        } else {
            if (neighbor.compareTo(BigInteger.TWO) >= 0) {
                return combine(NULL2, combineMe(me, meHash, neighborHash));
            } else {
                return combine(combineMe(neighbor, neighborHash, emptyRoot()), combineMe(me, meHash, emptyRoot()));
            }
        }
    }

    /** {@code me}'s hash on its side of a pair: left for an even slot, right for an odd one. */
    static byte[] combineMe(BigInteger me, byte[] meHash, byte[] other) {
        return me.remainder(BigInteger.TWO).compareTo(BigInteger.ZERO) == 0 ? combine(meHash, other) : combine(other, meHash);
    }

    /**
     * The cursor after {@code step}'s skip, failing on any malformed step (what cardano-client-lib's proof decoder
     * rejects). Without these checks a forged step can re-encode a present key's own leaf as a "neighbour" and prove it
     * absent (ADR-0002 review r1, finding 1): nibbles must be 0..15, prefix bytes nibbles, hashes 32 bytes, branch
     * neighbours 4 x 32 bytes, and the cursor must stay inside the 64-nibble path.
     */
    static BigInteger checkedNext(ProofStep step, BigInteger cursor) {
        BigInteger skip = skipOf(step);
        BigInteger next = cursor.add(skip);
        boolean ok = skip.compareTo(BigInteger.ZERO) >= 0 && next.compareTo(BigInteger.valueOf(64)) < 0
                && wellFormed(step);
        if (!ok) {
            Builtins.error();
            return next;
        } else {
            return next;
        }
    }

    static boolean wellFormed(ProofStep step) {
        return switch (step) {
            case ProofStep.Branch b -> ByteStringLib.length(b.neighbors()) == 128;
            case ProofStep.Fork f -> isNibble(f.neighbor().nibble()) && nibbleBytes(f.neighbor().prefix())
                    && ByteStringLib.length(f.neighbor().root()) == 32;
            case ProofStep.Leaf l -> ByteStringLib.length(l.key()) == 32 && ByteStringLib.length(l.value()) == 32;
        };
    }

    static boolean isNibble(BigInteger n) {
        return n.compareTo(BigInteger.ZERO) >= 0 && n.compareTo(BigInteger.valueOf(15)) <= 0;
    }

    /** Every byte of {@code bs} is a nibble (0..15) and there are fewer than 64 of them. */
    static boolean nibbleBytes(byte[] bs) {
        long n = ByteStringLib.length(bs);
        boolean ok = n < 64;
        long i = 0;
        while (i < n) {
            if (ByteStringLib.at(bs, i) > 15) {
                ok = false;
            }
            i = i + 1;
        }
        return ok;
    }

    static byte[] combine(byte[] left, byte[] right) {
        return Builtins.blake2b_256(Builtins.appendByteString(left, right));
    }

    static BigInteger skipOf(ProofStep step) {
        return switch (step) {
            case ProofStep.Branch b -> b.skip();
            case ProofStep.Fork f -> f.skip();
            case ProofStep.Leaf l -> l.skip();
        };
    }

    /** The {@code i}-th nibble of {@code bytes} (high nibble first). */
    static BigInteger nibble(byte[] bytes, BigInteger i) {
        BigInteger b = BigInteger.valueOf(ByteStringLib.at(bytes, i.divide(BigInteger.TWO).longValue()));
        return i.remainder(BigInteger.TWO).compareTo(BigInteger.ZERO) == 0
                ? b.divide(BigInteger.valueOf(16))
                : b.remainder(BigInteger.valueOf(16));
    }

    /** The nibbles of {@code bytes} in {@code [start, end)}, one byte each. */
    static byte[] nibbles(byte[] bytes, BigInteger start, BigInteger end) {
        byte[] acc = ByteStringLib.empty();
        BigInteger i = end;
        while (i.compareTo(start) > 0) {
            i = i.subtract(BigInteger.ONE);
            acc = ByteStringLib.cons(nibble(bytes, i).longValue(), acc);
        }
        return acc;
    }
}
