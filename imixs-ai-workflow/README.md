# Imixs-AI-Workflow

The Imixs-AI-Workflow module provides Adapter classes, CDI Beans and Service EJBs to integrate the Imixs-AI framework into the workflow processing life cycle.

- **LLMAdapter**<br/>The Workflow Adapter class 'LLMAdapter' is used within the processing life cycle on a workflow instance to execute a LLM prompt. The adapter builds the prompt based on a given Prompt Template and evaluates the result object. <br/>

- **LLM-Definition** <br/>A data structure holding the information of a single LLM service endpoint <br/>

- **LLM-Service** <br/>A service EJB interacting with a Imixs-AI service endpoint <br/>

- **LLM-Controller** <br/> A CDI bean for user interaction like data input, data verification and data confirmation. <br/>

- **ImixsAIContextHandler** <br/> A CDI bean to setup a LLM chat conversation.

## The LLMAdapter

The adapter _'org.imixs.llm.workflow.LLMAdapter'_ is used to send a prompt to the MML Service endpoint. The LLMAdaper automatically builds the prompt based on a prompt-template and stores the result into the corresponding workitem.

### Configuration by Properties

The LLMAdapter can be configured by the following imixs.properties

- _llm.service.endpoint_ - defines the service endpoint of tha Imixs-AI service

The parameters can be set in the imixs.properties or as environment variables:

    LLM_SERVICE_ENDPOINT=http://imixs-ai-llm:8000/

These parameters can be overwritten by the model.

### Configuration by the Model

The main prompt configuration of the LLMAdapter is done through the model by defining a workflow result item named '_llm-config_'.

<img src="../doc/images/imixs-llm-adapter-config.png" />

See the following example:

```xml
<llm-config name="PROMPT">
 <endpoint>http://imixs-ai.imixs.com:8000/</endpoint>

 <result-item>....</result-item>
 <result-event>....</result-event>
</llm-config>
```

Properties:

| Property       | Type | Description                                                                |
| -------------- | ---- | -------------------------------------------------------------------------- |
| `endpoint`     | URL  | Rest API endpoint for the llama-cpp server                                 |
| `result-item`  | Text | Item name to store the result returned by the LLM Server                   |
| `result-event` | Text | Optional event identifier to process the result returned by the LLM Server |

**Note:** The llm-config name `PROMPT` is mandatory. It defines the prompt definition and the service endpoint.

### The Prompt Definition

The prompt definition can be defined by a BPMN Data item containing the prompt template. The Prompt Template is defined by a XML document containing the model ID and teh prompt. See the following example:

```xml
<?xml version="1.0" encoding="UTF-8"?>
    <PromptData>
        <model_id>mistral-7b-instruct-v0.2.Q4_K_M.gguf</model_id>
        <prompt><![CDATA[<s>
    [INST] You are a clerk in a logistics company and you job is to check invoices documents. [/INST]


    <filecontent>^.+\.([pP][dD][fF])$</filecontent>

    </s>
    [INST] Extract the language the invoice is written in and the company name.

    Output the information in a JSON object.
    Create only the json object. Do not provide explanations or notes.

    Example JSON Object:

    {
        "language": "German",
        "company.name": "Kraxi GmbH",
    }
    [/INST]

    ]]>
    </prompt>
</PromptData>
```

**Note:** The prompt layout itself is defined by the Large Language Model and can diversify for each LLM.

## The Build-Events

Before a prompt is send to the llama-cpp service endpoint, the prompt-template is processed by Imixs-AI by so called PromptBuilder classes. These are CDI beans reacting on the `LLMPromptEvent` and are responsible to adapt the content of a prompt-template with content provided by the current workitem. There are some standard PromptBuilder classes that can be used out of the box:

### LLMIAdaptTextBuilder

The `LLMIAdaptTextBuilder` can be used to adapt all kind of text elements supported by the [Imixs-Workflow Adapt Text Feature](https://www.imixs.org/doc/engine/adapttext.html). For example you add item values to any part of the prompt-template

    <itemvalue>invoice.summary</itemvalue>

to place the 'invoice.summary' item into the template,

    <username>$editor</username>

to place the userid of the current editor into the template.

Find more about Text adapters:

- [Imixs-Workflow Adapt Text](https://www.imixs.org/doc/engine/adapttext.html)
- [Imixs-Office-Workflow Text Adapter](https://doc.office-workflow.com/textadapter/index.html)

### LLMFileContextBuilder

The `LLMFileContextBuilder` is used to place the content of files attached to the current workitem into the prompt-template. The Builder scans for all files matching a given filename or regular expression and adds the file content into the prompt-template. For example:

    <FILECONTEXT>example.txt</FILECONTEXT>

will place the content of the attached file `example.txt' into the prompt-template, or

    <FILECONTEXT>^.+\.([pP][dD][fF])$</FILECONTEXT>

will place the content of all PDF files into the prompt-template.

You can place the `<FILECONTEXT>` tag multiple times into one prompt-template.

## The Result-Events

To process the result returned by the LLM in an individual way you can specify a optional result-adapter-class. This class is expected as a CDI bean which is triggered by CDI events send from the LLMWorkflow Service during prompt processing

The Events are defined by the classes:

- **LLMResultEvent** - a CDI event fired by the LLMWorkflow. This even can be used in a observer pattern to provide alternative text processing after the LLM result is available.

Depending on the event type a CDI bean can react on a LLMResultEvent or ignore it.

Example of a definition

```xml
<llm-config name="PROMPT">
  <endpoint>http://imixs-ai.imixs.com:8000/</endpoint>
  <result-event>JSON</result-event>
</llm-config>
```

The configuration will trigger a LLMResultEvent with the event type 'JSON'. A CDI Bean can react on this event type:

```java
  ...
    public void onEvent(@Observes LLMResultEvent event) {
        if (event.getWorkitem() == null) {
            return;
        }
        if ("JSON".equals(event.getEventType())) {
            String jsonString = event.getPromptResult();
            .....
        }
    }
  ...
```

## The ImixsAIContextHandler

The ImixsAIContextHandler is a builder class for conversations with an LLM. The class holds the context for a conversation based on a history of
_system_, _user_ and _assistant_ messages. The context is stored in a List of ItemCollection instances that can be persisted and managed by the
Imixs-Workflow engine.

The class supports methods to add system messages, user questions with metadata, and assistant answers. In addition the ImixsAIContextHandler provides methods to convert a conversation into a OpenAI API-compatible message format.

A provided prompt template may look like this example:

```xml
<imixs-ai name="PROMPT">
  <debug>true</debug>
  <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
  <result-event>BOOLEAN</result-event>
  <PromptDefinition>
    <prompt_options>{"n_predict": 16, "temperature": 0 }</prompt_options>
    <prompt role="system"><![CDATA[
       You are a sales expert. You evaluate the following condition to 'true' or 'false'. ]]>
    </prompt>
    <prompt role="user"><![CDATA[
       <itemvalue>$workflowsummary</itemvalue> ]]>
    </prompt>
  </PromptDefinition>
</imixs-ai>
```

# Suggest Items

The llm-config can contain an optional suggest configuration providing a item list and a suggest mode.

```xml
<llm-config name="SUGGEST">
   <items>invoice.number,cdtr.name</items>
   <mode>ON|OFF</mode>
</llm-config>
```

The field 'items' contains a list of item names. This list will be stored in the item `ai.suggest.items`.
An UI can use this information for additional input support (e.g. a suggest list)
The field 'mode' provides a suggest mode for a UI component. The information is stored in the item `ai.suggest.mode`

# Prompt Engineering

If you work with prompt templates including very complex text data like business documents it is important that you make use of the `BOS Token` (Begin of String) and the `EOS token` (End Of String). These tokens indicate to the model that this context is a full completion/exchange and the completion is already finished.

Example

```
<s>[INST] You are a clerk in a logistics company and you job is to check invoices documents. [/INST]

<FILECONTEXT>^.+\.([pP][dD][fF])$</FILECONTEXT>

</s>
[INST] Extract the language the invoice is written in and the company name of the creditor.
Output the information in a JSON object. Create only the json object. Do not provide explanations or notes.
Example JSON Object:
{
  "invoice.language": "English",
  "cdtr.name": "Kraxi GmbH",
} [/INST]
```

Note that it is recommended to use new-lines between the beginning and and of a file context.

## BOS and EOS

Take care about the correct usage of BOS and EOS and other marker Strings like `<s>`, `</s>` or `[INST]`.
These strings often expect an additional space character!

```
<s>[INST] This is my instruction. [/INST]
```

Note the spaces in this example!.

## Few Shot Learning

If you use the 'few shot learning' take care about your examples. Ensure that your examples match exactly the instruction and the format given in the instruction. If not this can cause bad results and at least a longer processing time!

# Integration

As Imixs-AI-Workflow is based on the Open AI API the integration is done by a corresponding LLM endpoint. However an LLM is in most cases protected by a security layer or Access Token. There are two ways to connect an LLM endpoint while considering the security aspect - API Token or BASIC autentication.

## API Token

To access an LLM endpoint with an API token the environment variable `LLM_SERVICE_API_KEY` need to be defined globally for the workflow instance.
The Imixs-AI-Workflow detects the token and automatically establishes a Bearer Token Authentication against the given API Endpoint.

## BASIC Authentication

Optional a basic authentication can be used to connect to the LLM Service. In this case the environment variables
`LLM_SERVICE_ENDPOINT_USER` and `LLM_SERVICE_ENDPOINT_PASSWORD` need to be defined globally for the application.

## Debug Mode

You can activate a debug mode to print out prompt processing information during a workflow processing life cycle.

```xml
<llm-config name="PROMPT">
   ......
   <debug>true</debug>
</llm-con
```
