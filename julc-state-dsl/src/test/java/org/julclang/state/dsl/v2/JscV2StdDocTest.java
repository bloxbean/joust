package org.julclang.state.dsl.v2;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ADR-0018 D8: the docs site's std library reference ({@code www/src/content/docs/languages/jsc-std.mdx}) holds exactly
 * the binding rows of {@link JscV2#bindings()}, rendered between its {@code std:begin} and {@code std:end} markers. A row
 * added, removed or changed in {@code Std} fails this test until the page is regenerated:
 * {@code ./gradlew :julc-state-dsl:test --tests '*JscV2StdDocTest*' -Djsc.writeStdDoc=true}.
 */
class JscV2StdDocTest {

    static final Path PAGE = Path.of("../www/src/content/docs/languages/jsc-std.mdx");
    static final String BEGIN = "{/* std:begin: generated from JscV2.bindings() by JscV2StdDocTest; do not edit */}";
    static final String END = "{/* std:end */}";

    /** The tables, one per module, in the order of the binding table. */
    static String render(List<JscV2.BindingRow> rows) {
        var modules = new LinkedHashMap<String, List<JscV2.BindingRow>>();
        for (var r : rows) modules.computeIfAbsent(r.module(), m -> new ArrayList<>()).add(r);
        var sb = new StringBuilder();
        modules.forEach((module, rs) -> {
            sb.append("\n### `").append(module).append("`\n\n");
            sb.append("| Function | Signature | Domain | A time value as an argument | Target (policy) |\n");
            sb.append("|---|---|---|---|---|\n");
            for (var r : rs) {
                var params = new ArrayList<String>();
                for (int i = 0; i < r.params().size(); i++) params.add(type(r, r.params().get(i), i));
                String sig = r.name() + "(" + String.join(", ", params) + "): " + type(r, r.result(), -1);
                String target = r.target() == null ? "none" : "`" + r.target() + "`";
                sb.append("| `").append(r.name()).append("` | `").append(sig).append("` | ").append(text(r.domain())).append(" | ")
                        .append(r.timeTotal() ? "yes" : "no").append(" | ").append(target).append(" (")
                        .append(r.policy().toLowerCase().replace('_', ' ')).append(") |\n");
            }
        });
        return sb.toString();
    }

    /**
     * std/list rows are generic: their placeholder element type Int is written T. nth's index stays an Int, and so does
     * indexOf's result (an index).
     */
    static String type(JscV2.BindingRow r, String t, int param) {
        if (!r.module().equals("std/list")) return t;
        if (param == 1) return r.name().equals("nth") ? t : "T";
        if (param < 0 && r.name().equals("indexOf")) return t;
        return t.replace("List<Int>", "List<T>").replace("Option<Int>", "Option<T>");
    }

    /** Prose in an MDX table cell: no JSX braces or tags, no column bars. */
    static String text(String s) {
        return s.replace("|", "\\|").replace("{", "\\{").replace("}", "\\}").replace("<", "&lt;");
    }

    @Test
    void thePageHoldsExactlyTheBindingRows() throws Exception {
        String page = Files.readString(PAGE);
        int b = page.indexOf(BEGIN), e = page.indexOf(END);
        assertTrue(b >= 0 && e > b, "the page has the std:begin and std:end markers");
        String want = render(JscV2.bindings()) + "\n";
        String have = page.substring(b + BEGIN.length(), e);
        if (Boolean.getBoolean("jsc.writeStdDoc")) {
            Files.writeString(PAGE, page.substring(0, b + BEGIN.length()) + want + page.substring(e));
            return;
        }
        assertEquals(want, have, "regenerate with -Djsc.writeStdDoc=true");
        for (var r : JscV2.bindings()) assertTrue(have.contains("| `" + r.name() + "` |"), r.name());
    }
}
