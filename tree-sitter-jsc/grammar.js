/**
 * @file tree-sitter grammar for JOUST's contract language: `.jsc` 2 (ADR-0018) and `.jsc` v1 (ADR-0005).
 *
 * It follows the hand-written parsers: julc-state-dsl/src/main/java/org/julclang/state/dsl/v2/{Lexer,Parser}.java for
 * `.jsc` 2 (a file starting with `jsc 2`) and julc-state-dsl/src/main/java/org/julclang/state/dsl/{Lexer,Parser}.java
 * for v1 (a file starting with `package`). Both are checked against one shared corpus (corpus/, README.md); the known
 * differences are listed in README.md.
 */

/* eslint-disable arrow-parens */
/* eslint-disable camelcase */
/* eslint-disable-next-line spaced-comment */
/// <reference types="tree-sitter-cli/dsl" />
// @ts-check

const PREC = {
  COND: 1,
  COALESCE: 2,
  OR: 3,
  AND: 4,
  EQ: 5,
  CMP: 6,
  ADD: 7,
  MUL: 8,
  UNARY: 9,
  POSTFIX: 10,
  CALL: 11,
};

/** One or more `rule`, separated by `sep`. */
const sep1 = (rule, sep) => seq(rule, repeat(seq(sep, rule)));

/** A left-associative binary operator of one precedence level (each level is shown as a `binary_expression`). */
const binary = (p, left, ops, right) => prec.left(p, seq(field('left', left), field('operator', ops), field('right', right)));

/** A level's rule, shown as `binary_expression`. */
const bin = ($, rule) => alias(rule, $.binary_expression);

module.exports = grammar({
  name: 'jsc',

  extras: $ => [/\s/, $.line_comment, $.block_comment],

  externals: $ => [
    $._index_open,       // '[' on the line of the expression before it: m[k] (v2 Parser.postfix)
    $.bytes,             // #hex with an even number of digits (both lexers)
    $._block_open,       // '{' right after a match arm's or a lambda's '=>': always a block (v2 Parser.match, lambdaBody)
    $._call_open,        // '(' on the line of the name before it: f(x); on a later line it starts a new expression (review r2 F2)
    $.refused,            // never produced: see _javascript_word
    $._error_sentinel,
  ],

  word: $ => $.identifier,

  conflicts: $ => [
    // `x {` is a record or variant literal when x is a declared type (the Java parser knows the declared names; this
    // grammar keeps both readings and drops the one that fails, e.g. `if done { … }`).
    [$._primary, $.record_literal],
    // `(a, b) => …` against a parenthesised expression.
    [$._primary, $.lambda_parameters],
    // An `if` in a block: a statement, or the block's value when it has an else and both arms end in values.
    [$.block, $._value_block],
    [$.match_arm, $._value_arm],
    [$._arm_block, $._arm_value_block],
    // `batched only` (intent only) against `batched` followed by `only <field>`, in v2 (M1c) and v1.
    [$.modifier],
    [$.v1_modifier],
  ],

  rules: {
    source_file: $ => choice($._v2_file, $._v1_file),

    // ================================================================== .jsc 2

    _v2_file: $ => seq(
      $.version,
      repeat(';'),
      $.package_declaration,
      repeat(';'),
      repeat(seq($._item, repeat(';'))),
      $.contract_declaration,
      repeat(';'),
      repeat(seq($._item, repeat(';'))),
    ),

    version: $ => seq('jsc', '2'),

    package_declaration: $ => seq('package', field('name', $.package_name)),

    package_name: $ => sep1($.identifier, '.'),

    _item: $ => choice(
      $.import_declaration,
      $.type_declaration,
      $.enum_declaration,
      $.function_declaration,
    ),

    import_declaration: $ => seq(
      'import', '{', sep1(field('name', $.identifier), ','), '}', 'from', field('source', $.string),
    ),

    type_declaration: $ => seq('type', field('name', $.identifier), '=', field('fields', $.field_list)),

    field_list: $ => seq('{', repeat(seq($.field_declaration, optional(choice(',', ';')))), '}'),

    field_declaration: $ => seq(field('name', $.identifier), ':', field('type', $.type)),

    enum_declaration: $ => seq(
      'enum', field('name', $.identifier),
      '{', repeat(seq($.variant_declaration, optional(choice(',', ';')))), '}',
    ),

    variant_declaration: $ => seq(field('name', $.identifier), optional(field('fields', $.field_list))),

    type: $ => seq(
      field('name', $.identifier),
      optional(seq('<', sep1(field('argument', $.type), ','), '>')),
    ),

    function_declaration: $ => seq(
      'fn', field('name', $.identifier), field('parameters', $.parameters), ':', field('type', $.type),
      choice(seq('=', field('value', $._expression)), field('body', $.block)),
    ),

    parameters: $ => seq('(', optional(sep1($.parameter, ',')), ')'),

    parameter: $ => seq(field('name', $.identifier), ':', field('type', $.type)),

    contract_declaration: $ => seq(
      'contract', field('name', $.identifier),
      optional(field('options', $.contract_options)),
      field('body', $.contract_body),
    ),

    contract_options: $ => seq('(', optional(sep1($.contract_option, ',')), ')'),

    contract_option: $ => seq(field('name', $.identifier), '=', field('value', $.number)),

    // Exactly one state block (v2 Parser.contract: JSC0121, JSC0126).
    contract_body: $ => seq(
      '{',
      repeat(seq($._member, repeat(';'))),
      $.state_declaration,
      repeat(';'),
      repeat(seq($._member, repeat(';'))),
      '}',
    ),

    _member: $ => choice(
      $.init_declaration,
      $.action_declaration,
      $.destroy_declaration,
      $.view_declaration,
      $.function_declaration,
      $.type_declaration,
      $.enum_declaration,
      $.params_declaration,
      $.event_declaration,
      $.invariant_declaration,
    ),

    state_declaration: $ => seq('state', field('fields', $.field_list)),

    // M2b (ADR-0009 via ADR-0018): the deploy-time parameters (read as params.f; once, JSC0431), the contract's events
    // (the variants of its Event) and its invariants.
    params_declaration: $ => seq('params', field('fields', $.field_list)),

    event_declaration: $ => seq('event', field('name', $.identifier), optional(field('fields', $.field_list))),

    invariant_declaration: $ => seq(
      'invariant', field('name', $.identifier),
      choice(seq('=', field('value', $._expression)), field('body', $.block)),
    ),

    init_declaration: $ => seq(
      'init', field('parameters', $.parameters), repeat($.modifier), field('body', $.block),
    ),

    action_declaration: $ => seq(
      'action', field('name', $.identifier), field('parameters', $.parameters), repeat($.modifier), field('body', $.block),
    ),

    destroy_declaration: $ => seq(
      'destroy', field('name', $.identifier), field('parameters', $.parameters), repeat($.modifier), field('body', $.block),
    ),

    view_declaration: $ => seq(
      'view', field('name', $.identifier), field('parameters', $.parameters), ':', field('type', $.type),
      choice(seq('=', field('value', $._expression)), field('body', $.block)),
    ),

    // M1c (ADR-0018 D10, r11): `batched` and `batched only` (intent only); `batched only owner` is `batched` and then
    // `only owner`, which the checker refuses (JSC0417), as the v2 parser reads it.
    modifier: $ => choice(
      'payable',
      'open',
      'batched',
      seq('batched', 'only'),
      seq(choice('only', 'after', 'before'), field('field', $.identifier)),
      // M2b: quorum <keys> <threshold> and authorized <credential>, each a state field or params.f.
      seq('quorum', field('keys', $._field_ref), field('threshold', $._field_ref)),
      seq('authorized', field('field', $._field_ref)),
    ),

    _field_ref: $ => choice($.identifier, $.params_field),

    params_field: $ => seq('params', '.', field('name', $.identifier)),

    // ------------------------------------------------------------------ blocks and statements

    // Statements, then the block's value (its last expression or `return e`), each optionally followed by ';'
    // (v2 Parser.block: an expression that is not last is an error, JSC0127; `return` is last, JSC0129).
    block: $ => seq(
      '{',
      repeat(choice($._statement, ';')),
      optional(seq($._block_result, repeat(';'))),
      '}',
    ),

    // A block that must end in a value: an `if` or `match` used as a value, a lambda's block.
    _value_block: $ => seq(
      '{',
      repeat(choice($._statement, ';')),
      $._block_result,
      repeat(';'),
      '}',
    ),

    // After '=>' (a match arm, a lambda), '{' is a block's, never an object's: `A => { ...state }` is refused, as in
    // the Java parser (JSC0903, JSC1005). The external scanner gives that '{' as _block_open.
    _arm_block: $ => seq(
      $._block_open,
      repeat(choice($._statement, ';')),
      optional(seq($._block_result, repeat(';'))),
      '}',
    ),

    _arm_value_block: $ => seq(
      $._block_open,
      repeat(choice($._statement, ';')),
      $._block_result,
      repeat(';'),
      '}',
    ),

    _block_result: $ => choice($.return_statement, $._expression),

    _statement: $ => choice(
      $.const_declaration,
      $.require_statement,
      $.pay_statement,
      $.send_statement,
      $.mint_statement,
      $.accept_statement,
      $.emit_statement,
      $.for_statement,
      prec.dynamic(1, $.if_statement),
      prec.dynamic(1, $.match_statement),
    ),

    const_declaration: $ => seq(
      'const', field('name', $.identifier), optional(seq(':', field('type', $.type))), '=', field('value', $._expression),
    ),

    require_statement: $ => seq(
      'require', '(', field('condition', $._expression), ',', field('message', $.string), ')',
    ),

    pay_statement: $ => seq('pay', '(', field('to', $._expression), ',', field('amount', $._expression), ')'),

    // ADR-0018 M2 (D4): the effects on native assets. send takes no datum yet (JSC0429).
    send_statement: $ => seq('send', '(', field('to', $._expression), ',', field('value', $._expression), ')'),

    mint_statement: $ => seq(choice('mint', 'burn'), '(', field('name', $._expression), ',', field('quantity', $._expression), ')'),

    accept_statement: $ => seq('accept', '(', field('value', $._expression), ')'),

    // M2b: an event of the call (a value of the contract's Event), in source order.
    emit_statement: $ => seq('emit', '(', field('event', $._expression), ')'),

    for_statement: $ => seq(
      'for', '(', 'const', field('name', $.identifier), 'of', field('iterable', $._expression), ')', field('body', $.block),
    ),

    return_statement: $ => seq('return', field('value', $._expression)),

    if_statement: $ => seq(
      'if', field('condition', $._expression), field('consequence', $.block),
      optional(seq('else', field('alternative', choice($.block, $.if_statement)))),
    ),

    // A match whose arms may end in statements (v2 Parser.block: a match is a statement unless it is the block's value).
    match_statement: $ => seq(
      'match', field('value', $._expression),
      '{', repeat1(seq($.match_arm, optional(choice(',', ';')))), '}',
    ),

    match_arm: $ => seq(
      field('pattern', $._pattern), '=>',
      field('body', choice(alias($._arm_block, $.block), $.require_statement, $.pay_statement, $.send_statement, $.mint_statement,
        $.accept_statement, $._expression)),
    ),

    _pattern: $ => choice($.some_pattern, $.variant_pattern),

    some_pattern: $ => seq('Some', '(', field('binding', $.identifier), ')'),

    variant_pattern: $ => seq(
      field('name', $.identifier),
      optional(seq('{', optional(seq(sep1($.pattern_binding, ','), optional(','))), '}')),
    ),

    pattern_binding: $ => seq(field('field', $.identifier), optional(seq(':', field('name', $.identifier)))),

    // ------------------------------------------------------------------ expressions

    _expression: $ => choice($.conditional_expression, $._coalesce),

    conditional_expression: $ => prec.right(PREC.COND, seq(
      field('condition', $._coalesce), '?', field('consequence', $._expression), ':', field('alternative', $._expression),
    )),

    // `??` is not mixed with && or || without parentheses (v2 Parser.noMixedLogic, JSC0132): its operands are below ||.
    _coalesce: $ => choice(bin($, $.coalesce_expression), $._or),

    coalesce_expression: $ => binary(PREC.COALESCE, choice(bin($, $.coalesce_expression), $._equality), '??', $._equality),

    _or: $ => choice(bin($, $.or_expression), $._and),

    or_expression: $ => binary(PREC.OR, $._or, '||', $._and),

    _and: $ => choice(bin($, $.and_expression), $._equality),

    and_expression: $ => binary(PREC.AND, $._and, '&&', $._equality),

    _equality: $ => choice(bin($, $.equality_expression), $._comparison),

    equality_expression: $ => binary(PREC.EQ, $._equality, choice('==', '!='), $._comparison),

    // Comparisons do not chain (JSC0134).
    _comparison: $ => choice(bin($, $.comparison_expression), $._additive),

    comparison_expression: $ => binary(PREC.CMP, $._additive, choice('<', '<=', '>', '>='), $._additive),

    _additive: $ => choice(bin($, $.additive_expression), $._multiplicative),

    additive_expression: $ => binary(PREC.ADD, $._additive, choice('+', '-'), $._multiplicative),

    _multiplicative: $ => choice(bin($, $.multiplicative_expression), $._unary),

    multiplicative_expression: $ => binary(PREC.MUL, $._multiplicative, choice('*', '/', '%'), $._unary),

    _unary: $ => choice($.unary_expression, $._postfix),

    unary_expression: $ => prec(PREC.UNARY, seq(field('operator', choice('!', '-')), field('operand', $._unary))),

    _postfix: $ => choice($.field_expression, $.method_call, $.index_expression, $._primary),

    field_expression: $ => prec(PREC.POSTFIX, seq(field('object', $._postfix), '.', field('field', $.identifier))),

    method_call: $ => prec(PREC.CALL, seq(
      field('object', $._postfix), '.', field('method', $.identifier), field('arguments', $.arguments),
    )),

    index_expression: $ => prec(PREC.POSTFIX, seq(
      field('object', $._postfix), $._index_open, field('index', $._expression), ']',
    )),

    _primary: $ => choice(
      $.number,
      $.bytes,
      $.string,
      $.boolean,
      $.parenthesized_expression,
      $.list_literal,
      $.object_literal,
      $.record_literal,
      $.if_expression,
      $.match_expression,
      $.call_expression,
      $.identifier,
      $._javascript_word,
    ),

    // JavaScript words the v2 parser refuses where an expression or a statement starts (null, this, new, switch, ...:
    // JSC0138–JSC0142, JSC0908, JSC1007; let, var, while, do: JSC0128, JSC1003). Each is a keyword there, followed by a
    // token the scanner never produces, so it is always an error.
    _javascript_word: $ => seq(
      choice('null', 'undefined', 'this', 'class', 'async', 'await', 'new', 'function', 'switch', 'let', 'var', 'while', 'do'),
      $.refused,
    ),

    number: $ => seq(field('value', $.integer), optional(field('unit', 'ada'))),

    boolean: _ => choice('true', 'false'),

    parenthesized_expression: $ => seq('(', $._expression, ')'),

    list_literal: $ => seq(
      '[',
      repeat(seq($._expression, ',')),
      optional(choice($._expression, $.spread_element)),
      ']',
    ),

    spread_element: $ => seq('...', field('value', $._expression)),

    object_literal: $ => $._object_body,

    // A spread goes first (JSC0144).
    _object_body: $ => seq(
      '{',
      optional(seq(
        choice($.spread_element, $.field_initializer),
        repeat(seq(',', $.field_initializer)),
        optional(','),
      )),
      '}',
    ),

    field_initializer: $ => seq(field('name', $.identifier), optional(seq(':', field('value', $._expression)))),

    record_literal: $ => seq(field('type', $.identifier), $._object_body),

    if_expression: $ => seq(
      'if', field('condition', $._expression), field('consequence', alias($._value_block, $.block)),
      'else', field('alternative', choice(alias($._value_block, $.block), $.if_expression)),
    ),

    // A match used as a value: every arm has a value (JSC0907).
    match_expression: $ => seq(
      'match', field('value', $._expression),
      '{', repeat1(seq(alias($._value_arm, $.match_arm), optional(choice(',', ';')))), '}',
    ),

    _value_arm: $ => seq(
      field('pattern', $._pattern), '=>',
      field('body', choice(alias($._arm_value_block, $.block), $._expression, $._statement_arm)),
    ),

    // A require or pay arm in a match used as a value: the Java parser reads it as a statement arm and then refuses the
    // match (JSC0907), so here it is always an error.
    _statement_arm: $ => seq(choice('require', 'pay', 'send', 'mint', 'burn', 'accept'), $.refused),

    call_expression: $ => prec(PREC.CALL, seq(field('function', $.identifier), field('arguments', $.arguments))),

    arguments: $ => seq(alias($._call_open, '('), optional(sep1(choice($.lambda, $._expression), ',')), ')'),

    // Lambdas are only arguments of calls (v2 Parser.argument).
    lambda: $ => seq(
      field('parameters', choice($.identifier, $.lambda_parameters)), '=>',
      field('body', choice(alias($._arm_value_block, $.block), $._expression)),
    ),

    lambda_parameters: $ => seq('(', optional(sep1($.identifier, ',')), ')'),

    // ================================================================== .jsc v1 (ADR-0005; frozen)

    _v1_file: $ => seq(
      'package', field('name', $.package_name), optional(';'),
      $.v1_contract_declaration,
    ),

    v1_contract_declaration: $ => seq(
      'contract', field('name', $.identifier),
      optional(seq('(', sep1($.v1_option, ','), ')')),
      '{', repeat($._v1_member), '}',
    ),

    v1_option: $ => seq(
      field('name', alias(choice('minAda', 'maxDatumBytes', 'maxValidityMs'), $.identifier)), '=', field('value', $.integer),
    ),

    _v1_member: $ => choice($.v1_state_declaration, $.v1_method_declaration, $.v1_view_declaration),

    v1_state_declaration: $ => seq('state', field('name', $.identifier), ':', field('type', $.v1_type), optional(';')),

    v1_type: _ => choice('Int', 'Bool', 'Bytes', 'Key', seq('Map', '<', 'Key', ',', 'Int', '>')),

    v1_method_declaration: $ => seq(
      choice('init', seq(choice('action', 'destroy'), field('name', $.identifier))),
      field('parameters', $.v1_parameters),
      repeat($.v1_modifier),
      field('body', $.v1_block),
    ),

    v1_view_declaration: $ => seq(
      'view', field('name', $.identifier), field('parameters', $.v1_parameters), ':', field('type', $.v1_type),
      '=', field('value', $._v1_expression), optional(';'),
    ),

    v1_parameters: $ => seq('(', optional(sep1($.v1_parameter, ',')), ')'),

    v1_parameter: $ => seq(field('name', $.identifier), ':', field('type', $.v1_type)),

    v1_modifier: $ => choice(
      'payable',
      'open',
      'batched',
      seq('batched', 'only'),
      seq(choice('only', 'after', 'before'), field('field', $.identifier)),
    ),

    v1_block: $ => seq('{', repeat(seq($._v1_statement, optional(';'))), '}'),

    _v1_statement: $ => choice(
      $.v1_let,
      $.v1_require,
      $.v1_pay,
      $.v1_if,
      $.v1_delete,
      $.v1_assignment,
      $.v1_map_assignment,
    ),

    v1_let: $ => seq('let', field('name', $.identifier), '=', field('value', $._v1_expression)),

    v1_require: $ => seq('require', field('condition', $._v1_expression), field('message', $.v1_string)),

    v1_pay: $ => seq('pay', field('amount', $._v1_expression), 'to', field('to', $._v1_expression)),

    v1_if: $ => seq(
      'if', field('condition', $._v1_expression), field('consequence', $.v1_block),
      optional(seq('else', field('alternative', choice($.v1_if, $.v1_block)))),
    ),

    v1_delete: $ => seq('delete', field('map', $.identifier), '[', field('key', $._v1_expression), ']'),

    v1_assignment: $ => seq(
      optional(seq('this', '.')), field('field', $.identifier),
      field('operator', choice('=', '+=', '-=')), field('value', $._v1_expression),
    ),

    v1_map_assignment: $ => seq(
      field('map', $.identifier), '[', field('key', $._v1_expression), ']',
      field('operator', choice('=', '+=', '-=')), field('value', $._v1_expression),
    ),

    _v1_expression: $ => $._v1_or,

    _v1_or: $ => choice(bin($, $.v1_or_expression), $._v1_and),

    v1_or_expression: $ => binary(PREC.OR, $._v1_or, '||', $._v1_and),

    _v1_and: $ => choice(bin($, $.v1_and_expression), $._v1_equality),

    v1_and_expression: $ => binary(PREC.AND, $._v1_and, '&&', $._v1_equality),

    // == and < do not chain in v1 either.
    _v1_equality: $ => choice(bin($, $.v1_equality_expression), $._v1_comparison),

    v1_equality_expression: $ => binary(PREC.EQ, $._v1_comparison, choice('==', '!='), $._v1_comparison),

    _v1_comparison: $ => choice(bin($, $.v1_comparison_expression), $._v1_additive),

    v1_comparison_expression: $ => binary(PREC.CMP, $._v1_additive, choice('<', '<=', '>', '>='), $._v1_additive),

    _v1_additive: $ => choice(bin($, $.v1_additive_expression), $._v1_multiplicative),

    v1_additive_expression: $ => binary(PREC.ADD, $._v1_additive, choice('+', '-'), $._v1_multiplicative),

    _v1_multiplicative: $ => choice(bin($, $.v1_multiplicative_expression), $._v1_unary),

    v1_multiplicative_expression: $ => binary(PREC.MUL, $._v1_multiplicative, choice('*', '/', '%'), $._v1_unary),

    _v1_unary: $ => choice(alias($.v1_unary_expression, $.unary_expression), $._v1_primary),

    v1_unary_expression: $ => prec(PREC.UNARY, seq(field('operator', choice('!', '-')), field('operand', $._v1_unary))),

    _v1_primary: $ => choice(
      alias($._v1_number, $.number),
      $.bytes,
      $.boolean,
      $.v1_this,
      $.v1_call,
      $.v1_index,
      $.identifier,
      alias($.v1_parenthesized_expression, $.parenthesized_expression),
    ),

    v1_parenthesized_expression: $ => seq('(', $._v1_expression, ')'),

    _v1_number: $ => seq(field('value', $.integer), optional(field('unit', 'ada'))),

    v1_this: $ => seq('this', '.', field('field', $.identifier)),

    v1_call: $ => prec(PREC.CALL, seq(
      field('function', choice($.identifier, alias('after', $.identifier), alias('before', $.identifier))),
      '(', optional(sep1($._v1_expression, ',')), ')',
    )),

    v1_index: $ => prec(PREC.POSTFIX, seq(field('map', $.identifier), '[', field('key', $._v1_expression), ']')),

    // ================================================================== tokens

    identifier: _ => /[A-Za-z_][A-Za-z0-9_]*/,

    integer: _ => /[0-9][0-9_]*/,

    // .jsc 2: escapes \" \\ \n; no control characters (Lexer: JSC0103–JSC0106).
    string: $ => seq('"', repeat(choice($.string_content, $.escape_sequence)), token.immediate('"')),

    string_content: _ => token.immediate(prec(1, /[^"\\\x00-\x1f\x7f]+/)),

    escape_sequence: _ => token.immediate(/\\["\\n]/),

    // v1: no escapes (v1 Lexer).
    v1_string: _ => token(/"[^"\\\n]*"/),

    line_comment: _ => token(seq('//', /[^\n]*/)),

    block_comment: _ => token(seq('/*', /[^*]*\*+([^/*][^*]*\*+)*/, '/')),
  },
});
