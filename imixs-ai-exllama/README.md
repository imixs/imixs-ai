# Imixs-AI-ExLlamaV2

[ExLlamaV2](https://github.com/turboderp/exllamav2)  is an inference library for running local LLMs on modern consumer GPUs.

You can find the exlv2 model variants for Mistral-7B on hugging face: https://huggingface.co/turboderp/Mistral-7B-instruct-exl2


# Docker

We provide a Dockerfile using GPU via CUDA. To build the Docker file run:

    $ docker build . -f ./Dockerfile -t imixs/imixs-ai-exllama
