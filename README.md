# Imixs-AI Project

**Imixs-AI** is an open source project that seamlessly integrates **Large Language Models (LLMs)** into the processing live cycle of the [Imixs-Workflow Engine](https://www.imixs.org).
The project provides a Rest API and adapter classes to facilitate the interaction of AI into a BPMN 2.0 based workflows.
Imixs-AI is model-independent and can operate on different LLMs. This is a powerful and flexible way to integrate modern LLMs into any kind of business processes.

The project is divided into a generic Adapter Service providing a Rest API to interact with different LLMs and a Workflow Integration module providing plug-ins and adapters to interact with a LLM. Imixs-AI completely integrates via the BPMN 2.0 standard into the concept of LLMs. This means that no programming skills are required when interacting with a LLM.

<img width="800" src="./doc/images/architecture.png" />


## Imixs-AI-LLM 

The module [imixs-ai-llm](./imixs-ai-llama-cpp/README.md) provides a model agnostic Rest API which acts as an *Anti Corruption Layer* between an LLM and the Imixs-Workflow Engine. Imixs-AI-LLM is based on the [LLaMA-cpp project](https://github.com/ggerganov/llama.cpp) and is designed to run LLMs in a Docker container. The project provides a Rest API endpoint for text completion with a llama prompt.

<img src="./doc/images/rest-api-01.png" />
 

## Imixs-AI-Workflow

The module [imixs-ai-workflow](./imixs-ai-workflow) provides Adapter classes, CDI Beans and Service EJBs to integrate Imixs-AI into the workflow processing life cycle. The prompt definition can be defined directly in a Imixs BPMN 2.0 model. Prompt-Templates used by the LLMAdapter Class can be defined in a BPMN Data item:


<img src="./doc/images/imixs-llm-adapter-config.png" />





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