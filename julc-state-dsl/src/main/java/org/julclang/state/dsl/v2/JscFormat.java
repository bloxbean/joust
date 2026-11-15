package org.julclang.state.dsl.v2;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.julclang.state.dsl.JscException;

/**
 * ADR-0021 M5d (r9, §15): the `.jsc` formatter. One fixed style, no options. It changes only the whitespace between
 * tokens and comments: it never adds, removes, splits or reorders a token or a comment, and keeps every line break that
 * separates two of them (a line's tokens stay on that line). A file the lexer or the parser refuses is not formatted
 * (F4), and the output is checked to hold the same tokens and comments before it is returned.
 */
public final class JscFormat {

    private JscFormat() {}

    /** The formatted text, or the reason it is not formatted (the first error of a file that does not parse). */
    public record Result(String text, String error, int line, int col, String code, List<Edit> edits) {
        public boolean ok() {
            return text != null;
        }
    }

    /**
     * One change from the source to the formatted text: the source's UTF-16 offsets [{@code start}, {@code end}) become
     * {@code text}. The edits are in order and do not overlap; each is the whitespace between two items (tokens or
     * comments), the whitespace at either end of the file, or a comment's own text (line ends and trailing spaces). A
     * client's range formatting applies those in its range.
     */
    public record Edit(int start, int end, String text) {}

    /** An item of the file: a token or a comment, by its offsets in the source. */
    private record Item(int start, int end, int line, int endLine, boolean comment, boolean lineComment, String text) {}

    private static final Set<String> OPEN = Set.of("{", "(", "[");
    private static final Set<String> CLOSE = Set.of("}", ")", "]");
    /** Tokens a continuation line starts with, or the line before it ends with (binary operators, `.`, `?`, `:`, `=>`). */
    private static final Set<String> CONTINUES_LINE = Set.of("else");
    private static final Set<String> CONTINUES = Set.of("+", "-", "*", "/", "%", "==", "!=", "<=", ">=", "&&", "||", "=>", "?", ":",
            ".", "=", "|", "&", "^", "<<", ">>", "??");
    private static final Set<String> NO_SPACE_BEFORE = Set.of(")", "]", ",", ";", ".");
    private static final Set<String> NO_SPACE_AFTER = Set.of("(", "[", ".", "...");

    /**
     * The style's version (#120 F4): what the formatter changes is fixed per version. Version 1 sets indentation, line
     * starts, blank lines, the spaces after `,` and inside braces, and the spaces next to `.`, brackets, `,` and `;`;
     * other spacing within a line is kept as written. A later version that normalises more is a style change, said in the
     * release notes, and `--check` at another version may then disagree.
     */
    public static final int STYLE = 1;

    /** The file on its own: only the names it imports from {@code java:} libraries are types (#120 C3: no guessing). */
    public static Result format(String source) {
        return format(source, null, null);
    }

    /**
     * F4 (#120 C3): {@code source} formatted as the module {@code id} of {@code modules} (null: the file alone). It is
     * parsed with the type names its imports bring, read as the build reads them; when an import cannot be read (the file
     * alone, or a module that does not load), with only those it names from {@code java:} libraries. A file that then
     * does not parse is refused: never formatted by guessing which names are types.
     */
    public static Result format(String source, String id, JscModules modules) {
        if (source == null) source = "";
        String bom = source.startsWith("﻿") ? "﻿" : "";
        // F4: the file must lex and parse.
        try {
            Set<String> types = Checker.importedTypes(source, id, modules);
            Parser.parse(source, types == null ? Set.of() : types);
        } catch (JscException e) {
            return new Result(null, e.getMessage(), e.line, e.col, e.code, List.of());
        } catch (RuntimeException | StackOverflowError e) {
            return new Result(null, "the file could not be read: " + e, 1, 1, "JSC0005", List.of());
        }
        // The byte order mark stays, outside the items (the lexer's columns on line 1 do not count it).
        String body = source.substring(bom.length());
        List<Item> items;
        try {
            items = items(body);
        } catch (JscException e) {
            return new Result(null, e.getMessage(), e.line, e.col, e.code, List.of());
        }
        String rendered = render(body, items);
        String out = bom + rendered;
        // F4: the output holds the same tokens and comments, in order, with the same line breaks between them.
        List<Item> after;
        try {
            after = items(rendered);
            if (!same(items, after, body, rendered)) {
                return new Result(null, "the formatter's output differs from the source in more than whitespace (an internal error: please report it)",
                        1, 1, "JSC0005", List.of());
            }
        } catch (JscException e) {
            return new Result(null, "the formatter's output does not lex (an internal error: please report it): " + e.getMessage(), 1, 1, "JSC0005",
                    List.of());
        }
        return new Result(out, null, 0, 0, null, edits(body, items, rendered, after, bom.length()));
    }

    /** The tokens (but EOF) and comments of {@code src}, in source order, by offsets. */
    private static List<Item> items(String src) {
        int[] lineStarts = lineStarts(src);
        var comments = new ArrayList<int[]>();
        List<Lexer.Tok> toks = Lexer.lex(src, comments);
        var out = new ArrayList<Item>();
        for (Lexer.Tok t : toks) {
            if (t.kind() == Lexer.K.EOF) continue;
            int s = offset(lineStarts, t.line(), t.col());
            int e = offset(lineStarts, t.line(), t.end());
            out.add(new Item(s, e, t.line(), t.line(), false, false, src.substring(s, e)));
        }
        for (int[] c : comments) {
            int s = offset(lineStarts, c[0], c[1]);
            int e = offset(lineStarts, c[2], c[3]);
            String text = src.substring(s, e);
            if (text.startsWith("//")) {
                // #120 C4: a line comment's trailing whitespace, and the CR of its CRLF, belong to the gap after it, so the
                // line end is replaced whole (an edit never ends between CR and LF, which no LSP position names).
                String kept = text.stripTrailing();
                e = s + kept.length();
                text = kept;
            } else {
                // A block comment keeps its text; only its line ends become LF.
                text = text.replace("\r\n", "\n");
            }
            out.add(new Item(s, e, c[0], c[2], true, text.startsWith("//"), text));
        }
        out.sort((a, b) -> Integer.compare(a.start, b.start));
        return out;
    }

    /** The edits from {@code src} to {@code out}, item by item (both have the same items); offsets shifted by the BOM. */
    private static List<Edit> edits(String src, List<Item> a, String out, List<Item> b, int shift) {
        var edits = new ArrayList<Edit>();
        int at = 0, bt = 0;
        for (int i = 0; i <= a.size(); i++) {
            int as = i < a.size() ? a.get(i).start : src.length();
            int bs = i < b.size() ? b.get(i).start : out.length();
            String was = src.substring(at, as), now = out.substring(bt, bs);
            if (!was.equals(now)) edits.add(new Edit(at + shift, as + shift, now));
            if (i == a.size()) break;
            Item x = a.get(i), y = b.get(i);
            // A comment's own text: CRLF inside a block comment, a line comment's trailing whitespace.
            String xs = src.substring(x.start, x.end), ys = out.substring(y.start, y.end);
            if (!xs.equals(ys)) edits.add(new Edit(x.start + shift, x.end + shift, ys));
            at = x.end;
            bt = y.end;
        }
        return edits;
    }

    private static int[] lineStarts(String src) {
        int n = 1;
        for (int i = 0; i < src.length(); i++) if (src.charAt(i) == '\n') n++;
        int[] out = new int[n + 1];
        for (int i = 0, k = 1; i < src.length(); i++) if (src.charAt(i) == '\n') out[k++] = i + 1;
        out[n] = src.length() + 1;
        return out;
    }

    private static int offset(int[] lineStarts, int line, int col) {
        return lineStarts[Math.max(0, Math.min(line - 1, lineStarts.length - 1))] + col - 1;
    }

    private static boolean same(List<Item> a, List<Item> b, String sa, String sb) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            Item x = a.get(i), y = b.get(i);
            if (x.comment != y.comment || !x.text.equals(y.text)) return false;
            // A line break between two items stays one, and none is added (`.jsc` reads newlines as layout).
            if (i > 0 && (x.line > a.get(i - 1).endLine) != (y.line > b.get(i - 1).endLine)) return false;
        }
        return true;
    }

    /**
     * The layout: a line's indentation is one level more than the line that opened the innermost bracket still open
     * (however many brackets that line opened), a line that starts with a closing bracket goes back to its opener's
     * line, and a continuation line (it starts with an operator, `.` or `else`, or the line before ends with one) is
     * one level deeper.
     */
    private static String render(String src, List<Item> items) {
        var o = new StringBuilder(src.length() + 64);
        var open = new java.util.ArrayDeque<Integer>();   // for each open bracket, the indentation of its line
        int lineIndent = 0;
        Item prev = null;
        boolean lineOpen = false;
        String lastLineEnd = null;   // the last token of the previous output line (comments excluded)
        int prevEndLine = 0;
        for (int k = 0; k < items.size(); k++) {
            Item it = items.get(k);
            boolean newLine = prev == null || it.line > prevEndLine;
            if (newLine) {
                if (prev != null) {
                    o.append('\n');
                    // At most one blank line, none after an opening brace or before a closing one.
                    boolean blank = it.line - prevEndLine > 1 && !(!prev.comment && OPEN.contains(prev.text))
                            && !(!it.comment && CLOSE.contains(it.text));
                    if (blank) o.append('\n');
                }
                int level;
                if (!it.comment && CLOSE.contains(it.text)) {
                    level = open.isEmpty() ? 0 : open.peek();
                } else {
                    level = open.isEmpty() ? 0 : open.peek() + 1;
                    boolean continuation = !it.comment && (CONTINUES.contains(it.text) || CONTINUES_LINE.contains(it.text))
                            || lastLineEnd != null && CONTINUES.contains(lastLineEnd);
                    if (continuation) level++;
                }
                lineIndent = level;
                o.append("  ".repeat(level));
                o.append(it.text);
                lineOpen = true;
            } else {
                if (spaced(src, prev, it)) o.append(' ');
                o.append(it.text);
            }
            if (!it.comment) {
                if (OPEN.contains(it.text)) open.push(lineIndent);
                else if (CLOSE.contains(it.text) && !open.isEmpty()) open.pop();
            }
            // The last token of this output line, for the next line's continuation.
            Item next = k + 1 < items.size() ? items.get(k + 1) : null;
            boolean endsLine = next == null || next.line > it.endLine;
            if (endsLine) {
                Item lastTok = null;
                int first = lineFirst(items, k).line;
                for (int j = k; j >= 0 && items.get(j).line >= first; j--) {
                    if (!items.get(j).comment) {
                        lastTok = items.get(j);
                        break;
                    }
                }
                lastLineEnd = lastTok == null ? null : lastTok.text;
            }
            prev = it;
            prevEndLine = it.endLine;
        }
        if (lineOpen) o.append('\n');
        return o.toString();
    }

    /** The first item of the output line that item {@code k} is on. */
    private static Item lineFirst(List<Item> items, int k) {
        int j = k;
        while (j > 0 && items.get(j - 1).endLine >= items.get(j).line) j--;
        return items.get(j);
    }

    /** Whether a space goes between {@code a} and {@code b} on one line (§15's rules). */
    private static boolean spaced(String src, Item a, Item b) {
        // A trailing line comment: one space before it. Around a block comment: as the source had it.
        if (b.lineComment) return true;
        if (a.comment || b.comment) return b.start > a.end;
        String x = a.text, y = b.text;
        if (NO_SPACE_BEFORE.contains(y) || NO_SPACE_AFTER.contains(x)) return false;
        if (x.equals(",")) return true;
        if (x.equals("{")) return !y.equals("}");
        if (y.equals("}")) return !x.equals("{");
        // Elsewhere: one space where the source had any, none where it had none.
        return b.start > a.end;
    }
}
