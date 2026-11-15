-- JOUST's .jsc language server in Neovim 0.11 or later (ADR-0021 D3b, D9, §16 E4).
--
--   require('joust').setup({ path = '/absolute/path/to/joust-lsp' })   -- optionally node = '/absolute/path/to/node'
--
-- `path` is the joust-lsp of a @julclang/joust YOU installed (npm install -g @julclang/joust@<version>; then
-- `$(npm prefix -g)/bin/joust-lsp`), by absolute path. It is never looked up on PATH, never taken from a project
-- (node_modules/.bin, a workspace setting), and never started through npx: a repository you open cannot choose the
-- program that runs. A server inside the project of the buffer being opened is refused for that buffer (#121 F1: the
-- project as JOUST's own markers find it; an install under $HOME, with Neovim started there, is the user's own).
-- The server runs on an absolute `node` (#121 S2): the one given, else the first `node` on an ABSOLUTE PATH entry (a
-- relative entry such as ./node_modules/.bin is never used), and never one inside the buffer's project.
-- The server runs advisory (its own bundled compiler); --project-engine is not passed (it would run the engine
-- JavaScript of every repository you open: see the docs before adding it yourself).
local M = {}

-- JOUST's own markers: the buffer's project, for the server's workspace folder and the "inside the project" check.
M.project_markers = { 'joust.config.json', 'package.json', 'settings.gradle', 'settings.gradle.kts' }
-- The workspace folder: the project, else the repository.
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

--- The first `node` on an absolute PATH entry (a relative or empty entry names a directory of the project), or nil.
--- #121 F9: on Windows, none (PATH's form differs): give setup({ node = 'C:\\…\\node.exe' }) there.
function M.find_node()
  if vim.fn.has('win32') == 1 then return nil end
  for _, dir in ipairs(vim.split(vim.env.PATH or '', ':', { plain = true })) do
    if dir:sub(1, 1) == '/' then
      local p = dir:gsub('/$', '') .. '/node'
      if vim.fn.executable(p) == 1 then return p end
    end
  end
  return nil
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
  local node, nwhy = M.check_path(opts.node or vim.g.joust_node_path or M.find_node())
  if not node then
    notify('node: ' .. (nwhy or 'none on an absolute PATH entry: give setup({ node = "/absolute/path/to/node" })'))
    return false
  end
  M.server = server
  M.node = node
  vim.lsp.config('joust', {
    cmd = { node, server, '--stdio' },
    filetypes = { 'jsc' },
    -- The root decides the workspace folder the server confines its reads to; a server inside it is refused.
    root_dir = function(bufnr, on_dir)
      local root = M.root(bufnr)
      -- #121 F1: the buffer's project as JOUST's markers find it (never .git alone, never the working directory).
      local project = real(vim.fs.root(bufnr, M.project_markers))
      -- #121 F9: the home directory itself is never a project (a stray ~/package.json is not one).
      local home = real(vim.env.HOME)
      if project and home and project == home then project = nil end
      for _, p in ipairs({ server, node }) do
        if project and inside(p, project) then
          -- A warning, not an error: raised inside the FileType autocommand, an error would end the buffer's other handlers.
          notify(('not starting %s: it is inside the project %s (a project cannot choose the server)'):format(p, project), vim.log.levels.WARN)
          return
        end
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
