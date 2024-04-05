# Imixs-AI-ExLlamaV2

[ExLlamaV2](https://github.com/turboderp/exllamav2)  is an inference library for running local LLMs on modern consumer GPUs.

You can find the exlv2 model variants for Mistral-7B on hugging face: https://huggingface.co/turboderp/Mistral-7B-instruct-exl2


Model Sources:

 - https://huggingface.co/alokabhishek/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2



# Download Mistral 7B Model

To download the models form huggingface.co you should use the huggingface-cli. To install this tool  run:

```
$ sudo apt install python3-pip python3.11-venv -y
$ cd
$ source ~/.env/bin/activate
$ pip install --upgrade huggingface_hub
```


To download the Mistral 7B Model from [huggingface.co](https://huggingface.co/alokabhishek/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2): 

```
$ cd models
$ mkdir Mistral-7B-Instruct-v0.2-5.0-bpw-exl2
$ cd Mistral-7B-Instruct-v0.2-5.0-bpw-exl2
$ huggingface-cli  download  alokabhishek/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2  --local-dir . --local-dir-use-symlinks False
```

Next make sure the two models are located unter `models/`





# Docker

We provide a Dockerfile using GPU via CUDA. To build the Docker file run:

    $ docker build . -t imixs/imixs-ai-exllama

Run

    $ docker run --gpus all -v ./models:/models   imixs/imixs-ai-exllama



