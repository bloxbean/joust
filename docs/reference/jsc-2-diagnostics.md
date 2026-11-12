# `.jsc` 2 diagnostics

Every error and warning of the `.jsc` 2 front end has a stable code (ADR-0018 D12, J5), printed with its
position on both build paths: `File.jsc:12:5: error[JSC0502]: …`. A code names one rule and is never reused for
another; a message may change wording, its code does not. `…` marks the parts a message fills in (names, types,
positions). This catalogue is checked against the front end's sources (`JscV2DiagnosticsTest`).

## Files (ADR-0018 D2, D12)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0001 | error | the file name … (none) … ' … ' … may contain only ASCII letters and digits, '.', '_' and '-', and must start with a letter or digit (it is embedded in the generated Java) | JscV2 |
| JSC0002 | error | the ABI manifest … differs from the committed one at line …: a datum or redeemer encoding changed, which strands deployed instances; accept it with the acceptJscAbi task (npm: joust build --accept-abi) and commit it | JscMain, joust build |
| JSC0003 | error | no ABI manifest … beside this file: the encoding of its datum and redeemers must be committed; create it with the acceptJscAbi task (npm: joust build --accept-abi), review it and commit it | JscMain, joust build |
| JSC0004 | error | the ABI manifest of … disagrees with the processor's encoding: … (an internal error of the .jsc lowering: please report it) | Toolchain |
| JSC0005 | error | internal error lowering this file: … (an internal error of the .jsc front end: please report it) | JscMain, Toolchain |

## Syntax and JavaScript habits (ADR-0018 D2, D3, D12)

**The nesting cap (J18): 64 levels**, the same on every build path (Gradle, the npm CLI's Web Image engine, editors).
The parser counts a level for each parenthesis, block, argument, match or if arm, lambda and type argument it enters
(JSC0149, at the token that would open the 65th); then it measures the tree it built, where each expression and block
is a level and a chain it does not balance (`/`, `%`, `??`, `==`/`!=`, field reads, method calls, `[ ]`, and an else-if
chain of at most 8 branches without statements used as a value) counts a level per link (JSC0150, at the first node
past the cap). Chains of `+`/`-`, `*`, `&&` and `||` are balanced as they are read, and an else-if chain is one node,
so their length is not limited. Before the cap, the Web Image's stack held about 250 nested parentheses; any stack
exhaustion that still happens is reported as JSC0005, never as a crash.

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0101 | error | unterminated comment | Lexer |
| JSC0102 | error | a #hex literal needs an even number of digits | Lexer |
| JSC0103 | error | unterminated string: a string ends on its line | Lexer |
| JSC0104 | error | control character U+%04X in a string: write printable text (\n for a new line) | Lexer |
| JSC0105 | error | unterminated string | Lexer |
| JSC0106 | error | unknown escape \ | Lexer |
| JSC0107 | error | unexpected character ' … ': write … for the logical operator | Lexer |
| JSC0108 | error | unexpected character ' … ' | Lexer |
| JSC0109 | error | expected ' … ' but found | Parser |
| JSC0110 | error | expected … but found | Parser |
| JSC0111 | error | ' … ' is a Java keyword, so it cannot be a package segment | Parser |
| JSC0112 | error | a .jsc 2 file starts with 'jsc 2' | Parser |
| JSC0113 | error | this compiler reads 'jsc 2' files | Parser |
| JSC0114 | error | one contract per file | Parser |
| JSC0115 | error | functions are written 'fn name(x: Int): Int = …' in .jsc | Parser |
| JSC0116 | error | there are no classes in .jsc: declare records with 'type Name = { f: T }' and the contract with 'contract Name { … }' | Parser |
| JSC0117 | error | a file holds imports, types, fns and one contract: put constants in a fn (fn limit(): Int = 10) | Parser |
| JSC0118 | error | expected import, type, enum, fn or contract but found | Parser |
| JSC0119 | error | the file declares no contract | Parser |
| JSC0120 | error | expected an integer | Parser |
| JSC0121 | error | the state is declared once: state { … } | Parser |
| JSC0122 | error | the state is a block: state { count: Int, owner: Key } | Parser |
| JSC0123 | error | there is no ' … ' in .jsc: a call runs in one transaction; write 'action name(…) { … }' | Parser |
| JSC0124 | error | a contract holds state, init, actions, destroys, views, fns and types: put a constant in a fn (fn limit(): Int = 10) | Parser |
| JSC0125 | error | expected state, init, action, destroy, view, fn, type or enum but found | Parser |
| JSC0126 | error | the contract declares no state { … } | Parser |
| JSC0127 | error | an expression's value is unused here: only the last one is the block's value | Parser |
| JSC0128 | error | values are immutable in .jsc: write 'const … = …' | Parser |
| JSC0129 | error | return is the last statement of a block: a block's value is its last expression, so there is no early return (write if c { … } else { … } as the last expression) | Parser |
| JSC0130 | error | values are immutable in .jsc: build a new one, e.g. return { ...state, x: x + 1 } | Parser |
| JSC0131 | error | require's message is a string: require(cond, "why") | Parser |
| JSC0132 | error | ?? cannot be mixed with … without parentheses: write (a ?? b) … c or a ?? (b … c) | Parser |
| JSC0133 | error | write … : .jsc equality is always by value | Parser |
| JSC0134 | error | comparisons do not chain: use && | Parser |
| JSC0135 | error | values are immutable in .jsc: there is no … ; build a new value, e.g. return { ...state, x: x + 1 } | Parser |
| JSC0136 | error | unexpected end of file in an expression | Parser |
| JSC0137 | error | an if used as a value needs an else, and both arms must end in a value | Parser |
| JSC0138 | error | there is no … in .jsc: use Option<T> with None and Some(x) | Parser |
| JSC0139 | error | there is no 'this' in .jsc: a bare field name, or state.field, reads the state | Parser |
| JSC0140 | error | there are no classes in .jsc: records are 'type Name = { f: T }', built with Name { f: e } | Parser |
| JSC0141 | error | there is no ' … ' in .jsc: everything a call computes happens in its transaction | Parser |
| JSC0142 | error | there is no 'new' in .jsc: build a record with Name { f: e } | Parser |
| JSC0143 | error | unexpected … in an expression | Parser |
| JSC0144 | error | a spread goes first: { ...state, f: e } | Parser |
| JSC0145 | error | invisible format character U+…: .jsc files may not hold bidirectional controls or other format characters, even in comments and strings, so that the text reads as it compiles | Lexer |
| JSC0146 | error | ' … ' (U+…) is not an ASCII letter (or digit): names in .jsc are written with ASCII letters, digits and '_', so that no two names can look alike | Lexer |
| JSC0147 | error | U+… breaks a line for some readers but not for .jsc: end lines with LF (or CRLF), so that a comment cannot hide the code after it | Lexer |
| JSC0148 | error | control character U+…: .jsc files hold no control characters but TAB and line ends (LF or CRLF), even in comments and strings, so that the text reads as it compiles | Lexer |
| JSC0149 | error | this nests deeper than … levels (each parenthesis, block, argument, arm, lambda and type argument opens one): name a part with const or move it into a fn (ADR-0018 J18) | Parser |
| JSC0150 | error | this expression nests deeper than … levels: a chain of /, %, ??, ==, field reads, method calls or [ ] counts a level per link; name a part with const or move it into a fn (ADR-0018 J18) | Depth |

## Names and declarations (ADR-0018 D3, J10)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0201 | error | the function … is declared twice or shadows a built-in | Checker |
| JSC0202 | error | the fn … has the name of a method of the contract; rename one (both become methods of one Java class) | Checker |
| JSC0203 | error | the method name … would clash with the generated name … (a declared type or variant: a method's redeemer is a Java class named after it, beside the contract's types and variants); rename it | Checker |
| JSC0204 | error | the method … is declared twice | Checker |
| JSC0205 | error | names start with a capital: | Checker |
| JSC0206 | error | names are written without '_' (e.g. … _ … ) | Checker |
| JSC0207 | error | the … has the contract's name; rename one (it is generated inside the contract's class) | Checker |
| JSC0208 | error | ' … ' is a built-in name, so it cannot be a state field: choose another name | Checker |
| JSC0209 | error | declares … twice | Checker |
| JSC0210 | error | names cannot contain '__' (reserved for generated code): | Checker |
| JSC0211 | error | ' … ' is reserved and cannot be a … name | Checker |
| JSC0212 | error | ' … ' is already declared; names are never redeclared or shadowed | Checker |
| JSC0213 | error | ' … ' would hide the state field or built-in of the same name; choose another name (e.g. new … ) | Checker |
| JSC0214 | error | ' … ' is a built-in name | Checker |
| JSC0215 | error | ' … ' is never used (a const that is not a check must be used; use require(…) for checks) | Checker |
| JSC0216 | error | ' … ' is a name the generated client uses for its own values; rename the parameter | Checker |
| JSC0217 | error | unknown name ' … ' | Checker |
| JSC0218 | error | spread a named value: { ...state, … } or { ...r, … } | Checker |
| JSC0219 | error | is given twice | Checker |
| JSC0220 | error | ' … ' is a name the generated validator or client uses for its own types; choose another … name | Checker |
| JSC0221 | error | the fn … has the name of a declared type or variant; rename one | Checker |
| JSC0222 | error | ' … ' would hide the type / enum / variant / fn …; a name is bound once and never replaces a declaration | Checker |

## Types and values (ADR-0018 D5, D6)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0301 | error | the type … contains itself; recursive types are not supported | Checker |
| JSC0302 | error | the enum … contains itself; recursive types are not supported | Checker |
| JSC0303 | error | the state needs at least one field | Checker |
| JSC0304 | error | comes with Value and Address in a later milestone of .jsc 2 (ADR-0018 §8: M2) | Checker |
| JSC0305 | error | Option<Option<…>> is not allowed: JavaScript cannot tell Some(None) from None | Checker |
| JSC0306 | error | comes in a later milestone of .jsc 2 (ADR-0018 §8: M2) | Checker |
| JSC0307 | error | unknown type ' … ' (Int, Bool, Bytes, Key, Hash, List<T>, Option<T>, Map<K, V>, BigMap<K, V>, … or a declared type or enum) | Checker |
| JSC0308 | error | a … 's keys are Bytes, Key or Hash (at most 64 bytes, canonical order: ADR-0018 D5), not | Checker |
| JSC0309 | error | duplicate parameter | Checker |
| JSC0310 | error | ' … ': no state field | Checker |
| JSC0311 | error | ' … ' needs a … field; … is | Checker |
| JSC0312 | error | a fn ends in its value | Checker |
| JSC0313 | error | there is no early return in .jsc: a block's value is its last expression; make this if … the block's last expression, with an else (if c { … } else { … }) | Checker |
| JSC0314 | error | this if is a statement (it is not the block's last expression, or it has no else), … so its arms end in statements, not a value | Checker |
| JSC0315 | error | a block used as a value holds only const and require statements | Checker |
| JSC0316 | error | takes one argument | Checker |
| JSC0317 | error | strings are only require messages and utf8("…") arguments | Checker |
| JSC0318 | error | the type of None is unknown here: give it one (const o: Option<Int> = None) | Checker |
| JSC0319 | error | ' … ' comes in a later milestone of .jsc 2 (ADR-0018 §8) | Checker |
| JSC0320 | error | has no field | Checker |
| JSC0321 | error | . … needs a record, a List or a Map, not | Checker |
| JSC0322 | error | Some(None) is not allowed: an Option holds a value, and JavaScript cannot tell Some(None) from None (ADR-0018 D5) | Checker |
| JSC0323 | error | : this literal has … bytes; … has 28 | Checker |
| JSC0324 | error | unknown function ' … ' | Checker |
| JSC0325 | error | takes … argument … s | Checker |
| JSC0326 | error | takes … arguments | Checker |
| JSC0327 | error | . … (…) needs a List or a Map; … has no methods | Checker |
| JSC0328 | error | … on a List (or Map) of Options would be an Option<Option<…>>, which .jsc does not have (JavaScript cannot tell Some(None) from None): use xs.isEmpty or a fold (a Map: test m.has(k)) | Checker |
| JSC0329 | error | unknown type | Checker |
| JSC0330 | error | this object's type is unknown here: write Type { … }, or give the const a type | Checker |
| JSC0331 | error | needs … (or start with ...state) | Checker |
| JSC0332 | error | needs | Checker |
| JSC0333 | error | the type of this comparison is unknown: give one side a type (const none: Option<Int> = None) | Checker |
| JSC0334 | error | compares two Ints or two Bytes, not … and | Checker |
| JSC0335 | error | unknown operator | Checker |
| JSC0336 | error | cannot compare … with | Checker |
| JSC0337 | error | None ?? d is always d: write d | Checker |
| JSC0338 | error | ?? needs an Option on its left, not | Checker |
| JSC0339 | error | expected … but this is | Checker |
| JSC0340 | error | the type … nests other types more than … deep (ADR-0018 J18): flatten it | Checker |

## Methods, effects and audiences (ADR-0018 D4, D10)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0401 | error | the contract needs an init(…) | Checker |
| JSC0402 | error | is out of range | Checker |
| JSC0403 | error | unknown contract option ' … ' (minAda, maxDatumBytes, maxValidityMs) | Checker |
| JSC0404 | error | an init … a view … cannot pay (ADR-0018 D4) | Checker |
| JSC0405 | error | an init takes only 'payable' | Checker |
| JSC0406 | error | a destroy cannot be payable | Checker |
| JSC0407 | error | unknown modifier | Checker |
| JSC0408 | error | can pay out, so it must say who may call it: add 'only <field>' or 'open' (ADR-0018 D4) | Checker |
| JSC0409 | error | a destroy ends the instance, so it must pay out what it holds: pay(…) at least once (ADR-0018 D4) | Checker |
| JSC0410 | error | an init returns the first state: return { … } … an action evaluates to the next state: return { ...state, … } | Checker |
| JSC0411 | error | a destroy ends the instance: it has no next state | Checker |
| JSC0412 | error | a view evaluates to its value | Checker |
| JSC0413 | error | pay(…) is allowed only in an action or destroy, not in a fn, view, lambda or value block (ADR-0018 D4) | Checker |
| JSC0414 | error | 'state' is the input state of an action, destroy or view | Checker |
| JSC0415 | error | is part of the transaction: it is not available in a view or fn (pass it as an argument) | Checker |
| JSC0416 | error | a require message starting '#word:' is reserved for the checks the lowering generates (ADR-0018 D12): reword it | Checker |

## Time (ADR-0018 D11, J6)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0501 | error | a time value sets this payout: write pay(to, max(0, …)) so its sign cannot … make the call fail (ADR-0018 D11) | Checker |
| JSC0502 | error | a time value cannot decide here: compare times only with after(t) or before(t) in a require (ADR-0018 D11) | Checker |
| JSC0503 | error | (t) needs the transaction: not in a view or fn | Checker |
| JSC0504 | error | (t) is not used inside a lambda (ADR-0018 D11): require it outside the list method | Checker |
| JSC0505 | error | this threshold depends on the current call's validity window, which can invert … (t): use a stored or given time (ADR-0018 D11) | Checker |
| JSC0506 | error | (t) holds for the whole validity window: use it only at the top of a require, under && or \|\| | Checker |
| JSC0507 | error | can fail, so a time value cannot become … asKey … a key … a hash … (ADR-0018 D11) | Checker |
| JSC0508 | error | reads the validity window: it is not available in a view or fn (pass it as an argument) | Checker |
| JSC0509 | error | can fail for some arguments, so a time value cannot reach it (ADR-0018 D11) | Checker |
| JSC0510 | error | nth fails for a negative index, so a time value cannot be its index (ADR-0018 D11) | Checker |
| JSC0511 | error | a time value cannot decide here: indexOf compares it (ADR-0018 D11) | Checker |
| JSC0512 | error | fails for a negative count, so a time value cannot be its count (ADR-0018 D11) | Checker |
| JSC0513 | error | a time value cannot decide here: contains compares it (ADR-0018 D11) | Checker |
| JSC0514 | error | a time value cannot decide … 's predicate (ADR-0018 D11) | Checker |
| JSC0515 | error | a time value cannot be a divisor: it could make the call fail (ADR-0018 D11) | Checker |
| JSC0516 | error | a time value cannot be a bound of range: the number of steps, and so the budget, would … depend on the window (ADR-0018 D11) | Checker |
| JSC0517 | error | a time value cannot be a map key: … would depend on the validity window (ADR-0018 D11) | Checker |
| JSC0518 | error | a time value cannot be stored in a BigMap<…, Int>: storing it can fail (a negative value, or … one too large to encode), so the window would decide whether the call fails (ADR-0018 D11) | Checker |

## Functions and recursion (ADR-0018 D7, J9)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0601 | error | … calls itself from inside a lambda, an if or match arm, a value block or a ?? default: the generated helper would join a cycle that is not a self-cycle (ADR-0018 D7, J9); move the recursive call out | Checker |
| JSC0602 | error | is part of a mutual recursion: only direct self-recursion is allowed (ADR-0018 D7) | Checker |

## Imports and the standard library (ADR-0018 D8, D9, J7)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0701 | error | expected a module string such as "std/crypto" | Parser |
| JSC0702 | error | ' … ' is not in … (it is in … ) | Checker |
| JSC0703 | error | ' … ' is imported and also declared as a fn; rename the fn | Checker |
| JSC0704 | error | is in … : import { … } from " … " | Checker |

## Map and BigMap (ADR-0018 D5, D7, J14)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0801 | error | a Map has a key and a value type: Map<Key, Int> | Checker |
| JSC0802 | error | a BigMap has a key and a value type: BigMap<Key, Int> | Checker |
| JSC0803 | error | a BigMap is a state field only: it cannot be a parameter, a record or enum field, an element … or a const (ADR-0018 D7) | Checker |
| JSC0804 | error | a BigMap holds Int or Bytes values (ADR-0018 D5), not | Checker |
| JSC0805 | error | an empty map is … .empty() | Checker |
| JSC0806 | error | a Map's size is m.size | Checker |
| JSC0807 | error | Map has no property ' … ' (size, isEmpty, keys, values) | Checker |
| JSC0808 | error | a BigMap's isEmpty means "empty before this call", so it is read only in a destroy (ADR-0018 D7) | Checker |
| JSC0809 | error | a BigMap has no property ' … ': read it with get(k), has(k) or m[k] (ADR-0018 D7) | Checker |
| JSC0810 | error | BigMap.empty() starts a map in an init; elsewhere a BigMap field keeps its own entries | Checker |
| JSC0811 | error | the new value of the BigMap field … must be … itself, changed by set/delete … , or BigMap.empty() in an init … (ADR-0018 D7) | Checker |
| JSC0812 | error | a BigMap is not comparable: it is a witness of a Merkle root, not its contents (ADR-0018 D6) | Checker |
| JSC0813 | error | a BigMap cannot be … (a value holding one, the state's type included, when its BigMap is written): it is read and changed only as its state field (ADR-0018 D7) | Checker |
| JSC0814 | error | a BigMap cannot be captured by a lambda: read the keys you need before the list method (ADR-0018 D7) | Checker |
| JSC0815 | error | has one function: … .empty() | Checker |
| JSC0816 | error | the type of this map is unknown here: give it one (const m: Map<Key, Int> = Map.empty()) | Checker |
| JSC0817 | error | BigMap.empty() is only the value of a BigMap state field in an init (ADR-0018 D7) | Checker |
| JSC0818 | error | get takes one key: m.get(k) | Checker |
| JSC0819 | error | has takes one key: m.has(k) | Checker |
| JSC0820 | error | set takes a key and a value: m.set(k, v) | Checker |
| JSC0821 | error | delete takes one key: m.delete(k) | Checker |
| JSC0822 | error | on a Map takes a two-parameter lambda: m. … ((k, v) => …) | Checker |
| JSC0823 | error | fold on a Map takes an initial value and a three-parameter lambda: m.fold(0, (acc, k, v) => acc + v) | Checker |
| JSC0824 | error | a Map fold does not accumulate the state: fold the values you need, then build the state | Checker |
| JSC0825 | error | a Map's membership test is m.has(k) | Checker |
| JSC0826 | error | Map has no method ' … ' (get, has, set, delete, any, all, fold) | Checker |
| JSC0827 | error | a BigMap is changed only in the value of its own field in the next state, { ...state, f: f.set(k, v) }, not in a require, a condition or a view (ADR-0018 D7) | Checker |
| JSC0828 | error | BigMap has no method ' … ' (get, has, set, delete; m[k] on BigMap<K, Int>) | Checker |
| JSC0829 | error | m[k] reads a BigMap<K, Int>; … a Map is read with m.get(k) … a List has no index: use xs.head, find or fold … has no index | Checker |
| JSC0830 | error | m[k] reads a BigMap<K, Int>; a BigMap<K, Bytes> is read with m.get(k) | Checker |

## Enums and match (ADR-0018 D5, D6)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC0901 | error | an enum is written 'enum … { A, B { x: Int } }', without '=' | Parser |
| JSC0902 | error | a match arm is written 'Pattern => value' | Parser |
| JSC0903 | error | an arm's '{' starts a block: write an object as its value in parentheses, A => ({ ...state, f: e }) | Parser |
| JSC0904 | error | a match needs at least one arm | Parser |
| JSC0905 | error | expected a pattern (a variant, Some(x), None or _) but found | Parser |
| JSC0906 | error | a variant's fields are matched by name: … { field } or … { field: name } | Parser |
| JSC0907 | error | a match used as a value needs a value in every arm | Parser |
| JSC0908 | error | there is no switch in .jsc: write match e { A => …, B { x } => … } | Parser |
| JSC0909 | error | the enum … needs at least one variant | Checker |
| JSC0910 | error | the name … is declared twice: types, enums and variants share one namespace (each becomes a Java class) | Checker |
| JSC0911 | error | is a variant of … , not a type: write | Checker |
| JSC0912 | error | has fields: write … { … , … } | Checker |
| JSC0913 | error | is an enum: read its fields with match (match v { A { … } => … }) | Checker |
| JSC0914 | error | an … is one of its variants: write … { … }, … { … } | Checker |
| JSC0915 | error | a variant is built from its fields: … { … } takes no spread | Checker |
| JSC0916 | error | match needs an enum or an Option, not | Checker |
| JSC0917 | error | this arm is unreachable: the arms before it cover every case | Checker |
| JSC0918 | error | _ binds nothing | Checker |
| JSC0919 | error | is not a case of … (it is a variant of … ) … : the cases are … , | Checker |
| JSC0920 | error | is matched twice | Checker |
| JSC0921 | error | Some binds its value: Some(x) => … | Checker |
| JSC0922 | error | an Option's value is bound as Some(x) | Checker |
| JSC0923 | error | is bound twice | Checker |
| JSC0924 | error | this match is not exhaustive: add an arm for … , … (or _ => …) | Checker |
| JSC0925 | error | ' … ' is bound but never used: leave the field out of the pattern | Checker |
| JSC0926 | error | the type of this match is unknown here: give it one (const o: Option<Int> = match …) | Checker |
| JSC0927 | error | this match is a statement (it is not the block's last expression, or an arm has no … value), so its arms end in statements, not a value | Checker |
| JSC0930 | error | nested patterns are not supported: bind the value, Some(v), and match v again | Parser, Checker |

## Lists, lambdas and ranges (ADR-0018 D7)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC1001 | error | loops are 'for (const x of xs) { … }'; accumulate with xs.fold(init, (acc, x) => …) | Parser |
| JSC1002 | error | 'for … in' iterates keys in JavaScript; write 'for (const … of list)' | Parser |
| JSC1003 | error | there are no while loops in .jsc: use xs.fold(init, (acc, x) => …), range(a, b), or a recursive fn | Parser |
| JSC1004 | error | functions are written 'fn name(x: Int): Int = …' at the top of the file or in the contract; lambdas 'x => …' are only arguments of list methods | Parser |
| JSC1005 | error | a lambda's '{' starts a block: write an object as its value in parentheses, x => ({ ...x, f: e }) | Parser |
| JSC1006 | error | a lambda's block must end in its value | Parser |
| JSC1007 | error | there are no function values in .jsc: declare 'fn name(x: Int): Int = …', and write lambdas 'x => …' only as list-method arguments | Parser |
| JSC1008 | error | a spread '...rest' goes last in a list: [x, ...rest] | Parser |
| JSC1009 | error | range(a, b) is only the receiver of any, all, fold, filter or map, or the list of a for loop (no list is built: ADR-0018 D7) | Checker |
| JSC1010 | error | for (const x of …) needs a List, not | Checker |
| JSC1011 | error | a for loop's body has no value; accumulate with fold | Checker |
| JSC1012 | error | a lambda is only an argument of a list method (fold, any, all, find, filter, map) | Checker |
| JSC1013 | error | a List's size is xs.length | Checker |
| JSC1014 | error | List has no property ' … ' (length, isEmpty, head) | Checker |
| JSC1015 | error | utf8 takes a string literal | Checker |
| JSC1016 | error | takes a List, not | Checker |
| JSC1017 | error | takes one Int: xs. … (n) | Checker |
| JSC1018 | error | xs. … is a property: write it without parentheses | Checker |
| JSC1019 | error | sum() takes no arguments | Checker |
| JSC1020 | error | sum() needs a List<Int> | Checker |
| JSC1021 | error | reverse() takes no arguments | Checker |
| JSC1022 | error | contains takes one value | Checker |
| JSC1023 | error | concat takes one list | Checker |
| JSC1024 | error | takes a one-parameter lambda: xs. … (x => …) | Checker |
| JSC1025 | error | fold takes an initial value and a two-parameter lambda: xs.fold(0, (acc, x) => acc + x) | Checker |
| JSC1026 | error | List has no method ' … ' | Checker |
| JSC1027 | error | ...rest needs a List | Checker |
| JSC1028 | error | the type of this list is unknown here: give it one (const xs: List<Int> = []) | Checker |

## Warnings (ADR-0018 D11, D12)

| Code | Severity | Message | Raised by |
|---|---|---|---|
| JSC9001 | warning | never reads balance; a destroy must pay out everything the instance holds | Checker |
| JSC9002 | warning | builds its next state from a time value with an expression the checker follows as a … whole, not field by field, so every state field becomes a time field; build it as … { ...state, f: … } to keep the others ordinary (ADR-0018 D11) | Checker |
| JSC9003 | warning | stores a value of the validity window in … : whoever submits the transaction … chooses the window, so validFrom can be up to maxValidityMs early and validTo that much late … (ADR-0018 D11) | Checker |
| JSC9004 | warning | stores a value derived from a stored time in … : that time came from an earlier … call's validity window, so its bias (up to maxValidityMs) carries into … (ADR-0018 D11) | Checker |
| JSC9005 | warning | a time value sets this payout: whoever submits the transaction chooses the … window, so validFrom can be up to maxValidityMs early and validTo that much late, and the … amount moves with it (ADR-0018 D11) | Checker |
