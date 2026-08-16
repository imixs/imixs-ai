# Imixs-AI-BPMN

The Imixs-AI-BPMN module provides Rest Services and Adapter classes to translate BPMN models, BPMN processes or single BPMN Elements into text that can be processed by an LLM.

- **BPMNTemplateBuilder**<br/>A builder class to translate a BPMN model into a text block that can be processed by an LLM

- **BPMNTemplateRestService** <br/>A Rest API to output a BPMN model as a text block that can be processed by a LLM<br/>

## BPMN Skill

The Imixs-AI-BPMN module provides a AIPrompt Handler to resove the available models with there initial tasks.
The handler can be integrated into a LLM call by using the following tag:

```
....
WORKFLOW LIST:

<skill.bpmn/>
...
```

`skill.bpmn` will be replaced with the current BPMN skill.

### Rest API

You can check geh BPMN Skill tree by calling the following Rest API Endpoint:

```
/api/ai/bpmn/skills
```

## BPMN Templates

With the BPMN Template feature you can translate a BPMN Model in a LLM understandable markup text.
You can test the bpmn templates by calling the integrated Rest API for each model version.

To generate a template for a specific model version call:

```
/api/ai/bpmn/template/model/version/{modelversion}
```

Example for a System prompt for a BPMN model in german language:

```
Erkläre mir den Ablauf folgenden Geschäftsprozesses:

Business Process: Angebot

START
  |
  |- [Task: 1000] Import
  |   |-- [Event: 20] Angebot erstellen --> [Task: 1100] RETRIEVAL
  |   |-- [Event: 50] (RAG) --> [Task: 1000] Import
  |
  |- [Task: 1100] RETRIEVAL
  |   |-- [Event: 50] (RAG) --> [Task: 1101] GENERATE
  |
  |- [Task: 1101] GENERATE
  |   |-- [Event: 50] (LLM) --> [Task: 1200] Prüfung
  |
  |- [Task: 1200] Prüfung
  |   |-- [Event: 10] Speichern --> [Task: 1200] Prüfung
  |   |-- [Event: 11] Zurück (Debug) --> [Task: 1000] Import
  |   |-- [Event: 20] Angebot erstellen --> [Task: 1100] RETRIEVAL
  |   |-- [Event: 40] Versenden --> [Task: 1400] Bestellt
  |
  |- [Task: 1400] Bestellt
  |   |-- [Event: 10] Speichern --> [Task: 1400] Bestellt
  |   |-- [Event: 20] Erledigt --> [Task: 1900] Eingestellt
  |   |-- [Event: 99] [Wiedervorlage] --> [Task: 1500] Nicht lagerned
  |
  |- [Task: 1500] Nicht lagerned
  |   |-- [Event: 10] Speichern --> [Task: 1400] Bestellt
  |   |-- [Event: 20] Erledigt --> [Task: 1900] Eingestellt
  |   |-- [Event: 30] Löschen --> [Task: 1990] Gelöscht
  |
 END
  |
  |- [Task: 1900] Eingestellt
  |
  |- [Task: 1990] Gelöscht
  |
```

## BPMN Form

The Imixs-AI-BPMN module also provides a `BPMNFormPromptHandler` that resolves the [Imixs Form Specification](https://www.imixs.org/doc/forms/index.html) associated with the current task into a ready-to-use extraction block for an LLM prompt.

Instead of manually writing and maintaining the target XML structure and the field mapping suggestions in a system prompt, a BPMN modeller only needs to reference the current task's form definition once:

```
....
<bpmn.form root="invoice" />
...
```

The `<bpmn.form/>` tag is resolved into a complete block consisting of:

- an introductory instruction sentence
- an XML target structure, derived from the form's `<item>` elements, including `type="date"`/`type="double"` attributes where applicable
- a field mapping list describing the extraction rules for each field (data type, format hints, enum values for select fields, `[required]`/`[readonly]` flags)
- a fixed output instruction footer describing how the LLM should format its response

The generated XML structure is compatible with the `AIResultHandlerXML` adapter, which maps the child elements of the result XML back into the current workitem.

### Tag Attributes

| Attribute  | Description                                                                                                                                  |
| ---------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| `root`     | The root element name of the generated XML structure (default: `data`)                                                                       |
| `items`    | Optional comma separated list of item names to restrict the output to (default: all items)                                                   |
| `readonly` | Set to `ignore` to exclude readonly fields entirely. By default readonly fields are included and flagged as `[readonly]` context information |

### Field Descriptions

Form items can optionally define a `description` attribute in addition to the standard `label`, which is used specifically to guide the LLM extraction (independent of the UI label shown to end users):

```xml
<item name="invoice.total" type="currency" required="true" label="Total:"
      description="The total invoice amount in the original currency. Prefer EUR, then USD, then PLN if multiple currencies are present." />
```

If no `description` is provided, the handler falls back to the item's `label`.

### Example

Given a form definition with invoice fields, the following minimal prompt:

```
You are a clerk at a logistic company. Your task is to check incoming invoices.
<bpmn.form root="invoice" readonly="ignore" />
```

is resolved into:

```
You are a clerk at a logistic company. Your task is to check incoming invoices.
Transfer the data into an XML object with the following structure:
<invoice>
  <invoice.number>...</invoice.number>
  <invoice.date type="date">2026-08-16</invoice.date>
  <invoice.total type="double">1234.00</invoice.total>
  <cdtr.iban>...</cdtr.iban>
  <cdtr.bic>...</cdtr.bic>
</invoice>
Field mapping:
- invoice.number (text): Invoice No.:
- invoice.date (date, format YYYY-MM-DD): Invoice Date:
- invoice.total (double, ISO 4217, decimal point, no thousand separator): Total: [required]
- cdtr.iban (text): IBAN:
- cdtr.bic (text): BIC:
Output only the XML object above! Do not add explanations or comments, and do not create any XML tags other than those shown. The example values (e.g. "...", "2024-12-31", "1234.00") only illustrate the expected format - if you don't have data for a field, leave the corresponding tag empty.
```
