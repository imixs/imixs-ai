FROM python:3.10-bookworm
#FROM python

#RUN pip install llama-cpp-python
RUN CMAKE_ARGS="-DLLAMA_BLAS=ON -DLLAMA_BLAS_VENDOR=OpenBLAS" pip install llama-cpp-python

RUN pip install fastapi
RUN pip install "uvicorn[standard]"
RUN pip install fastapi-xml

COPY ./app /app
#COPY ./models/mistral*.gguf /app
WORKDIR /app

#CMD ["python", "main.py"]        
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"]