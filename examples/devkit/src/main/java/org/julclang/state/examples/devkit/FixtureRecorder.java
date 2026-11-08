package org.julclang.state.examples.devkit;

import com.bloxbean.cardano.client.backend.api.BackendService;
import com.bloxbean.cardano.client.transaction.spec.Transaction;
import com.bloxbean.cardano.client.transaction.util.TransactionUtil;
import com.bloxbean.cardano.client.util.HexUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.julclang.state.runtime.StateRuntime;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Records DevKit runs as fixtures, the oracle of ADR-0015's refactoring (M0). For each transaction the runtime
 * builds, one JSON file holds:
 * <ul>
 *   <li>what it is for;</li>
 *   <li>every chain read made on that thread since the previous transaction (the call's snapshot: service, method,
 *       arguments, and the result by reference: {@code {"$ref": sha256}} into {@code ../blobs}, where each distinct
 *       result is stored once);</li>
 *   <li>the transaction without vkey witnesses (unsigned bytes) and its hash.</li>
 * </ul>
 * The call's arguments are in its redeemer.
 * <p>
 * Enabled by {@code -Djoust.fixtures=<dir>} ({@link DevKit#runtime()}); nothing changes otherwise.
 */
public final class FixtureRecorder {

    static final ObjectMapper JSON = new ObjectMapper().disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final Path dir;
    private final AtomicInteger seq = new AtomicInteger();
    private final ThreadLocal<ArrayNode> reads = ThreadLocal.withInitial(JSON::createArrayNode);

    private final Path blobs;

    FixtureRecorder(Path dir) throws IOException {
        this.dir = dir;
        this.blobs = dir.resolveSibling("blobs");
        Files.createDirectories(dir);
        Files.createDirectories(blobs);
    }

    /** {@code {"$ref": sha256}} of {@code result}, stored once in the blob directory (compact JSON). */
    private com.fasterxml.jackson.databind.JsonNode blob(com.fasterxml.jackson.databind.JsonNode result) {
        try {
            byte[] bytes = JSON.writeValueAsBytes(result);
            String sha = HexUtil.encodeHexString(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            Path f = blobs.resolve(sha + ".json");
            if (!Files.exists(f)) Files.write(f, bytes);
            return JSON.createObjectNode().put("$ref", sha);
        } catch (Exception e) {
            return result;
        }
    }

    /** The backend, recording every service call. */
    BackendService wrap(BackendService backend) {
        return (BackendService) Proxy.newProxyInstance(BackendService.class.getClassLoader(), new Class<?>[]{BackendService.class},
                (proxy, m, args) -> {
                    if (m.isDefault()) return InvocationHandler.invokeDefault(proxy, m, args);
                    Object service = invoke(backend, m, args);
                    if (service != null && m.getReturnType().isInterface() && m.getName().endsWith("Service")) {
                        return service(m.getReturnType(), service);
                    }
                    return service;
                });
    }

    private Object service(Class<?> type, Object target) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) return invoke(target, m, args);
            Object result = invoke(target, m, args);
            ObjectNode read = JSON.createObjectNode();
            read.put("service", type.getSimpleName());
            read.put("method", m.getName());
            read.set("args", JSON.valueToTree(args == null ? new Object[0] : args));
            if (m.getName().startsWith("submit")) read.put("result", "(submission)");
            else read.set("result", blob(safeTree(result)));
            reads.get().add(read);
            return result;
        });
    }

    private static Object invoke(Object target, Method m, Object[] args) throws Throwable {
        try {
            return m.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private static com.fasterxml.jackson.databind.JsonNode safeTree(Object o) {
        try {
            return JSON.valueToTree(o);
        } catch (RuntimeException e) {
            return JSON.getNodeFactory().textNode("(unserialisable " + o.getClass().getName() + ")");
        }
    }

    /** The runtime's observer: writes one fixture per built transaction. */
    void built(StateRuntime.BuiltTx b) {
        try {
            Transaction copy = TransactionUtil.createCopy(b.tx());
            if (copy.getWitnessSet() != null) copy.getWitnessSet().setVkeyWitnesses(null);
            ObjectNode f = JSON.createObjectNode();
            int n = seq.incrementAndGet();
            f.put("seq", n);
            f.put("what", b.what());
            f.put("prepared", b.prepared());
            f.put("txHash", TransactionUtil.getTxHash(b.tx()));
            f.put("unsignedTx", HexUtil.encodeHexString(copy.serialize()));
            f.set("reads", reads.get());
            reads.remove();
            String name = String.format("%04d-%s.json", n, b.what().replaceAll("[^A-Za-z0-9.]+", "_"));
            Files.writeString(dir.resolve(name.length() > 120 ? name.substring(0, 115) + ".json" : name),
                    JSON.writerWithDefaultPrettyPrinter().writeValueAsString(f));
        } catch (Exception e) {
            System.err.println("  [fixtures] could not record " + b.what() + ": " + e);
        }
    }
}
