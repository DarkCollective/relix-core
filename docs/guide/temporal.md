# Time

Relix has real temporal types — `DATE`, `TIME`, `TIMESTAMP` and `DURATION` are
`java.time` values, not strings that happen to sort — and four operators built on top of
them: the AS-OF join, the interval join, `SESSIONIZE` and `DOWNSAMPLE`.

This page runs one fleet-telemetry session throughout. Two probes report a temperature
every so often; each probe is recalibrated from time to time, and a reading is only
trustworthy once the calibration **in effect when it was taken** has been applied. That
last phrase is the AS-OF join, and it is the reason this page exists.

```java
Relix relix = Relix.open();

relix.table("RawReadings", List.of("device", "at", "temp"), List.of(
        Map.of("device", "probe-1", "at", "2026-03-01T07:30:00Z", "temp", 20.1),
        Map.of("device", "probe-1", "at", "2026-03-01T08:00:00Z", "temp", 20.4),
        Map.of("device", "probe-1", "at", "2026-03-01T08:05:00Z", "temp", 20.6),
        Map.of("device", "probe-1", "at", "2026-03-01T09:45:00Z", "temp", 21.0),
        Map.of("device", "probe-1", "at", "2026-03-01T09:50:00Z", "temp", 21.2),
        Map.of("device", "probe-1", "at", "2026-03-01T13:00:00Z", "temp", 22.3),
        Map.of("device", "probe-2", "at", "2026-03-01T11:00:00Z", "temp", 18.7),
        Map.of("device", "probe-2", "at", "2026-03-01T11:20:00Z", "temp", 18.9)));

relix.table("RawCalibrations", List.of("device", "at", "offset"), List.of(
        Map.of("device", "probe-1", "at", "2026-03-01T08:00:00Z", "offset", 0.5),
        Map.of("device", "probe-1", "at", "2026-03-01T12:00:00Z", "offset", 0.7),
        Map.of("device", "probe-2", "at", "2026-02-20T09:00:00Z", "offset", -0.2)));

relix.define("""
        Readings     := { π device, to_timestamp(at) → at, temp (RawReadings) };
        Calibrations := { π device, to_timestamp(at) → at, offset (RawCalibrations) };
        """);

// A heading, printed compactly. Used a few times below.
class Show {
    static String heading(Schema schema) {
        return schema.columns().stream()
                .map(column -> column.name() + ":" + column.type().display())
                .collect(java.util.stream.Collectors.joining(", "));
    }
}

System.out.println(Show.heading(relix.relation("Readings").schema()));
System.out.println(Show.heading(relix.relation("RawReadings").schema()));
```

```
device:string, at:timestamp, temp:number
device:string, at:string, temp:number
```

## A string that sorts correctly is not a timestamp

The two `to_timestamp` projections are not decoration. `table` infers its types, and it
has two to choose from: a column of numbers is `NUMBER` and everything else is `STRING`.
An ISO-8601 instant written as text **orders** exactly as the instant does, so a `τ` over
it looks right, a `σ` over it looks right, and an AS-OF join over it finds the right row.

What it has no answer for is *how far apart* two of them are. There is no distance between
two strings, so the tolerance clause below — and every `DURATION` arithmetic — is an
analysis error over a text column rather than a bound that quietly does nothing. Converting
once, at the edge, is what stops that reaching the query.

A column that is a `TIMESTAMP` from the start is the other route: declare the heading and
hand over the rows, which is [`source`](data-in.md#rows-you-produce-on-demand).

## Binding a moment

A `java.time` value goes into a query as a literal *node*, built by `timestamp`, `date`,
`time` or `duration`. Nothing is formatted into text and parsed back:

```java
import java.time.Instant;

Instant shiftStart = Instant.parse("2026-03-01T09:00:00Z");

Relation afterShift = relix.relation("Readings")
        .select(ge(attr("at"), timestamp(shiftStart)));

System.out.println(afterShift.render());
afterShift.toList().forEach(System.out::println);
```

```
σ at ≥ TIMESTAMP '2026-03-01T09:00:00Z' (Readings)
(device=probe-1, at=2026-03-01T09:45:00Z, temp=21)
(device=probe-1, at=2026-03-01T09:50:00Z, temp=21.2)
(device=probe-1, at=2026-03-01T13:00:00Z, temp=22.3)
(device=probe-2, at=2026-03-01T11:00:00Z, temp=18.7)
(device=probe-2, at=2026-03-01T11:20:00Z, temp=18.9)
```

`timestamp(Instant)` is the one to reach for when the moment is a variable — a request
parameter, a clock reading, the end of the last run. Its sibling `timestamp(String)`
takes the ISO-8601 text instead, for when the moment is a constant written in the source.

## AS-OF: the row in effect at this time

An AS-OF join matches each left row to the **single nearest** right row rather than to an
exact one. The condition carries both halves of that: the equality keys say which rows are
even candidates, and the one inequality says which direction "nearest" runs in.

Read as a picture, a history relation cuts the timeline into stretches, each ruled by the
row that opened it. An AS-OF join asks, for every probe, which stretch it landed in:

```
                    ┌──── offset 0.5 ────────────────┬──── offset 0.7 ─────────▶
  Calibrations      ●                                ●
  (probe-1)       08:00                            12:00

  Readings       ▲    ▲     ▲              ▲  ▲                      ▲
  (probe-1)    07:30 08:00 08:05         09:45 09:50               13:00
                 │
            before the first
            row: no match
```

Three of the boundaries in that picture are decisions rather than pictures: whether 08:00
belongs to the stretch it opens, what happens to 07:30 on the left of everything, and
whether 13:00 falls off the right-hand end. All three are visible in the result below.

```java
Relation corrected = relix.relation("Readings").asOfJoin(
        relix.relation("Calibrations"),
        allOf(eq(attr("Readings.device"), attr("Calibrations.device")),
              ge(attr("Readings.at"), attr("Calibrations.at"))));

System.out.println(corrected.render());
corrected.toList().forEach(System.out::println);
```

```
(Readings) ASOF (Readings.device = Calibrations.device) ∧ (Readings.at ≥ Calibrations.at) (Calibrations)
(device=probe-1, at=2026-03-01T07:30:00Z, temp=20.1, device_r=NULL, at_r=NULL, offset=NULL)
(device=probe-1, at=2026-03-01T08:00:00Z, temp=20.4, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T08:05:00Z, temp=20.6, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T09:45:00Z, temp=21, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T09:50:00Z, temp=21.2, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T13:00:00Z, temp=22.3, device_r=probe-1, at_r=2026-03-01T12:00:00Z, offset=0.7)
(device=probe-2, at=2026-03-01T11:00:00Z, temp=18.7, device_r=probe-2, at_r=2026-02-20T09:00:00Z, offset=-0.2)
(device=probe-2, at=2026-03-01T11:20:00Z, temp=18.9, device_r=probe-2, at_r=2026-02-20T09:00:00Z, offset=-0.2)
```

The two sides are concatenated, and `device` occurs on both, so the right-hand copies come
back as `device_r` and `at_r`. Every join in the language disambiguates a repeated name that
way; a projection like the one below is how a result stops carrying them.

Five things in that result are worth reading off one at a time, because each is a boundary
the operator has to decide and none of them is guessable from a signature.

The 07:30 reading precedes every calibration its probe has, so there is nothing at-or-before
it. AS-OF is **left-outer by default**: the probe survives with NULLs on the right rather
than disappearing.

The 08:00 reading sits **exactly on** a calibration. `≥` includes the boundary, so it
matches; written `>` it would not, and the row would fall back to whatever came earlier.
That is the whole difference between the two spellings and it shows up on exactly one row.

The 09:45 and 09:50 readings fall between two calibrations and take the **earlier** one —
"most recent at-or-before" is the point, not "closest".

The 13:00 reading is after the last calibration, and keeps it. A history relation does not
have to be extended to the end of time; the last row stays in effect.

The probe-2 readings match a calibration from nine days earlier, and *not* probe-1's
calibration from three hours earlier. The equality key is what makes that true — without
it the nearest row in time would win, and it belongs to another device.

### Applying the match

The join's output is one row, so the correction is an ordinary projection over it:

```java
corrected.project(List.of(
                projected(attr("Readings.device"), "device"),
                projected(attr("Readings.at"), "at"),
                projected(plus(attr("temp"), attr("offset")), "corrected")))
        .toList()
        .forEach(System.out::println);
```

```
(device=probe-1, at=2026-03-01T07:30:00Z, corrected=NULL)
(device=probe-1, at=2026-03-01T08:00:00Z, corrected=20.9)
(device=probe-1, at=2026-03-01T08:05:00Z, corrected=21.1)
(device=probe-1, at=2026-03-01T09:45:00Z, corrected=21.5)
(device=probe-1, at=2026-03-01T09:50:00Z, corrected=21.7)
(device=probe-1, at=2026-03-01T13:00:00Z, corrected=23)
(device=probe-2, at=2026-03-01T11:00:00Z, corrected=18.5)
(device=probe-2, at=2026-03-01T11:20:00Z, corrected=18.7)
```

The unmatched 07:30 row propagates its NULL through the arithmetic, which is the correct
answer: there is no calibration, so there is no corrected reading. Filtering those rows out
is `σ`, or the `inner` flag on the four-argument form below.

### Tolerance: rejecting a match that is too old

A nine-day-old calibration is not really "in effect". `WITHIN` bounds how far back a match
may be, and a candidate further away than that is rejected as though it were not there:

```java
import java.time.Duration;
import java.util.Optional;
import com.darkcollective.relix.ast.TieBreak;

Relation fresh = relix.relation("Readings").asOfJoin(
        relix.relation("Calibrations"),
        allOf(eq(attr("Readings.device"), attr("Calibrations.device")),
              ge(attr("Readings.at"), attr("Calibrations.at"))),
        Optional.of(duration(Duration.ofDays(7))),
        false,
        TieBreak.LAST);

System.out.println(fresh.render());
fresh.toList().forEach(System.out::println);
```

```
(Readings) ASOF (Readings.device = Calibrations.device) ∧ (Readings.at ≥ Calibrations.at) WITHIN DURATION 'PT168H' (Calibrations)
(device=probe-1, at=2026-03-01T07:30:00Z, temp=20.1, device_r=NULL, at_r=NULL, offset=NULL)
(device=probe-1, at=2026-03-01T08:00:00Z, temp=20.4, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T08:05:00Z, temp=20.6, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T09:45:00Z, temp=21, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T09:50:00Z, temp=21.2, device_r=probe-1, at_r=2026-03-01T08:00:00Z, offset=0.5)
(device=probe-1, at=2026-03-01T13:00:00Z, temp=22.3, device_r=probe-1, at_r=2026-03-01T12:00:00Z, offset=0.7)
(device=probe-2, at=2026-03-01T11:00:00Z, temp=18.7, device_r=NULL, at_r=NULL, offset=NULL)
(device=probe-2, at=2026-03-01T11:20:00Z, temp=18.9, device_r=NULL, at_r=NULL, offset=NULL)
```

Only the probe-2 rows change: their calibration is nine days old, past the seven-day bound,
so they are rejected exactly as the 07:30 row was — the bound removes a match, it does not
remove a row. (`Duration.ofDays(7)` normalises to 168 hours on the way out, which is why the
rendered text says `PT168H`; it is the same duration.)

The four-argument form is where the rest of the operator's settings live. `inner` is the
third argument — pass `true` and an unmatched probe is dropped instead of NULL-padded:

```java
Relation freshInner = relix.relation("Readings").asOfJoin(
        relix.relation("Calibrations"),
        allOf(eq(attr("Readings.device"), attr("Calibrations.device")),
              ge(attr("Readings.at"), attr("Calibrations.at"))),
        Optional.of(duration(Duration.ofDays(7))),
        true,
        TieBreak.LAST);

System.out.println(freshInner.count() + " readings have a fresh calibration");
```

```
5 readings have a fresh calibration
```

`TieBreak` decides which row wins when two right rows share the same nearest value. The
default, `LAST`, keeps the later one in input order; `FIRST` keeps the earlier. A history
with no duplicate timestamps never reaches the question — which is the shape to prefer,
because a tie has two defensible answers and nothing in the data says which you meant.

The tolerance is measured the same way the language's own subtraction is, so it is defined
between two `TIMESTAMP`s, two `DATE`s or two `DURATION`s. A `WITHIN` over a match column
with no distance — text, a number, a `TIME` whose difference wraps — is an error at
analysis time.

Against a Postgres connection the join itself folds into a `LEFT JOIN LATERAL`; the
tolerance is applied in the engine.

## Interval joins: overlap rather than a point

AS-OF asks about an instant. When both sides carry a *range*, the question is geometric —
does this outage fall inside a planned maintenance window? — and the operator states the
relationship by name rather than as four endpoint comparisons.

```java
relix.table("RawWindows", List.of("device", "opens", "closes"), List.of(
        Map.of("device", "probe-1", "opens", "2026-03-01T09:00:00Z",
               "closes", "2026-03-01T10:00:00Z"),
        Map.of("device", "probe-2", "opens", "2026-03-01T14:00:00Z",
               "closes", "2026-03-01T15:00:00Z")));

relix.table("RawOutages", List.of("device", "down", "up"), List.of(
        Map.of("device", "probe-1", "down", "2026-03-01T09:30:00Z",
               "up", "2026-03-01T09:40:00Z"),
        Map.of("device", "probe-1", "down", "2026-03-01T11:00:00Z",
               "up", "2026-03-01T11:15:00Z"),
        Map.of("device", "probe-2", "down", "2026-03-01T14:30:00Z",
               "up", "2026-03-01T15:30:00Z")));

relix.define("""
        Windows := { π device, to_timestamp(opens) → opens, to_timestamp(closes) → closes
                       (RawWindows) };
        Outages := { π device, to_timestamp(down) → down, to_timestamp(up) → up
                       (RawOutages) };
        """);

System.out.println(relix.relation("Outages").count() + " outages, "
        + relix.relation("Windows").count() + " planned windows");
```

```
3 outages, 2 planned windows
```

`intervalJoin` takes the Allen relation and the four endpoint columns. It has no equality
key of its own — the relationship it tests is purely geometric — so pairing an outage with
*its own* device's window is an ordinary `σ` around it:

```java
import com.darkcollective.relix.ast.AllenRelation;

Relation overlapping = relix.relation("Outages")
        .intervalJoin(relix.relation("Windows"), AllenRelation.INTERSECTS,
                "Outages.down", "Outages.up", "Windows.opens", "Windows.closes")
        .select(eq(attr("Outages.device"), attr("Windows.device")));

System.out.println(overlapping.render());
overlapping.toList().forEach(System.out::println);
```

```
σ Outages.device = Windows.device ((Outages) IJOIN INTERSECTS (Outages.down, Outages.up, Windows.opens, Windows.closes) (Windows))
(device=probe-1, down=2026-03-01T09:30:00Z, up=2026-03-01T09:40:00Z, device_r=probe-1, opens=2026-03-01T09:00:00Z, closes=2026-03-01T10:00:00Z)
(device=probe-2, down=2026-03-01T14:30:00Z, up=2026-03-01T15:30:00Z, device_r=probe-2, opens=2026-03-01T14:00:00Z, closes=2026-03-01T15:00:00Z)
```

`INTERSECTS` is the convenience superset — any shared time at all. The thirteen named
relations are finer, and the difference between two of them is exactly the question an ops
team is asking. An outage that sits **strictly inside** a planned window is explained;
one that starts inside and runs past the end is not:

```java
Relation explained = relix.relation("Outages")
        .intervalJoin(relix.relation("Windows"), AllenRelation.DURING,
                "Outages.down", "Outages.up", "Windows.opens", "Windows.closes")
        .select(eq(attr("Outages.device"), attr("Windows.device")));

explained.project(List.of(
                projected(attr("Outages.device"), "device"),
                projected(attr("Outages.down"), "down")))
        .toList()
        .forEach(System.out::println);
```

```
(device=probe-1, down=2026-03-01T09:30:00Z)
```

Two of the three outages intersect a window; only one is `DURING` it. Intervals are
half-open — `[start, end)` — and the thirteen base relations use strict endpoints, so a
shared boundary is named by its own relation (`MEETS`, `STARTS`, `FINISHES`, `EQUALS`)
rather than folded into `DURING`. The interval join is an inner join: there is no
NULL-padding, and an outage with no window simply does not appear.

## SESSIONIZE: splitting a stream at the gaps

`SESSIONIZE` walks an ordered stream and starts a new session wherever the gap to the
previous row exceeds a threshold. Every input row is kept — the operator only *adds* a
column — so it composes with whatever comes next.

```java
Relation bursts = relix.relation("Readings")
        .sessionize("at", duration(Duration.ofMinutes(30)), List.of("device"), "burst");

System.out.println(bursts.render());
bursts.toList().forEach(System.out::println);
```

```
SESSIONIZE at GAP DURATION 'PT30M' PER device AS burst (Readings)
(device=probe-1, at=2026-03-01T07:30:00Z, temp=20.1, burst=1)
(device=probe-1, at=2026-03-01T08:00:00Z, temp=20.4, burst=1)
(device=probe-1, at=2026-03-01T08:05:00Z, temp=20.6, burst=1)
(device=probe-1, at=2026-03-01T09:45:00Z, temp=21, burst=2)
(device=probe-1, at=2026-03-01T09:50:00Z, temp=21.2, burst=2)
(device=probe-1, at=2026-03-01T13:00:00Z, temp=22.3, burst=3)
(device=probe-2, at=2026-03-01T11:00:00Z, temp=18.7, burst=1)
(device=probe-2, at=2026-03-01T11:20:00Z, temp=18.9, burst=1)
```

The 07:30 and 08:00 readings are **exactly** thirty minutes apart and stay in the same
burst. The gap is strict: a new session begins when the gap is *greater than* the
threshold, not when it reaches it. Sessions are numbered from 1 within each partition, so
probe-2's first burst is `1` and not `4`.

Counting the bursts is then an ordinary γ over the added column:

```java
import com.darkcollective.relix.ast.AggregateOperator;

bursts.aggregate(List.of("device", "burst"),
                List.of(agg(AggregateOperator.COUNT, "temp", "readings"),
                        agg(AggregateOperator.MAX, "temp", "peak")))
        .sort(asc("device"), asc("burst"))
        .toList()
        .forEach(System.out::println);
```

```
(device=probe-1, burst=1, readings=3, peak=20.6)
(device=probe-1, burst=2, readings=2, peak=21.2)
(device=probe-1, burst=3, readings=1, peak=22.3)
(device=probe-2, burst=1, readings=2, peak=18.9)
```

## DOWNSAMPLE: one row per time bucket

`DOWNSAMPLE` aligns rows onto fixed-width buckets and consolidates each one. Where γ groups
by a value that is already in the data, this one *derives* the group from the clock:

```java
import com.darkcollective.relix.ast.ConsolidationFunction;

Relation hourly = relix.relation("Readings")
        .downsample("at", "1h", ConsolidationFunction.AVG, List.of("device"));

System.out.println(hourly.render());
hourly.toList().forEach(System.out::println);
```

```
DOWNSAMPLE at BY '1h' USING AVG PER device (Readings)
(device=probe-1, bucket=2026-03-01T07:00:00Z, avg_temp=20.1)
(device=probe-1, bucket=2026-03-01T08:00:00Z, avg_temp=20.5)
(device=probe-1, bucket=2026-03-01T09:00:00Z, avg_temp=21.1)
(device=probe-1, bucket=2026-03-01T13:00:00Z, avg_temp=22.3)
(device=probe-2, bucket=2026-03-01T11:00:00Z, avg_temp=18.8)
```

`bucket` holds the aligned start of the window. The interval accepts either the shorthand
(`30s`, `5m`, `1h`, `1d`, `1w`) or ISO-8601 (`PT5M`, `P1D`).

Which columns survive depends on the function, and the rule is about what the function
*means* rather than about a list of names. `AVG` and `SUM` compute, so they consolidate the
`NUMBER` columns and drop everything else. `MIN` and `MAX` compare, and comparison is
defined on strings and timestamps as well as on numbers, so they keep every scalar column.

Dropping the `PER device` key is what makes that visible — `device` stops being a grouping
key and becomes something to consolidate:

```java
relix.relation("Readings")
        .downsample("at", "1h", ConsolidationFunction.MAX, List.of())
        .sort(asc("bucket"))
        .toList()
        .forEach(System.out::println);
```

```
(bucket=2026-03-01T07:00:00Z, max_device=probe-1, max_temp=20.1)
(bucket=2026-03-01T08:00:00Z, max_device=probe-1, max_temp=20.6)
(bucket=2026-03-01T09:00:00Z, max_device=probe-1, max_temp=21.2)
(bucket=2026-03-01T11:00:00Z, max_device=probe-2, max_temp=18.9)
(bucket=2026-03-01T13:00:00Z, max_device=probe-1, max_temp=22.3)
```

`COUNT` produces a single `count` column and keeps nothing else, which is the shape to
reach for when the question is about density rather than about values.

`DOWNSAMPLE` and `SESSIONIZE` both buffer and sort their input before emitting anything,
so both are evaluated in the engine and neither is folded into a backend query.
