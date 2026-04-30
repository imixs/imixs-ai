# Imixs-AI-Workflow

The Imixs-AI-Workflow module provides Adapter classes, CDI Beans and Service EJBs to integrate the Imixs-AI framework into the workflow processing life cycle.

- **OpenAIAPIService** <br/>The Imixs-AI backend service EJB interacting with a LLM service endpoint based on [Open AI API](https://github.com/openai/openai-openapi) <br/>

- **LLMConfigService** <br/>A Singleton EJB that loads and exposes the LLM endpoint registry defined in `imixs-llm.xml`. <br/>

- **Prompt-Definition** <br/>A XML data structure holding the prompt and the LLM options applied for a specific call.<br/>

- **ImixsAIAPAdapter**<br/>A generic Workflow Adapter class used within the processing life cycle on a workflow instance to execute a LLM prompt definition. The adapter builds the prompt based on a given Prompt Template and evaluates the result object. <br/>

- **ImixsAIContextHandler** <br/> A CDI bean to setup a LLM chat conversation.

- **ImixsAISuggestController** <br/> A CDI bean for user interaction like data input, data verification and data confirmation. <br/>

- **ConditionalAIAdapter** <br/> A CDI bean evaluating conditions against an LLM <br/>

The Imixs-AI project provides a flexible way to extend a BPMN model with LLMs.

<img src="../doc/images/imixs-llm-adapter-config.png" />

## LLM Endpoint Configuration

All LLM endpoints used by Imixs-AI-Workflow are registered in a central XML configuration file named `imixs-llm.xml`. The BPMN model never references a URL directly – it references a logical endpoint id that is resolved at runtime via the `LLMConfigService`.

The path to the configuration file is configured via MicroProfile Config:

```
# As environment variable:
LLM_CONFIG_FILE=/opt/imixs/imixs-llm.xml

# Or as system property:
llm.config.file=/opt/imixs/imixs-llm.xml
```

If the property is not set, the registry starts empty and a warning is logged at deployment time.

### File format

Each `<endpoint>` element defines one logical LLM service. A model is either a completion model OR an embedding model – never both. The BPMN configuration references them separately by their id.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<imixs-llm>

    <!-- Completion model – used for chat completions, conditions, analysis etc. -->
    <endpoint id="my-llm">
        <url>https://api.llama.cpp.imixs.com/</url>
        <apikey>${env.LLM_API_KEY}</apikey>
        <options>{
            "model": "llama-3.1-70b-instruct",
            "temperature": 0.2,
            "max_tokens": 1024
        }</options>
    </endpoint>

    <!-- Embedding model – used for RAG indexing and retrieval. -->
    <endpoint id="my-embeddings">
        <url>https://embeddings.llama.cpp.imixs.com/</url>
        <options>{
            "model": "all-MiniLM-L6-v2"
        }</options>
    </endpoint>

</imixs-llm>
```

| Element     | Required | Description                                                                                                                                         |
| ----------- | -------- | --------------------------------------------------------------------------------------------------------------------------------------------------- |
| `id`        | yes      | Logical id referenced from the BPMN model                                                                                                           |
| `<url>`     | yes      | The endpoint URL of the OpenAI-compatible service                                                                                                   |
| `<apikey>`  | no       | Optional API key for Bearer authentication. Locally hosted instances typically do not need one                                                      |
| `<options>` | no       | Default LLM options as a JSON object. These are forwarded as-is to the LLM endpoint and merged with options from BPMN events and prompt definitions |

Environment placeholders of the form `${env.VAR_NAME}` are supported in `<url>`, `<apikey>` and `<options>` – useful for keeping secrets out of the file or for switching the model name per environment.

### Why JSON inside `<options>`

The `<options>` element holds an opaque JSON object. The `LLMConfigService` does not interpret individual keys – it forwards them as-is. This keeps the configuration provider-neutral: any parameter supported by the OpenAI API (or by a specific server like llama.cpp) can be configured without code changes. Arrays and nested objects (`stop`, `response_format`, …) are supported naturally.

### Options layering

LLM options can be defined on three levels. They are merged additively in the following order, with each layer overriding matching keys from the previous one:

1. **Endpoint defaults** – defined in `imixs-llm.xml` and apply to every call against this endpoint
2. **BPMN event override** – defined in the `<imixs-ai>` element of a BPMN signal event (see below)
3. **Prompt definition override** – defined in the `<prompt_options>` element of a Prompt Template

The final JSON body sent to the LLM endpoint is the merge of all three layers. A typical use case is to keep the model name and `max_tokens` stable on the endpoint level, while letting individual events or prompts override only the `temperature`.

## The OpenAIAPIAdapter

The adapter class `org.imixs.ai.workflow.OpenAIAPIAdapter` is used to send a prompt to the LLM Service endpoint. The adapter automatically builds the prompt based on a prompt definition template and stores the result into the corresponding workitem.

The adapter supports two different modes:

- **PROMPT** – send a prompt to a LLM endpoint
- **SUGGEST** – provide a list of items related to the last LLM interaction

### PROMPT Mode

The configuration of the `OpenAIAPIAdapter` is done through the model by defining a workflow result xml tag named `<imixs-ai>`:

```xml
<imixs-ai name="PROMPT">
  <endpoint>my-llm</endpoint>
  <options>{"temperature": 0.7}</options>
  <result-item>offer.proposal</result-item>
  <result-event>JSON</result-event>
  <debug>true</debug>
</imixs-ai>
```

The `imixs-ai` name `PROMPT` is mandatory. The `OpenAIAPIAdapter` can be configured by the following properties:

| Property          | Type    | Description                                                                                   |
| ----------------- | ------- | --------------------------------------------------------------------------------------------- |
| `endpoint`        | Text    | Logical endpoint id as registered in `imixs-llm.xml`                                          |
| `options`         | JSON    | Optional LLM options merged on top of the endpoint defaults (Layer 2 of the options layering) |
| `result-item`     | Text    | Item name to store the result returned by the LLM Server                                      |
| `result-event`    | Text    | Optional event identifier to process the result returned by the LLM Server                    |
| `prompt-template` | XML     | Optional embedded prompt definition                                                           |
| `debug`           | Boolean | Optional, prints debug information                                                            |

The `endpoint` value is the logical id of an entry in `imixs-llm.xml` – not a URL. The actual URL, API key and endpoint-level option defaults are resolved by the `LLMConfigService` at runtime.

#### The Prompt Template

The prompt is defined in a prompt template – an XML object containing the prompt messages and optional `prompt_options`. The prompt template may contain a sequence of prompt messages with one of the roles `system`, `user`, `assistant`, according to the OpenAI API chat template.

```xml
<PromptDefinition>
  <prompt_options>{"temperature": 0.3, "top_p": 0.9}</prompt_options>
  <prompt role="system">You are a computer expert.</prompt>
  <prompt role="user">How long is a byte?</prompt>
</PromptDefinition>
```

The `<prompt_options>` element holds a JSON object with options that are merged on top of the endpoint defaults and BPMN event options (Layer 3 of the options layering).

A prompt template should be defined in a separate BPMN DataObject associated with the corresponding BPMN event.

<img src="../doc/images/imixs-llm-prompt-definition.png" />

This is the recommended way to define a prompt template. Optionally the prompt template can also be embedded into the definition by the tag `<prompt-template>`:

```xml
<imixs-ai name="PROMPT">
  <debug>true</debug>
  <endpoint>my-llm</endpoint>
  <result-event>BOOLEAN</result-event>
  <prompt-template>
    <PromptDefinition>
      <prompt_options>{"temperature": 0.3}</prompt_options>
      <prompt role="system">You are a sales expert. Your task is to summarize ingoing orders. </prompt>
      <prompt role="user"><itemvalue>$workflowsummary</itemvalue></prompt>
    </PromptDefinition>
  </prompt-template>
</imixs-ai>
```

**Note:** In the embedded mode you must not use `<![CDATA[ ... ]]>` tags within the prompt template! This is only allowed in a DataObject.

#### The CDI Event ImixsAIPromptEvent

During processing the prompt definition, the Imixs `OpenAIAPIService` fires a CDI event of the type `org.imixs.ai.workflow.ImixsAIPromptEvent` before a prompt is processed. The event allows an application to add dynamic application data into the prompt. The ImixsAIPromptEvent contains the prompt template and the workitem. An observer CDI Bean can update and extend the given prompt.

**Example:**

```xml
<imixs-ai name="PROMPT">
  <PromptDefinition>
    <prompt role="system"><![CDATA[
       You are a sales expert. Your task is to summarize ingoing orders. ]]>
    </prompt>
    <prompt role="user"><![CDATA[
       Order: {order-data}
        ]]>
    </prompt>
  </PromptDefinition>
</imixs-ai>
```

The following example replaces the placeholder `{order-data}` with an application specific value.

```java
public class MyPromptAdapter {
    public void onEvent(@Observes ImixsAIPromptEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }
        String prompt = event.getPromptTemplate();
        // replace placeholder
        String orderData = myService.getOrderData();
        prompt = prompt.replace("{order-data}", orderData);
        // update the prompt template
        event.setPromptTemplate(prompt);
    }
}
```

#### The CDI Event ImixsAIResultEvent

To process the result returned by the LLM in a customized way you can implement a CDI Observer Bean reacting on the event class `org.imixs.ai.workflow.ImixsAIResultEvent`. The CDI event is fired after the completion result message was received by the `OpenAIAPIService`. This event can be used in an observer pattern to provide alternative text processing after the LLM result is available.

Depending on the `result-event` specified in the `imixs-ai` definition, a CDI bean can react on a specific result event.

**Example:**

```xml
<imixs-ai name="PROMPT">
  <endpoint>my-llm</endpoint>
  <result-event>JSON</result-event>
</imixs-ai>
```

The configuration will trigger a LLMResultEvent with the event type 'JSON'. A CDI Bean can react on this event type:

```java
public class MyResultEventHandler {
    public void onEvent(@Observes ImixsAIResultEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }
        if ("JSON".equals(event.getEventType())) {
            String jsonString = event.getPromptResult();
            // ...
        }
    }
}
```

#### Debugging

You can activate a debug mode to print out prompt processing information during a workflow processing life cycle.

```xml
<imixs-ai name="PROMPT">
   ......
   <debug>true</debug>
</imixs-ai>
```

When debug mode is enabled, the merged option set actually sent to the LLM endpoint is logged – this is the recommended way to verify that the three options layers combine as expected.

### SUGGEST Mode

The imixs-ai configuration can contain an optional suggest-mode providing an item list and a suggest mode.

```xml
<imixs-ai name="SUGGEST">
   <items>invoice.number,cdtr.name</items>
   <mode>ON|OFF</mode>
</imixs-ai>
```

The field `items` contains a list of item names. This list will be stored in the item `ai.suggest.items`. A UI can use this information for additional input support (e.g. a suggest list). The field `mode` provides a suggest mode for a UI component. The information is stored in the item `ai.suggest.mode`.

## The ImixsAIContextHandler

The ImixsAIContextHandler is a builder class for conversations with an LLM. The class holds the context for a conversation based on a history of _system_, _user_ and _assistant_ messages. The context is stored in a List of ItemCollection instances that can be persisted and managed by the Imixs-Workflow engine.

The class supports methods to add system messages, user questions with metadata, and assistant answers. In addition the ImixsAIContextHandler provides methods to convert a conversation into a OpenAI API-compatible message format.

A provided prompt template may look like this example:

```xml
<imixs-ai name="PROMPT">
  <debug>true</debug>
  <endpoint>my-llm</endpoint>
  <result-event>BOOLEAN</result-event>
  <PromptDefinition>
    <prompt_options>{"temperature": 0}</prompt_options>
    <prompt role="system"><![CDATA[
       You are a sales expert. You evaluate the following condition to 'true' or 'false'. ]]>
    </prompt>
    <prompt role="user"><![CDATA[
       <itemvalue>$workflowsummary</itemvalue> ]]>
    </prompt>
  </PromptDefinition>
</imixs-ai>
```

You can add a prompt definition template programmatically:

```java
imixsAIContextHandler.addPromptDefinition(myTemplate);
```

and you can add additional prompt messages in a sequence:

```java
imixsAIContextHandler.addMessage(ImixsAIContextHandler.ROLE_USER, userPrompt,
    workflowService.getSessionContext().getCallerPrincipal().getName(), null);
```

**Note:** Adding a `system` message will reset the current context. If you want to maintain a long conversation you may only add the system message once in the beginning.

### Working with options programmatically

The `LLMOptions` class represents a set of LLM options as an opaque JSON object. It supports additive merging across the three configuration layers and is the type passed between the configuration, adapter and context handler:

```java
// Layer 1: endpoint defaults from imixs-llm.xml
LLMOptions options = llmConfigService.getOptions("my-llm");

// Layer 2: programmatic override
options.merge("{\"temperature\": 0.5}");

// Seed the context handler – Layer 3 (prompt_options) merges on top
// when addPromptDefinition is called.
imixsAIContextHandler.setOptions(options);
imixsAIContextHandler.addPromptDefinition(myTemplate);
```

## The ImixsAIAssistantAdapter

The adapter class `org.imixs.ai.workflow.ImixsAIAssistantAdapter` is an alternative adapter class to separate the prompt messages by different BPMN model elements. The adapter is used to assist a more complex business process with LLMs implementing a continuous consistent prompt template by combining multiple template layers:

<img src="../doc/images/assist-adapter.png" />

- **Task Template:** A DataObject with a prompt definition associated with a Task element. It defines the initial AI 'system' role and describes the process goals, the process context and available next steps within the process. (WHO am I, WHAT do I do, HOW do I work)

- **Event Template:** A DataObject with a prompt definition associated with an Event element containing specific instructions for the current action as also context business data (WHAT should I do NOW)

Each DataObject can hold **Business Data** to provide process variables from workflow fields and additional context or instructions from the user.

This modular approach ensures clean separation of concerns:

- Role definition happens only once in the initial task template
- Event templates focus purely on specific actions
- All templates can be maintained independently

The Template Association in BPMN is done by Tasks and Events connected to DataObjects. The final prompt structure follows this OpenAI Message pattern:

```
"messages": [
    {
        "role": "system",
        "content": TASK TEMPLATE
    },
    {
        "role": "user",
        "content": EVENT TEMPLATE
    }
]
```

The Adapter can be configured similar to the OpenAIAPIAdapter class:

```xml
<imixs-ai name="ASSISTANT">
  <endpoint>my-llm</endpoint>
  <result-item>request.response.text</result-item>
  <result-event>JSON</result-event>
</imixs-ai>
```

The `result-item` holds the message history.

## The ConditionalAIAdapter

The ConditionalAIAdapter reacts on CDI Events of type BPMNConditionEvent and evaluates a condition against an LLM.

The Adapter defines a Default Expression template for LLMs. The BPMN configuration must only include the user prompt. See the following example:

```xml
<imixs-ai name="CONDITION">
    <debug>true</debug>
    <endpoint>my-llm</endpoint>
    <result-item>my.condition</result-item>
    <prompt>Is Germany an EU member country?</prompt>
</imixs-ai>
```

**Caching:** The ConditionalAIAdapter implements a caching mechanism. The adapter class stores the hash value into the item `<result-item>.hash` to avoid duplicate calls against the LLM with the same prompt in one processing cycle.

## Tool Calling

The `ImixsAIContextHandler` supports the OpenAI API tool calling feature. This allows an LLM to request the execution of predefined functions during a conversation. The result is added back into the conversation context so the LLM can continue with the information provided.

### Defining Functions

Functions are defined per request and are **not persisted** as part of the conversation context. They are typically set by the agent before each request based on the current BPMN process context:

```java
contextHandler.addFunction(
    "load_skill",
    "Loads details about an available BPMN workflow process",
    """
    {
        "type": "object",
        "properties": {
            "process_id": {
                "type": "string",
                "description": "The ID of the BPMN process"
            }
        },
        "required": ["process_id"]
    }
    """);
```

The `tool_choice` parameter controls how the LLM uses the defined functions. The default value is `"auto"`, meaning the LLM decides itself whether to call a function or respond with text. You can change this behavior:

- `auto` – LLM decides (default)
- `none` – NO tool calls allowed
- `required` – LLM must call a tool

### Processing Tool Call Results

When the LLM responds with a tool call (`finish_reason: "tool_calls"`), the `OpenAIAPIService` fires a CDI event of the type `ImixsAIToolCallEvent`. An observer can handle the tool call and set the result:

```java
@ApplicationScoped
public class WorkflowToolCallObserver {

    @Inject
    WorkflowService workflowService;

    public void onToolCall(@Observes ImixsAIToolCallEvent event) {
        if ("load_skill".equals(event.getToolName())) {
            String processId = event.getArguments().getString("process_id");
            // Load process details from workflow engine
            String skillContent = workflowService.loadSkill(processId);
            event.setResult(skillContent);
        }
    }
}
```

If no observer handles the tool call a `PluginException` is thrown.

The tool call result is automatically added to the conversation context so the LLM can continue:

```
User:      "I need next week off."
Assistant: tool_call → load_skill("urlaubsantrag")
Observer:  loads process details from workflow engine
Assistant: "I found the vacation request process. Please provide start and end date..."
```

### Security Considerations

The tool calling feature is designed to integrate exclusively with the Imixs Workflow Engine. This means all tool calls are executed within the existing user security context and are subject to the workflow engine's permission model. Observers should always verify that the current user has the required permissions before executing a tool call.

# Prompt Engineering

## Prompt Events

Before a prompt is sent to the LLM service endpoint, the prompt-template is processed by Imixs-AI by so called PromptBuilder classes. These are CDI beans reacting on the `LLMPromptEvent` and are responsible to adapt the content of a prompt-template with content provided by the current workitem. There are some standard PromptBuilder classes that can be used out of the box:

### LLMIAdaptTextBuilder

The `LLMIAdaptTextBuilder` can be used to adapt all kind of text elements supported by the [Imixs-Workflow Adapt Text Feature](https://www.imixs.org/doc/engine/adapttext.html). For example you add item values to any part of the prompt-template:

    <itemvalue>invoice.summary</itemvalue>

to place the `invoice.summary` item into the template,

    <username>$editor</username>

to place the userid of the current editor into the template.

Find more about Text adapters:

- [Imixs-Workflow Adapt Text](https://www.imixs.org/doc/engine/adapttext.html)
- [Imixs-Office-Workflow Text Adapter](https://doc.office-workflow.com/textadapter/index.html)

### LLMFileContextBuilder

The `LLMFileContextBuilder` is used to place the content of files attached to the current workitem into the prompt-template. The Builder scans for all files matching a given filename or regular expression and adds the file content into the prompt-template. For example:

    <FILECONTEXT>example.txt</FILECONTEXT>

will place the content of the attached file `example.txt` into the prompt-template, or

    <FILECONTEXT>^.+\.([pP][dD][fF])$</FILECONTEXT>

will place the content of all PDF files into the prompt-template.

You can place the `<FILECONTEXT>` tag multiple times into one prompt-template.

## BOS and EOS

Usually it is not necessary to use the LLMs BOS and EOS markers as this is covered automatically by the OpenAI API server. It is recommended to use the chat-message layout as explained before.

## Few Shot Learning

If you use 'few shot learning' take care about your examples. Ensure that your examples match exactly the instruction and the format given in the instruction. If not this can cause bad results and at least a longer processing time.

# Security

Imixs-AI-Workflow is based on the OpenAI API. Each LLM endpoint is registered in `imixs-llm.xml` and can be protected individually. The connection mechanism is API-Key (Bearer Token) authentication, configured per endpoint.

### API Key per Endpoint

To access an LLM endpoint with an API key, set the `<apikey>` element in the endpoint definition. The `OpenAIAPIConnector` automatically adds an `Authorization: Bearer <apikey>` header to every request against this endpoint.

```xml
<endpoint id="my-llm">
    <url>https://api.example.com/</url>
    <apikey>${env.LLM_API_KEY}</apikey>
    ...
</endpoint>
```

It is strongly recommended to keep the actual key out of the file and resolve it via an environment placeholder, as shown above. If no `<apikey>` is set, the request is sent without an `Authorization` header, which is typical for locally hosted LLM instances.

### XXE Hardening

The `LLMConfigService` parses `imixs-llm.xml` with external entity processing disabled (`disallow-doctype-decl`) to prevent XXE attacks. The configuration file should still be treated as a sensitive resource and kept under access control.
