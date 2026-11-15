# tree-sitter-jsc in editors

This archive is the `.jsc` tree-sitter grammar with its generated parser (`src/`) and queries, built by
`scripts/package.mjs` (ADR-0021 §16). The language server (`joust-lsp`, from `@julclang/joust`) gives diagnostics,
navigation, completion and formatting; this grammar gives highlighting and folding. The editor configurations are in
the JOUST repository's `editors/` directory, and the site's editors page explains them.

## Neovim (0.11 or later)

Build the parser into a directory on your `runtimepath` and copy the queries beside it:

```sh
mkdir -p ~/.local/share/nvim/site/parser ~/.local/share/nvim/site/queries
cc -O2 -shared -fPIC -Isrc src/parser.c src/scanner.c -o ~/.local/share/nvim/site/parser/jsc.so
cp -R neovim/queries/jsc ~/.local/share/nvim/site/queries/
```

JOUST's Neovim module (`editors/neovim/lua/joust.lua`) sets the `jsc` filetype and starts tree-sitter highlighting
when the parser is installed.

## Helix

Point a grammar at this directory and build it, then copy the queries into Helix's runtime:

```toml
# ~/.config/helix/languages.toml (with JOUST's editors/helix/languages.toml)
[[grammar]]
name = "jsc"
source = { path = "/absolute/path/to/tree-sitter-jsc-<version>" }
```

```sh
mkdir -p ~/.config/helix/runtime/grammars ~/.config/helix/runtime/queries
hx --grammar build
cp -R helix/queries/jsc ~/.config/helix/runtime/queries/
hx --health jsc
```

`helix/queries/jsc/highlights.scm` is `queries/highlights.scm` with Helix's capture names (`constant.numeric` for
`number`, `namespace` for `module`, and so on).
