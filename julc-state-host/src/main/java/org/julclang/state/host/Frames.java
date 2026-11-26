package org.julclang.state.host;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * ADR-0022 D2's framing: the LSP base protocol, {@code Content-Length: N\r\n\r\n} followed by N bytes of UTF-8 JSON, with
 * N at most {@link #MAX_BODY} in both directions, a header of at most {@link #MAX_HEADER} bytes, and a frame (header and
 * body) complete within {@link #STALL_NANOS} of its first byte. The only header fields are {@code Content-Length}
 * (required, once) and {@code Content-Type} (ignored, as LSP allows).
 */
final class Frames {

    private Frames() {}

    static final int MAX_BODY = 4 * 1024 * 1024;
    static final int MAX_HEADER = 1024;
    static final long STALL_NANOS = 10_000_000_000L;

    /** A protocol violation (D2): the host writes {@link #getMessage()} as one line to stderr and exits. */
    static final class Violation extends Exception {
        Violation(String message) {
            super(message, null, false, false);
        }
    }

    /** Reads frames; {@link #startedAt()} is the monotonic time a frame's first byte arrived, or 0 between frames. */
    static final class Reader {
        private final InputStream in;
        private volatile long startedAt;

        Reader(InputStream in) {
            this.in = in;
        }

        /** When the current frame's first byte arrived ({@link System#nanoTime()}), or 0 while waiting for one. */
        long startedAt() {
            return startedAt;
        }

        /** The next frame's body, or null at end of input between frames. */
        byte[] next() throws IOException, Violation {
            startedAt = 0;
            int first = in.read();
            if (first < 0) return null;
            startedAt = System.nanoTime();
            byte[] header = new byte[MAX_HEADER];
            int n = 0;
            header[n++] = (byte) first;
            while (!(n >= 4 && header[n - 4] == '\r' && header[n - 3] == '\n' && header[n - 2] == '\r' && header[n - 1] == '\n')) {
                if (n == MAX_HEADER) throw new Violation("frame header longer than " + MAX_HEADER + " bytes");
                int b = in.read();
                if (b < 0) throw new Violation("end of input inside a frame header");
                header[n++] = (byte) b;
            }
            long length = contentLength(new String(header, 0, n - 4, StandardCharsets.US_ASCII));
            if (length > MAX_BODY) throw new Violation("frame body of " + length + " bytes exceeds the " + MAX_BODY + "-byte cap");
            byte[] body = in.readNBytes((int) length);
            if (body.length != length) throw new Violation("end of input inside a frame body");
            if (System.nanoTime() - startedAt >= STALL_NANOS) throw new Violation("stalled frame");
            startedAt = 0;
            return body;
        }

        private static long contentLength(String header) throws Violation {
            long length = -1;
            for (String line : header.split("\r\n", -1)) {
                int colon = line.indexOf(':');
                if (colon <= 0) throw new Violation("malformed frame header line");
                String name = line.substring(0, colon).trim();
                String value = line.substring(colon + 1).trim();
                if (name.equalsIgnoreCase("Content-Length")) {
                    if (length >= 0) throw new Violation("two Content-Length headers");
                    if (!value.matches("[0-9]{1,10}")) throw new Violation("malformed Content-Length");
                    length = Long.parseLong(value);
                } else if (!name.equalsIgnoreCase("Content-Type")) {
                    throw new Violation("unexpected frame header " + (name.length() > 40 ? name.substring(0, 40) : name));
                }
                for (char c : line.toCharArray()) {
                    if (c < 0x20 || c > 0x7e) throw new Violation("non-ASCII byte in a frame header");
                }
            }
            if (length < 0) throw new Violation("frame header without Content-Length");
            return length;
        }
    }

    /**
     * Writes whole frames, one at a time, to the protocol's descriptor. {@link #writingSince()} is the monotonic time the
     * frame being written started, or 0 between frames: a frame that does not complete within {@link #STALL_NANOS} ends
     * the session (the client stopped reading), and the host's watchdog sees it without this writer's lock.
     */
    static final class Writer {
        private final OutputStream out;
        private volatile long writingSince;

        Writer(OutputStream out) {
            this.out = out;
        }

        long writingSince() {
            return writingSince;
        }

        /** Writes one frame; false if the write failed (the commit point is not reached, D8). */
        synchronized boolean write(byte[] body) {
            if (body.length > MAX_BODY) throw new IllegalStateException("an oversized frame must be replaced before writing");
            try {
                byte[] head = ("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                byte[] frame = new byte[head.length + body.length];
                System.arraycopy(head, 0, frame, 0, head.length);
                System.arraycopy(body, 0, frame, head.length, body.length);
                writingSince = System.nanoTime() | 1;   // never 0 while a frame is being written
                out.write(frame);
                out.flush();
                return true;
            } catch (IOException e) {
                return false;
            } finally {
                writingSince = 0;
            }
        }
    }
}
