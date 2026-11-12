package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M1b review C3: a call whose waiting thread is interrupted is cancelled. Its evaluation (on the interpreter's own deep
 * thread) stops at its next step and has ended when the caller returns; the caller gets {@link Interpreter.Cancelled}
 * (no answer, never a refusal) and keeps its interrupt status.
 */
class JscV2CancelTest {

    static final String SPIN = """
            jsc 2
            package t
            contract Spin {
              state { n: Int }
              init() { return { n: 0 } }
              view spin(): Int = range(0, 1267650600228229401496703205376).fold(0, (acc, x) => acc + x)
              view loop(): Int {
                for (const x of range(0, 1267650600228229401496703205376)) { }
                0
              }
            }
            """;

    static boolean evaluatorAlive() {
        return Thread.getAllStackTraces().keySet().stream().anyMatch(t -> t.getName().equals("jsc-interpreter") && t.isAlive());
    }

    @Test
    void anInterruptedCaller_cancelsItsEvaluation() throws Exception {
        var interp = JscV2.interpreter(SPIN, "Spin.jsc", Interpreter.JDK_HASHES);
        var state = new Interpreter.Rec("State", List.of(BigInteger.ZERO));
        for (String view : List.of("spin", "loop")) {
            var thrown = new AtomicReference<Throwable>();
            var stillInterrupted = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    interp.call(view, state, List.of(), null);
                } catch (Throwable e) {
                    thrown.set(e);
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                }
            }, "caller");
            caller.start();
            Thread.sleep(300);
            assertTrue(evaluatorAlive(), "the evaluation runs on its own thread");
            caller.interrupt();
            caller.join(10_000);
            assertFalse(caller.isAlive(), "the caller returned");
            assertInstanceOf(Interpreter.Cancelled.class, thrown.get(), String.valueOf(thrown.get()));
            assertTrue(stillInterrupted.get(), "the caller keeps its interrupt");
            assertFalse(evaluatorAlive(), "the evaluation ended before the caller returned");
        }
    }
}
