; Highlights for .jsc 2 and .jsc v1. When several patterns capture one node, the last one wins (as in
; tree-sitter-highlight and Neovim), so general patterns come first.

(identifier) @variable

; Built-in values of the transaction and the input state.
((identifier) @variable.builtin
  (#any-of? @variable.builtin "caller" "received" "balance" "validFrom" "validTo" "state" "params" "funds"))

; Types

(type name: (identifier) @type)
(v1_type) @type
(type_declaration name: (identifier) @type)
(enum_declaration name: (identifier) @type)
(variant_declaration name: (identifier) @constructor)
(record_literal type: (identifier) @type)
(variant_pattern name: (identifier) @constructor)
((identifier) @constructor
  (#match? @constructor "^[A-Z]"))

; Fields and parameters

(field_declaration name: (identifier) @property)
(field_initializer name: (identifier) @property)
(field_expression field: (identifier) @property)
(pattern_binding field: (identifier) @property)
(v1_this field: (identifier) @property)
(v1_state_declaration name: (identifier) @property)
(parameter name: (identifier) @variable.parameter)
(v1_parameter name: (identifier) @variable.parameter)
(lambda parameters: (identifier) @variable.parameter)
(lambda_parameters (identifier) @variable.parameter)
(modifier field: (identifier) @property)
(v1_modifier field: (identifier) @property)

; Functions, methods and the contract

(function_declaration name: (identifier) @function)
(call_expression function: (identifier) @function.call)
(v1_call function: (identifier) @function.call)
(method_call method: (identifier) @function.method.call)
(action_declaration name: (identifier) @function.method)
(destroy_declaration name: (identifier) @function.method)
(view_declaration name: (identifier) @function.method)
(v1_method_declaration name: (identifier) @function.method)
(v1_view_declaration name: (identifier) @function.method)
(contract_declaration name: (identifier) @type)
(v1_contract_declaration name: (identifier) @type)
(contract_option name: (identifier) @property)
(v1_option name: (identifier) @property)
(package_name (identifier) @module)
(import_declaration name: (identifier) @function)

; Literals

(number) @number
(bytes) @number
(boolean) @boolean
(string) @string
(v1_string) @string
(escape_sequence) @string.escape
(import_declaration source: (string) @string.special)
(line_comment) @comment
(block_comment) @comment

; Keywords

(version) @keyword.directive

[
  "package" "import" "from" "type" "enum" "fn" "contract" "state" "init" "action" "destroy" "view"
] @keyword

[
  "const" "let" "for" "of" "return" "require" "pay" "to" "delete" "this" "match"
] @keyword

[ "if" "else" ] @keyword.conditional

[ "payable" "open" "only" "after" "before" "batched" "ada" ] @keyword.modifier

; Operators and punctuation

[
  "+" "-" "*" "/" "%" "==" "!=" "<" "<=" ">" ">=" "&&" "||" "!" "??" "?" "=>" "=" "+=" "-=" "..."
] @operator

[ "(" ")" "[" "]" "{" "}" ] @punctuation.bracket

[ "," ";" ":" "." ] @punctuation.delimiter
