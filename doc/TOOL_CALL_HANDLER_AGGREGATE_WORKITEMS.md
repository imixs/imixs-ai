# Tool Call Handler: `aggregate_workitems`

## 1. Purpose

Many BPMN-modeled business processes require an aggregate figure computed over a set
of related workitems — for example the sum of billable service hours booked against a
project, the count of open sub-tasks, or the average amount of linked invoices.

You can solve this kind of requirement with a Imixs-AI Agent and the ToolCall `aggregate_workitems`.
The goal of of this toll call handler is to expose the same capability as a **generic tool
call**, so an Imixs AI Agent can perform this kind of reporting step itself, driven by
a BPMN prompt definition instead of custom plugin code.

`aggregate_workitems` follows the same design pattern already established by
`find_workitem` and `link_workitem`: a generic index-field criteria object, no
hardcoded business field names in the handler, and the actual computation done
server-side in Java — never by the LLM.

## 2. Design Principle: The LLM Never Computes

This is the central architectural decision behind this tool and should not be relaxed for individual use cases:

- LLMs are unreliable at exact arithmetic over many rows, and returning raw workitem
  data to the LLM just to have it sum a field is slow, expensive in tokens, and
  unacceptable for figures that feed billing or reporting.
- The LLM's role is limited to **deciding which aggregation to run** (which field, which filter criteria, which function) and to **interpreting the returned number** in its response to the user — never to perform the calculation itself.
- All filtering and math (sum, count, average, min, max) is executed deterministically by code.

**Note:** The same principle applies to any subsequent ratio or deviation calculation (e.g. "hours booked vs. hours contracted"). This is **not** LLM work either. The Imixs-Worklfow engine provides a Script Engine that can be driven from the BPMN model. Ratio and
deviation logic belongs there, in a classic scripted adapter/event, not inside the a LLM tool call and not inside the LLM prompt. `aggregate_workitems` produces a clean, correct number; anything derived from combining several such numbers is a separate,
deterministic step in the model.

## 3. Relation to Existing Tools

| Tool                  | Purpose                                 | Writes to workitem?               | Returns raw data to LLM?      |
| --------------------- | --------------------------------------- | --------------------------------- | ----------------------------- |
| `find_workitem`       | Read-only lookup of matching workitems  | No                                | Yes (uniqueid + summary list) |
| `link_workitem`       | Find + link matches to current workitem | Yes ($workitemref / custom field) | Yes (match list)              |
| `aggregate_workitems` | Find + compute a single aggregate value | Optional (target field)           | No — only the computed result |

`aggregate_workitems` never returns row-level business data to the LLM. It may optionally persist the set of matched $uniqueids into a field for traceability, but that list is never sent back into the LLM context.

## 4. Tool Definition

### Function name

`aggregate_workitems`

### Parameters

| Parameter        | Type   | Required | Description                                                                                                                                                                                                                         |
| ---------------- | ------ | -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `criteria`       | object | yes      | Map of index field name → value, combined with AND. Same mechanism as `find_workitem`/`link_workitem`.                                                                                                                              |
| `relationField`  | string | no       | Item name on the _matched_ workitems that references the current workitem (default: `$workitemref`). Used to restrict the search to workitems belonging to the current one, without the LLM ever handling a uniqueid.               |
| `aggregateField` | string | yes      | Name of the numeric field to aggregate (e.g. `service.hours`).                                                                                                                                                                      |
| `function`       | enum   | yes      | One of `sum`, `count`, `avg`, `min`, `max`.                                                                                                                                                                                         |
| `targetField`    | string | no       | If given, the computed scalar result is written into this field on the **current** workitem. If omitted, the result is only returned to the LLM, not persisted.                                                                     |
| `matchIdsField`  | string | no       | If given, the list of `$uniqueid`s of all matched workitems is written into this field on the **current** workitem, for traceability and downstream processing. Independent of `targetField` — either, both, or neither may be set. |

`targetField` and `matchIdsField` are deliberately separate parameters rather than a
single "write result" flag, since a caller may want the scalar without the ID list
(e.g. a simple total) or the ID list without persisting the scalar (e.g. when the
scalar is only needed transiently for the agent's response text).

### Write semantics

Unlike `link_workitem`, which accumulates links via `appendItemValueUnique` because it
represents a manually curated, user-adjustable reference list, `aggregate_workitems`
represents a snapshot of a computed report. Both `targetField` and `matchIdsField` are
therefore **overwritten** on every call, not appended to. Re-running the aggregation
must always reflect the current state — an accumulating list would silently grow
incorrect over repeated reporting runs.

### Result truncation

If the number of matches exceeds the platform's result limit (same constant class as
`MAX_RESULT_COUNT` / `MAX_LINK_COUNT` in the existing handlers), the aggregate `value`
is still computed over **all** matches, not just the truncated set — the scalar must
stay correct regardless of how many uniqueids can be stored. Only the persisted ID
list in `matchIdsField` is capped. This is reported explicitly in the result so the
scalar and the stored ID list are never silently inconsistent:

```json
{
  "matchCount": 340,
  "matchIdsStored": 200,
  "truncated": true
}
```

### Example call

```json
{
  "criteria": { "service.billable": "true" },
  "aggregateField": "service.hours",
  "function": "sum",
  "targetField": "service.hours.summary",
  "matchIdsField": "service.hours.summary.refs"
}
```

### Example result (`toolMessage`)

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

The `toolMessage` returned to the LLM still contains **no row-level business data** —
no individual hour values, no field contents of the matched workitems. It contains only
the scalar result and bookkeeping metadata (counts, target field names, truncation
flag). The `$uniqueid` list itself goes only into the workitem field, never into the
LLM's conversation context.

## 5. Handler Responsibilities

1. Validate that `criteria`, `aggregateField` and `function` are present; reject with
   `event.setError(...)` otherwise, following the same pattern as the other handlers.
2. Resolve the set of related workitems: query by `criteria`, restricted to those
   whose `relationField` matches the current workitem's uniqueid.
3. Determine the full match count (`matchCount`) over **all** resolved workitems,
   before any truncation is applied — this figure must reflect reality, not the
   capped subset.
4. Filter and aggregate the resolved `aggregateField` values in Java (`BigDecimal`
   recommended over `double` to avoid rounding artifacts in financial figures), over
   the **full** match set — the scalar is never computed over a truncated subset.
5. Skip non-numeric or missing field values with a warning, analogous to how
   `update_workitem` skips unconvertible entries — one bad row must not fail the
   whole aggregation.
6. If the match count exceeds the platform result limit, cap the list of collected
   `$uniqueid`s at that limit and set `truncated: true`; otherwise `matchIdsStored`
   equals `matchCount` and `truncated` is `false`.
7. If `targetField` is set, **overwrite** it on the current workitem with the
   computed scalar (never append/accumulate — see write semantics in §4).
8. If `matchIdsField` is set, **overwrite** it on the current workitem with the
   (possibly capped) list of matched `$uniqueid`s — again, replace, not append.
   `targetField` and `matchIdsField` are handled independently: either, both, or
   neither may be configured for a given call.
9. Build the `toolMessage` result containing only the scalar, the bookkeeping
   metadata (`matchCount`, `matchIdsStored`, `truncated`), and the configured field
   names — never the individual field values of the matched workitems.
10. Optionally set `resultValue` if a result-type handler is configured for the
    agent, following the same convention as `find_workitem`/`link_workitem`.

## 6. Guidance for BPMN Modelers

This tool is more abstract than `update_workitem`, since it performs a computation
rather than a direct field mapping. When writing the prompt for a task that uses it,
the modeler should be explicit about:

- **Which field to aggregate and its expected unit** — the LLM has no domain
  knowledge of what `service.hours` means unless stated in the prompt.
- **The exact filter condition**, spelled out as criteria, not left to inference —
  e.g. "only include bookings where `service.billable` is `true`".
- **Whether to persist the result**, and under which field name — if `targetField`
  is omitted, nothing is written, which is easy to miss when authoring a prompt.
- **What happens after the aggregation** — if a ratio or deviation against a budget
  or contracted value is needed, the prompt should instruct the agent to call
  `task_complete` and let a subsequent BPMN script event perform that calculation,
  rather than asking the LLM to compute or interpret the ratio itself.

### Example prompt fragment

```
Use the tool call "aggregate_workitems" to sum up all billable service hours booked
against the current project. Use exactly:
  - criteria: {"service.billable": "true"}
  - aggregateField: "service.hours"
  - function: "sum"
  - targetField: "service.hours.summary"

After the tool call returns, call task_complete with a short summary stating the
total billable hours and how many bookings were included (matchCount).
Do not calculate or state any percentage or budget comparison yourself — this is
handled by a subsequent processing step.
```

## 7. Open Points for Discussion

- Should `relationField` default resolution walk only direct references
  (`$workitemref`), or should the handler also support a reverse lookup pattern
  (searching workitems that reference the _current_ workitem via an arbitrary
  named field, as configured per task)? Current proposal assumes the latter is
  just `relationField` pointing at that field name — no separate mechanism needed.
- Precision/rounding policy for `avg` — number of decimal places should probably be
  a fixed platform convention rather than a per-call parameter, to keep the tool
  schema minimal.
- Should `count` require `aggregateField` at all, or should it be optional for that
  one function (counting matches regardless of a specific field's value)?
