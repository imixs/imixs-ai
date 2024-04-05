# See: https://github.com/abetlen/llama-cpp-python/blob/main/docker/cuda_simple/Dockerfile
ARG CUDA_IMAGE="12.1.1-devel-ubuntu22.04"
FROM nvidia/cuda:${CUDA_IMAGE}

# Install Python3
RUN apt-get update && apt-get upgrade -y \
    && apt-get install -y build-essential python3 python3-pip gcc 

# Install from release (with prebuilt extension)
# Python Version 3.10.12
# cuda_12.1.r12.1/compiler.32688072_0
# ==> exllamav2-0.0.17+cu121-cp310-cp310-linux_x86_64.whl 
RUN pip install https://github.com/turboderp/exllamav2/releases/download/v0.0.17/exllamav2-0.0.17+cu121-cp310-cp310-linux_x86_64.whl 






# Install fastAPI and copy app
#RUN pip install fastapi-xml
COPY ./app /app
WORKDIR /app

CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]
