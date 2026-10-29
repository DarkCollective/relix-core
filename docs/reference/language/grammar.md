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
import_stmt       ::= "import" NONBLANK_STRING_DQ ";"
                    | "import" ( "source" | "relation" | "function" ) script_name
                               "from" NONBLANK_STRING_DQ ";"
                    | "import" "{" script_name ( "," script_name )* ","? "}"
                               "from" NONBLANK_STRING_DQ ";"
                    | "import" script_name "from" NONBLANK_STRING_DQ ";"

/* Name := { expression };   a view
   Name := [ | a | b | … ];  an inline table (see Inline tables)          */
assignment        ::= script_name ":=" assignment_body ";"
assignment_body   ::= "{" rel_expr "}"
                    | inline_table ( "schema" schema_block )? ( "references" references_block )?

/* query Name;  or  query { expression };  — marks a result to produce.
   The bare form takes one plain name: write query { relix.plan }; for a
   dotted one. */
query_stmt        ::= "query" ( script_name | "{" rel_expr "}" ) ";"

/* def f(x: NUMBER): NUMBER := { x * 2 };            a scalar function
   def recent(n: NUMBER): relation := { λ n (…) };   a table-valued function
   def srcs(E: RELATION(src, w: NUMBER)): relation := { π src (E) };
                                                     a relation parameter, which
   only a table-valued function may declare */
def_stmt          ::= "def" script_name
                      ( "(" ( scalar_param ( "," scalar_param )* )? ")" ":"
                            scalar_type ":=" "{" operand "}"
                      | "(" ( parameter ( "," parameter )* )? ")" ":"
                            "relation" ":=" "{" rel_expr "}" ) ";"
scalar_param      ::= script_name ":" scalar_type
parameter         ::= scalar_param | script_name ":" relation_type
relation_type     ::= "RELATION" "(" column_name ( ":" type )? ( "," column_name ( ":" type )? )* ")"

/* relate "places" / "placed by" Orders.customer_id [0..*] -> Customers.customer_id [1..1]; */
relate_stmt       ::= "relate" "symmetric"? NONBLANK_STRING_DQ ( "/" NONBLANK_STRING_DQ )?
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

/* source Orders from csv("orders.csv") { schema: { id: NUMBER } };
   With no schema the heading is inferred from the file: names from its header row, or
   from columns: [ … ] for a file with none; not both schema and columns. */
csv_source        ::= "csv" "(" STRING_DQ ")" "{" ( csv_field ( ","? csv_field )* ","? )? "}"
csv_field         ::= "header" ":" boolean_literal
                    | "schema" ":" schema_block
                    | "columns" ":" "[" column_name ( "," column_name )* ","? "]"
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

/* source Nums from generator { name: "Range", lo: "1", hi: "10" };
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
                    | "method" ":" ( "GET" | "POST" | "QUERY" )
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

/* A column may be nested — one of a relation parameter's too; a scalar def parameter
   or return type may not. */
type              ::= scalar_type
                    | "{" ( column_name ":" type ","? )+ "}"
                    | "[" type "]"
scalar_type       ::= "NUMBER" | "STRING" | "BOOLEAN" | "ANY"
                    | "DATE" | "TIME" | "TIMESTAMP" | "DURATION" | "TIMESTAMPTZ"
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
       dashes is ignored, and the first remaining row is the header. A row may end
       with a -- comment after its last "|".

     CSV:        Cities := csv[
                     city,country
                     Paris,FR
                 ];
       Blank lines are ignored and the first line is the header. A field may be
       double-quoted, with "" for a quote inside it.

   Either way the header names each column once: no header cell is empty, and no
   two name the same column, ignoring case. Every other row has exactly as many
   cells as the header; an empty cell is a missing value. A Markdown table therefore
   puts each row on a line of its own.

   Cells are trimmed. A cell's type is inferred: a column whose every value is a
   number is NUMBER, anything else is STRING.

   A schema block after the rows declares some or all columns' types:
     Readings := [ … ] schema { read_at: TIMESTAMP };
   Each cell of a declared column is parsed to its type as a CSV source parses a
   field, and an empty cell is NULL. The block names columns of the header, each
   once, with a scalar type other than ANY and no binding or modifier; a cell that
   does not parse is an error at its row. */
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
     Users ⨝ Users.id = Orders.user_id Orders
   USING may stand there too, or follow the right input as SQL writes it:
     Orders ⟕ USING (customer_id) Customers
     Orders LJOIN Customers USING (customer_id)                         */
join_tail         ::= natural_join_op postfix_expr
                    | conditional_join_op predicate postfix_expr
                    | using_join_op using_columns postfix_expr
                    | using_join_op postfix_expr using_columns
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
using_join_op     ::= natural_join_op
                    | "⟕" | "|><" | "LJOIN" | "⟖" | "><|" | "RJOIN" | "⟗" | "|><|" | "FJOIN"
using_columns     ::= "USING" "(" name ( "," name )* ")"
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
                    | "ITERATE" | "FORALL" | "SAMPLE" | "SHUFFLE" | "ROLL" | "SOLVE" | "OPTIMIZE" | "TOP" | "COVER"
                    | "DOWNSAMPLE" | "ROLLING" | "WINDOW" | "SESSIONIZE"
                    | "PIVOT" | "UNPIVOT" | "TREE"
```

## Unary operators

```ebnf
/* Every unary operator takes its input LAST, in parentheses:  σ p (R). */
unary_operation   ::= projection | selection | rename | aggregation | sort | limit
                    | distinct | why | unnest
                    | closure | cluster | path | trace | fixpoint | iterate
                    | universal | sample | shuffle | roll | solve | optimize | top_k | cover
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
   Grouping keys come first as a plain list; at least one aggregate is required.
   With no keys the whole input is one group. A key is an operand, so {k} is a
   struct and groups by a one-field struct rather than by the column k.
   γ ROLLUP (region, product), SUM(amount) → total (Sales) groups by several sets of
   the keys at once; elements combine, one set from each, as SQL's do. */
aggregation       ::= ( "γ" | "GROUP" ) "BY"?
                      ( grouping_element ( "," grouping_element )* ","? )?
                      aggregate ( "," aggregate )* ","? input
grouping_element  ::= ( "ROLLUP" | "CUBE" ) "(" grouping_key ( "," grouping_key )* ")"
                    | "GROUPING" "SETS" "(" grouping_set ( "," grouping_set )* ")"
                    | grouping_key
grouping_set      ::= "(" ( grouping_key ( "," grouping_key )* )? ")"
                    | grouping_key
grouping_key      ::= operand ( arrow name )?
aggregate         ::= ( "SUM" | "AVG" | "MIN" | "MAX" ) "(" "DISTINCT"? operand ")" agg_filter? ( arrow name )?
                    | ( "COLLECT" | "ARRAY_AGG" ) "(" "DISTINCT"? operand agg_order? ")" ( arrow name )?
                    | string_agg agg_filter? ( arrow name )?
                    | "COUNT" "(" ( "DISTINCT"? operand | "*" ) ")" agg_filter? ( arrow name )?
                    | ( "ARGMAX" | "ARGMIN" ) "(" operand "," operand ")" agg_filter? ( arrow name )?
                    | statistic CALL_OPEN "DISTINCT"? operand ")" agg_filter? ( arrow name )?
                    | ( "GROUPING" | "GROUPING_ID" ) CALL_OPEN operand ( "," operand )* ")" ( arrow name )?
                    | ( "PERCENTILE_CONT" | "PERCENTILE_DISC" ) CALL_OPEN operand
                      ( "," operand ")"
                      | ")" "WITHIN" ( "γ" | "GROUP" ) "(" "ORDER" "BY" sort_key ")" )
                      agg_filter? ( arrow name )?
/* Matched by their text, so none is reserved: a column may be called median. */
statistic         ::= "STDDEV" | "STDDEV_SAMP" | "STDDEV_POP"
                    | "VARIANCE" | "VAR_SAMP" | "VAR_POP" | "MEDIAN"
/* One aggregate in three engines' spellings, each matched by its text:
   STRING_AGG(name, ', ' ORDER BY name), GROUP_CONCAT(name ORDER BY name SEPARATOR ', ')
   and LISTAGG(name, ', ') WITHIN GROUP (ORDER BY name). */
string_agg        ::= "STRING_AGG" CALL_OPEN "DISTINCT"? operand "," operand agg_order? ")"
                    | "GROUP_CONCAT" CALL_OPEN "DISTINCT"? operand agg_order? ( "SEPARATOR" operand )? ")"
                    | "LISTAGG" CALL_OPEN "DISTINCT"? operand ( "," operand )? ")"
                      ( "WITHIN" ( "γ" | "GROUP" ) "(" agg_order ")" )?
agg_order         ::= "ORDER" "BY" sort_key ( "," sort_key )*
/* SUM(amount) FILTER (WHERE status = 'open'): only the rows where the condition holds. */
agg_filter        ::= "FILTER" "(" "WHERE" predicate ")"

/* τ amount DESC, name (Orders);  ORDER BY amount DESC (Orders);
   τ bonus DESC NULLS FIRST (Sales): NULLs go last unless a key says first. */
sort              ::= ( "τ" | "SORT" | "ORDER" ) "BY"? sort_key ( "," sort_key )* input
sort_key          ::= operand ( "ASC" | "DESC" )? ( "NULLS" ( "FIRST" | "LAST" ) )?

/* λ 10 (R): the first 10 rows.  λ 20, 10 (R): skip 20, then take 10.
   λ 3 WITH TIES (τ score DESC (R)): the first 3, and every row tied with the third. */
limit             ::= ( "λ" | "LIMIT" ) INTEGER ( "," INTEGER )?
                      ( input | "WITH" "TIES" "(" sort ")" )

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

/* FIX Reach (base, step): inside step, Reach names the relation being built.
   The name may be a keyword's spelling, FIX Path (…): see the rules below. */
fixpoint          ::= "FIX" name "(" rel_expr "," rel_expr ")"

/* ITERATE Board (base, step) ROUNDS 4: inside step, Board names the previous
   round, and each round replaces it. ROUNDS, UNTIL and STABLE are matched by
   their text rather than reserved, so they remain usable as names. */
iterate           ::= "ITERATE" name "(" rel_expr "," rel_expr ")" iterate_stop
iterate_stop      ::= "ROUNDS" INTEGER
                    | "UNTIL" ( "STABLE" | name ( "," name )* "WITHIN" NUMBER
                                "PER" name ( "," name )* ) "MAX" INTEGER "ROUNDS"

/* ∀ student : grade >= 50 (Results) */
universal         ::= ( "∀" | "FORALL" ) ( name ( "," name )* )? ":" predicate input

/* SAMPLE 0.1 (R): each row with probability 0.1.  SAMPLE 100 ROWS (R): exactly 100. */
sample            ::= "SAMPLE" NUMBER ( "SEED" INTEGER )? input
                    | "SAMPLE" INTEGER "ROWS" ( "SEED" INTEGER )? input

/* SHUFFLE (R): every row once, in random order.  SHUFFLE SEED 7 (R): reproducible. */
shuffle           ::= "SHUFFLE" ( "SEED" INTEGER )? input

/* ROLL (R): an endless uniform draw with replacement from the faces R (a die roll).
   The result is unbounded — compose λ to take a finite number of rolls. A BY weight
   expression draws each face with probability proportional to it (a loaded die). */
roll              ::= "ROLL" ( "BY" operand )? ( "SEED" INTEGER )? input

/* SOLVE total = price * qty (R).  Several equations solve for as many blanks:
   SOLVE { total = a + b, diff = a - b } (R).  PER fits across each group:
   SOLVE y = slope * x + intercept PER series (R).  WITHIN, MAX … ROUNDS and START
   govern an equation solved by search: SOLVE area = side * side START side = -1 (R).
   START, like ROUNDS, is matched by its text rather than reserved. */
solve             ::= "SOLVE" ( equation | "{" equation ( "," equation )* "}" )
                      per_identifiers? ( "WITHIN" NUMBER )? ( "MAX" INTEGER "ROUNDS" )?
                      ( "START" solve_start ( "," solve_start )* )?
                      input
equation          ::= operand "=" operand
solve_start       ::= name "=" operand

/* OPTIMIZE MAXIMIZE SUM(value) SUBJECT TO SUM(weight) <= 50 PER owner (Items)
   OPTIMIZE ALLOCATE (0, 1) MINIMIZE SUM(cost) SUBJECT TO SUM(1) = 1 -> share (Plans) */
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
   Without PER, the top rows overall.  TOP 3 WITH TIES …: and the rows tied with the third. */
top_k             ::= "TOP" INTEGER ( "," INTEGER )? ( "WITH" "TIES" )? sort_key ( "," sort_key )*
                      per_identifiers? input

/* COVER 2 (Params): a small set of rows covering every pair of column values. */
cover             ::= "COVER" "EXACT"? INTEGER input

/* DOWNSAMPLE ts BY '5m' USING AVG PER sensor FOR 1000 ROWS (Readings) */
downsample        ::= "DOWNSAMPLE" name "BY" NONBLANK_STRING "USING"
                      ( "AVG" | "MIN" | "MAX" | "SUM" | "COUNT" )
                      per_names? ( "FOR" POSITIVE_INTEGER "ROWS" )? input
per_names         ::= "PER" name ( "," name )*

/* ROLLING AVG(price) OVER 3 ROWS SORT ts PER ticker AS avg3 (Trades);
   ROLLING AVG(price) OVER ROWS BETWEEN 2 PRECEDING AND 2 FOLLOWING SORT ts AS centred (Trades);
   ROLLING SUM(amount) OVER PARTITION PER region AS region_total (Orders) */
rolling           ::= "ROLLING" ( ( "SUM" | "AVG" | "COUNT" | "MIN" | "MAX" ) "(" operand ")"
                                | ( "STDDEV" | "STDDEV_SAMP" | "STDDEV_POP" | "VARIANCE" | "VAR_SAMP" | "VAR_POP" )
                                  CALL_OPEN operand ")" )
                      "OVER" ( ( ( INTEGER | "ALL" ) "ROWS" | frame_clause ) window_sort
                             | "PARTITION" window_sort? )
                      per_names? "AS" name input
window_sort       ::= ( "τ" | "SORT" ) sort_key ( "," sort_key )*
/* The words of a frame are matched by their text; none is reserved. A RANGE offset
   measures the one sort key: a number for a NUMBER, a DURATION for a date or time. */
frame_clause      ::= ( "ROWS" | "RANGE" ) ( "BETWEEN" frame_bound "AND" frame_bound | frame_bound )
frame_bound       ::= "UNBOUNDED" ( "PRECEDING" | "FOLLOWING" )
                    | "CURRENT" "ROW"
                    | ( NUMBER | "DURATION" STRING ) ( "PRECEDING" | "FOLLOWING" )

/* WINDOW RANK() SORT amount DESC PER customer_id AS rnk (Orders) */
window            ::= "WINDOW" window_function
                      ( "τ" | "SORT" ) sort_key ( "," sort_key )* per_names? "AS" name input
window_function   ::= ( "ROW_NUMBER" | "RANK" | "DENSE_RANK" | "PERCENT_RANK" | "CUME_DIST" ) "(" ")"
                    | "NTILE" "(" operand ")"
                    | ( "LAG" | "LEAD" ) "(" operand ( "," operand ( "," operand )? )? ")"
                    | ( "FIRST_VALUE" | "LAST_VALUE" ) "(" operand ")"
                    | "NTH_VALUE" "(" operand "," operand ")"

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
                    | ( ( "¬" | "NOT" ) )? "LIKE" operand ( "ESCAPE" STRING )?
                    | ( "~" | "~*" | "!~" | "!~*" ) operand     /* a regular-expression match */
                    | ( ( "¬" | "NOT" ) )? "MATCHES" operand
                    | ( ( "¬" | "NOT" ) )? "BETWEEN" operand ( "∧" | "AND" ) operand
                    | ( "=" | "≠" | "!=" | "<>" ) null_word     /* a null test */
                    | "IS" ( ( "¬" | "NOT" ) )? null_word        /* a null test */
                    | "IS" ( ( "¬" | "NOT" ) )? "DISTINCT" "FROM" operand

comparison_op     ::= "=" | "≠" | "!=" | "<>" | "<" | "≤" | "<=" | ">" | "≥" | ">="
null_word         ::= "⊥" | "NULL"

/* x IN {1, 2, 3}: a set is written in braces, never in parentheses.
   x IN tags: an array-valued column. */
set_expr          ::= "{" ( operand ( "," operand )* )? "}"
                    | operand
```

## Operands (scalar expressions)

```ebnf
/* * / and % bind tighter than + and -, which bind tighter than ||; all
   associate to the left.  A leading "-" negates the term right after it.
   a || b is SQL's spelling of a + b, and x % y of Mod(x, y). */
operand           ::= sum ( "||" sum )*
sum               ::= term ( ( "+" | "-" ) term )*
term              ::= factor ( ( "*" | "/" | "%" ) factor )*
factor            ::= "-" factor
                    | primary_operand ( "AT" "TIME" "ZONE" zone_operand )*
/* ts AT TIME ZONE 'Europe/London' is TIMEZONE('Europe/London', ts). */
zone_operand      ::= "-" zone_operand
                    | primary_operand

primary_operand   ::= literal
                    | bound_parameter
                    | cast_call
                    | extract_call
                    | substring_call
                    | position_call
                    | datediff_call
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
function_call     ::= ( operand_name - "CAST" ) ( "." name )* CALL_OPEN ( argument ( "," argument )* )? ")"

/* SQL's CAST(qty AS NUMBER) and EXTRACT(WEEK FROM d): a conversion and a date field,
   each the function it names (see the CAST and EXTRACT pages). */
/* DATEDIFF(month, a, b): SQL Server's unit, a bare word, is read as a string. */
datediff_call     ::= "DATEDIFF" CALL_OPEN name "," argument ( "," argument )* ")"
cast_call         ::= "CAST" CALL_OPEN operand "AS" cast_type ")"
cast_type         ::= "NUMBER" | "STRING" | "BOOLEAN" | "DATE" | "TIME" | "TIMESTAMP"
                    | "TIMESTAMPTZ" | "TIMESTAMP" "WITH" "TIME" "ZONE"
extract_call      ::= "EXTRACT" CALL_OPEN extract_field "FROM" operand ")"
/* SUBSTRING(s FROM 2 FOR 3) is SUBSTRING(s, 2, 3); POSITION('@' IN s) is STRPOS(s, '@'). */
substring_call    ::= "SUBSTRING" CALL_OPEN operand
                      ( "FROM" operand ( "FOR" operand )? | "FOR" operand ) ")"
position_call     ::= "POSITION" CALL_OPEN operand ( "∈" | "IN" ) operand ")"
extract_field     ::= "YEAR" | "MONTH" | "DAY" | "HOUR" | "MINUTE" | "SECOND"
                    | "DOW" | "ISODOW" | "DOY" | "WEEK" | "ISOYEAR" | "QUARTER" | "EPOCH"
/* An argument may be a condition: IIf(amount > 100, 'big', 'small'). */
argument          ::= operand
                    | predicate

operand_name      ::= name - ( "TRUE" | "FALSE" | "NULL" | "DATE" | "TIME" | "TIMESTAMP" | "DURATION" )

/* $id: a bound parameter, its value supplied when the query runs (PARAMETER). */
bound_parameter   ::= PARAMETER

literal           ::= STRING
                    | NUMBER
                    | "TRUE" | "FALSE"
                    | null_word                  /* the missing value: IIf(c, NULL, x) */
                    | ( "DATE" | "TIME" | "TIMESTAMP" | "DURATION" ) STRING
                      /* ISO-8601 text: DATE '2026-03-01', TIMESTAMP '2026-03-01T09:30:00Z',
                         TIME '09:30', DURATION 'PT30M', DURATION 'P1M' */
                    | interval_literal
                    | timestamptz_literal
/* A timestamp that keeps its offset: TIMESTAMPTZ '2026-03-01T09:00:00+02:00', and the
   standard's TIMESTAMP WITH TIME ZONE '…'. TIMESTAMPTZ is matched by its text. */
timestamptz_literal ::= ( "TIMESTAMPTZ" | "TIMESTAMP" "WITH" "TIME" "ZONE" ) STRING
/* SQL's interval, a DURATION, matched by its text: INTERVAL '1 month',
   INTERVAL '3' DAY, INTERVAL 1 MONTH. A unit follows a quoted number only. */
interval_literal  ::= "INTERVAL" ( STRING name? | "-"? NUMBER name )

/* {id, total: amount * 2}: a struct.  {id} is short for {id: id}. */
struct_construction ::= "{" ( IDENTIFIER ( ":" operand )? ( "," IDENTIFIER ( ":" operand )? )* )? "}"
/* [a, b, 3]: an array. */
array_construction  ::= "[" ( operand ( "," operand )* )? "]"
```

## Names and tokens

```ebnf
/* In most positions any word is accepted as a name, reserved or not.
   IDENTIFIER is the stricter form a few positions require (the PER list of
   OPTIMIZE and TOP, struct field names); a reserved word there must be
   backticked. */
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
                    | "FIX" | "ITERATE" | "FORALL" | "SAMPLE" | "SHUFFLE" | "ROLL" | "SEED" | "SOLVE" | "OPTIMIZE"
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

   PARAMETER     "$" then, with no space between, a letter or "_" and then
                 letters, digits and "_": $id, $since_2.

   DELIMITED_IDENTIFIER
                 "`" any characters except a newline "`", with "``" standing for
                 one backtick: `order`, `line total`.  Never empty, and never
                 only whitespace.

   NUMBER        digits, optionally "." and more digits, optionally an
                 exponent: "e" or "E", an optional sign, and digits:
                 42, 3.14, 1e6, 2.5E-3.  No leading "." and no sign (a "-"
                 before a number is the minus operator).
   INTEGER       a NUMBER of digits alone: no fraction and no exponent.
   POSITIVE_INTEGER
                 an INTEGER of at least 1.

   CALL_OPEN     a "(" written directly after a name, with no space or
                 comment between them.  Wherever the grammar asks for "(",
                 a CALL_OPEN is accepted too; the reverse is not so.

   STRING        expression layer: '…' or "…".  Escapes: \\  \"  \'  \n  \t;
                 any other backslash sequence is an error.  The string's own
                 quote doubled is one quote, as in SQL: 'it''s'.
   STRING_DQ     statement layer: "…" only.  \" is a quote; any other
                 backslash is kept as written, so "C:\data\a.csv" needs no
                 doubling.
   NONBLANK_STRING, NONBLANK_STRING_DQ
                 a STRING or STRING_DQ holding more than whitespace.

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
- **A FIX or ITERATE relation may be named with a keyword's spelling.**
  `FIX Path (E, E ⋈ Path)` binds `Path`. In the step, the name written as it was
  bound and followed by the end, `)`, `,` or a binary operator is the relation
  being built; followed by anything else it is the keyword, so in
  `FIX Sort (E, SORT src (Sort))` the first `SORT` sorts.
- **Two slips are read as what was meant, with a warning.** A `;` written before a
  statement's closing brace, `query { R; };`, is ignored: the statement's `;` comes
  after the brace. A view written without its braces, `Reach := FIX Reach (Base,
  Step);`, is read as if they were there, up to the `;` that ends it. Both run, and
  analysis warns of each with the form the grammar gives.
- **A join's condition comes before its right input.** `A ⨝ A.id = B.a_id B`. An
  AS-OF join puts `INNER`, `WITHIN` and `TIES` there too.
- **A predicate always compares.** A condition is a comparison, a membership or
  pattern test, or a null test, joined by `∧`, `∨` and `¬`. A bare boolean does not
  stand alone: write `σ active = true (R)`, not `σ active (R)`, and
  `σ IsNull(x) = true (R)` rather than `σ IsNull(x) (R)`.
- **Sets use braces.** `x IN {1, 2}` tests membership. `x IN (1, 2)` is a syntax
  error: a parenthesis opens a single expression.
- **Braces elsewhere build a struct.** Outside a set, `{a}` is a struct with one
  field `a`. A grouping key is an operand, so `γ {region}, COUNT(*) (Orders)`
  parses and groups by a struct, not by the column. Write `γ region, COUNT(*) (Orders)`.
- **NULL is a value, and also part of the null test.** `IIf(c, NULL, x)` writes the
  missing value. After `=`, `≠` or `IS`, the word is the null test instead: `x = ⊥`,
  `x = NULL`, `x IS NULL` and `x IS NOT NULL` all ask whether `x` is NULL. Any other
  comparison with NULL, `x < NULL` or `NULL = x`, parses and is refused by analysis,
  being never true. A column named `null` needs backticks.
- **A CSV source declares a `schema` or its `columns`, not both.** `csv_field`
  allows either; giving the two in one source is an error.
- **`<>`, `!=` and `≠` are one operator.** `<>` is two characters with nothing
  between them; `< >` is not an operator.
- **`BETWEEN` includes both bounds.** `x BETWEEN lo AND hi` is `x >= lo ∧ x <= hi`,
  and the `AND` there belongs to `BETWEEN`: `x BETWEEN 1 AND 5 AND y = 2` tests two
  things. `NOT BETWEEN` is `x < lo ∨ x > hi`.
- **`IS DISTINCT FROM` is never UNKNOWN.** `a IS NOT DISTINCT FROM b` is true when
  both are NULL, where `a = b` is UNKNOWN.
- **A LIKE escape is one character.** `ESCAPE '!'` makes the character after each
  `!` literal, so `'50!%'` matches `50%`. `BETWEEN`, `DISTINCT FROM` and `ESCAPE`
  are matched by their text rather than reserved, so they remain usable as names.
- **SQL's literal and operator spellings.** `'it''s'` is `'it\'s'`, `1e6` is
  `1000000` and `2.5E-3` is `0.0025`. `a || b` is `a + b`, so it concatenates two
  strings and adds two numbers; it binds more loosely than `+`, as in PostgreSQL.
  `x % y` is `Mod(x, y)` and binds as `*` does. A doubled quote is one quote only in
  the expression layer.
- **`USING` matches the named columns only.** `A JOIN B USING (id)` joins on `id`
  and puts it once, first, in the output; any other column both inputs have stays
  twice, the right one suffixed `_r`. A full join's `id` is whichever side has one.
  A column may not be named twice.
- **`CAST` and `EXTRACT` are SQL's.** `CAST(x AS NUMBER)` is the conversion to the
  type, and a NULL casts to NULL. `EXTRACT(WEEK FROM d)` is `WEEK(d)`. `CAST` is not
  a name a function can have; `EXTRACT` without `FROM` is an ordinary call.
- **A regular-expression match is a test.** `s ~ p` is `REGEXP_LIKE(s, p) = TRUE`,
  `~*` ignores case, and `!~`, `!~*` and `NOT MATCHES` compare with FALSE. The pattern
  is Java's dialect; `MATCHES` is not reserved.
- **A percentile's order runs one way.** `PERCENTILE_CONT(p) WITHIN GROUP (ORDER BY x
  DESC)` is the fraction `1 - p` ascending; `PERCENTILE_DISC` takes `ASC` only.
- **A string aggregate's separator has three defaults.** `STRING_AGG` names its
  separator; `GROUP_CONCAT` joins with a comma unless it says `SEPARATOR`, as MySQL's
  does, and `LISTAGG` with nothing, as Oracle's. Without an `ORDER BY` the values come
  in the input's order.
- **`AT TIME ZONE` binds before arithmetic.** `ts AT TIME ZONE z` is
  `TIMEZONE(z, ts)`; its three words are not reserved. `DATEDIFF(month, a, b)` reads
  a bare first word as the unit's name.
- **Where NULLs sort.** Without `NULLS FIRST` or `NULLS LAST`, a sort key puts its
  NULLs last whether it runs ascending or descending. PostgreSQL and Oracle put them
  first in a descending sort, so a query ported from them says `NULLS FIRST`. `NULLS`,
  `FIRST` and `LAST` are matched by their text rather than reserved.
- **`WITH TIES` needs an order.** `λ n WITH TIES` takes a `τ` as its input, since the
  sort keys decide what ties; `TOP n WITH TIES` has its own.
- **Two minus signs.** The ASCII hyphen `-` subtracts numbers. The Unicode minus `−`
  (U+2212) is set difference, whose ASCII spellings are `DIFF`, `MINUS` and
  `EXCEPT`.
- **The two layers quote and point differently.** Outside braces, strings are
  double-quoted and the arrow is `->`. Inside braces, strings take either quote and
  the arrow is `→` or `->`.
- **Reserved words as names.** A reserved word can name a column almost anywhere
  (`γ region, COUNT(*) → count (Orders)`). In relation position it cannot, nor as a
  struct field, a `FIX` or `ITERATE` name or a `PER` key of `OPTIMIZE` or `TOP`; there it must be
  backticked: `` π name (`order`) ``. Backticks are always safe. `DATE`, `TIME`,
  `TIMESTAMP` and `DURATION` start a literal only when their quoted string follows, so
  a column named `time` or `date` reads as a column: `σ time > 1000 (Laps)`.
- **`query` names one plain name.** `query Totals;` works, but a dotted name needs
  the expression form: `query { relix.plan };`.
- **Whole numbers must fit.** An INTEGER that counts rows or bounds (`λ`, `TOP`,
  `SAMPLE … ROWS`, `SEED`, `DOWNSAMPLE … FOR`, a multiplicity, a pagination
  default) must fit in a 64-bit signed integer, and one that counts hops, a
  covering strength or a window frame in a 32-bit one. A larger number is
  rejected where it is written.
- **Temporal literals are checked as they are read.** `DATE '2024-02-30'` has the
  shape the grammar describes but is not a real date, so the parser rejects it. The
  same holds for `TIME`, `TIMESTAMP` and `DURATION`, whose strings must be valid
  ISO-8601 values.
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

SQL's `<>` is the same operator as `!=` and `≠`:

```relix
query { σ status <> 'open' (Orders) };
```

SQL's quote, concatenation, remainder and exponent spellings:

```relix
query { π 'it''s ' || customer → note, qty % 3 → bucket, amount * 1e-2 → scaled (Orders) };
```

`BETWEEN` needs both bounds:

```relix-invalid
query { σ amount BETWEEN 10 (Orders) };
```

A null test, in each of its spellings:

```relix
query { σ email = ⊥ ∨ phone IS NOT NULL (Customers) };
```

Grouping keys come first, with no braces, and at least one aggregate is required:

```relix-invalid
query { γ region (Orders) };
```

Braces around a grouping key are not an error, but they change the result. This
groups by a one-field struct and returns one struct column instead of `region`:

```relix
query { γ {region}, COUNT(*) → orders (Orders) };
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
