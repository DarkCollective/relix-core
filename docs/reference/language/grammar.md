# Name: Grammar (EBNF)

# Syntax:
rule    ::= alternative | alternative      -- a production
"SELECT"                                   -- a keyword or symbol, written literally
name?   name*   name+   ( … )              -- optional, zero or more, one or more, group
WORD - reserved_word                       -- a WORD that is not a reserved word

# Description:
This page is the whole grammar of a `.relix` script in one place, from the
statements at the top of a file down to a single character of a string. The other
pages in this manual explain what each construct *means*; this one says exactly what
you may *write*, which makes it the page to read when you are generating Relix rather
than reading it: a code generator, a language model, or a person writing a parser
for an editor.

The grammar is written in the W3C style of EBNF, the notation the XML and XPath
specifications use:

| Notation | Meaning |
|----------|---------|
| `rule ::= …` | defines `rule` |
| `"SELECT"` | the text itself; a **word** matches in any letter case (`select`, `Select`), a **symbol** matches exactly |
| `a b` | `a` followed by `b` |
| `a \| b` | `a` or `b` |
| `a?` | `a` or nothing |
| `a*` | zero or more `a` |
| `a+` | one or more `a` |
| `( … )` | grouping |
| `a - b` | an `a` that is not also a `b` |
| `UPPER_CASE` | a token class, defined in *Tokens* at the end of the grammar |
| `/* … */` | a comment |

Every operator has a Unicode glyph and an ASCII spelling, and the grammar lists both
wherever they are accepted, glyph first: `( "σ" | "SELECT" )`. The two spellings
parse to the same tree and may be mixed freely in one expression. The
[operator spellings](spellings.md) page has the same pairs as a table.

A script has two layers, and the grammar keeps them apart because their tokens
differ slightly. The **statement layer** is what sits outside braces: `source`,
`:=`, `query` and the other statements. The **expression layer** is what sits inside
`{ … }`, the relational algebra itself. For example, a statement-layer string must
use double quotes, while an expression-layer string may use either kind.

The grammar is also published as a single plain-text file,
[grammar.ebnf.txt](https://relix.darkcollective.com/reference/language/grammar.ebnf.txt),
for tools that want the rules without the page around them. It holds the blocks below,
in order, followed by the rules EBNF cannot state.

# Grammar:

## Scripts and statements

```ebnf
/* A script: an optional namespace, then an optional env, then statements.
   Every statement ends in ";". */
script            ::= namespace_decl? env_decl? statement*

namespace_decl    ::= "namespace" script_name ";"
env_decl          ::= "env" ( "from" STRING_DQ )? ( "using" STRING_DQ )? ";"

/* "private" hides a declaration from scripts that import this one. */
statement         ::= "private"? ( import_stmt
                                 | source_decl
                                 | connection_decl
                                 | relate_stmt
                                 | def_stmt
                                 | query_stmt
                                 | assignment )

/* Statement-layer names are plain words; backticks are not accepted here. */
script_name       ::= WORD

/* import "lib.relix";                      everything the file exports
   import relation Totals from "lib.relix"; one name, of a stated kind
   import { A, B } from "lib.relix";        several names
   import Totals from "lib.relix";          one name                     */
import_stmt       ::= "import" STRING_DQ ";"
                    | "import" ( "source" | "relation" | "function" ) script_name
                               "from" STRING_DQ ";"
                    | "import" "{" script_name ( "," script_name )* ","? "}"
                               "from" STRING_DQ ";"
                    | "import" script_name "from" STRING_DQ ";"

/* Name := { expression };   a view
   Name := [ | a | b | … ];  an inline table (see Inline tables)          */
assignment        ::= script_name ":=" assignment_body ";"
assignment_body   ::= "{" rel_expr "}"
                    | inline_table ( "references" references_block )?

/* query Name;  or  query { expression };  — marks a result to produce.
   The bare form takes one plain name: write query { relix.plan }; for a
   dotted one. */
query_stmt        ::= "query" ( script_name | "{" rel_expr "}" ) ";"

/* def f(x: NUMBER): NUMBER := { x * 2 };            a scalar function
   def recent(n: NUMBER): relation := { λ n (…) };   a table-valued function */
def_stmt          ::= "def" script_name "(" ( parameter ( "," parameter )* )? ")" ":"
                      ( scalar_type ":=" "{" operand "}"
                      | "relation" ":=" "{" rel_expr "}" ) ";"
parameter         ::= script_name ":" scalar_type

/* relate "places" / "placed by" Orders.customer_id [0..*] -> Customers.customer_id [1..1]; */
relate_stmt       ::= "relate" "symmetric"? STRING_DQ ( "/" STRING_DQ )?
                      endpoint "->" endpoint ";"
endpoint          ::= relation_columns multiplicity?
multiplicity      ::= "[" INTEGER ".." ( INTEGER | "*" ) "]"

/* Rel.col  or  Rel(col, …); the relation may itself be dotted. */
relation_columns  ::= script_name ( "." script_name )* "(" script_name ( "," script_name )* ")"
                    | script_name ( "." script_name )+

/* { customer_id -> Customers.customer_id, (a, b) -> T(x, y) } */
references_block  ::= "{" ( reference ","? )* "}"
reference         ::= ( script_name | "(" script_name ( "," script_name )* ")" )
                      "->" relation_columns
```

## Sources and connections

```ebnf
source_decl       ::= "source" script_name "from" source_config ";"
source_config     ::= csv_source
                    | json_source
                    | http_source
                    | database_source
                    | generator_source
                    | connection_table_source

/* Inside a { field: value, … } block the fields may come in any order,
   commas between them are optional, and a trailing comma is allowed.
   Which fields are required is noted per source; a missing one is an error. */

/* source Orders from csv("orders.csv") { schema: { id: NUMBER } };   schema required */
csv_source        ::= "csv" "(" STRING_DQ ")" "{" ( csv_field ( ","? csv_field )* ","? )? "}"
csv_field         ::= "header" ":" boolean_literal
                    | "schema" ":" schema_block
                    | "references" ":" references_block

/* source Docs from json("docs.json") { records: "$.items" };   the block is optional */
json_source       ::= "json" "(" STRING_DQ ")"
                      ( "{" ( json_field ( ","? json_field )* ","? )? "}" )?
json_field        ::= "records" ":" STRING_DQ
                    | "references" ":" references_block

/* url, table and schema required */
database_source   ::= "database" "{" ( database_field ( ","? database_field )* ","? )? "}"
database_field    ::= "url" ":" STRING_DQ
                    | "table" ":" STRING_DQ
                    | "schema" ":" schema_block
                    | "references" ":" references_block

/* source Nums from generator { name: "Range", start: "1", end: "10" };
   name required; every other field is an argument to the generator. */
generator_source  ::= "generator" "{" ( generator_field ( ","? generator_field )* ","? )? "}"
generator_field   ::= script_name ":" STRING_DQ

/* A table on a named connection: source T from warehouse { table: "t", schema: {…} };
   table and schema required. */
connection_table_source ::= connection_name "{" ( table_field ( ","? table_field )* ","? )? "}"
connection_name   ::= script_name - ( "http" | "database" | "csv" | "json" | "generator" )
table_field       ::= "table" ":" STRING_DQ
                    | "schema" ":" schema_block
                    | "references" ":" references_block

/* url required */
http_source       ::= "http" "{" ( http_field ( ","? http_field )* ","? )? "}"
http_field        ::= "url" ":" STRING_DQ
                    | "method" ":" ( "GET" | "POST" )
                    | "headers" ":" "{" ( STRING_DQ ":" STRING_DQ ","? )* "}"
                    | "auth" ":" auth_spec
                    | "body" ":" STRING_DQ
                    | "extract" ":" extract_spec
                    | "paginate" ":" "{" ( paginate_entry ","? )* "}"
                    | "schema" ":" http_schema_block
auth_spec         ::= "bearer" "(" STRING_DQ ")"
                    | "basic" "(" STRING_DQ "," STRING_DQ ")"
                    | "apikey" "(" ( "query" "(" STRING_DQ ")" | STRING_DQ ) "," STRING_DQ ")"
extract_spec      ::= "json" "(" STRING_DQ ")"
                    | "csv" "(" ( "header" ":" boolean_literal )? ")"
paginate_entry    ::= script_name ":" "query" "(" STRING_DQ ")"
                      ( "[" "default" ":" INTEGER "]" )?

/* connection wh from jdbc { url: "${WH_URL}", user: "app" };
   "database" is an alias for "jdbc", which accepts only the keys url, user,
   password, dialect and collation, and requires url. Other connector types
   (mongodb, gedcom, …) accept any keys and check them themselves. */
connection_decl   ::= "connection" script_name "from" connector_type
                      "{" ( script_name ":" ( STRING_DQ | WORD ) ","? )* "}" ";"
connector_type    ::= WORD

/* A declared heading: { id: NUMBER, addr: { city: STRING }, tags: [STRING] } */
schema_block      ::= "{" ( column_spec ","? )* "}"
http_schema_block ::= "{" ( http_column_spec ","? )* "}"
column_spec       ::= column_name ":" type column_binding? column_modifier?
/* An HTTP column may be an input ("in": a request parameter) or an output ("out", the default). */
http_column_spec  ::= column_name ":" ( "in" | "out" )? type column_binding? column_modifier?
column_name       ::= WORD | DELIMITED_IDENTIFIER
column_binding    ::= "as" ( "query" | "path" | "header" ) "(" STRING_DQ ")"
                    | "at" STRING_DQ
column_modifier   ::= "[" ( "required" | "default" ":" STRING_DQ ) "]"

/* A column may be nested; a def parameter or return type may not. */
type              ::= scalar_type
                    | "{" ( column_name ":" type ","? )+ "}"
                    | "[" type "]"
scalar_type       ::= "NUMBER" | "STRING" | "BOOLEAN" | "ANY"
                    | "DATE" | "TIME" | "TIMESTAMP" | "DURATION"
boolean_literal   ::= "true" | "false"
```

## Inline tables

```ebnf
/* An inline table is read as raw text between the brackets, line by line,
   not as tokens. Two layouts:

     Markdown:   Cities := [
                     | city   | country |
                     |--------|---------|
                     | Paris  | FR      |
                 ];
       A line that does not start with "|" is ignored, a row whose cells are all
       dashes is ignored, and the first remaining row is the header.

     CSV:        Cities := csv[
                     city,country
                     Paris,FR
                 ];
       Blank lines are ignored and the first line is the header. A field may be
       double-quoted, with "" for a quote inside it.

   Cells are trimmed. A cell's type is inferred: a column whose every value is a
   number is NUMBER, anything else is STRING. */
inline_table      ::= MARKDOWN_TABLE
                    | "csv" CSV_TABLE
```

## Relational expressions

```ebnf
/* What goes inside { … }. Binary operators associate to the left, and bind in
   three tiers, tightest first:
     1. joins, ×, ÷, ∘ and LATERAL
     2. ∩
     3. ∪, ⊎, ⊔, −, ∆
   so  A ⋈ B ∪ C ∩ D  is  (A ⋈ B) ∪ (C ∩ D).  Parenthesise when in doubt. */
rel_expr          ::= intersect_expr ( union_tier_op intersect_expr )*
intersect_expr    ::= join_expr ( intersect_op join_expr )*
join_expr         ::= postfix_expr join_tail*

union_tier_op     ::= "∪" | "UNION"
                    | "⊎" | "UALL"
                    | "⊔" | "OUNION"
                    | "−" | "DIFF" | "MINUS" | "EXCEPT"     /* "−" is U+2212, not the ASCII hyphen */
                    | "∆" | "SYMDIFF"
intersect_op      ::= "∩" | "INTER" | "INTERSECT"

/* A join's condition is written BETWEEN the operator and its right input:
     Users ⨝ Users.id = Orders.user_id Orders                           */
join_tail         ::= natural_join_op postfix_expr
                    | conditional_join_op predicate postfix_expr
                    | asof_join
                    | interval_join
                    | product_op postfix_expr
                    | lateral_join

natural_join_op   ::= "⋈" | "JOIN"
conditional_join_op ::= "⨝" | "><"                          /* theta join */
                    | "⟕" | "|><" | "LJOIN"                  /* left outer */
                    | "⟖" | "><|" | "RJOIN"                  /* right outer */
                    | "⟗" | "|><|" | "FJOIN"                 /* full outer */
                    | "⋉" | "SEMI"                           /* semi-join */
                    | "▷" | "ANTI"                           /* anti-join */
                    | "USEMI"                                /* pairwise universal semi-join */
product_op        ::= "×" | "CROSS"
                    | "÷" | "DIV"
                    | "∘" | "COMPOSE"

/* L ASOF [INNER] L.ts >= R.ts [WITHIN DURATION 'PT5M'] [TIES(FIRST)] R */
asof_join         ::= "ASOF" "INNER"? predicate ( "WITHIN" operand )?
                      ( "TIES" "(" ( "FIRST" | "LAST" ) ")" )? postfix_expr

/* L IJOIN OVERLAPS (L.start, L.end, R.start, R.end) R */
interval_join     ::= "IJOIN" allen_relation "(" dotted_name "," dotted_name ","
                      dotted_name "," dotted_name ")" postfix_expr
allen_relation    ::= "INTERSECTS" | "OVERLAPS" | "OVERLAPPED_BY" | "DURING" | "CONTAINS"
                    | "STARTS" | "STARTED_BY" | "FINISHES" | "FINISHED_BY" | "EQUALS"
                    | "MEETS" | "MET_BY" | "PRECEDES" | "PRECEDED_BY"

/* L LATERAL fn(L.col, 3) — the right side is always a function call. */
lateral_join      ::= "LATERAL" name "(" ( operand ( "," operand )* )? ")"

/* R⁺ OVER (src, dst)  transitive closure;  R* OVER (src, dst)  reflexive. */
postfix_expr      ::= primary_rel ( ( "⁺" | "*" ) "OVER" "(" name edge_separator name ")" )?

/* "," reads an edge one way; "↔" (ASCII "<->") reads it both ways. */
edge_separator    ::= "," | "↔" | "<->"

primary_rel       ::= "(" rel_expr ")"
                    | truth_relation
                    | relation_call
                    | relation_ref
                    | unary_operation

/* UNIT (alias DEE): one row, no columns.  EMPTY (alias DUM): no rows, no columns. */
truth_relation    ::= "UNIT" | "DEE" | "EMPTY" | "DUM"

/* Orders, warehouse.orders, relix.relations — a name, possibly dotted. */
relation_ref      ::= relation_name ( "." name )*
/* A table-valued function: recentOrders(30). */
relation_call     ::= relation_name ( "." name )* "(" ( operand ( "," operand )* )? ")"
relation_name     ::= name - relation_keyword

/* Words that begin an expression and so cannot name a relation unless
   backticked:  `order`  is the relation named order. */
relation_keyword  ::= "UNIT" | "DEE" | "EMPTY" | "DUM"
                    | "PROJECT" | "SELECT" | "RENAME" | "GROUP" | "SORT" | "ORDER"
                    | "LIMIT" | "DISTINCT" | "UNNEST" | "WHY"
                    | "CLOSURE" | "RCLOSURE" | "CLUSTER" | "PATH" | "TRACE" | "FIX"
                    | "FORALL" | "SAMPLE" | "SOLVE" | "OPTIMIZE" | "TOP" | "COVER"
                    | "DOWNSAMPLE" | "ROLLING" | "WINDOW" | "SESSIONIZE"
                    | "PIVOT" | "UNPIVOT" | "TREE"
```

## Unary operators

```ebnf
/* Every unary operator takes its input LAST, in parentheses:  σ p (R). */
unary_operation   ::= projection | selection | rename | aggregation | sort | limit
                    | distinct | why | unnest
                    | closure | cluster | path | trace | fixpoint
                    | universal | sample | solve | optimize | top_k | cover
                    | downsample | rolling | window | sessionize | tree | pivot | unpivot

input             ::= "(" rel_expr ")"
arrow             ::= "→" | "->"

/* π name, price * qty → total (Orders) */
projection        ::= ( "π" | "PROJECT" ) projected ( "," projected )* input
projected         ::= operand ( arrow name )?

/* σ amount > 100 ∧ status = 'open' (Orders) */
selection         ::= ( "σ" | "SELECT" ) predicate input

/* ρ Staff (Employees)                    rename the relation
   ρ (id → staff_id, name → staff) (E)    rename some columns
   ρ Staff (sid, sname, dept, mgr, pay) (E)  rename every column, in order */
rename            ::= ( "ρ" | "RENAME" )
                      ( name ( "(" rename_list ")" )? | "(" rename_list ")" ) input
rename_list       ::= name ( "," name )*
                    | name arrow name ( "," name arrow name )*

/* γ customer_id, SUM(amount) → total, COUNT(*) → orders (Orders)
   Grouping keys come first, without braces; at least one aggregate is required.
   With no keys the whole input is one group. */
aggregation       ::= ( "γ" | "GROUP" ) "BY"?
                      ( grouping_key ( "," grouping_key )* ","? )?
                      aggregate ( "," aggregate )* ","? input
grouping_key      ::= operand ( arrow name )?
aggregate         ::= ( "SUM" | "AVG" | "MIN" | "MAX" | "COLLECT" ) "(" operand ")" ( arrow name )?
                    | "COUNT" "(" ( operand | "*" ) ")" ( arrow name )?
                    | ( "ARGMAX" | "ARGMIN" ) "(" operand "," operand ")" ( arrow name )?

/* τ amount DESC, name (Orders);  ORDER BY amount DESC (Orders) */
sort              ::= ( "τ" | "SORT" | "ORDER" ) "BY"? sort_key ( "," sort_key )* input
sort_key          ::= operand ( "ASC" | "DESC" )?

/* λ 10 (R): the first 10 rows.  λ 20, 10 (R): skip 20, then take 10. */
limit             ::= ( "λ" | "LIMIT" ) INTEGER ( "," INTEGER )? input

distinct          ::= ( "δ" | "DISTINCT" ) input
/* ω (R): each row with its lineage as a nested provenance column. */
why               ::= ( "ω" | "WHY" ) input

/* μ items WITH ORDINALITY pos (Orders): one row per array element. */
unnest            ::= ( "μ" | "UNNEST" ) name ( "WITH" "ORDINALITY" name )? input

/* Graph operators. */
closure           ::= ( "CLOSURE" | "RCLOSURE" ) name edge_separator name input
cluster           ::= "CLUSTER" name "," name "AS" name input
/* HOPS n is short for HOPS 1 TO n; bounds must satisfy 1 ≤ min ≤ max. */
path              ::= "PATH" name edge_separator name "HOPS" INTEGER ( "TO" INTEGER )?
                      "AS" name input
trace             ::= "TRACE" name edge_separator name "VIA" name
                      ( "MINIMIZE" | "MAXIMIZE" ) "AS" name input

/* FIX Reach (base, step): inside step, Reach names the relation being built. */
fixpoint          ::= "FIX" IDENTIFIER "(" rel_expr "," rel_expr ")"

/* ∀ student : grade >= 50 (Results) */
universal         ::= ( "∀" | "FORALL" ) ( name ( "," name )* )? ":" predicate input

/* SAMPLE 0.1 (R): each row with probability 0.1.  SAMPLE 100 ROWS (R): exactly 100. */
sample            ::= "SAMPLE" NUMBER ( "SEED" INTEGER )? input
                    | "SAMPLE" INTEGER "ROWS" ( "SEED" INTEGER )? input

/* SOLVE total = price * qty (R) */
solve             ::= "SOLVE" operand "=" operand input

/* OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 50 PER owner (Items)
   OPTIMIZE ALLOCATE (0, 1) MINIMIZE SUM(cost) SUBJECT TO SUM(share) = 1 -> share (Plans) */
optimize          ::= "OPTIMIZE" objective "SUBJECT" "TO" constraints per_identifiers? input
                    | "OPTIMIZE" "ALLOCATE" "(" signed_number "," signed_number ")"
                      objective "SUBJECT" "TO" constraints arrow IDENTIFIER
                      per_identifiers? input
objective         ::= ( "MAXIMIZE" | "MINIMIZE" ) sum_term
constraints       ::= constraint ( ( "∧" | "AND" ) constraint )*
constraint        ::= sum_term ( "<=" | "≤" | ">=" | "≥" | "=" ) signed_number
sum_term          ::= "SUM" "(" operand ")"
signed_number     ::= "-"? NUMBER
per_identifiers   ::= "PER" IDENTIFIER ( "," IDENTIFIER )*

/* TOP 3 amount DESC PER customer_id (Orders).  TOP 10, 5 …: skip 10, keep 5.
   Without PER, the top rows overall. */
top_k             ::= "TOP" INTEGER ( "," INTEGER )? sort_key ( "," sort_key )*
                      per_identifiers? input

/* COVER 2 (Params): a small set of rows covering every pair of column values. */
cover             ::= "COVER" "EXACT"? INTEGER input

/* DOWNSAMPLE ts BY '5m' USING AVG PER sensor FOR 1000 ROWS (Readings) */
downsample        ::= "DOWNSAMPLE" name "BY" STRING "USING"
                      ( "AVG" | "MIN" | "MAX" | "SUM" | "COUNT" )
                      per_names? ( "FOR" INTEGER "ROWS" )? input
per_names         ::= "PER" name ( "," name )*

/* ROLLING AVG(price) OVER 3 ROWS SORT ts PER ticker AS avg3 (Trades) */
rolling           ::= "ROLLING" ( "SUM" | "AVG" | "COUNT" | "MIN" | "MAX" ) "(" operand ")"
                      "OVER" ( INTEGER | "ALL" ) "ROWS"
                      ( "τ" | "SORT" ) sort_key ( "," sort_key )* per_names? "AS" name input

/* WINDOW RANK() SORT amount DESC PER customer_id AS rnk (Orders) */
window            ::= "WINDOW" window_function
                      ( "τ" | "SORT" ) sort_key ( "," sort_key )* per_names? "AS" name input
window_function   ::= ( "ROW_NUMBER" | "RANK" | "DENSE_RANK" | "PERCENT_RANK" ) "(" ")"
                    | "NTILE" "(" operand ")"
                    | ( "LAG" | "LEAD" ) "(" operand ( "," operand ( "," operand )? )? ")"
                    | ( "FIRST_VALUE" | "LAST_VALUE" ) "(" operand ")"

/* SESSIONIZE ts GAP DURATION 'PT30M' PER user_id AS session (Events) */
sessionize        ::= "SESSIONIZE" name "GAP" operand per_names? "AS" name input

/* TREE id BY parent_id ORDER position AS children (Nodes) */
tree              ::= "TREE" name "BY" name ( "ORDER" sort_key ( "," sort_key )* )?
                      "AS" name input

/* PIVOT revenue BY month PER region (Sales) */
pivot             ::= "PIVOT" name "BY" name per_names? input
/* UNPIVOT (jan, feb, mar) AS (month, revenue) (Sales) */
unpivot           ::= "UNPIVOT" "(" name ( "," name )* ")" "AS" "(" name "," name ")" input
```

## Predicates

```ebnf
/* NOT binds tightest, then AND, then OR. */
predicate         ::= and_predicate ( ( "∨" | "OR" ) and_predicate )*
and_predicate     ::= not_predicate ( ( "∧" | "AND" ) not_predicate )*
not_predicate     ::= ( "¬" | "NOT" ) not_predicate
                    | primary_predicate
primary_predicate ::= "(" predicate ")"
                    | operand condition_tail

condition_tail    ::= comparison_op operand
                    | ( "∈" | "IN" ) set_expr
                    | ( "∉" | ( "¬" | "NOT" ) "IN" ) set_expr
                    | ( ( "¬" | "NOT" ) )? "LIKE" operand
                    | ( "=" | "≠" | "!=" ) null_word            /* a null test */
                    | "IS" ( ( "¬" | "NOT" ) )? null_word        /* a null test */

comparison_op     ::= "=" | "≠" | "!=" | "<" | "≤" | "<=" | ">" | "≥" | ">="
null_word         ::= "⊥" | "NULL"

/* x IN {1, 2, 3}: a set is written in braces, never in parentheses.
   x IN tags: an array-valued column. */
set_expr          ::= "{" ( operand ( "," operand )* )? "}"
                    | operand
```

## Operands (scalar expressions)

```ebnf
/* * and / bind tighter than + and -; all four associate to the left.
   A leading "-" negates the term right after it. */
operand           ::= term ( ( "+" | "-" ) term )*
term              ::= factor ( ( "*" | "/" ) factor )*
factor            ::= "-" factor
                    | primary_operand

primary_operand   ::= literal
                    | function_call
                    | column_ref
                    | "(" operand ")"
                    | "(" predicate ")"          /* a condition's truth value, as a BOOLEAN */
                    | struct_construction
                    | array_construction

/* Orders.amount, addr.city — a column, a qualified column, or a nested field. */
column_ref        ::= operand_name ( "." name )*

/* Round(price, 2).  The "(" must follow the name with NO space between
   (CALL_OPEN): "Round (price)" is the column Round followed by a parenthesis. */
function_call     ::= operand_name ( "." name )* CALL_OPEN ( argument ( "," argument )* )? ")"
/* An argument may be a condition: IIf(amount > 100, 'big', 'small'). */
argument          ::= operand
                    | predicate

operand_name      ::= name - ( "TRUE" | "FALSE" | "DATE" | "TIME" | "TIMESTAMP" | "DURATION" )

literal           ::= STRING
                    | NUMBER
                    | "TRUE" | "FALSE"
                    | ( "DATE" | "TIME" | "TIMESTAMP" | "DURATION" ) STRING
                      /* ISO-8601 text: DATE '2026-03-01', TIMESTAMP '2026-03-01T09:30:00Z',
                         TIME '09:30', DURATION 'PT30M' */

/* {id, total: amount * 2}: a struct.  {id} is short for {id: id}. */
struct_construction ::= "{" ( IDENTIFIER ( ":" operand )? ( "," IDENTIFIER ( ":" operand )? )* )? "}"
/* [a, b, 3]: an array. */
array_construction  ::= "[" ( operand ( "," operand )* )? "]"
```

## Names and tokens

```ebnf
/* In most positions any word is accepted as a name, reserved or not.
   IDENTIFIER is the stricter form a few positions require (FIX, the PER
   list of OPTIMIZE and TOP, struct field names); a reserved word there
   must be backticked. */
name              ::= WORD | DELIMITED_IDENTIFIER
dotted_name       ::= name ( "." name )*
IDENTIFIER        ::= ( WORD - reserved_word ) | DELIMITED_IDENTIFIER

/* The words the expression layer reserves. Letter case is ignored. */
reserved_word     ::= "TRUE" | "FALSE" | "NULL" | "UNIT" | "DEE" | "EMPTY" | "DUM"
                    | "DATE" | "TIME" | "TIMESTAMP" | "DURATION"
                    | "SUM" | "AVG" | "COUNT" | "MIN" | "MAX" | "COLLECT" | "ARGMAX" | "ARGMIN"
                    | "ASC" | "DESC" | "PROJECT" | "SELECT" | "RENAME" | "GROUP" | "SORT"
                    | "ORDER" | "LIMIT" | "DISTINCT" | "UNNEST" | "WITH" | "ORDINALITY"
                    | "CLOSURE" | "RCLOSURE" | "CLUSTER" | "PATH" | "HOPS" | "AS" | "OVER"
                    | "FIX" | "FORALL" | "SAMPLE" | "SEED" | "SOLVE" | "OPTIMIZE"
                    | "ALLOCATE" | "MAXIMIZE" | "MINIMIZE" | "SUBJECT" | "TO" | "TOP" | "PER"
                    | "ROWS" | "COVER" | "EXACT" | "DOWNSAMPLE" | "BY" | "USING" | "FOR"
                    | "LATERAL" | "ROLLING" | "WINDOW" | "SESSIONIZE" | "GAP" | "TRACE"
                    | "VIA" | "PIVOT" | "UNPIVOT" | "TREE" | "WHY"
                    | "JOIN" | "SEMI" | "ANTI" | "LJOIN" | "RJOIN" | "FJOIN" | "USEMI"
                    | "ASOF" | "IJOIN" | "WITHIN" | "TIES" | "CROSS" | "UNION" | "UALL"
                    | "OUNION" | "DIFF" | "MINUS" | "EXCEPT" | "INTER" | "INTERSECT"
                    | "DIV" | "SYMDIFF" | "COMPOSE" | "AND" | "OR" | "NOT" | "IS" | "IN" | "LIKE"

/* ---- Tokens ----------------------------------------------------------
   Whitespace separates tokens and is otherwise ignored, except that a
   function call's "(" must touch its name.

   Comments:     "--" to the end of the line, and a block that opens with
                 slash-star and closes with star-slash, as in C.
                 "//" is not a comment.

   WORD          a letter or "_", then letters, digits and "_".  Any Unicode
                 letter counts, except the operator glyphs (σ, π, ρ, …).

   DELIMITED_IDENTIFIER
                 "`" any characters except a newline "`", with "``" standing for
                 one backtick: `order`, `line total`.  Never empty.

   NUMBER        digits, optionally "." and more digits: 42, 3.14.
                 No exponent, no leading "." and no sign (a "-" before a
                 number is the minus operator).
   INTEGER       a NUMBER without a fractional part.

   CALL_OPEN     a "(" written directly after a name, with no space or
                 comment between them.  Wherever the grammar asks for "(",
                 a CALL_OPEN is accepted too; the reverse is not so.

   STRING        expression layer: '…' or "…".  Escapes: \\  \"  \'  \n  \t;
                 any other backslash sequence is an error.
   STRING_DQ     statement layer: "…" only.  \" is a quote; any other
                 backslash is kept as written, so "C:\data\a.csv" needs no
                 doubling.

   MARKDOWN_TABLE  "[" raw text "]"     (see Inline tables)
   CSV_TABLE       "[" raw text "]"
   ----------------------------------------------------------------------- */
```

# Rules the grammar does not show:
A few rules depend on context in a way EBNF cannot state compactly, and a few more
are easy to miss in the rules above.

- **Keywords ignore case; names keep it.** `select`, `SELECT` and `Select` are the
  same keyword. A name is stored as written.
- **A function call's parenthesis touches the name.** `Round(price, 2)` is a call.
  `Round (price, 2)` is a column called `Round` followed by a parenthesis. This is
  what lets `τ name (Users)` sort `Users` by the column `name`.
- **A join's condition comes before its right input.** `A ⨝ A.id = B.a_id B`. An
  AS-OF join puts `INNER`, `WITHIN` and `TIES` there too.
- **A predicate always compares.** A condition is a comparison, a membership or
  pattern test, or a null test, joined by `∧`, `∨` and `¬`. A bare boolean does not
  stand alone: write `σ active = true (R)`, not `σ active (R)`, and
  `σ IsNull(x) = true (R)` rather than `σ IsNull(x) (R)`.
- **Sets use braces.** `x IN {1, 2}` tests membership. `x IN (1, 2)` is a syntax
  error: a parenthesis opens a single expression.
- **There is no NULL value to write.** `⊥` and `NULL` appear only in a null test:
  `x = ⊥`, `x = NULL`, `x IS NULL` and `x IS NOT NULL` all ask whether `x` is NULL.
  Anywhere else, the word `null` names a column.
- **`<>` is not an operator.** Write `!=` or `≠`.
- **Two minus signs.** The ASCII hyphen `-` subtracts numbers. The Unicode minus `−`
  (U+2212) is set difference, whose ASCII spellings are `DIFF`, `MINUS` and
  `EXCEPT`.
- **The two layers quote and point differently.** Outside braces, strings are
  double-quoted and the arrow is `->`. Inside braces, strings take either quote and
  the arrow is `→` or `->`.
- **Reserved words as names.** A reserved word can name a column almost anywhere
  (`γ region, COUNT(*) → count (Orders)`). In relation position it cannot, nor as a
  struct field, a `FIX` name or a `PER` key of `OPTIMIZE` or `TOP`; there it must be
  backticked: `` π name (`order`) ``. Backticks are always safe.
- **`query` names one plain name.** `query Totals;` works, but a dotted name needs
  the expression form: `query { relix.plan };`.
- **Statement order.** `namespace` must be the first statement and `env` the next;
  the other statements may come in any order.
- **Nesting depth.** One expression may nest at most 250 levels deep.

The grammar says what parses. Analysis then checks what it means: that every column
exists, that a positional rename names every column, that `SAMPLE`'s probability is
between 0 and 1. A script can parse and still be rejected there, with a message
naming the problem.

# Examples:
The shape of a whole script: an inline table, a function, two views and two results.

```relix
Regions := [
    | region | manager |
    |--------|---------|
    | EU     | Ana     |
    | US     | Ben     |
];

def with_tax(amount: NUMBER): NUMBER := { amount * 1.2 };

Big := { σ amount > 100 (Orders) };

Summary := {
    γ region, SUM(with_tax(amount)) → gross, COUNT(*) → orders (Big) ⋈ Regions
};

query Summary;
query { τ gross DESC (Summary) };
```

A join's condition sits between the operator and the right-hand relation:

```relix
query { Customers ⨝ Customers.customer_id = Orders.customer_id Orders };
```

A function call's parenthesis touches the name. With a space, `amount` is a column
and `(Orders)` is the input:

```relix
query { τ amount DESC (Orders) };
```

A set is written in braces:

```relix
query { σ status IN {'open', 'held'} (Orders) };
```

Parentheses do not make a set:

```relix-invalid
query { σ status IN ('open', 'held') (Orders) };
```

SQL's `<>` is not an operator:

```relix-invalid
query { σ status <> 'open' (Orders) };
```

A null test, in each of its spellings:

```relix
query { σ email = ⊥ ∨ phone IS NOT NULL (Customers) };
```

Grouping keys come first, with no braces, and at least one aggregate is required:

```relix-invalid
query { γ region (Orders) };
```

To list the distinct values of a column, project it and remove the duplicates:

```relix
query { δ (π region (Orders)) };
```

Statement-layer strings take double quotes only:

```relix-invalid
source Orders2 from csv('orders.csv') { schema: { id: NUMBER } };
```

`//` is not a comment:

```relix-invalid
query { Orders }; // every order
```

# See Also:
- [operator spellings](spellings.md): every glyph beside its ASCII form, as a table
- [comments](comments.md): the two comment forms
- [delimited identifier](delimited-identifier.md): backticks, and why a reserved word needs them
- [inline table](inline-table.md): the Markdown and CSV table forms in full
- [source](source.md), [http source](http-source.md), [connection](connection.md): what each source field does
- [assignment & query](assignment.md), [def](def.md), [import](import.md), [relate](relate.md): the statements
