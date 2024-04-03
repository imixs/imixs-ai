from typing import Union
from dataclasses import dataclass, field
from fastapi import FastAPI

from llama_cpp import Llama
from imixs.core import datamodel
import time

# Setup FastAPI with the default XMLAPPResponse class
# 
app = FastAPI()

model = None

#model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_M.gguf"
model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_S.gguf"




@app.get("/simple")
async def test_get():

    print("--start simple test --")
    llm = Llama(model_path=model_path, n_gpu_layers=30, n_ctx=3584, n_batch=521, verbose=True)
    # adjust n_gpu_layers as per your GPU and model
    output = llm("Q: Name and explain the planets in the solar system? A: ", max_tokens=200, stop=["Q:", "\n"], echo=True)
    print(output)

    return {"message": "Hello World"}


@app.post("/simplepost")
async def test_simplepost(prompt: datamodel.PromptEntity):

    print("--start simple test --")
    llm = Llama(model_path=model_path, n_gpu_layers=30, n_ctx=3584, n_batch=521, verbose=True)
    # adjust n_gpu_layers as per your GPU and model
    output = llm(prompt.instruction, max_tokens=200, stop=["Q:", "\n"], echo=True)
    print(output)

    return {"message": "Hello World"}


