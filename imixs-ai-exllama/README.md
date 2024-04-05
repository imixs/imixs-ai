# Imixs-AI-ExLlamaV2

[ExLlamaV2](https://github.com/turboderp/exllamav2)  is an inference library for running local LLMs on modern consumer GPUs.

You can find the exlv2 model variants for Mistral-7B on hugging face: https://huggingface.co/turboderp/Mistral-7B-instruct-exl2


Model Sources:

 - https://huggingface.co/alokabhishek/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2



# Download Mistral 7B Model


Quick Install of git-lfs:


    curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.deb.sh | sudo bash


To download the Mistral 7B Model from [huggingface.co](https://huggingface.co/turboderp/Mistral-7B-instruct-exl2/tree/4.0bpw): 

# https://huggingface.co/turboderp/Mistral-7B-instruct-exl2/tree/4.0bpw
```
$ sudo apt install python3-pip python3.11-venv -y
$ cd
$ source .env/bin/activate
$ pip install --upgrade huggingface_hub
$ huggingface-cli download turboderp/Mistral-7B-instruct-exl2/tree/4.0bpw 4.0bpw --local-dir . --local-dir-use-symlinks False
```

Next make sure the two models are located unter `imixs-ai/imixs-ai-llm/models`

```
$ cd 
$ cd imixs-ai/imixs-ai-llm
$ mkdir models
$ cd models
```




from huggingface_hub import hf_hub_download repo_id = "turboderp/Mistral-7B-instruct-exl2" directory_name = "tree/4.0bpw 4.0bpw" download_path = hf_hub_download(repo_id=repo_id, filename=directory_name) 



# Docker

We provide a Dockerfile using GPU via CUDA. To build the Docker file run:

    $ docker build . -t imixs/imixs-ai-exllama



Run


    $ docker run --gpus all -v ./models:/models   imixs/imixs-ai-exllama



