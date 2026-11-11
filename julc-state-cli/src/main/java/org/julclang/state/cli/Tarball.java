package org.julclang.state.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * An npm package tarball, extracted with limits (ADR-0017 D3 step 4, I12): regular files and directories only, every
 * path relative and under {@code package/}, at most {@link #MAX_BYTES} of content and {@link #MAX_ENTRIES} entries.
 * Anything else (links, devices, absolute paths, {@code ..}) refuses the whole tarball.
 */
final class Tarball {

    static final long MAX_BYTES = 1 << 20;
    static final int MAX_ENTRIES = 1000;
    /** The whole decompressed stream: contents, headers, pax records and padding (PR #55 IC1). */
    static final long STREAM_BYTES = 4L << 20;

    /** {@code in}, failing once more than {@code limit} bytes have been read from it. */
    static InputStream budget(InputStream in, long limit) {
        return new java.io.FilterInputStream(in) {
            long read;

            private int count(int n) throws IOException {
                if (n > 0 && (read += n) > limit) throw new IOException("tarball decompresses to more than " + limit + " bytes");
                return n;
            }

            @Override
            public int read() throws IOException {
                int b = super.read();
                if (b >= 0) count(1);
                return b;
            }

            @Override
            public int read(byte[] buf, int off, int len) throws IOException {
                return count(super.read(buf, off, len));
            }

            @Override
            public long skip(long n) throws IOException {
                byte[] sink = new byte[8192];
                long done = 0;
                while (done < n) {
                    int r = read(sink, 0, (int) Math.min(sink.length, n - done));
                    if (r < 0) break;
                    done += r;
                }
                return done;
            }
        };
    }

    static final class Bad extends Exception {
        Bad(String message) {
            super(message);
        }
    }

    private Tarball() {
    }

    /** Extracts {@code tgz} into the new directory {@code into}; returns {@code into}. Any read error refuses it. */
    static Path extract(Path tgz, Path into) throws IOException, Bad {
        try {
            return extractUnchecked(tgz, into);
        } catch (java.io.EOFException | java.util.zip.ZipException e) {
            throw new Bad("tarball is truncated or not gzip: " + e.getMessage());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().startsWith("tarball")) throw new Bad(e.getMessage());
            throw e;
        }
    }

    private static Path extractUnchecked(Path tgz, Path into) throws IOException, Bad {
        Files.createDirectories(into);
        Path root = into.toRealPath();
        long total = 0;
        int entries = 0;
        try (InputStream in = budget(new GZIPInputStream(Files.newInputStream(tgz)), STREAM_BYTES)) {
            byte[] header = new byte[512];
            String paxPath = null;
            while (true) {
                if (in.readNBytes(header, 0, 512) < 512) throw new Bad("tarball ends early");
                if (isZero(header)) return into;
                if (++entries > MAX_ENTRIES) throw new Bad("tarball has too many entries");
                long size = octal(header, 124, 12);
                char type = (char) header[156];
                long padded = (size + 511) / 512 * 512;
                if (type == 'x' || type == 'g') {          // pax headers: a long path may be given here
                    if (size > 64 * 1024) throw new Bad("tarball pax header too large");
                    String pax = new String(in.readNBytes((int) size), StandardCharsets.UTF_8);
                    in.skipNBytes(padded - size);
                    if (type == 'x') paxPath = paxValue(pax, "path");
                    continue;
                }
                String name = paxPath != null ? paxPath : name(header);
                paxPath = null;
                if (name.indexOf('\0') >= 0) throw new Bad("tarball entry with a NUL in its name");
                Path rel;
                try {
                    rel = Path.of(name).normalize();
                } catch (java.nio.file.InvalidPathException e) {
                    throw new Bad("tarball entry with an invalid name");
                }
                if (name.isEmpty() || rel.isAbsolute() || rel.startsWith("..") || !rel.startsWith("package")) {
                    throw new Bad("tarball entry outside package/: " + name);
                }
                Path target = root.resolve(rel).normalize();
                if (!target.startsWith(root)) throw new Bad("tarball entry outside package/: " + name);
                switch (type) {
                    case '0', '\0' -> {
                        if (size > MAX_BYTES) throw new Bad("tarball larger than " + MAX_BYTES + " bytes");
                        total += size;
                        if (total > MAX_BYTES) throw new Bad("tarball larger than " + MAX_BYTES + " bytes");
                        Files.createDirectories(target.getParent());
                        Files.write(target, in.readNBytes((int) size));
                        in.skipNBytes(padded - size);
                    }
                    case '5' -> {
                        if (size != 0) throw new Bad("tarball directory entry with content: " + name);
                        Files.createDirectories(target);
                    }
                    default -> throw new Bad("tarball entry of type '" + type + "' is not allowed: " + name);
                }
            }
        }
    }

    /** The package's {@code bin.<name>}: a file inside the package. */
    @SuppressWarnings("unchecked")
    static Path bin(Path pkg, String name) throws Bad, IOException {
        Map<String, Object> json = Json.readSmall(pkg.resolve("package.json"))
                .orElseThrow(() -> new Bad("the package has no readable package.json"));
        Object bin = json.get("bin");
        String rel = bin instanceof String s ? s : bin instanceof Map<?, ?> m && m.get(name) instanceof String s ? s : null;
        if (rel == null) throw new Bad("the package has no bin '" + name + "'");
        Path real = pkg.toRealPath();
        Path file = real.resolve(rel).normalize();
        if (!file.startsWith(real) || !Files.isRegularFile(file)) throw new Bad("the package's bin is not inside it");
        return file;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static long octal(byte[] b, int off, int len) throws Bad {
        long v = 0;
        boolean digits = false;
        for (int i = off; i < off + len; i++) {
            byte c = b[i];
            if (c == 0 || c == ' ') {
                if (digits) break;
                continue;
            }
            if (c < '0' || c > '7') throw new Bad("tarball header is not octal");
            v = v * 8 + (c - '0');
            digits = true;
        }
        return v;
    }

    private static String cstring(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static String name(byte[] h) {
        String name = cstring(h, 0, 100);
        boolean ustar = cstring(h, 257, 6).startsWith("ustar");
        String prefix = ustar ? cstring(h, 345, 155) : "";
        return prefix.isEmpty() ? name : prefix + "/" + name;
    }

    private static String paxValue(String pax, String key) {
        for (String line : pax.split("\n")) {
            int sp = line.indexOf(' ');
            int eq = line.indexOf('=');
            if (sp > 0 && eq > sp && line.substring(sp + 1, eq).equals(key)) return line.substring(eq + 1);
        }
        return null;
    }
}
