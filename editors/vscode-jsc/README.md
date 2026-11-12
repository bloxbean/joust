# JOUST `.jsc` for VS Code (and Shiki)

Syntax highlighting for JOUST's contract language, `.jsc` 2 (ADR-0018) and `.jsc` v1 (ADR-0005), as a TextMate
grammar (ADR-0018 D13). It is not published to a marketplace.

- `syntaxes/jsc.tmLanguage.json`: the grammar (`scopeName` `source.jsc`). The docs site loads it into Shiki
  (`www/astro.config.mjs`, Expressive Code's `shiki.langs`), so ```` ```jsc ```` blocks are highlighted by it.
- `language-configuration.json`: comments, brackets, auto-closing pairs, indentation, and a word pattern (names,
  numbers, `#hex`).
- `package.json`: the extension manifest (language `jsc` for `*.jsc`).

**Its place (review r2 F7).** ADR-0021 D9 puts the VS Code extension at `js/vscode-joust/`. This directory holds the
grammar until then: M5a moves the grammar and the language configuration there (or reuses them from here), and this
directory goes. `engines.vscode` is already ADR-0021's verified minimum, 1.109.0. `JscTextMateTest` (julc-state-dsl)
checks that the grammar names exactly the words the front ends know: the keywords, the built-ins, the prelude and every
`std` binding.

To use it locally: copy or link this directory into `~/.vscode/extensions/` and reload VS Code.

The structural grammar is [`tree-sitter-jsc`](../../tree-sitter-jsc); this one only colours tokens, and keeps to what
a TextMate grammar can see on one line.

```sh
cd editors/vscode-jsc
npm install
npm test      # tokenises the examples with vscode-textmate and checks the scopes
```
