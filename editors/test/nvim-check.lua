-- Run by editors.test.mjs: nvim --headless -i NONE -u nvim-check.lua (ADR-0021 §16 E4, E5). One case per run, from the
-- environment; the result as JSON in $JOUST_RESULT, then Neovim quits.
local env = function(k) return os.getenv(k) end
vim.opt.rtp:prepend(env('JOUST_EDITORS') .. '/neovim')
if env('JOUST_SITE') then vim.opt.rtp:prepend(env('JOUST_SITE')) end
local joust = require('joust')
local case = env('JOUST_CASE')

local function finish(t)
  local f = assert(io.open(env('JOUST_RESULT'), 'w'))
  f:write(vim.json.encode(t))
  f:close()
  vim.cmd('qa!')
end

local function clients()
  local out = {}
  for _, c in ipairs(vim.lsp.get_clients({ bufnr = 0 })) do table.insert(out, { name = c.name, cmd = c.config.cmd }) end
  return out
end

local ok, err = pcall(function()
  if case == 'diagnostics' then
    local started = joust.setup({ path = env('JOUST_LSP') })
    vim.cmd.edit(env('JOUST_FILE'))
    local got = vim.wait(120000, function()
      for _, d in ipairs(vim.diagnostic.get(0)) do
        if d.code == 'JSC0217' then return true end
      end
      return false
    end, 200)
    local codes = {}
    for _, d in ipairs(vim.diagnostic.get(0)) do table.insert(codes, d.code or '') end
    finish({ started = started, diagnostics = got, codes = codes, filetype = vim.bo.filetype, clients = clients(),
      treesitter = vim.treesitter.highlighter.active[vim.api.nvim_get_current_buf()] ~= nil })
  elseif case == 'refused' then
    -- Each path is refused by setup (no client is configured).
    local results = {}
    for _, p in ipairs(vim.split(env('JOUST_PATHS'), '\n', { trimempty = true })) do
      local _, why = joust.check_path(p)
      table.insert(results, { path = p, refused = why ~= nil, why = why })
    end
    finish({ results = results })
  elseif case == 'inside' then
    -- An absolute, executable path inside the project: setup accepts it, but the buffer's root refuses to start it.
    local started = joust.setup({ path = env('JOUST_LSP') })
    vim.cmd.edit(env('JOUST_FILE'))
    vim.wait(4000, function() return #clients() > 0 end, 100)
    finish({ started = started, clients = clients() })
  else
    finish({ error = 'unknown case ' .. tostring(case) })
  end
end)
if not ok then finish({ error = tostring(err) }) end
