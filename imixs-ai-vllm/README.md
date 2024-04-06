# Imixs-AI-vLLM

[vLLM](https://docs.vllm.ai)  is a Python library that also contains pre-compiled C++ and CUDA (12.1) binaries.



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
$ source ~/.env/bin/activate
$ cd models
$ mkdir Mistral-7B-Instruct-v0.2-5.0-bpw-exl2
$ cd Mistral-7B-Instruct-v0.2-5.0-bpw-exl2
$ huggingface-cli  download  alokabhishek/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2  --local-dir . --local-dir-use-symlinks False
```

Next make sure the two models are located unter `models/`


## Alternative Model

To download a different Mistral 7B Model from [huggingface.co](https://huggingface.co/turboderp/Mistral-7B-instruct-exl2/tree/2.5bpw): 

```
$ source ~/.env/bin/activate
$ cd models
$ mkdir Mistral-7B-Instruct-2.5bpw
$ cd Mistral-7B-Instruct-2.5bpw
$ huggingface-cli  download  turboderp/Mistral-7B-instruct-exl2 --revision 2.5bpw --local-dir . --local-dir-use-symlinks False
```



# Docker

We provide a Dockerfile using GPU via CUDA. To build the Docker file run:

    $ docker build . -t imixs/imixs-ai-vllm

Run

    $ docker run --gpus all -v ./models:/models   imixs/imixs-ai-vllm



# Problems:

https://github.com/turboderp/exllamav2/issues/371