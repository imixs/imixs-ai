# Tool Call Handler: `aggregate_workitems`

## 1. Purpose

Many BPMN-modeled business processes require an aggregate figure computed over a set
of related workitems — for example the sum of billable service hours booked against a
project, the count of open sub-tasks, or the average amount of linked invoices.

You can solve this kind of requirement with an Imixs-AI Agent and the tool call
`aggregate_workitems`. The goal of this tool call handler is to expose the same
capability as a **generic tool call**, so an Imixs AI Agent can perform this kind of
reporting step itself, driven by a BPMN prompt definition instead of custom plugin
code.

`aggregate_workitems` follows the same design pattern already established by
`find_workitem` and `link_workitem`, and shares its query resolution mechanism with
both of them via `WorkitemSearchService`: a generic index-field criteria object, no
hardcoded business field names in the handler, and the actual computation done
server-side in Java — never by the LLM.

## 2. Design Principle: The LLM Never Computes

This is the central architectural decision behind this tool and should not be relaxed
for individual use cases:

- LLMs are unreliable at exact arithmetic over many rows, and returning raw workitem
  data to the LLM just to have it sum a field is slow, expensive in tokens, and
  unacceptable for figures that feed billing or reporting.
- The LLM's role is limited to **deciding which aggregation to run** (which field,
  which filter criteria, which function) and to **interpreting the returned number**
  in its response to the user — never to perform the calculation itself.
- All filtering and math (sum, count, average, min, max) is executed deterministically
  by code.

**Note:** The same principle applies to any subsequent ratio or deviation calculation
(e.g. "hours booked vs. hours contracted"). This is **not** LLM work either. The
Imixs-Workflow engine provides a Script Engine that can be driven from the BPMN model.
Ratio and deviation logic belongs there, in a classic scripted adapter/event, not
inside the LLM tool call and not inside the LLM prompt. `aggregate_workitems` produces
a clean, correct number; anything derived from combining several such numbers is a
separate, deterministic step in the model.

## 3. Relation to Existing Tools

| Tool                  | Purpose                                 | Writes to workitem?               | Returns raw data to LLM?      |
| --------------------- | --------------------------------------- | --------------------------------- | ----------------------------- |
| `find_workitem`       | Read-only lookup of matching workitems  | No                                | Yes (uniqueid + summary list) |
| `link_workitem`       | Find + link matches to current workitem | Yes ($workitemref / custom field) | Yes (match list)              |
| `aggregate_workitems` | Find + compute a single aggregate value | Optional (target field)           | No — only the computed result |

`aggregate_workitems` never returns row-level business data to the LLM. It may
optionally persist the set of matched `$uniqueid`s into a field for traceability, but
that list is never sent back into the LLM context.

All three tools share the same underlying search mechanism (`WorkitemSearchService`),
including the `<item>itemname</item>` reference syntax and query sanitization
described in §4 below — a change to that mechanism affects all three uniformly.

## 4. Selecting Workitems: `criteria` and `filter`

### `criteria`

A map of index field name → value, combined with AND — the same mechanism used by
`find_workitem`/`link_workitem`. A value is either a literal, or references a field of
the **current** workitem using `<item>itemname</item>` syntax instead of being retyped
by the LLM.

```json
{ "$workflowgroup": "Efforts", "$workitemref": "<item>$uniqueid</item>" }
```

There is no separate `relationField` parameter (an earlier design iteration had one).
The relation to the current workitem is not a distinct concept — it is simply another
criterion, typically `"$workitemref": "<item>$uniqueid</item>"`. This removes a
special case from the tool's surface without losing any capability: any field on the
matched workitems can serve as the relation, exactly as any other criterion can.

**Why `<item>itemname</item>` instead of having the LLM retype a value:** LLMs are
measurably unreliable at reproducing long, unstructured strings such as a `$uniqueid`
across a conversation — a single transcription mistake produces a silently wrong
query, not an error. `<item>itemname</item>` is resolved server-side by
`WorkitemSearchService` directly from the current workitem, so the value never passes
through the LLM as free-form text at all. A reference can also sit inside a larger
literal (`"INV-<item>project.id</item>"`) and a value may contain more than one
reference. Resolution fails hard — with a clear error, not a silent empty or wrong
result — if the referenced item does not exist, is blank, or the `<item>`/`</item>`
tag pair is malformed/unbalanced.

**Query injection protection:** Before a literal criteria value is embedded into the
underlying Lucene query, `WorkitemSearchService` strips characters that could let the
value break out of its quoted context (double quotes and backslashes — the latter
because Lucene uses `\` to escape a quote, which could otherwise be used to escape the
query's own closing quote). `AND`/`OR`/parentheses are deliberately left untouched,
since inside a quoted phrase they are literal text to Lucene, not operators — removing
them would only corrupt legitimate values without any security benefit.

### `filter`

`filter` narrows the matches further for conditions that cannot be expressed as an
index criterion — most commonly a business field that is not part of the search index.
It uses the platform's existing `WorkitemHelper.matches(workitem, filter)` regex
mechanism (`(fieldname:pattern)`, combined blocks), applied **per matched workitem,
after** the search — never as part of the Lucene query itself.

```json
"filter": "(service.billable:true)"
```

**`filter` is a required schema field, even though it is empty in most calls.** This
is a deliberate choice, not an oversight. In practice, with several locally hosted
models tested (a genuinely optional field is dropped noticeably more often than a
field the schema always forces the model to emit, even with an empty value), a filter
argument left truly optional was observed — reproducibly, at `temperature: 0` — to be
silently omitted by the model in one of two or more otherwise near-identical calls in
the same completion. Marking it required, with an empty value meaning "no filter",
removed that failure mode in subsequent testing. This finding originated in the
sibling `aggregate_dataview` tool (see the separate documentation for that tool) and
was ported back here once confirmed. `WorkitemHelper.matches(workitem, null_or_blank)`
already treats an empty filter as "everything matches", so no handler logic changed —
only the schema and its description.

The prompt should always spell the filter expression out completely and instruct the
model to pass it through unchanged — never to construct or adapt it itself:

> "Pass the following as the filter parameter, exactly as given: `(service.billable:true)`"

**Pagination is unaffected by filtering.** `AggregateWorkitemsService.aggregate(...)`
pages through the raw search results in fixed-size batches; whether a given page has
more data still to come is decided from the _raw_ page size returned by the search,
before filtering — filtering only decides which of the already-fetched items are
counted, aggregated, or added to the match id list. A page entirely filtered out still
correctly triggers the next page.

## 5. Parameters

| Parameter        | Type   | Required | Description                                                                                                                                                                                                                         |
| ---------------- | ------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `criteria`       | object | yes      | Map of index field name → value, combined with AND. See §4.                                                                                                                                                                         |
| `filter`         | string | yes      | Post-search condition, or an empty value if not needed. See §4. Required for reliability reasons, not because it is usually meaningful.                                                                                             |
| `aggregateField` | string | yes\*    | Name of the numeric field to aggregate (e.g. `service.hours`). \*Not required when `function` is `count`.                                                                                                                           |
| `function`       | enum   | yes      | One of `sum`, `count`, `avg`, `min`, `max`.                                                                                                                                                                                         |
| `targetField`    | string | no       | If given, the computed scalar result is written into this field on the **current** workitem. If omitted, the result is only returned to the LLM, not persisted.                                                                     |
| `matchIdsField`  | string | no       | If given, the list of `$uniqueid`s of all matched workitems is written into this field on the **current** workitem, for traceability and downstream processing. Independent of `targetField` — either, both, or neither may be set. |

`targetField` and `matchIdsField` are deliberately separate parameters rather than a
single "write result" flag, since a caller may want the scalar without the ID list
(e.g. a simple total) or the ID list without persisting the scalar (e.g. when the
scalar is only needed transiently for the agent's response text).

## 6. Write Semantics

Unlike `link_workitem`, which accumulates links via `appendItemValueUnique` because it
represents a manually curated, user-adjustable reference list, `aggregate_workitems`
represents a snapshot of a computed report. Both `targetField` and `matchIdsField` are
therefore **overwritten** on every call, not appended to. Re-running the aggregation
must always reflect the current state — an accumulating list would silently grow
incorrect over repeated reporting runs.

## 7. Result Truncation

The aggregate result is always computed over **all** matches, however many there are —
`aggregate_workitems` pages through the full result set rather than capping it the way
`find_workitem`/`link_workitem` cap what they show. Only the persisted match id list
(`matchIdsField`) is capped, at `MAX_MATCH_IDS_STORED` (500). This is reported
explicitly so the scalar and the stored id list are never silently inconsistent:

```json
{
  "matchCount": 340,
  "matchIdsStored": 200,
  "truncated": true
}
```

## 8. Example Call

```json
{
  "criteria": {
    "$workflowgroup": "Efforts",
    "$workitemref": "<item>$uniqueid</item>"
  },
  "filter": "(service.billable:true)",
  "aggregateField": "service.hours",
  "function": "sum",
  "targetField": "service.hours.summary",
  "matchIdsField": "service.hours.summary.refs"
}
```

## 9. Example Result (`toolMessage`)

```json
{
  "function": "sum",
  "aggregateField": "service.hours",
  "matchCount": 23,
  "matchIdsStored": 23,
  "truncated": false,
  "value": 187.5,
  "targetField": "service.hours.summary",
  "matchIdsField": "service.hours.summary.refs"
}
```

The `toolMessage` returned to the LLM contains **no row-level business data** — no
individual hour values, no field contents of the matched workitems, and not even the
`filter` expression itself. It contains only the scalar result and bookkeeping
metadata. The `$uniqueid` list goes only into the workitem field, never into the LLM's
conversation context.

## 10. Handler Responsibilities

1. Validate that `criteria`, `aggregateField` (unless `function` is `count`) and
   `function` are present and `function` is one of the five supported values; reject
   with `event.setError(...)` otherwise.
2. Resolve `<item>itemname</item>` references in `criteria` against the current
   workitem and build the Lucene query (`WorkitemSearchService`), sanitizing literal
   values against query injection.
3. Page through the full result set in fixed-size batches; pagination is driven by the
   raw page size, unaffected by filtering (see §4).
4. Apply `filter` (`WorkitemHelper.matches`) per raw item, before counting or
   aggregating it — a non-matching item is skipped entirely.
5. Determine the full match count (`matchCount`) over **all** filtered matches, before
   any id-list truncation is applied.
6. Aggregate the resolved `aggregateField` values in Java (`BigDecimal`, not `double`,
   to avoid rounding artifacts in financial figures) over the **full** filtered match
   set.
7. Skip non-numeric or missing field values with a warning, analogous to how
   `update_workitem` skips unconvertible entries — one bad row must not fail the whole
   aggregation.
8. If the match count exceeds `MAX_MATCH_IDS_STORED`, cap the collected `$uniqueid`
   list at that limit and set `truncated: true`; otherwise `matchIdsStored` equals
   `matchCount` and `truncated` is `false`.
9. If `targetField` is set, **overwrite** it with the computed scalar (never
   append/accumulate — see §6).
10. If `matchIdsField` is set, **overwrite** it with the (possibly capped) list of
    matched `$uniqueid`s — again, replace, not append.
11. Build the `toolMessage` result containing only the scalar and bookkeeping
    metadata — never individual field values of the matched workitems.
12. Optionally set `resultValue` if a result-type handler is configured for the agent,
    following the same convention as `find_workitem`/`link_workitem`.

## 11. Guidance for BPMN Modelers

This tool is more abstract than `update_workitem`, since it performs a computation
rather than a direct field mapping. When writing the prompt for a task that uses it,
the modeler should be explicit about:

- **Which field to aggregate and its expected unit** — the LLM has no domain knowledge
  of what `service.hours` means unless stated in the prompt.
- **The complete `criteria` object**, including the relation to the current workitem
  via `<item>$uniqueid</item>` where relevant — do not describe criteria as prose
  bullet points scattered across several lines; give the model one complete example
  object to copy.
- **The exact `filter` expression**, spelled out completely, with an explicit
  instruction to pass it through unchanged — never left to the model to construct.
  Always include the `filter` key even when it is empty, since it is a required
  parameter.
- **Whether to persist the result**, and under which field name — if `targetField` is
  omitted, nothing is written, which is easy to miss when authoring a prompt.
- **What happens after the aggregation** — if a ratio or deviation against a budget or
  contracted value is needed, the prompt should instruct the agent to call
  `task_complete` and let a subsequent BPMN script event perform that calculation,
  rather than asking the LLM to compute or interpret the ratio itself.

For prompts that need several very similar calls (e.g. billable vs. non-billable
hours), give each call as one complete, copy-ready JSON object rather than describing
only what differs between them — models have been observed to drop the one parameter
that changes between otherwise-identical calls more readily than parameters that stay
constant across all of them.

### Example Prompt Fragment

```
Use the tool call "aggregate_workitems" to sum up all billable service hours booked
against the current project. Call it exactly like this:
  {"criteria": {"$workflowgroup": "Efforts", "$workitemref": "<item>$uniqueid</item>"},
   "filter": "(service.billable:true)",
   "aggregateField": "service.hours",
   "function": "sum",
   "targetField": "service.hours.summary"}

After the tool call returns, call task_complete with a short summary stating the
total billable hours and how many bookings were included (matchCount).
Do not calculate or state any percentage or budget comparison yourself — this is
handled by a subsequent processing step.
```

## 12. Open Points for Discussion

- Precision/rounding policy for `avg` — the current implementation uses a fixed
  4-decimal scale (`RoundingMode.HALF_UP`). This should probably become a documented
  platform convention rather than something callers can override, to keep the tool
  schema minimal — worth revisiting if a use case needs different precision.
- The `filter`-required finding (§4) was established empirically against one specific
  locally hosted model family. Whether the same treatment should be applied
  preemptively to other optional-but-often-needed parameters (`targetField`,
  `matchIdsField`) — even though no failure has been observed there yet — is an open
  question; doing so for every optional parameter "just in case" would also make the
  schema harder to reason about and give the model less genuine flexibility.
- The query-injection sanitization in `WorkitemSearchService` (§4) strips `"` and `\`
  from literal values. Whether this should be extended to other Lucene special
  characters as real-world business data surfaces further edge cases remains open.
