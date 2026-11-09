package org.julclang.state.runtime;

import com.bloxbean.cardano.vds.core.api.NodeStore;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * The JVM's default MerkleMap node store (ADR-0015 §13.12 M3): RocksDB (cardano-client-lib's
 * {@code RocksDbNodeStore}) in a directory, so mirrors outlive the process; in memory only when asked
 * ({@code joust.mapStore=memory}, as tests do). RocksDB is native and loaded by name: code that never needs the default
 * (the runtime engine, which is given its store) never reaches it, and its image leaves RocksDB out.
 * <p>
 * One store per directory per JVM (RocksDB locks its directory): every runtime of this process on that path shares it.
 */
final class DefaultMapStore {

    private DefaultMapStore() {}

    static final String PROPERTY = "joust.mapStore";
    static final String DEFAULT_PATH = "./.joust/mpf";
    private static final String ROCKSDB = "com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore";

    private static final Map<String, NodeStore> OPEN = new HashMap<>();

    static synchronized NodeStore open(String path) {
        String where = path != null ? path : System.getProperty(PROPERTY, DEFAULT_PATH);
        if ("memory".equals(where)) return OPEN.computeIfAbsent("memory", k -> new InMemoryNodeStore());
        Path dir = Path.of(where).toAbsolutePath().normalize();
        NodeStore s = OPEN.get(dir.toString());
        if (s != null) return s;
        try {
            Files.createDirectories(dir);
            s = (NodeStore) Class.forName(ROCKSDB).getConstructor(String.class).newInstance(dir.toString());
        } catch (ClassNotFoundException e) {
            throw new JoustException("no MerkleMap store: add cardano-client-merkle-patricia-forestry-rocksdb, or give the"
                    + " runtime one (StateRuntime.Builder.mapStore), or set -D" + PROPERTY + "=memory for tests");
        } catch (ReflectiveOperationException | java.io.IOException e) {
            Throwable cause = e instanceof java.lang.reflect.InvocationTargetException t ? t.getCause() : e;
            throw new JoustException("cannot open the MerkleMap store at " + dir + " (another process may hold it; set"
                    + " StateRuntime.Builder.mapStorePath or -D" + PROPERTY + "): " + cause.getMessage(), cause);
        }
        OPEN.put(dir.toString(), s);
        if (s instanceof AutoCloseable c) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    c.close();
                } catch (Exception ignored) {
                    // closing at exit: nothing left to do
                }
            }, "joust-map-store-close"));
        }
        return s;
    }
}
