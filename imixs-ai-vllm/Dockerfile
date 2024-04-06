# See: https://github.com/abetlen/llama-cpp-python/blob/main/docker/cuda_simple/Dockerfile
ARG CUDA_IMAGE="12.1.1-devel-ubuntu22.04"
FROM nvidia/cuda:${CUDA_IMAGE}

# Install Python3
RUN apt-get update && apt-get upgrade -y \
    && apt-get install -y build-essential python3 python3-pip gcc 

# Install vLLM with CUDA 12.1.
RUN pip install vllm


# Install fastAPI and copy app
RUN pip install fastapi uvicorn fastapi-xml
COPY ./app /app
WORKDIR /app

#CMD ["python3", "./main.py"]
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]