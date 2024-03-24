# Imixs-AI Project

The Imixs-AI Project provides a AI for business applications based on [Imixs-Workflow](https://www.imixs.org).  The project operates on Large Language Models (LLMs) and provides a generic Rest API to interact with. Imixs-AI is model-independent and can be operated with different LLMs. 
The project is divided into general and model-specific modules and provides Docker images to run the modules in a container environment. 

## Imixs-AI-LLM 

The module [imixs-ai-llm](./imixs-ai-llm) providing a model agnostic AI implementation based on [Llama.cpp](https://github.com/ggerganov/llama.cpp). Lamma CCP allows you to run a LLM with minimal setup and state-of-the-art performance on a wide variety of hardware – locally and in the cloud. 

We currently support the following Large Language models, but the project can be adapted to many other LLMs:
    
- [Mistral-7B Instruct](https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF) from [Mistral AI](https://mistral.ai)
- [Llama 2 7B Chat](https://huggingface.co/TheBloke/Llama-2-7B-Chat-GGUF) from Meta


## Imixs-AI-Workflow

The module [imixs-ai-workflow](./imixs-ai-workflow) provides Adapter classes, CDI Beans and Service EJBs to integrate Imixs-AI into the workflow processing life cycle.
