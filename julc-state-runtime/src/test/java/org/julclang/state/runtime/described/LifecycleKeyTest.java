package org.julclang.state.runtime.described;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PR #62 S11: the validator's Lifecycle definition is found by its exact name, so two descriptions that differ only in
 * member order (the same canonical form, so the same manifest) read the same redeemer indices; without the exact name,
 * more than one {@code *.Lifecycle} is refused rather than taken in document order.
 */
class LifecycleKeyTest {

    static final ObjectMapper JSON = new ObjectMapper();

    static ObjectNode description(String... lifecycles) {
        var d = JSON.createObjectNode();
        var x = d.putObject("x-joust");
        x.put("package", "app");
        x.put("validator", "CounterValidator");
        var defs = d.putObject("definitions");
        for (String l : lifecycles) defs.putObject(l).put("title", "Lifecycle");
        return d;
    }

    @Test
    void theExactNameWinsWhateverTheOrder() {
        assertEquals("app.CounterValidator.Lifecycle",
                DescribedContract.lifecycleKey(description("other.Thing.Lifecycle", "app.CounterValidator.Lifecycle")));
        assertEquals("app.CounterValidator.Lifecycle",
                DescribedContract.lifecycleKey(description("app.CounterValidator.Lifecycle", "other.Thing.Lifecycle")));
    }

    @Test
    void withoutItOnlyOneLifecycleIsAccepted() {
        assertEquals("app.Old.Lifecycle", DescribedContract.lifecycleKey(description("app.Old.Lifecycle")));
        var e = assertThrows(IllegalArgumentException.class,
                () -> DescribedContract.lifecycleKey(description("a.X.Lifecycle", "b.Y.Lifecycle")));
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> DescribedContract.lifecycleKey(description()));
    }
}
