[![License](https://img.shields.io/badge/License-EPL_2.0-blue.svg)](https://opensource.org/licenses/EPL-2.0)

# Imixs-AI Project

**Imixs-AI** is an open source project that seamlessly integrates **Large Language Models (LLMs)** into the processing live cycle of the [Imixs-Workflow Engine](https://www.imixs.org).
The project is based on the [Open-AI API](https://github.com/openai/openai-openapi) specification and can be used to access compliant AI Servers like the [LLaMA.cpp HTTP Server](https://github.com/ggerganov/llama.cpp), [Ollama](https://ollama.com/), [vLLM](https://docs.vllm.ai/en/latest/), as also cloud-based AI services.
This gives you the complete freedom to choose your preferred hosting solution and LLM.

The project provides services and adapters to facilitate the interaction of AI into a BPMN 2.0 based workflow. Imixs-AI completely integrates via the BPMN 2.0 standard into the concept of LLMs. This means that no programming skills are required when interacting with a LLM.

<img width="800" src="./doc/images/architecture.png" />

## Imixs-AI-Workflow

The module [imixs-ai-workflow](./imixs-ai-workflow) provides Jakarta EE services and adapter classes to access a LLM Server based on the [Open-AI API](https://github.com/openai/openai-openapi) specification.
A prompt can be defined directly in a Imixs BPMN 2.0 model. Prompt-Templates used by the LLMAdapter Class can be defined in a BPMN Data item:

<img src="./doc/images/imixs-llm-adapter-config.png" />

## Imixs-AI-BPMN

The module [imixs-ai-bpmn](./imixs-ai-bpmn) provides an api to transform BPMN 2.0 Models into a text representation understandable by any LLM.

## Imixs-AI-RAG

The module [imixs-ai-rag](./imixs-ai-rag) provides services to integrate a RAG system based on Cassandra 4.0

## Imixs-AI-LLaMAcpp

The module [imixs-ai-llama-cpp](./imixs-ai-llama-cpp/README.md) provides a short introduction how to run a LLaMAc++ server with docker.
