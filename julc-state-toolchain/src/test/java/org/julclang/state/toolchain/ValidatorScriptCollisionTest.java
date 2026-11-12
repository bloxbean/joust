package org.julclang.state.toolchain;

import com.bloxbean.cardano.client.util.HexUtil;
import org.julclang.clientlib.JulcScriptLoader;
import org.julclang.clientlib.PlutusDataAdapter;
import org.julclang.ledger.Credential;
import org.julclang.ledger.ScriptHash;
import org.julclang.state.api.Ctx;
import org.julclang.state.runtime.ContractSpec;
import org.julclang.state.runtime.StateContractHandle;
import org.julclang.state.runtime.ValidatorScripts;
import org.julclang.state.testkit.StateHarness;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 M1c review S1/F1 (the security review's repro): this class path holds each Java example and its .jsc 2 port,
 * whose validators share a simple name and so a script resource name ({@code META-INF/plutus/VaultValidator.plutus.json}).
 * The runtime ({@code StateContractHandle.scriptFor}) and the testkit ({@code StateHarness.of}) must each use the
 * script beside their own validator class, as the toolchain builds it from that contract's source.
 */
class ValidatorScriptCollisionTest {

    static String hash(ContractSpec<?> s) throws Exception {
        return HexUtil.encodeHexString(StateContractHandle.scriptFor(s, null).getScriptHash());
    }

    static ContractSpec<?> spec(String client) throws Exception {
        return (ContractSpec<?>) Class.forName(client).getField("SPEC").get(null);
    }

    @Test
    void eachValidator_getsTheScriptBesideIt() throws Exception {
        var loader = getClass().getClassLoader();
        for (String n : List.of("Vault", "Escrow", "Crowdfund", "Token", "Pool")) {
            String resource = "META-INF/plutus/" + n + "Validator.plutus.json";
            assertEquals(2, Collections.list(loader.getResources(resource)).size(), resource + ": the collision this test is about");
            String pkg = n.toLowerCase();
            String javaPath = "org/julclang/state/examples/" + pkg + "/" + n + ".java";
            var javaBuilt = Toolchain.build(Map.of(javaPath, Files.readString(Path.of("../examples/" + pkg + "/src/main/java/" + javaPath))), Map.of());
            var portBuilt = Toolchain.build(Map.of("org/julclang/state/examples/dsl/" + n + ".jsc",
                    Files.readString(JscV2M1aFourWayTest.DSL.resolve(n + ".jsc"))), Map.of());
            String javaOwn = javaBuilt.contracts().get(0).scriptHash(), portOwn = portBuilt.contracts().get(0).scriptHash();
            assertNotEquals(javaOwn, portOwn);
            var javaSpec = spec("org.julclang.state.examples." + pkg + "." + n + "Client");
            var portSpec = spec("org.julclang.state.examples.dsl." + n + "Client");
            assertEquals(javaOwn, hash(javaSpec), n + ": the Java example's runtime script");
            assertEquals(portOwn, hash(portSpec), n + ": the port's runtime script");
            assertEquals(javaOwn, HexUtil.encodeHexString(StateHarness.of(javaSpec).policy()), n + ": the Java example's harness");
            assertEquals(portOwn, HexUtil.encodeHexString(StateHarness.of(portSpec).policy()), n + ": the port's harness");
            System.out.println("[S1] " + n + "Validator: 2 copies on the class path; Java " + javaOwn.substring(0, 8) + "…, port "
                    + portOwn.substring(0, 8) + "…, each loaded beside its class");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJavaEscrowsHarness_evaluatesTheJavaEscrow() throws Exception {
        // The repro: a 27-byte seller is accepted by the Java escrow (it does not check keys) and refused by the port (D5).
        // Before the fix the Java harness evaluated the port's script and refused it.
        var h = StateHarness.of(org.julclang.state.examples.escrow.EscrowClient.SPEC);
        byte[] buyer = StateHarness.fill(28, 0xB0), seller27 = StateHarness.fill(27, 0x5E), arbiter = StateHarness.fill(28, 0xAB);
        BigInteger future = StateHarness.T0.add(BigInteger.valueOf(3_600_000)), price = BigInteger.valueOf(25_000_000);
        Ctx ctx = h.ctx(buyer, price, BigInteger.ZERO);
        var tx = h.create(new org.julclang.state.examples.escrow.EscrowValidator.CreateInstance(h.seed(), seller27, arbiter, future, ctx),
                org.julclang.state.examples.escrow.Escrow.open(seller27, arbiter, future, ctx), ctx);
        h.assertAccepted(tx);
        var hp = StateHarness.of(org.julclang.state.examples.dsl.EscrowClient.SPEC);
        Ctx pctx = hp.ctx(buyer, price, BigInteger.ZERO);
        hp.assertRejected(hp.create(new org.julclang.state.examples.dsl.EscrowValidator.CreateInstance(hp.seed(), seller27, arbiter, future, pctx),
                new org.julclang.state.examples.dsl.Escrow.State(buyer, seller27, arbiter, price, future), pctx), "a 27-byte seller (D5)");
    }

    /**
     * Review F11: JuLC's parameter checks are kept on every path that computes a script, a policy id or an address. A
     * {@code @Params} contract without its parameters is refused (its unapplied script's address would lock funds), an
     * unparameterized validator with parameters is refused, and the count must match.
     */
    @Test
    void juLCsParameterChecks_areKeptOnEveryPath() throws Exception {
        Class<?> treasury = org.julclang.state.examples.treasury.TreasuryValidator.class;
        var spec = org.julclang.state.examples.treasury.TreasuryClient.SPEC;
        var cfg = PlutusDataAdapter.convert(new org.julclang.state.examples.treasury.Treasury.Config(
                new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x6A))), BigInteger.valueOf(100_000_000)));
        // 1. parameterized, no parameters: as JuLC refuses it, on every path.
        assertThrows(IllegalArgumentException.class, () -> JulcScriptLoader.load(treasury), "JuLC's own refusal");
        var e = assertThrows(IllegalArgumentException.class, () -> ValidatorScripts.load(treasury));
        assertTrue(e.getMessage().contains("TreasuryValidator is parameterized and requires 1 param(s)"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StateContractHandle.scriptFor(spec, null));
        // Issue #73: cborHex(Class) applies the same check (an unapplied script is not the contract).
        e = assertThrows(IllegalArgumentException.class, () -> ValidatorScripts.cborHex(treasury));
        assertTrue(e.getMessage().contains("TreasuryValidator is parameterized and requires 1 param(s)"), e.getMessage());
        // Issue #73: the policy id's refusal says why (the cause's message), not only "cannot hash".
        var p = assertThrows(RuntimeException.class, () -> StateContractHandle.policyIdFor(spec, null), "the policy id of an unapplied script");
        assertTrue(p.getMessage().contains("cannot hash the") && p.getMessage().contains("TreasuryValidator is parameterized and requires 1 param(s)"),
                p.getMessage());
        assertThrows(RuntimeException.class, () -> org.julclang.state.examples.treasury.TreasuryClient.policyIdFor(null));
        // The generated client's contract(runtime, params): the handle computes its script, policy and address first.
        assertThrows(IllegalArgumentException.class, () -> org.julclang.state.examples.treasury.TreasuryClient.contract(null, null));
        assertThrows(IllegalArgumentException.class, () -> StateHarness.of(spec), "the testkit's harness");
        // 2. unparameterized, with parameters.
        Class<?> verifier = org.julclang.state.verifier.MapVerifier.class;
        assertThrows(IllegalArgumentException.class, () -> JulcScriptLoader.load(verifier, cfg), "JuLC's own refusal");
        e = assertThrows(IllegalArgumentException.class, () -> ValidatorScripts.load(verifier, cfg));
        assertTrue(e.getMessage().contains("MapVerifier is not parameterized, but 1 param(s) were provided"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> StateContractHandle.scriptFor(org.julclang.state.examples.dsl.VaultClient.SPEC,
                new org.julclang.state.examples.treasury.Treasury.Config(new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 1))), BigInteger.ONE)));
        // 3. the count.
        e = assertThrows(IllegalArgumentException.class, () -> ValidatorScripts.load(treasury, cfg, cfg));
        assertTrue(e.getMessage().contains("requires 1 param(s), but 2 were provided"), e.getMessage());
        // And with its parameters, every path agrees with JuLC's loader.
        String applied = JulcScriptLoader.load(treasury, cfg).getCborHex();
        assertEquals(applied, StateContractHandle.scriptFor(spec, new org.julclang.state.examples.treasury.Treasury.Config(
                new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x6A))), BigInteger.valueOf(100_000_000))).getCborHex());
    }

    @Test
    void parameters_areAppliedAsJuLCsLoaderAppliesThem() throws Exception {
        // A validator without a twin: the script beside it, with params applied, equals JuLC's own loading.
        var cfg = new org.julclang.state.examples.treasury.Treasury.Config(new Credential.ScriptCredential(new ScriptHash(StateHarness.fill(28, 0x6A))),
                BigInteger.valueOf(100_000_000));
        Class<?> v = org.julclang.state.examples.treasury.TreasuryValidator.class;
        var ours = ValidatorScripts.load(v, PlutusDataAdapter.convert(cfg));
        var juLC = JulcScriptLoader.load(v, PlutusDataAdapter.convert(cfg));
        assertEquals(juLC.getCborHex(), ours.getCborHex());
        var seen = new HashSet<String>();
        for (Class<?> verifier : List.of(org.julclang.state.verifier.MapVerifier.class, org.julclang.state.verifier.OutputVerifier.class)) {
            assertEquals(JulcScriptLoader.load(verifier).getCborHex(), ValidatorScripts.load(verifier).getCborHex());
            assertEquals(ValidatorScripts.load(verifier).getCborHex(), ValidatorScripts.cborHex(verifier));
            assertTrue(seen.add(ValidatorScripts.load(verifier).getCborHex()));
        }
    }
}
