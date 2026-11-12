// External tokens of tree-sitter-jsc (ADR-0018 D13).
//  _index_open: '[' on the same line as the expression before it, so m[k] is an index and a '[' on the next line
//               starts a new expression (v2 Parser.postfix).
//  bytes:       '#' and an even number of hex digits; an odd number is refused by both lexers (JSC0102), so no token
//               is produced and the parse has an error.
//  _block_open: '{' where a match arm's or a lambda's body starts: there '{' always starts a block (v2 Parser.match,
//               lambdaBody), so `x => { ...x }` is refused as by the Java parser.
// Comments are skipped here too (a block comment that spans lines puts the '[' on another line, as in the lexer).
#include "tree_sitter/parser.h"

#include <stdbool.h>

enum TokenType { INDEX_OPEN, BYTES, BLOCK_OPEN, NEVER, ERROR_SENTINEL };

void *tree_sitter_jsc_external_scanner_create(void) { return NULL; }
void tree_sitter_jsc_external_scanner_destroy(void *payload) {}
unsigned tree_sitter_jsc_external_scanner_serialize(void *payload, char *buffer) { return 0; }
void tree_sitter_jsc_external_scanner_deserialize(void *payload, const char *buffer, unsigned length) {}

static bool is_hex(int32_t c) {
  return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
}

bool tree_sitter_jsc_external_scanner_scan(void *payload, TSLexer *lexer, const bool *valid_symbols) {
  if (valid_symbols[ERROR_SENTINEL]) return false;
  if (!valid_symbols[INDEX_OPEN] && !valid_symbols[BYTES] && !valid_symbols[BLOCK_OPEN]) return false;
  bool same_line = true;
  for (;;) {
    int32_t c = lexer->lookahead;
    if (c == '\n') {
      same_line = false;
      lexer->advance(lexer, true);
    } else if (c == ' ' || c == '\t' || c == '\r' || c == '\f' || c == '\v') {
      lexer->advance(lexer, true);
    } else if (c == '/') {
      lexer->advance(lexer, true);
      if (lexer->lookahead == '/') {
        while (lexer->lookahead != '\n' && !lexer->eof(lexer)) lexer->advance(lexer, true);
      } else if (lexer->lookahead == '*') {
        lexer->advance(lexer, true);
        bool star = false;
        for (;;) {
          if (lexer->eof(lexer)) return false;
          int32_t d = lexer->lookahead;
          if (d == '\n') same_line = false;
          lexer->advance(lexer, true);
          if (star && d == '/') break;
          star = d == '*';
        }
      } else {
        return false;   // a '/' that starts no comment: not ours
      }
    } else {
      break;
    }
  }
  if (valid_symbols[BLOCK_OPEN] && lexer->lookahead == '{') {
    lexer->advance(lexer, false);
    lexer->mark_end(lexer);
    lexer->result_symbol = BLOCK_OPEN;
    return true;
  }
  if (valid_symbols[INDEX_OPEN] && same_line && lexer->lookahead == '[') {
    lexer->advance(lexer, false);
    lexer->mark_end(lexer);
    lexer->result_symbol = INDEX_OPEN;
    return true;
  }
  if (valid_symbols[BYTES] && lexer->lookahead == '#') {
    lexer->advance(lexer, false);
    unsigned digits = 0;
    while (is_hex(lexer->lookahead)) {
      lexer->advance(lexer, false);
      digits++;
    }
    if (digits % 2 != 0) return false;
    lexer->mark_end(lexer);
    lexer->result_symbol = BYTES;
    return true;
  }
  return false;
}
