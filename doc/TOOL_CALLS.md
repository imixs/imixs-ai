# Tool Calls

<p class="lead">An AI Agent cannot modify a business process directly. Every action it takes is executed through a <strong>Tool Call</strong>.</p>

```
LLM
 │  "I think I should..."
 ▼
Tool Call
 │
 ▼
Business System
 │
 ▼
Result
 │
 ▼
LLM
```

The LLM never writes to your data directly - it proposes an action, the platform executes it against the real business system, and the result is fed back so the agent can decide what to do next. This is what keeps the agent safe and predictable: it can only do what a tool explicitly allows it to do.

See [AI Agents](index.md) for the underlying concept, and [How to Model BPMN AI Agents](howto_model.md) for how tool calls are used inside a system prompt.

---

## Why Tool Calls?

Tool Calls turn "the LLM said something" into "the system did something." A tool call has a clearly defined input (its arguments) and a clearly defined output (what the agent is told happened). That output is what steers the agent's next decision — a rich, informative result actively guides the agent toward the right next step, while a vague one leaves it guessing.

---

## Built-in Tool Calls

Every agent has access to these tools without any extra configuration:

**`task_complete`** — signals that the task is fully done and no further user input is needed.

| Argument | Description                                 |
| -------- | ------------------------------------------- |
| `result` | A short final summary of the completed task |

Don't call this while questions are still open — respond with plain text instead, which routes the case to a human.

**`find_workitem`** — searches for existing workitems by field criteria (combined with AND) and returns up to 20 matches. Read-only; it does not change anything.

| Argument   | Description                                                                                                      |
| ---------- | ---------------------------------------------------------------------------------------------------------------- |
| `criteria` | Field name/value pairs to search for. See [Selecting Workitems](#selecting-workitems-criteria-and-filter) below. |
| `filter`   | Optional, for special cases only. See [Selecting Workitems](#selecting-workitems-criteria-and-filter) below.     |

**`link_workitem`** — same search as `find_workitem`, but links every match directly to the current case (up to 10). Use this whenever the goal is to actually create the link, not just to look at candidates — search and link happen together, so the agent never has to copy an identifier by hand.

| Argument   | Description                                                                                            |
| ---------- | ------------------------------------------------------------------------------------------------------ |
| `criteria` | Same as `find_workitem`                                                                                |
| `filter`   | Same as `find_workitem`                                                                                |
| `refField` | Optional field to additionally store the link in (the link is always stored in `$workitemref` as well) |

The result reports `linkedCount` and the matched cases, so your prompt can tell the agent exactly how to react to zero, one, or several matches.

**`aggregate_workitems`** — computes a single number (sum, count, average, minimum, or maximum) over a set of workitems, e.g. "the total of all billable hours booked to this project." The computation always runs on the server — the agent never sees the individual workitems or their field values, only the final result. This matters: LLMs are not reliable at doing exact arithmetic over many records, and a figure that feeds a report or an invoice has to be exactly right, not approximately right.

| Argument         | Description                                                                                                                    |
| ---------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `criteria`       | Same as `find_workitem`                                                                                                        |
| `filter`         | Same as `find_workitem`                                                                                                        |
| `aggregateField` | The numeric field to aggregate, e.g. `service.hours`. Not needed when `function` is `count`.                                   |
| `function`       | One of `sum`, `count`, `avg`, `min`, `max`                                                                                     |
| `targetField`    | Optional field on the current case to write the result into. If omitted, the result is only returned, not saved.               |
| `matchIdsField`  | Optional field on the current case to write the list of matched case IDs into, for traceability. Independent of `targetField`. |

Because a report has to reflect every matching workitem, `aggregate_workitems` does not cap the number of results the way `find_workitem` and `link_workitem` do — it works through as many as exist. Only the optional `matchIdsField` list is capped at 500 entries; the computed number itself is always exact regardless of how many workitems matched.

**`update_workitem`** — writes field values into the current case, the same way a person would fill out a form.

| Argument | Description                                                                                                               |
| -------- | ------------------------------------------------------------------------------------------------------------------------- |
| `values` | Field name → `{"value": "...", "type": "string" \| "double" \| "date"}`. Dates as `YYYY-MM-DD`, doubles as plain decimals |

Fields starting with `$` are reserved and ignored, so the agent can never accidentally overwrite workflow control data.

**`find_skill` / `get_skill`** — let the agent pick up domain knowledge at runtime instead of it having to be baked into every prompt. See [Working with Skills](skills.md) for details.

---

## Selecting Workitems: `criteria` and `filter`

`find_workitem`, `link_workitem`, and `aggregate_workitems` all select workitems the same way, using two arguments that serve different purposes.

**`criteria`** is the normal way to select workitems: field name/value pairs, combined with AND, e.g.

```json
{ "$workflowgroup": "Contract", "id": "M-AH-4524" }
```

**`filter`** is for the rare case where a condition cannot be expressed through `criteria` alone — for example a business field that isn't part of the standard search. You will only need it occasionally; most prompts never use it. When you do need it, write it directly as a filter expression and tell the agent to use it exactly as given:

```json
{
  "criteria": { "$workflowgroup": "Efforts", "$workitemref": "{{$uniqueid}}" },
  "filter": "(service.billable:true)"
}
```

The filter format is a list of `(fieldname:pattern)` blocks. Always spell it out completely in your prompt instructions and tell the agent to pass it through unchanged — never ask the agent to construct or adapt a filter itself. Which fields need `filter` instead of `criteria` for a given case type is something you determine once while modeling the process, not something the agent should have to work out at runtime.

### Referencing the current case: `{{itemname}}`

A criteria value is usually a literal the agent fills in itself — an extracted license plate number, a fixed category name. But sometimes the value you need is already sitting in a field on the _current_ case, and retyping it is both unnecessary and risky. This is especially true for a case ID (`$uniqueid`): it's a long, unstructured string, and asking the agent to copy it by hand invites the occasional transcription mistake — which produces a wrong search result with no error message to warn you.

Instead, write `{{itemname}}` in place of the value, and the platform substitutes the actual field value from the current case before the search runs:

```json
{ "criteria": { "$workflowgroup": "Efforts", "$workitemref": "{{$uniqueid}}" } }
```

`{{itemname}}` can also sit inside a larger literal, e.g. `"INV-{{project.id}}"`. Prefer `{{itemname}}` over a literal whenever the value already exists on the current case — it removes an entire class of mistake, not just reduces its likelihood. If the referenced field doesn't exist or is empty on the current case, the tool call fails with a clear error rather than silently searching for nothing or for the wrong thing.

`{{itemname}}` is not supported inside `filter` — a filter expression is always a fixed string you write in the prompt, never something built at runtime.

---

## Prompt Examples

### Linking a related case (`link_workitem`)

```
After writing the fine data, use the tool call "link_workitem" to search for the matching
leasing contract and link it to the current workitem in one step. Pass the extracted plate
number (fine.plate.number) as the value for the index field "id", and the fixed value
"Contract" for the index field "$workflowgroup" inside the criteria parameter. Also pass
"contract.ref" as the refField parameter, so the link is additionally stored in that field.
Example: {"criteria": {"id": "M-AH-4524", "$workflowgroup": "Contract"}, "refField":
"contract.ref"}

If link_workitem reports exactly one linked match (linkedCount: 1), call task_complete with
a short one-sentence summary. If it reports zero or more than one match, do not call
task_complete - respond with a short plain-text note instead, stating how many contracts
were found and that a manager needs to review the case.
```

### Summing a project's booked hours (`aggregate_workitems`)

```
Use the tool call "aggregate_workitems" to sum up all billable hours booked to the current
project. Pass the following as the criteria parameter:
{"$workflowgroup": "Efforts", "$workitemref": "{{$uniqueid}}"}

Pass the following as the filter parameter, exactly as given:
(service.billable:true)

Use "service.hours" as the aggregateField, "sum" as the function, and store the result in
the targetField "service.hours.total".

After the tool call returns, call task_complete with a short summary stating the total
billable hours and how many bookings were included (matchCount). Do not calculate or state
any percentage or budget comparison yourself.
```

---

## Designing Good Tool Calls

A few habits that make tool calls reliable in practice:

- **Describe what the arguments mean, not just their names.** Which fields exist and what they represent depends entirely on your process — the agent only knows what your prompt tells it.
- **Prefer `{{itemname}}` over asking the agent to retype a value.** Especially for `$uniqueid` and other identifiers already present on the current case.
- **Write out `filter` expressions completely in the prompt.** Never ask the agent to construct or adapt one itself — a filter is a fixed piece of text you provide, not something the agent reasons about.
- **Give the model something concrete to react to.** A tool result like _"zero matches found"_ is far more actionable than a bare success/failure flag — it tells the agent what to do next.
- **Design for the branch, not just the happy path.** Every tool call your prompt relies on should have a clear instruction for what happens when it returns nothing, one match, or several.
