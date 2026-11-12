# tree-sitter-jsc

A [tree-sitter](https://tree-sitter.github.io/) grammar for JOUST's contract language: `.jsc` 2 (ADR-0018) and the
frozen `.jsc` v1 (ADR-0005), in one grammar. A file that starts with `jsc 2` is read as `.jsc` 2, one that starts with
`package` as v1, as the build dispatches them (ADR-0018 D2, D13). It serves editors that use tree-sitter (highlighting,
folding, structure); ADR-0021 D5 keeps the language server on the hand-written parser, not on this grammar. Token colouring for Shiki (the docs
site) and VS Code is the TextMate grammar in [`editors/vscode-jsc`](../editors/vscode-jsc).

```sh
cd tree-sitter-jsc
npm install          # tree-sitter-cli (a dev dependency), into node_modules/
npm run generate     # src/parser.c and friends, from grammar.js
npm test             # generate, tree-sitter's own tests (test/corpus/*.txt), then the shared corpus
npx tree-sitter parse path/to/File.jsc
npx tree-sitter query queries/highlights.scm path/to/File.jsc
```

| Path | What |
|---|---|
| `grammar.js` | the grammar: `.jsc` 2 rules first, then v1's (`v1_*` nodes) |
| `src/scanner.c` | external tokens: `[` and `(` on the line of what they follow (an index, a call), `#hex` of an even length, `{` after `=>` |
| `queries/highlights.scm`, `queries/folds.scm` | highlighting and folding |
| `test/corpus/*.txt` | tree-sitter's tests: expected syntax trees, and `:error` cases |
| `corpus/` | the **shared parse corpus** (below) |
| `test/shared-corpus.mjs` | the tree-sitter side of the shared corpus |

The generated parser (`src/parser.c`, `src/grammar.json`, `src/node-types.json`, `src/tree_sitter/`) is not committed
(`.gitignore`): `npm run generate` writes it, and CI regenerates it on every run. ADR-0018 D13 (r13) keeps it out of the
repository until the grammar is packaged (ADR-0021 M5e), when the generated files go into the release artefact.

## The shared parse corpus

The hand-written parsers (`julc-state-dsl`: `v2/Lexer`, `v2/Parser`; v1's `Lexer`, `Parser`) and this grammar are two
parsers of one language, so both read the same files and must reach the same verdict: the file parses, or it is
refused. Only parsing is compared (lexer and parser), not the checker.

- `corpus/v2/valid/*.jsc`, `corpus/v1/valid/*.jsc`: every construct; they parse.
- `corpus/v2/invalid/*.jsc`, `corpus/v1/invalid/*.jsc`: one syntax error each (JavaScript habits, `??` mixed with
  `&&`, chained comparisons, odd `#hex`, a missing state, an arm's `{ ...`, a statement arm in a match used as a
  value, …); they are refused.
- Every other `.jsc` file of the repository (examples, templates, migrated files; found, not listed; the Gradle
  template's `@@package@@` replaced by a package name) parses.
- `docs/research/**/*.jsc` (v1 probes, some refused on purpose) have the verdict `corpus/research-verdicts.txt` states.
- `corpus/known/**/*.jsc`: one file per known difference (below), with both verdicts in `corpus/known.txt`; each side
  checks its own, so a difference that disappears or changes is noticed.
- **Structure, not only verdicts** (review r2 F5): for the valid corpus files and the repository's files, the
  declarations each parser reads (package, imports, types, enums, fns, the contract's options, state fields, methods
  with their parameters and modifiers) must be the ones in `corpus/shapes.txt`, which `JscCorpusTest` writes
  (`-Djsc.writeShapes=true`) and checks for Java, and `test/shared-corpus.mjs` checks for tree-sitter.

The Java side is `julc-state-dsl/src/test/java/org/julclang/state/dsl/JscCorpusTest.java`
(`./gradlew :julc-state-dsl:test --tests '*JscCorpusTest*'`); the tree-sitter side is `npm test` here. A new file in
`docs/research` needs a line in `research-verdicts.txt` (`JscCorpusTest.main` prints them). A `.jsc` file elsewhere in
the repository that is refused on purpose belongs in `corpus/*/invalid/` (or in a test's source text), not beside the
examples.

## Known differences

Where the two parsers do not decide alike. Each one is pinned by a file in `corpus/known/` (or, for v1 block comments,
by the research probe), listed in `corpus/known.txt` with both verdicts.

- **v1 block comments.** The v1 lexer has only `//` comments; tree-sitter's extras are global, so this grammar accepts
  `/* … */` in a v1 file too (`docs/research/…/probes/26_block_comment.jsc`).
- **Constructors.** The Java parser reads `Name {` as a record or variant literal only when the file declares `Name`
  as a type or variant (and the brace looks like an object). This grammar keeps both readings for any name and keeps
  the one that parses, so `if Ready { x }`, where `Ready` is a declared type, is an `if` here and an error in Java. A
  misspelt `return` (`retun { ...state, … }`) is a constructor literal here, so the typo shows no syntax error.
- **File-level lexical rules of `.jsc` 2** are not grammar rules: format characters (JSC0145), line ends other than LF
  and CRLF (JSC0147) and a Java keyword as a package segment (JSC0111) are refused only by the Java front end. A file
  that starts with a byte-order mark parses here; the Java dispatch reads it as v1.
- **`--x`.** The Java lexer reads `--` as one token and refuses it (JSC0135); here it is two minus signs.
- **`Some` as a bare pattern** (a variant named `Some`): the Java parser accepts it; here `Some` in a pattern needs
  `(name)`.
- **Names.** The v1 lexer accepts non-ASCII letters in names; this grammar's names are ASCII. In both versions the
  hand-written parsers refuse a keyword where a name is expected; this grammar reads a keyword as a name wherever no
  keyword is expected (`return else { … }` is `return` of a constructor literal named `else`).
- **Which reading of a final `if` or `match`.** In a block, a trailing `if … else …` or `match` whose arms all end in
  values is the block's value in the Java parser; both readings parse here and the statement one is kept (dynamic
  precedence). The verdict is the same.
