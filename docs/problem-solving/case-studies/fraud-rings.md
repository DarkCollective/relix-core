# Fraud rings

*Existence + Graph + Explanation*

## The problem

*"Money is being cycled between accounts to launder it. Find the rings — groups of
accounts that pass money round in a loop — flag every account within a few hops of a
known-bad one, and, for anything we escalate, show exactly which transfers made it
suspicious."*

## Classifying it

Three questions, three classes — say them out loud before writing anything:

- *"accounts that pass money round in a loop"* — a **[graph](../recipes/reachability.md)**
  question. A ring is a cycle: an account reachable from itself. `CLOSURE`, then keep the
  self-pairs.
- *"within a few hops of a known-bad account"* — still graph, but **bounded** and
  distance-aware: `PATH` with a hop window, scoped to the flagged account.
- *"show which transfers made it suspicious"* — an **[explanation](../recipes/explanation.md)**
  question: `WHY` reifies the lineage of a flagged result.

## The data

```relix
Accounts := [
| account | holder | flagged |
|---------|--------|---------|
| 1001    | Ada    | false   |
| 1002    | Bo     | false   |
| 1003    | Cy     | true    |
| 1004    | Dee    | false   |
| 2001    | Eve    | false   |
| 2002    | Fin    | false   |
];

Transfers := [
| src  | dst  | amount |
|------|------|--------|
| 1001 | 1002 | 500    |
| 1002 | 1003 | 480    |
| 1003 | 1001 | 460    |
| 1001 | 1004 | 200    |
| 2001 | 2002 | 300    |
];

Edges := { π src, dst (Transfers) };
```

`1001 → 1002 → 1003 → 1001` is a ring; `1004` is paid out of it; `2001 → 2002` is an
ordinary one-way transfer.

## Stage 1: the rings (graph — a cycle)

An account is in a ring when it can reach **itself**. Compute reachability, then keep
the self-pairs:

```relix
Reach       := { CLOSURE src, dst (Edges) };
RingMembers := { δ (π src → account (σ src = dst (Reach))) };
query { RingMembers };
```

<!-- output: paste from a run. Expected: 1001, 1002, 1003 — the three accounts on the loop. 1004 and the 2001→2002 pair are not on any cycle. -->

## Stage 2: proximity to a flagged account (graph — bounded)

Which accounts are within three transfer hops of the flagged `1003`? A `σ` on the
endpoint scopes the traversal, and `PATH` stamps each with its distance:

```relix
query {
    τ gen, account (
        π dst → account, depth → gen (
            σ src = 1003 (PATH src, dst HOPS 1 TO 3 AS depth (Edges))))
};
```

<!-- output: paste from a run. Expected: 1001 (1 hop), 1002 and 1004 (2 hops), 1003 back to itself (3 hops). -->

Put the holders back with an ordinary [existence](../recipes/existence.md) semi-join to
`RingMembers` (each account once):

```relix
query { π account, holder (Accounts ⋉ Accounts.account = RingMembers.account RingMembers) };
```

<!-- output: paste from a run. Expected: 1001 Ada, 1002 Bo, 1003 Cy. -->

## Stage 3: why is this escalated (explanation)

For a transfer into a flagged account, `WHY` names the exact source rows behind it — the
audit trail an escalation needs:

```relix
Flagged := { π account (σ flagged = true (Accounts)) };
query { WHY (π src, dst, amount (Transfers ⨝ Transfers.dst = Flagged.account Flagged)) };
```

<!-- output: paste from a run. Expected: the 1002→1003 transfer, its provenance naming the Transfers row and the flagged Accounts row (1003, Cy). -->

## What this shows

The three classes compose without any special machinery: the graph stage produces a
relation of ring members, the existence stage filters accounts by it, and the
explanation stage reifies lineage as a column the other operators can read. Each stage is
named after what its rows *are* — `RingMembers`, `Flagged` — so the pipeline reads as the
investigation does.

**A caution the graph stage earns:** one shared value merges a ring. A placeholder
account, or a legitimate hub everyone transacts with, will pull unrelated accounts into
one giant "ring" — look at the largest component first, exactly as the
[connected-groups](../recipes/connected-groups.md) recipe warns. And a ring is evidence,
not proof; keep the `WHY` trail so a human decides.

## Recipes drawn on

- [Reachable, how far, and by what route](../recipes/reachability.md) — `CLOSURE`, `PATH`.
- [Has at least one](../recipes/existence.md) — the semi-join back to accounts.
- [Why is this row here](../recipes/explanation.md) — `WHY`.
- [Things that belong together](../recipes/connected-groups.md) — the merge caution.
