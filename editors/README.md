# Editor configurations for `.jsc`

JOUST's language server, `joust-lsp`, in editors other than VS Code (ADR-0021 D3b, D9, §16). These editors have no
workspace trust, so both configurations start **a `joust-lsp` you installed**, by absolute path. A repository you open
never chooses the program that runs. The site's editors page (`www/src/content/docs/languages/editors.mdx`) is the
user guide.

| Path | What |
|---|---|
| `neovim/lua/joust.lua` | Neovim 0.11+: `require('joust').setup({ path = '/absolute/path/to/joust-lsp' })`. It checks the path (absolute, executable, not `npx`, not a `node_modules/.bin`, not inside the buffer's project by JOUST's markers), starts it on an absolute `node` (given, or the first on an absolute `PATH` entry), sets the `jsc` filetype, configures `vim.lsp.config('joust')`, and starts tree-sitter highlighting when the parser is installed |
| `helix/languages.toml` | Helix: the server, the `jsc` language and the grammar, with two absolute placeholders to replace |
| `test/editors.test.mjs` | The configurations' and docs' lint (always), Neovim headless and `hx --health jsc` (with the editors; CI runs them on pinned releases) |
| `test/nvim-check.lua` | The Neovim harness `editors.test.mjs` runs (`nvim --headless -u`) |

Install the server with `npm install -g @julclang/joust@<version>`; its path is `$(npm prefix -g)/bin/joust-lsp`.

```sh
node --test editors/test/editors.test.mjs                      # lint; the editor tests skip without nvim and hx
NVIM_BIN=/path/to/nvim HX_BIN=/path/to/hx JOUST_REQUIRE_EDITORS=1 node --test editors/test/editors.test.mjs
```

The Neovim and Helix tests need the toolchain engine in `js/joust/engine`, `vscode-languageserver` in
`js/node_modules`, and, for highlighting and Helix, the tree-sitter package (`cd tree-sitter-jsc && npm ci && npm run
package`).
