package org.julclang.state.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/** ADR-0015 §13.12 M3: the JVM's default MerkleMap store is RocksDB in a directory, one per path per JVM. */
class DefaultMapStoreTest {

    @Test
    void rocksDbByDefaultSharedPerPathMemoryOnRequest() throws Exception {
        String dir = Files.createTempDirectory("joust-mpf").toString();
        var a = DefaultMapStore.open(dir);
        assertEquals("com.bloxbean.cardano.vds.mpf.rocksdb.RocksDbNodeStore", a.getClass().getName());
        assertSame(a, DefaultMapStore.open(dir), "RocksDB locks its directory: one store per path per JVM");
        byte[] k = new byte[32];
        k[0] = 7;
        a.put(k, new byte[]{1, 2, 3});
        assertArrayEquals(new byte[]{1, 2, 3}, a.get(k));
        assertTrue(Files.list(java.nio.file.Path.of(dir)).findAny().isPresent(), "kept on disk");
        assertInstanceOf(InMemoryNodeStore.class, DefaultMapStore.open("memory"));
    }
}
