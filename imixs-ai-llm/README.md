
## Performance Issues

https://github.com/abetlen/llama-cpp-python/issues/982



# Docker Test

    docker run -v ./models:/models ghcr.io/ggerganov/llama.cpp:light -m /models/mistral-7b-instruct-v0.2.Q4_K_M.gguf -p "Wer ist der aktuelle Bundeskanzler von Deutschland?" -n 512


# Llama-cpp-pyhton

API: 
https://llama-cpp-python.readthedocs.io/en/latest/api-reference/


# Prompt Engineering 

https://www.promptingguide.ai/models/mistral-7b

https://community.aws/content/2dFNOnLVQRhyrOrMsloofnW0ckZ/how-to-prompt-mistral-ai-models-and-why

https://blog.cloudflare.com/workers-ai-update-hello-mistral-7b-de-de

https://www.promptingguide.ai/models/mixtral

https://docs.mistral.ai/guides/prompting-capabilities/


# GPU Support



https://medium.com/@ryan.stewart113/a-simple-guide-to-enabling-cuda-gpu-support-for-llama-cpp-python-on-your-os-or-in-containers-8b5ec1f912a4


## Install NVIDIA Driver on Linux (Debian Bookworm)

To run llama-cpp with GPU on Linux Debian make sure you installed the NVIDIA driver package. Details about the installation process on debian can be found in [this blog post](https://www.linuxcapable.com/install-nvidia-drivers-on-debian/). Also see the [official install guide ](https://wiki.debian.org/NvidiaGraphicsDrivers#Debian_12_.22Bookworm.22)

In the following I install the proprietary NVIDIA Drivers With Cuda Support on Debian Bookworm. There are also open source drivers available, but I did not test this. 



## 1) Update your APT repositories

```
$ sudo apt update
$ sudo apt upgrade
# Remove previous installed nvida drivers (optional)
$ sudo apt autoremove nvidia* --purge
# Enable Contrib and Non-Free Repositories on Debian
$ sudo apt install software-properties-common -y
$ sudo add-apt-repository contrib non-free-firmware
```

## 2) Import the Nvidia APT Repository

This repo allows access to additional Nvidia tools like nvida-smi

```
$ sudo apt install dirmngr ca-certificates software-properties-common apt-transport-https dkms curl -y
$ sudo curl -fSsL https://developer.download.nvidia.com/compute/cuda/repos/debian12/x86_64/3bf863cc.pub | sudo gpg --dearmor | sudo tee /usr/share/keyrings/nvidia-drivers.gpg > /dev/null 2>&1
$ echo 'deb [signed-by=/usr/share/keyrings/nvidia-drivers.gpg] https://developer.download.nvidia.com/compute/cuda/repos/debian12/x86_64/ /' | sudo tee /etc/apt/sources.list.d/nvidia-drivers.list
$ sudo apt update
```


## 3) Install Nvidia Drivers on Debian via DEFAULT APT Repository

We assume you have a 64-bit system

    $ sudo apt update
    $ sudo apt install linux-headers-amd64 nvidia-detect

Check Nvidia support

```
$ nvidia-detect
Detected NVIDIA GPUs:
01:00.0 VGA compatible controller [0300]: NVIDIA Corporation GP104 [GeForce GTX 1080] [10de:1b80] (rev a1)

Checking card:  NVIDIA Corporation GP104 [GeForce GTX 1080] (rev a1)
Your card is supported by all driver versions.
Your card is also supported by the Tesla drivers series.
Your card is also supported by the Tesla 470 drivers series.
It is recommended to install the
    nvidia-driver
package.
```


The output reveals that the machine features a GeForce GTX 1080 card and recommends installing the nvidia-driver package.
Now you can finally install the recommended package....

    $ apt install nvidia-driver nvidia-smi linux-image-amd64 cuda

I install in addition the Nvida Service-Management-Interface and the CUDA framework. 

Finally reboot your system....

    $ sudo reboot

**Note:** it may happen that you need a hard reset on your machine. I don't know exactly why but it could be something with driver conflicts. 

## 3) Verify Installation

To verify your installation run `nvidia-smi` which shows you some insights of your environment:

```
# nvidia-smi
Sun Mar 31 10:46:20 2024       
+-----------------------------------------------------------------------------------------+
| NVIDIA-SMI 550.54.15              Driver Version: 550.54.15      CUDA Version: N/A      |
|-----------------------------------------+------------------------+----------------------+
| GPU  Name                 Persistence-M | Bus-Id          Disp.A | Volatile Uncorr. ECC |
| Fan  Temp   Perf          Pwr:Usage/Cap |           Memory-Usage | GPU-Util  Compute M. |
|                                         |                        |               MIG M. |
|=========================================+========================+======================|
|   0  NVIDIA GeForce GTX 1080        Off |   00000000:01:00.0 Off |                  N/A |
| 36%   42C    P0             39W /  180W |       0MiB /   8192MiB |      0%      Default |
|                                         |                        |                  N/A |
+-----------------------------------------+------------------------+----------------------+
                                                                                         
+-----------------------------------------------------------------------------------------+
| Processes:                                                                              |
|  GPU   GI   CI        PID   Type   Process name                              GPU Memory |
|        ID   ID                                                               Usage      |
|=========================================================================================|
|  No running processes found                                                             |
+-----------------------------------------------------------------------------------------+
```




# Configuring Docker

To get things done in addition it is necessary to install the   'NVIDIA Container Toolkit'. 
   
```
curl -fsSL https://nvidia.github.io/libnvidia-container/gpgkey |sudo gpg --dearmor -o /usr/share/keyrings/nvidia-container-toolkit-keyring.gpg \
&& curl -s -L https://nvidia.github.io/libnvidia-container/stable/deb/nvidia-container-toolkit.list | sed 's#deb https://#deb [signed-by=/usr/share/keyrings/nvidia-container-toolkit-keyring.gpg] https://#g' | sudo tee /etc/apt/sources.list.d/nvidia-container-toolkit.list \
&& sudo apt-get update
$ sudo apt-get install -y nvidia-container-toolkit    
$ sudo nvidia-ctk runtime configure --runtime=docker
sudo systemctl restart docker
```

Test Setup with

    $ nvidia-container-cli -k -d /dev/tty info


should not show errors.

Start a test container: 

    $ docker run --gpus all nvidia/cuda:12.3.1-base-ubuntu20.04  nvidia-smi

This should just the nvidia-smi output form above.


# Download Mistral 7B Model

To download the Mistral 7B Model from [huggingface.co](https://huggingface.co/TheBloke/Mistral-7B-Instruct-v0.2-GGUF): 


```
$ sudo apt install python3-pip python3.11-venv -y
$ source .env/bin/activate
$ source .env/bin/activate
$ pip install --upgrade huggingface_hub
$ huggingface-cli download TheBloke/Mistral-7B-Instruct-v0.2-GGUF mistral-7b-instruct-v0.2.Q4_K_S.gguf --local-dir . --local-dir-use-symlinks False
$ huggingface-cli download TheBloke/Mistral-7B-Instruct-v0.2-GGUF mistral-7b-instruct-v0.2.Q4_K_M.gguf --local-dir . --local-dir-use-symlinks False
```

Next make sure the two models are located unter `imixs-ai/imixs-ai-llm/models`

```
$ cd 
$ cd imixs-ai/imixs-ai-llm
$ mkdir models
$ cd models
```



## Rund Docker


    $ docker run --gpus all -v ./models:/models  imixs/imixs-ai_gpu
