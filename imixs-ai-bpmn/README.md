# Imixs-AI-BPMN

The Imixs-AI-BPMN module provides Rest Services and Adapter classes to translate BPMN models, BPMN processes or single BPMN Elements into a text blocks that can be processed by an LLM.

- **BPMNTemplateBuilder**<br/>A builder class to translate a BPMN model into a text block that can be processed by an LLM

- **BPMNTemplateRestService** <br/>A Rest API to output a BPMN model as a text block that can be processed by a LLM<br/>

## The Rest API

You can test the bpmn templates by calling the integrated Rest API

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
