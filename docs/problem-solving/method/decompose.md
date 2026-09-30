# Decompose into views

Build a solution as a pipeline of named relations, one idea to a name. A view for the
candidates, a view for the ones that qualify, a view for the answer — each a single
step you can read, run and check on its own. This is the habit that turns a hard
question into a sequence of easy ones.

## It costs nothing

The reason to decompose freely is that it is free. A view is not a materialised
table the engine computes and stores; the optimiser **expands every view before it
rewrites**, so the rules that push a selection down, prune a partition or fold a
scan into a database see straight through the view boundaries. Ten named views and one
long expression optimise to the same plan. You pay nothing for the names, and you get
back a solution you can follow — so name everything worth naming.

## One view per quantifier

The natural seams are the quantifiers. A question with one quantifier is one view; a
question with two is two views, combined at the end. *"Suppliers fully certified and
never late"* becomes `FullyCertified`, `NeverLate`, and their intersection —
`Approved`. Each view answers one plain question, and the last line says how the
answers fit together. When you heard two words in [find the quantifier](quantifier.md),
this is where they become two names.

## Name a view for what its rows are

Name a view after *what its rows are*, not after what produced them. `Qualified`,
`Rings`, `BigSpenders`, `Breaches` — a reader learns the solution from the names
alone, because each one is a noun the domain already uses. `JoinedThenFiltered` names
the machinery and teaches nothing; `Approved` names the meaning and teaches the whole
step. The names are the outline of the argument, so make them read like one.

## Inspect each stage as you build it

Because each view is a real relation, you can look at it before you build the next.
Run `query { Stage }` over a few sample rows and check the grain is what you said.
Ask `:schema Stage` for the columns and types it produced. Ask `:tree Stage` for the
operator tree, or `:opt`/`:explain` for what the optimiser and planner made of it.
Building blind and debugging a hundred-line expression at the end is the slow path;
building a view at a time, and checking each, is the fast one.

## Generate, then dispose

Some problems have no obvious first relation — there is nothing to filter, because the
answer has to be *produced*. The move there is **generate and test**: a cross product
or a generator *proposes* every candidate, and a σ *disposes* of the ones that do not
hold. A test matrix is `Method × Currency × Tier` narrowed by a constraint; a search
is a range of values narrowed to those that satisfy an equation. Propose with ×,
dispose with σ — and thin the survivors with `COVER` when there are too many.

## When a view wants recursion

A pipeline of views walks *forward*: each reads the ones before it. When a step needs
to read *itself* — every ancestor, all the parts of the parts, everything reachable —
another view cannot express it, and you reach for recursion instead: `CLOSURE` for the
two-column case, `FIX` for the general one, `ITERATE` when each round *replaces* the
last rather than adding to it. That is the boundary of the technique: decompose until a
step would have to refer to its own output, and let a recursive operator take it from
there.
