-- JOUST's .jsc language server in Neovim 0.11 or later (ADR-0021 D3b, D9, §16 E4).
--
--   require('joust').setup({ path = '/absolute/path/to/joust-lsp' })
--
-- `path` is the joust-lsp of a @julclang/joust YOU installed (npm install -g @julclang/joust@<version>; then
-- `$(npm prefix -g)/bin/joust-lsp`), by absolute path. It is never looked up on PATH, never taken from a project
-- (node_modules/.bin, a workspace setting), and never started through npx: a repository you open cannot choose the
-- program that runs. A path inside the project of the buffer being opened is refused for that buffer.
-- The server runs advisory (its own bundled compiler); --project-engine is not passed (it would run the engine
-- JavaScript of every repository you open: see the docs before adding it yourself).
local M = {}

M.markers = { 'joust.config.json', 'package.json', 'settings.gradle', 'settings.gradle.kts', '.git' }

local uv = vim.uv or vim.loop

local function notify(msg, level)
  vim.notify('joust: ' .. msg, level or vim.log.levels.ERROR)
end

local function real(p)
  return p and uv.fs_realpath(p) or nil
end

-- Whether `p` is `dir` or below it (both real paths, compared by segments).
local function inside(p, dir)
  if not p or not dir then return false end
  if p == dir then return true end
  local d = dir:sub(-1) == '/' and dir or dir .. '/'
  return p:sub(1, #d) == d
end

--- Checks a server path: absolute, executable, not a package manager's runner, not a project's bin directory.
--- Returns the real path, or nil and the reason.
function M.check_path(path)
  if type(path) ~= 'string' or path == '' then
    return nil, 'no server path: call setup({ path = "/absolute/path/to/joust-lsp" }) (a joust-lsp you installed)'
  end
  local absolute = path:sub(1, 1) == '/' or path:match('^%a:[\\/]') ~= nil
  if not absolute then
    return nil, ('the server path must be absolute, not "%s": a bare name or a relative path is resolved against the project'):format(path)
  end
  if path:match('[\\/]node_modules[\\/]%.bin[\\/]') or path:match('[\\/]npx$') or path:match('[\\/]npx%.cmd$') then
    return nil, ('"%s" runs a project\'s package: use a joust-lsp you installed'):format(path)
  end
  local r = real(path)
  if not r then return nil, ('"%s" does not exist: install @julclang/joust, then give its bin/joust-lsp'):format(path) end
  if vim.fn.executable(r) ~= 1 then return nil, ('"%s" is not executable'):format(path) end
  return r
end

--- The project root of `bufnr` (the nearest marker), or the file's directory.
function M.root(bufnr)
  local name = vim.api.nvim_buf_get_name(bufnr)
  return vim.fs.root(bufnr, M.markers) or (name ~= '' and vim.fs.dirname(name)) or nil
end

function M.setup(opts)
  opts = opts or {}
  if vim.fn.has('nvim-0.11') ~= 1 then
    notify('needs Neovim 0.11 or later (vim.lsp.config)')
    return false
  end
  vim.filetype.add({ extension = { jsc = 'jsc' } })
  local server, why = M.check_path(opts.path or vim.g.joust_lsp_path)
  if not server then
    notify(why)
    return false
  end
  M.server = server
  vim.lsp.config('joust', {
    cmd = { server, '--stdio' },
    filetypes = { 'jsc' },
    -- The root decides the workspace folder the server confines its reads to; a server inside it is refused.
    root_dir = function(bufnr, on_dir)
      local root = M.root(bufnr)
      local r = real(root)
      if r and inside(server, r) then
        notify(('not starting %s: it is inside the project %s (a project cannot choose the server)'):format(server, r))
        return
      end
      local cwd = real(vim.fn.getcwd())
      if cwd and inside(server, cwd) then
        notify(('not starting %s: it is inside the working directory %s'):format(server, cwd))
        return
      end
      on_dir(root)
    end,
  })
  vim.lsp.enable('joust')
  -- Highlighting and folding from tree-sitter-jsc, when its parser is installed (EDITORS.md in the package).
  if opts.treesitter ~= false then
    vim.api.nvim_create_autocmd('FileType', {
      pattern = 'jsc',
      group = vim.api.nvim_create_augroup('joust-treesitter', { clear = true }),
      callback = function(ev)
        if pcall(vim.treesitter.start, ev.buf, 'jsc') then
          vim.wo.foldmethod = 'expr'
          vim.wo.foldexpr = 'v:lua.vim.treesitter.foldexpr()'
          vim.wo.foldenable = false
        end
      end,
    })
  end
  return true
end

return M
