# Decompose into views

<!-- skeleton -->

**The claim.** Build a solution as a pipeline of named relations, one idea each.
It costs nothing: the optimizer expands views before it rewrites, so it
optimizes across the view boundaries.

**To cover:**

- One view per quantifier: a question with two of them is two views.
- Naming a view after what its rows *are* ("Qualified", "Rings"), not after what
  produced them.
- Inspecting each stage as you go (`:tree`, `:schema`, `query { Stage }` on
  sample data).
- Generate and test: a generator or product proposes, σ disposes.
- When a pipeline wants recursion (`FIX`) rather than another view.
