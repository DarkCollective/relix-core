# The recipe coordinate

Every recipe opens with a **coordinate**: a single blockquote line, directly under
the title, that says what kind of problem the recipe solves. It is written for the
reader — computing a coordinate is what [Part I](README.md#the-method) teaches, and a
recipe showing its own is the method worked through — and it is machine-readable, so
the [recipe finder](README.md#recipe-finder) is generated from it rather than kept by
hand, and a build guard can check that every class of problem has a recipe.

## Why a line, not front-matter

The coordinate is a line of the page, not YAML front-matter above it, for two
reasons.

- **It is content.** The method chapters teach the reader to classify a problem
  before writing a query; the coordinate at the top of each recipe is that
  classification, shown. Hidden in front-matter it would stop teaching.
- **It renders.** These manuals render through one markdown grammar — fenced code,
  `##` sub-headings, blockquotes, lists, tables, indented code — shared by the site
  and the PDF. A blockquote is part of that grammar; a `---` front-matter block is
  not, and would be published as a horizontal rule and a run of text. And one
  artifact that both the reader and the finder read cannot drift, where a second
  copy in front-matter could.

## The format

Directly under the `#` title, one blockquote line, its fields separated by ` · `:

```
> **Grain:** one row per entity · **Class:** Universal · **Signals:** every, all, only · **Operators:** ÷, ∀, ▷
```

| Field | Axis | Value |
|---|---|---|
| **Grain** | grain | Free text finishing *"one row per …"*. |
| **Class** | quantifier | Exactly one class name from the table in [quantifier.md](method/quantifier.md). |
| **Signals** | quantifier | The words in a question that point here, comma-separated. Feeds the finder's index by question word, and names the same words the recipe's *How to recognise it* section expands. |
| **Operators** | — | The headline operators the recipe uses, comma-separated. Feeds the index by operator. |
| **Difficulties** | difficulty | *Optional.* One or more of `nested`, `dirty`, `NULL`, `unbounded`, `federated`, `time`, comma-separated. Present only when handling that difficulty is what the recipe is *about* — every recipe has a NULL pitfall, so mentioning NULL is not enough to earn the tag. |

A recipe has **one** Class. A problem that genuinely needs two is two recipes, each
linking the other under *Related* — the same rule the backlog applies to an issue
that wants two `area:` labels.

## The class registry

The set of valid Class names is the Class column of the quantifier table in
[quantifier.md](method/quantifier.md), and nowhere else, so the classes a reader is
taught and the classes a recipe may claim are one list. A coordinate whose Class is
not in that table is an error, not a new class.

## What reads it

- **The recipe finder** (Part V, generated): invert **Signals** for the index by
  question word, invert **Operators** for the index by operator, and group by
  **Class**.
- **The completeness guard** (ships with the manual, in relix-core): every class in
  the registry is covered by at least one recipe or still marked *planned* in the
  index; every recipe's Class is a registry class; and every coordinate parses —
  Grain, Class, Signals and Operators all present.

## The parse

The coordinate is the first blockquote after the `#` title. Fields are ` · `
-separated; each is `**Label:** value`; the multi-value fields (Signals, Operators,
Difficulties) are `, `-separated; surrounding whitespace is trimmed; the Class match
is case-insensitive. The labels are exactly Grain, Class, Signals, Operators and the
optional Difficulties.
