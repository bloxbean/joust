package org.julclang.state.examples.registry;

import org.julclang.core.PlutusData;
import org.julclang.core.types.JulcList;
import org.julclang.ledger.OutputDatum;
import org.julclang.ledger.TxOut;
import org.julclang.ledger.TxOutRef;
import org.julclang.ledger.Value;
import org.julclang.state.api.Ctx;
import org.julclang.state.examples.registry.RegistryWorld.Kind;
import org.julclang.state.testkit.Ledger;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

import static org.julclang.state.examples.registry.RegistryWorld.ADMIN;
import static org.julclang.state.examples.registry.RegistryWorld.PARTIES;
import static org.julclang.state.examples.registry.RegistryWorld.ada;
import static org.julclang.state.examples.registry.RegistryWorld.data;
import static org.julclang.state.examples.registry.RegistryWorld.keyId;
import static org.julclang.state.examples.registry.Tamper.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ADR-0016 §8 attack suite on the ledger model, grouped by operation. Each attack starts from an honest
 * transaction the model accepts (the control) and must be rejected; the accepted liveness cases come last.
 */
class RegistryAttacksTest {

    static final byte[] ALICE = PARTIES.get(0);
    static final byte[] BOB = PARTIES.get(1);
    static final byte[] CAROL = PARTIES.get(2);
    static final byte[] EMPTY = new byte[0];
    static final byte[] A = {0x61};
    static final byte[] B = {0x62};
    static final byte[] BB = {0x62, 0x62};
    static final byte[] C = {0x63};
    static final byte[] D = {0x64};
    static final byte[] E = {0x65};
    static final byte[] F = {0x66};

    RegistryWorld w;
    byte[] inst;

    /** A registry with the names b and d (Alice's, 5 ADA each) and Alice's ballot. */
    void registry() {
        w = new RegistryWorld();
        inst = w.createInstance();
        w.accept(w.claim(inst, ALICE, B, ada(5)));
        w.accept(w.claim(inst, ALICE, D, ada(5)));
        w.accept(w.cast(inst, ALICE, BigInteger.ONE));
    }

    /** The control: the honest transaction is accepted by a dry run. */
    void control(RegistryWorld.Built b) {
        Ledger.Result r = w.ledger.check(b.tx);
        assertTrue(r.accepted(), b.op + ": the honest transaction must be accepted:\n" + r);
    }

    PlutusData mintRedeemer(RegistryWorld.Built b) {
        return b.tx.redeemers.get(w.mint());
    }

    // ================================================================== insert

    @Nested
    class Insert {
        @BeforeEach
        void setUp() {
            registry();
        }

        @Test
        void thePlace() {
            control(w.claim(inst, BOB, C, ada(2)));
            w.anchoredAt(inst, Kind.ENTRY, D);
            var b = w.claim(inst, BOB, C, ada(2));
            w.reject(b, b.tx, "key(A) >= key (c after d)");
            w.anchoredAt(inst, Kind.ENTRY, B);
            b = w.claim(inst, BOB, B, ada(2));
            w.reject(b, b.tx, "key(A) == key (b after b)");
            w.anchoredAt(inst, Kind.ENTRY, B);
            b = w.claim(inst, BOB, E, ada(2));
            w.reject(b, b.tx, "A.next <= key (e after b, whose next is d)");
            b = w.claim(inst, BOB, D, ada(2));
            w.reject(b, b.tx, "A.next == key (d again: a duplicate)");
            b = w.claim(inst, BOB, EMPTY, ada(2));
            w.reject(b, b.tx, "the empty key");
            b = w.claim(inst, BOB, StateHarness.fill(65, 0x70), ada(2));
            w.reject(b, b.tx, "a key longer than maxKeyBytes (65 > 64)");
            b = w.cast(inst, ALICE, BigInteger.TWO);
            w.reject(b, b.tx, "a second ballot of the same voter");
        }

        @Test
        void aRetiredKeyInsertedAgain() {
            w.accept(w.retire(inst, Kind.ENTRY, B, BigInteger.ZERO));
            var b = w.claim(inst, BOB, B, ada(2));
            w.reject(b, b.tx, "b again, after the head (whose next is the retired b)");
            w.anchoredAt(inst, Kind.ENTRY, B);
            b = w.claim(inst, BOB, B, ada(2));
            w.reject(b, b.tx, "b again, after the retired b itself");
            w.accept(w.retire(inst, Kind.BALLOT, ALICE, BigInteger.ZERO));
            b = w.cast(inst, ALICE, BigInteger.TWO);
            w.reject(b, b.tx, "a ballot again after leaving: once ever");
        }

        @Test
        void anAnchorOfAnotherInstanceOrType() {
            byte[] other = w.createInstance();
            w.accept(w.claim(other, BOB, A, ada(2)));
            w.anchoredAt(other, Kind.ENTRY, A);
            var b = w.claim(inst, BOB, C, ada(2));
            w.reject(b, b.tx, "an anchor of another instance");
            w.anchoredAt(inst, Kind.BALLOT, EMPTY);
            b = w.claim(inst, BOB, C, ada(2));
            w.reject(b, b.tx, "Insert_claim (an Entry) with a Ballot anchor");
            w.anchoredAt(inst, Kind.ENTRY, EMPTY);
            b = w.cast(inst, BOB, BigInteger.ONE);
            w.reject(b, b.tx, "Insert_cast (a Ballot) with an Entry anchor");

            // A redeemer instance other than A.instance: the node and its token under the other instance.
            var h = w.claim(inst, BOB, C, ada(2));
            control(h);
            byte[] fake = keyId(other, Kind.ENTRY, C);
            var t = w.mutate(h, x -> {
                x.mint.clear();
                x.mint(w.policy, fake, BigInteger.ONE);
                mapRedeemer(x, w.mint(), r -> field(r, 0, bytes(other)));
                mapOutput(x, h.nodeOut, o -> withDatum(Ledger.canonical(new TxOut(o.address(), Ledger.value(o.value().lovelaceOf(),
                        w.policy, fake), o.datum(), o.referenceScript())), field(datum(o), 0, bytes(other))));
            });
            w.reject(h, t, "the redeemer's instance is not the anchor's");
        }

        @Test
        void forgedAnchors() {
            // A head datum at the state address without any token (anyone can create such an output).
            TxOutRef forged = w.ledger.fund(w.stateOut(Value.lovelace(ada(3)), RegistryWorld.head(Kind.ENTRY, inst, EMPTY,
                    BigInteger.ZERO)));
            w.anchoredAt(forged);
            var b = w.claim(inst, BOB, A, ada(2));
            w.reject(b, b.tx, "a forged anchor without a key token");
            // A node whose datum and token disagree (a fabricated token: the datum says b, the token is zz's).
            var entry = new Registry.Entry(B, ALICE, BigInteger.ZERO, BigInteger.ONE, JulcList.<byte[]>empty().prepend(ALICE),
                    BigInteger.ONE);
            TxOutRef liar = w.ledger.fund(w.stateOut(Ledger.value(w.minAda, w.policy, keyId(inst, Kind.ENTRY, new byte[]{0x7a, 0x7a})),
                    RegistryWorld.keyed(Kind.ENTRY, inst, D, BigInteger.ZERO, entry)));
            w.anchoredAt(liar);
            b = w.claim(inst, BOB, C, ada(2));
            w.reject(b, b.tx, "an anchor whose datum and token disagree");
        }

        @Test
        void anAnchorSpentWithAnOwnedAction() {
            var b = w.claim(inst, BOB, C, ada(2));
            control(b);
            Ctx ctx = new Ctx(ALICE, BigInteger.ZERO, ada(5), b.tx.lower, b.tx.upper);
            var t = w.mutate(b, x -> {
                x.redeemers.put(RegistryWorld.spend(b.anchor), data(new RegistryValidator.Upgrade(BigInteger.ONE, ctx)));
                x.signers.add(ALICE);
            });
            w.reject(b, t, "the anchor spent with its owner's action instead of Link");
        }

        @Test
        void theRelink() {
            var b = w.claim(inst, BOB, C, ada(2));
            control(b);
            int a = b.anchorOut;
            BigInteger upper = b.tx.upper;
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, o -> plusLovelace(o, BigInteger.ONE.negate()))), "lovelace skimmed");
            byte[] foreign = StateHarness.fill(28, 0xF0);
            TxOutRef src = w.ledger.fund(new TxOut(StateHarness.pkhAddress(BOB), Ledger.value(ada(2), foreign, new byte[]{1}),
                    new OutputDatum.NoOutputDatum(), Optional.empty()));
            w.reject(b, w.mutate(b, x -> {
                x.inputs.add(src);
                mapOutput(x, a, o -> plusToken(o, foreign, new byte[]{1}, BigInteger.ONE));
            }), "a token added to the relinked anchor");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> field(d, 1, bytes(E)))), "another next");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> field(d, 2, integer(upper.subtract(BigInteger.valueOf(1000)))))),
                    "linkedAt other than the upper bound");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> nested(d, 3, 2, integer(ada(6))))), "another field changed");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> tag(d, 5))), "the constructor changed (retired)");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, o -> withAddress(o, StateHarness.pkhAddress(BOB)))), "moved to a key address");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, Tamper::withStake)), "moved to the other state-address variant");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, Tamper::withDatumHash)), "a datum hash");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, Tamper::withRefScript)), "a reference script");
            var ok = w.mutate(b, x -> mapOutput(x, a, o -> plusLovelace(o, ada(1))));
            assertTrue(w.ledger.check(ok).accepted(), "a relink may add lovelace (D4b)");
        }

        @Test
        void theNewNode() {
            var b = w.claim(inst, BOB, C, ada(2));
            control(b);
            int n = b.nodeOut;
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> field(d, 1, bytes(E)))), "the wrong next");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> nested(d, 3, 0, bytes(new byte[]{0x63, 0x63})))), "the wrong key field");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> nested(d, 3, 2, integer(ada(3))))), "the wrong value (stake)");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> field(d, 2, integer(b.tx.lower)))), "linkedAt other than the upper bound");
            w.reject(b, w.mutate(b, x -> mapOutput(x, n, o -> plusLovelace(o, BigInteger.ONE))), "one lovelace more than minAda + received");
            w.reject(b, w.mutate(b, x -> mapOutput(x, n, Tamper::withStake)), "the node at the other address variant");
            w.reject(b, w.mutate(b, x -> mapOutput(x, n, Tamper::withRefScript)), "the node with a reference script");
            w.reject(b, w.mutate(b, x -> x.mint(w.policy, keyId(inst, Kind.ENTRY, C), BigInteger.ONE)), "two tokens minted");
            JulcList<byte[]> bob = JulcList.<byte[]>empty().prepend(BOB);
            var bad = w.claim(inst, BOB, C, ada(2), BigInteger.valueOf(7), bob, BigInteger.ONE);
            w.reject(bad, bad.tx, "a record violating the tier @Invariant");
            bad = w.claim(inst, BOB, C, ada(2), BigInteger.ONE, bob, BigInteger.TWO);
            w.reject(bad, bad.tx, "an unreachable @Quorum shape (2 of 1 guardian)");
        }

        @Test
        void theCooldownAndWindow() {
            w.accept(w.claim(inst, BOB, C, ada(2)));   // relinks b at the current upper bound
            w.waitForCooldown = false;
            var b = w.claim(inst, BOB, BB, ada(2));
            w.reject(b, b.tx, "lower < A.linkedAt + C");
            w.waitForCooldown = true;
            w.windowLength = RegistryWorld.W + 1_000;
            b = w.claim(inst, BOB, BB, ada(2));
            w.reject(b, b.tx, "a window longer than W");
            w.windowLength = RegistryWorld.W;
            b = w.claim(inst, BOB, BB, ada(2));
            control(b);
            w.reject(b, w.mutate(b, x -> x.upper = null), "an infinite upper bound");
            w.reject(b, w.mutate(b, x -> x.lower = null), "an infinite lower bound");
        }

        @Test
        void anUpperBoundOutsideTheTimeDomain() {
            // Below 2^32 (a clock in 1970) and at 2^64 (the far end).
            var early = new RegistryWorld(Ledger.Params.mainnet(), BigInteger.valueOf(1_000_000L));
            byte[] i = early.createInstance();
            var b = early.claim(i, BOB, C, ada(2));
            early.reject(b, b.tx, "upper < 2^32");
            var late = new RegistryWorld(Ledger.Params.mainnet(), BigInteger.TWO.pow(64).subtract(BigInteger.valueOf(60_000L)));
            byte[] j = late.createInstance();
            b = late.claim(j, BOB, C, ada(2));
            late.reject(b, b.tx, "upper >= 2^64");
        }

        @Test
        void signedKeyWithoutTheSignature() {
            var b = w.castBy(inst, CAROL, BOB, BigInteger.ONE);
            w.reject(b, b.tx, "a ballot for Bob, cast by Carol without Bob's signature");
            var signed = w.mutate(b, x -> x.signers.add(BOB));
            assertTrue(w.ledger.check(signed).accepted(), "with Bob's signature too, it is accepted");
        }

        @Test
        void sharedStateFromAnotherInstance() {
            byte[] other = w.createInstance();
            var b = w.cast(inst, BOB, BigInteger.ONE);
            control(b);
            w.reject(b, w.mutate(b, x -> {
                x.referenceInputs.clear();
                x.referenceInputs.add(w.instanceUtxo(other).ref());
            }), "@Shared read from another instance");
        }
    }

    // ================================================================== remove

    @Nested
    class Remove {
        @BeforeEach
        void setUp() {
            registry();
            w.accept(w.claim(inst, ALICE, F, ada(5)));
        }

        @Test
        void theRoles() {
            control(w.release(inst, D));
            w.anchoredAt(inst, Kind.ENTRY, B);
            var b = w.release(inst, F);
            w.reject(b, b.tx, "P.next != N.key (f after b)");
            var rm = w.release(inst, D);
            b = rm;
            var self = w.mutate(rm, x -> x.redeemers.put(w.mint(), data(new RegistryValidator.Remove_Entry(rm.node, rm.node))));
            w.reject(rm, self, "anchor == removed");
            // N a head: the Entry head spent as the removed node, its token burned.
            var head = w.node(inst, Kind.ENTRY, EMPTY);
            var h = w.mutate(rm, x -> {
                x.inputs.remove(rm.node);
                x.inputs.add(head.ref());
                x.redeemers.put(RegistryWorld.spend(head.ref()), x.redeemers.remove(RegistryWorld.spend(rm.node)));
                x.mint.clear();
                x.mint(w.policy, keyId(inst, Kind.ENTRY, EMPTY), BigInteger.ONE.negate());
                x.redeemers.put(w.mint(), data(new RegistryValidator.Remove_Entry(rm.anchor, head.ref())));
            });
            w.reject(rm, h, "N is a head");
            w.reject(rm, w.mutate(rm, x -> mapRedeemer(x, RegistryWorld.spend(rm.anchor), r -> data(new RegistryValidator.Upgrade(
                    BigInteger.ONE, new Ctx(ALICE, BigInteger.ZERO, ada(5), rm.tx.lower, rm.tx.upper))))), "P spent with an owned action");
        }

        @Test
        void aRetiredNodeIsNeverRemoved() {
            w.accept(w.retire(inst, Kind.ENTRY, D, BigInteger.ZERO));
            var b = w.release(inst, D);
            w.reject(b, b.tx, "N is a retired node");
        }

        @Test
        void neighboursOfAnotherInstanceOrType() {
            byte[] other = w.createInstance();
            w.accept(w.claim(other, BOB, B, ada(2)));
            w.accept(w.claim(other, BOB, D, ada(2)));
            w.anchoredAt(other, Kind.ENTRY, B);
            var b = w.release(inst, D);
            w.reject(b, b.tx, "P of another instance (its next is d too)");
            // P a Ballot whose next equals an Entry's key: Bob's and Carol's ballots, and an entry named Carol's key.
            byte[] lo = BOB.clone();
            byte[] hi = CAROL.clone();
            w.accept(w.cast(inst, lo, BigInteger.ONE));
            w.accept(w.cast(inst, hi, BigInteger.ONE));
            w.accept(w.claim(inst, CAROL, hi, ada(2)));
            w.anchoredAt(inst, Kind.BALLOT, lo);
            b = w.release(inst, hi);
            w.reject(b, b.tx, "P a Ballot whose next is the Entry's key");
        }

        @Test
        void burning() {
            var b = w.release(inst, D);
            control(b);
            w.reject(b, w.mutate(b, x -> x.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()))),
                    "the burn through BurnInstance (P still spent with Link)");
            w.reject(b, w.mutate(b, b.change - 1, x -> {
                x.inputs.remove(b.anchor);
                x.redeemers.remove(RegistryWorld.spend(b.anchor));
                x.outputs.remove(b.anchorOut);
                x.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()));
            }), "N destroyed through BurnInstance, without P");
            w.reject(b, w.mutate(b, x -> {
                x.mint.clear();
                x.redeemers.remove(w.mint());
            }), "N's token kept (sent to the change), no burn");
        }

        @Test
        void theRelink() {
            var b = w.release(inst, D);
            control(b);
            int a = b.anchorOut;
            var p = w.node(inst, Kind.ENTRY, B);
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, o -> withDatum(o, p.datum()))), "P not relinked");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> field(d, 1, bytes(D)))), "P relinked to N itself");
            w.reject(b, w.mutate(b, x -> mapDatum(x, a, d -> field(d, 1, bytes(E)))), "P relinked to another key");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, o -> plusLovelace(o, BigInteger.ONE.negate()))), "P skimmed");
            w.reject(b, w.mutate(b, x -> mapOutput(x, a, Tamper::withRefScript)), "P with a reference script");
        }

        @Test
        void theLedgerModelsOwnCase() {
            var b = w.release(inst, D);
            control(b);
            // N's redeemer omitted, and its payout redirected to an attacker: the removed node's script never runs.
            var t = w.mutate(b, x -> {
                x.redeemers.remove(RegistryWorld.spend(b.node));
                for (int i = 0; i < x.outputs.size(); i++) {
                    TxOut o = x.outputs.get(i);
                    if (o.datum() instanceof OutputDatum.OutputDatumInline d && d.datum().equals(data(b.node))) {
                        x.outputs.set(i, withDatum(withAddress(o, StateHarness.pkhAddress(CAROL)), data(b.node)));
                    }
                }
            });
            var r = w.ledger.check(t);
            assertFalse(r.accepted());
            assertTrue(r.reason().startsWith("missing redeemer"), r.reason());
            w.reject(b, w.mutate(b, x -> x.redeemers.put(RegistryWorld.spend(b.node), data(new RegistryValidator.Link()))),
                    "N spent with Link instead of its removing destroy");
        }
    }

    // ================================================================== retire

    @Nested
    class Retire {
        @BeforeEach
        void setUp() {
            registry();
        }

        @Test
        void theValueRule() {
            var b = w.retire(inst, Kind.ENTRY, B, BigInteger.ZERO);
            control(b);
            var n = w.node(inst, Kind.ENTRY, B);
            int payout = 1;
            BigInteger in = n.lovelace();
            // ctx.balance = everything (the deposit paid out too); the node keeps nothing.
            Ctx all = new Ctx(ALICE, BigInteger.ZERO, in, b.tx.lower, b.tx.upper);
            w.reject(b, w.mutate(b, x -> {
                x.redeemers.put(RegistryWorld.spend(b.node), data(new RegistryValidator.Retire(all)));
                mapOutput(x, payout, o -> plusLovelace(o, w.minAda));   // the builder funds the retired node's deposit
            }), "payouts above ctx.balance (paying the deposit)");
            Ctx less = new Ctx(ALICE, BigInteger.ZERO, in.subtract(w.minAda).subtract(ada(1)), b.tx.lower, b.tx.upper);
            w.reject(b, w.mutate(b, x -> {
                x.redeemers.put(RegistryWorld.spend(b.node), data(new RegistryValidator.Retire(less)));
                mapOutput(x, payout, o -> plusLovelace(o, ada(1).negate()));
                mapOutput(x, 0, o -> plusLovelace(o, ada(1)));
            }), "payouts below ctx.balance (funds left in the node)");
            w.reject(b, w.mutate(b, x -> {
                mapOutput(x, payout, o -> plusLovelace(o, ada(1).negate()));
                mapOutput(x, 0, o -> plusLovelace(o, ada(1)));
            }), "less paid than the payouts owe, the rest left in the node");
            w.reject(b, w.mutate(b, x -> mapOutput(x, payout, o -> new TxOut(o.address(), o.value(),
                    new OutputDatum.NoOutputDatum(), o.referenceScript()))), "an untagged payout");
            w.reject(b, w.mutate(b, x -> mapOutput(x, 0, o -> plusLovelace(o, BigInteger.ONE.negate()))), "the output below minAda");
            w.reject(b, w.mutate(b, x -> mapDatum(x, 0, d -> field(d, 1, bytes(E)))), "a datum change besides the retire");
            w.reject(b, w.mutate(b, x -> mapOutput(x, 0, o -> withDatum(o, n.datum()))), "the node kept live (not retired)");
            w.reject(b, w.mutate(b, x -> mapOutput(x, 0, Tamper::withRefScript)), "a reference script");
            Ctx paid = new Ctx(ALICE, ada(1), in.subtract(w.minAda), b.tx.lower, b.tx.upper);
            w.reject(b, w.mutate(b, x -> {
                x.redeemers.put(RegistryWorld.spend(b.node), data(new RegistryValidator.Retire(paid)));
                mapOutput(x, 0, o -> plusLovelace(o, ada(1)));
            }), "a retire with received");
        }

        @Test
        void mintingOrBurningTheNodesUnit() {
            var b = w.retire(inst, Kind.ENTRY, B, BigInteger.ZERO);
            control(b);
            byte[] unit = keyId(inst, Kind.ENTRY, B);
            w.reject(b, w.mutate(b, x -> {
                x.mint(w.policy, unit, BigInteger.ONE.negate());
                x.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()));
                mapOutput(x, 0, o -> plusToken(o, w.policy, unit, BigInteger.ONE.negate()));
            }), "the unit burned");
            w.reject(b, w.mutate(b, x -> {
                x.mint(w.policy, unit, BigInteger.ONE);
                x.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()));
            }), "a second unit minted");
        }

        @Test
        void topUpsAndZeroBalances() {
            var b = w.retire(inst, Kind.ENTRY, B, ada(1));
            assertTrue(w.ledger.check(b.tx).accepted(), "the owner may top the retired node up");
            w.accept(w.retire(inst, Kind.BALLOT, ALICE, BigInteger.ZERO));   // a ballot holds only its deposit: no payouts
        }
    }

    // ================================================================== link

    @Nested
    class Link {
        @BeforeEach
        void setUp() {
            registry();
        }

        /** Adds input {@code x} spent with Link, continuing unchanged. */
        Ledger.Tx withPassThrough(RegistryWorld.Built b, RegistryWorld.Utxo x) {
            return w.mutate(b, b.change + 1, t -> {
                t.inputs.add(x.ref());
                t.redeemers.put(RegistryWorld.spend(x.ref()), data(new RegistryValidator.Link()));
                t.outputs.add(0, x.out());
            });
        }

        @Test
        void aSecondNodeWithLink() {
            var b = w.claim(inst, BOB, C, ada(2));
            control(b);
            w.reject(b, withPassThrough(b, w.node(inst, Kind.ENTRY, D)), "a second node spent with Link");
            w.reject(b, withPassThrough(b, w.node(inst, Kind.BALLOT, EMPTY)), "a head of the other type spent with Link");
        }

        @Test
        void linkOnOtherKinds() {
            var b = w.claim(inst, BOB, C, ada(2));
            w.reject(b, withPassThrough(b, w.instanceUtxo(inst)), "Link on the instance");
            for (int kind : new int[]{0x4F, 0x49}) {
                byte[] fake = StateHarness.fill(29, kind);
                TxOutRef r = w.ledger.fund(w.stateOut(Ledger.value(ada(3), w.policy, fake), integer(BigInteger.ZERO)));
                w.reject(b, withPassThrough(b, new RegistryWorld.Utxo(r, w.ledger.utxo(r))),
                        "Link on a " + (kind == 0x4F ? "plain object" : "intent") + " token");
            }
        }

        @Test
        void linkUnderOtherMintRedeemers() {
            w.accept(w.claim(inst, ALICE, F, ada(5)));
            var b = w.release(inst, D);
            control(b);
            w.reject(b, w.mutate(b, x -> x.redeemers.put(w.mint(), data(new RegistryValidator.BurnInstance()))),
                    "Link while the mint redeemer is BurnInstance");
        }
    }

    // ================================================================== kinds and owned actions

    @Nested
    class KindsAndActions {
        @BeforeEach
        void setUp() {
            registry();
        }

        /** Spends {@code x} alone with {@code redeemer}, continuing unchanged. */
        RegistryWorld.Built alone(RegistryWorld.Utxo x, Object redeemer, byte[] signer, Ctx ctx) {
            var b = new RegistryWorld.Built("spend");
            b.tx.inputs.add(x.ref());
            b.tx.redeemers.put(RegistryWorld.spend(x.ref()), data(redeemer));
            b.tx.outputs.add(x.out());
            if (ctx != null) b.tx.window(ctx.validFrom(), ctx.validTo());
            return w.finish(b, signer);
        }

        @Test
        void aHeadTakesOnlyLink() {
            var head = w.node(inst, Kind.ENTRY, EMPTY);
            BigInteger[] win = w.windowNow(RegistryWorld.W);
            Ctx ctx = new Ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO, win[0], win[1]);
            for (Object r : List.of(new RegistryValidator.Upgrade(BigInteger.ONE, ctx), new RegistryValidator.Retire(ctx),
                    new RegistryValidator.Release(ctx), new RegistryValidator.Recover(BOB), new RegistryValidator.Link(),
                    new RegistryValidator.SetOpen(false))) {
                var b = alone(head, r, ALICE, ctx);
                w.reject(b, b.tx, "a head spent with " + r.getClass().getSimpleName());
            }
        }

        @Test
        void aRetiredNodeTakesNoActions() {
            w.accept(w.retire(inst, Kind.ENTRY, B, BigInteger.ZERO));
            var x = w.node(inst, Kind.ENTRY, B);
            BigInteger[] win = w.windowNow(RegistryWorld.W);
            Ctx ctx = new Ctx(ALICE, BigInteger.ZERO, BigInteger.ZERO, win[0], win[1]);
            for (Object r : List.of(new RegistryValidator.Upgrade(BigInteger.ONE, ctx), new RegistryValidator.Retire(ctx),
                    new RegistryValidator.Withdraw(BigInteger.ONE, ctx))) {
                var b = alone(x, r, ALICE, ctx);
                w.reject(b, b.tx, "a retired node spent with " + r.getClass().getSimpleName());
            }
        }

        @Test
        void aStateRedeemerOnANodeAndAKeyedOneOnTheInstance() {
            var b = alone(w.node(inst, Kind.ENTRY, B), new RegistryValidator.SetOpen(false), ADMIN, null);
            w.reject(b, b.tx, "the instance's action on a node");
            b = alone(w.instanceUtxo(inst), new RegistryValidator.Change(BigInteger.ONE), ALICE, null);
            w.reject(b, b.tx, "a keyed action on the instance");
            b = alone(w.node(inst, Kind.BALLOT, ALICE), new RegistryValidator.Recover(BOB), ALICE, null);
            w.reject(b, b.tx, "an Entry action on a Ballot");
        }

        @Test
        void keyedActionsKeepTheList() {
            var b = w.upgrade(inst, B, ALICE, ada(1));
            control(b);
            int n = b.nodeOut;
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> field(d, 1, bytes(E)))), "next changed");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> field(d, 2, integer(BigInteger.ONE)))), "linkedAt changed");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> field(d, 0, bytes(StateHarness.fill(32, 0x11))))), "instance changed");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> nested(d, 3, 0, bytes(BB)))), "the key changed");
            w.reject(b, w.mutate(b, x -> mapDatum(x, n, d -> tag(d, 5))), "retired by an action");
            var bad = w.upgrade(inst, B, ALICE, ada(1), BigInteger.valueOf(7));
            w.reject(bad, bad.tx, "an @Invariant violated (tier 7)");
            // recover sets an arbitrary owner. The size rule (I13 amended) measures the datum with next padded to
            // maxKeyBytes and linkedAt at 9 bytes: find the owner length where that envelope first exceeds 384 bytes;
            // there the actual datum (next = "d", one byte) still fits, and the action is refused.
            int boundary = -1;
            for (int len = 100; len < 330 && boundary < 0; len++) {
                var t = w.recover(inst, B, StateHarness.fill(len, 0x77), List.of(ALICE));
                PlutusData.ConstrData d = datum(t.tx.outputs.get(t.nodeOut));
                var env = field(field(d, 1, bytes(new byte[64])), 2, integer(BigInteger.TWO.pow(63)));
                if (org.julclang.core.cbor.PlutusDataCborEncoder.encode(env).length > 384) boundary = len;
            }
            assertTrue(boundary > 0);
            var big = w.recover(inst, B, StateHarness.fill(boundary, 0x77), List.of(ALICE));
            int actual = org.julclang.core.cbor.PlutusDataCborEncoder.encode(datum(big.tx.outputs.get(big.nodeOut))).length;
            assertTrue(actual <= 384, "the actual datum fits: " + actual);
            w.reject(big, big.tx, "a datum whose envelope breaks the size rule (owner of " + boundary + " bytes)");
            var fits = w.recover(inst, B, StateHarness.fill(boundary - 1, 0x77), List.of(ALICE));
            control(fits);
            System.out.println("[size rule] owner of " + (boundary - 1) + " bytes accepted, " + boundary + " refused (actual datum "
                    + actual + " bytes)");
        }
    }

    // ================================================================== creation

    @Nested
    class Creation {
        @BeforeEach
        void setUp() {
            w = new RegistryWorld();
        }

        @Test
        void theHeads() {
            var b = w.createInstanceTx();
            control(b);
            byte[] inst = RegistryWorld.hex(b.list.instance());
            byte[] ballotHead = keyId(inst, Kind.BALLOT, EMPTY);
            int headOut = 1;   // the Ballot head
            w.reject(b, w.mutate(b, b.change - 1, x -> {
                x.outputs.remove(headOut);
                x.mint(w.policy, ballotHead, BigInteger.ONE.negate());
            }), "no Ballot head");
            w.reject(b, w.mutate(b, b.change + 1, x -> {
                x.mint(w.policy, ballotHead, BigInteger.ONE);
                x.outputs.add(0, x.outputs.get(headOut));
            }), "two Ballot heads");
            byte[] unknown = keyId(inst, Kind.BALLOT, EMPTY).clone();
            byte[] foo = RegistryWorld.keyIdOfType(inst, "Foo", EMPTY);
            w.reject(b, w.mutate(b, b.change + 1, x -> {
                x.mint(w.policy, foo, BigInteger.ONE);
                x.outputs.add(0, w.stateOut(Ledger.value(w.minAda, w.policy, foo), RegistryWorld.head(Kind.BALLOT, inst, EMPTY,
                        BigInteger.ZERO)));
            }), "a head of an unknown type");
            byte[] other = StateHarness.fill(32, 0x0E);
            byte[] otherHead = keyId(other, Kind.BALLOT, EMPTY);
            w.reject(b, w.mutate(b, x -> {
                x.mint(w.policy, ballotHead, BigInteger.ONE.negate());
                x.mint(w.policy, otherHead, BigInteger.ONE);
                x.outputs.set(headOut, w.stateOut(Ledger.value(w.minAda, w.policy, otherHead), RegistryWorld.head(Kind.BALLOT,
                        other, EMPTY, BigInteger.ZERO)));
            }), "a head of another instance");
            w.reject(b, w.mutate(b, x -> mapDatum(x, headOut, d -> field(d, 1, bytes(A)))), "a head with a non-empty next");
            w.reject(b, w.mutate(b, x -> mapDatum(x, headOut, d -> field(d, 2, integer(BigInteger.ONE)))), "a head with linkedAt != 0");
            w.reject(b, w.mutate(b, x -> mapDatum(x, headOut, d -> tag(d, 3))), "a Ballot head with the Entry head's constructor");
            w.reject(b, w.mutate(b, x -> mapOutput(x, headOut, o -> plusLovelace(o, BigInteger.ONE.negate()))), "a head below minAda");
            w.reject(b, w.mutate(b, x -> x.mint(w.policy, keyId(inst, Kind.ENTRY, A), BigInteger.ONE)), "an extra keyed token");
            w.reject(b, w.mutate(b, x -> x.mint(w.policy, new byte[]{(byte) 0xCA, (byte) 0xFE}, BigInteger.ONE)), "a user token");
            w.reject(b, w.mutate(b, x -> mapOutput(x, headOut, o -> withAddress(o, StateHarness.pkhAddress(ADMIN)))),
                    "a head at a key address");
            w.reject(b, w.mutate(b, x -> mapOutput(x, headOut, Tamper::withStake)), "a head at the other address variant");
            w.reject(b, w.mutate(b, x -> mapOutput(x, headOut, Tamper::withRefScript)), "a head with a reference script");
            assertTrue(unknown.length == 29);
        }

        @Test
        void aBurnDuringCreation() {
            byte[] inst = w.createInstance();
            w.accept(w.claim(inst, ALICE, B, ada(2)));
            var n = w.node(inst, Kind.ENTRY, B);
            var b = w.createInstanceTx();
            Ctx ctx = new Ctx(ALICE, BigInteger.ZERO, n.lovelace(), b.tx.lower, b.tx.upper);
            w.reject(b, w.mutate(b, x -> {
                x.inputs.add(n.ref());
                x.redeemers.put(RegistryWorld.spend(n.ref()), data(new RegistryValidator.Release(ctx)));
                x.mint(w.policy, keyId(inst, Kind.ENTRY, B), BigInteger.ONE.negate());
                x.signers.add(ALICE);
            }), "CreateInstance burning a node");
        }
    }

    // ================================================================== combinations

    @Nested
    class Combinations {
        @BeforeEach
        void setUp() {
            registry();
            w.accept(w.claim(inst, ALICE, F, ada(5)));
            // Past every cooldown, so the operations below share one window (none waits).
            w.ledger.advance(RegistryWorld.C + RegistryWorld.W);
        }

        /** {@code a} and {@code b} in one transaction, under {@code a}'s mint redeemer. */
        Ledger.Tx merged(RegistryWorld.Built a, RegistryWorld.Built b) {
            return w.mutate(a, a.change + b.tx.outputs.size() - 1, x -> {
                for (TxOutRef in : b.tx.inputs) if (!x.inputs.contains(in) && w.ledger.utxo(in).address().equals(w.state)) x.inputs.add(in);
                b.tx.redeemers.forEach((p, r) -> {
                    if (!p.equals(w.mint())) x.redeemers.put(p, r);
                });
                for (int i = 0; i < b.tx.outputs.size(); i++) if (i != b.change) x.outputs.add(0, b.tx.outputs.get(i));
                b.tx.mint.forEach((p, m) -> m.forEach((n, q) -> x.mint(RegistryWorld.hex(p), RegistryWorld.hex(n), q)));
                b.tx.signers.forEach(x.signers::add);
            });
        }

        @Test
        void twoStructuralOperations() {
            var c = w.claim(inst, BOB, C, ada(2));
            var e = w.claim(inst, BOB, E, ada(2));
            w.reject(c, merged(c, e), "two inserts under one mint redeemer");
            var r = w.release(inst, F);
            w.reject(c, merged(c, r), "an insert and a remove under the insert's redeemer");
            w.reject(r, merged(r, c), "a remove and an insert under the remove's redeemer");
        }
    }

    // ================================================================== liveness: accepted

    @Nested
    class Liveness {

        @Test
        void aNodeAtTheSizeLimitThenTheLongestKeyNearTheEndOfTime() {
            w = new RegistryWorld(Ledger.Params.mainnet(), BigInteger.TWO.pow(64).subtract(BigInteger.valueOf(10L * 60_000L)));
            inst = w.createInstance();
            byte[] first = StateHarness.fill(64, 0x41);
            // Guardians until the envelope (next padded to 64 bytes, linkedAt 9 bytes) is as large as fits 384 bytes.
            JulcList<byte[]> gs = JulcList.empty();
            int count = 0;
            RegistryWorld.Built b = null;
            for (int n = 1; n <= 8; n++) {
                JulcList<byte[]> next = JulcList.empty();
                for (int i = n; i >= 1; i--) next = next.prepend(StateHarness.fill(28, 0x20 + i));
                var t = w.claim(inst, ALICE, first, ada(2), BigInteger.ONE, next, BigInteger.ONE);
                if (!w.ledger.check(t.tx).accepted()) break;
                b = t;
                gs = next;
                count = n;
            }
            assertTrue(count >= 2, "at least two guardians fit");
            var r = w.accept(b);
            System.out.println("[liveness] node at the size limit (" + count + " guardians): " + r);
            byte[] last = StateHarness.fill(64, 0x42);
            var r2 = w.accept(w.claim(inst, BOB, last, ada(2)));
            System.out.println("[liveness] the longest key after it, upper bound near 2^64: " + r2);
            assertTrue(w.ledger.now().compareTo(BigInteger.TWO.pow(64)) < 0);
            assertEquals(count, gs.size());
        }

        @Test
        void aRemoveWhereNextIsLongerThanTheKey() {
            registry();
            byte[] longKey = StateHarness.fill(64, 0x63);
            w.accept(w.claim(inst, ALICE, longKey, ada(2)));   // b -> ccc...c (64) -> d
            w.accept(w.release(inst, B));                        // head.next := b.next = 64 bytes
            w.accept(w.release(inst, longKey));
        }

        @Test
        void raisedCoinsPerUtxoByte() {
            registry();
            var params = Ledger.Params.mainnet().withCoinsPerUtxoByte(4_310L * 4);
            w.ledger.setParams(params);
            // The anchor b (minAda + 5 ADA) still covers its relink; the head (minAda only) needs a top-up.
            var head = w.node(inst, Kind.ENTRY, EMPTY);
            BigInteger need = w.ledger.minLovelace(head.out());
            assertTrue(need.compareTo(head.lovelace()) > 0, "the parameters were raised enough: " + need);
            var b = w.claim(inst, BOB, A, ada(10));
            var r = w.ledger.check(b.tx);
            assertFalse(r.accepted(), "without a top-up the relinked head is below the ledger minimum");
            assertTrue(r.reason().contains("minimum lovelace"), r.reason());
            BigInteger topUp = need.subtract(head.lovelace());
            var topped = w.mutate(b, x -> mapOutput(x, b.anchorOut, o -> plusLovelace(o, topUp)));
            var ok = w.ledger.submit(topped);
            assertTrue(ok.accepted(), "an insert with an anchor top-up: " + ok);
            b.effect.run();
            w.checkLists();
            // A remove whose predecessor needs a top-up, and a retire with the owner's top-up.
            var rel = w.release(inst, A);
            var relHead = w.node(inst, Kind.ENTRY, EMPTY);
            BigInteger relTop = w.ledger.minLovelace(rel.tx.outputs.get(rel.anchorOut)).subtract(relHead.lovelace()).max(BigInteger.ZERO);
            var relTopped = w.mutate(rel, x -> mapOutput(x, rel.anchorOut, o -> plusLovelace(o, relTop)));
            assertTrue(w.ledger.submit(relTopped).accepted(), "a remove with a predecessor top-up");
            rel.effect.run();
            var ret = w.retire(inst, Kind.ENTRY, B, BigInteger.ZERO);
            BigInteger retTop = w.ledger.minLovelace(ret.tx.outputs.get(0)).subtract(w.minAda);
            assertTrue(retTop.signum() > 0);
            assertFalse(w.ledger.check(ret.tx).accepted(), "a retire without a top-up is below the raised minimum");
            w.accept(w.retire(inst, Kind.ENTRY, B, retTop));
        }

        @Test
        void theOrderingVectors() {
            registry();
            byte[] x80 = {(byte) 0x80};
            byte[] x7f = {0x7f};
            byte[] ab = {(byte) 0xab};
            byte[] ab00 = {(byte) 0xab, 0x00};
            w.accept(w.claim(inst, BOB, x80, ada(2)));
            w.accept(w.claim(inst, BOB, x7f, ada(2)));   // 7f < 80: before it
            w.accept(w.claim(inst, BOB, ab00, ada(2)));
            w.accept(w.claim(inst, BOB, ab, ada(2)));    // a proper prefix first: ab < ab00
            var keys = List.copyOf(w.model.get(new RegistryWorld.ListId(RegistryWorld.hex(inst), Kind.ENTRY)).keySet());
            assertEquals(List.of("62", "64", "7f", "80", "ab", "ab00"), keys);
        }
    }
}
