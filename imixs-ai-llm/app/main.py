from typing import Union
from dataclasses import dataclass, field
from fastapi import FastAPI
from fastapi_xml import add_openapi_extension
from fastapi_xml import XmlRoute
from fastapi_xml import XmlAppResponse
from fastapi_xml import XmlBody

from llama_cpp import Llama
from imixs.core import datamodel
import time

# Setup FastAPI with the default XMLAPPResponse class
# 
# app = FastAPI()
app = FastAPI(title="FastAPI::XML", default_response_class=XmlAppResponse)
app.router.route_class = XmlRoute
add_openapi_extension(app)

model = None

model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_M.gguf"
#model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_S.gguf"



#####################
# Basis prompt method. This method expects a XMLPrompt dataobject holding the system and user message
# The output is stored in the tag 'output'.
#
# Example: 
# <XMLPrompt>
#	<system_message>Du bist ein hilfreicher Java Code Assistent.</system_message>
#	<user_message>Was ist die Imixs-Workflow engine?</user_message>
#   <output></output>
# </XMLPrompt>
#
@app.post("/prompt", response_model=datamodel.XMLPrompt, tags=["Imixs-AI"])
def prompt(data: datamodel.XMLPrompt = XmlBody()) -> datamodel.XMLPrompt:

    global model

    # Create a llama model if not yet initialized
    if model is None :

        start_time = time.time()
        print("--- Init Model...")
        model = Llama(
            model_path=model_path,
            n_gpu_layers=30, 
            n_ctx=3584, 
            n_batch=521, 
            verbose=True,
            echo=False
        )
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"--- Init Model...finished in {execution_time} sec")



    # Model parameters
    print("--- compute prompt v2a....")
    max_tokens = 2000
    prompt = f"""<s>[INST] {data.instruction} [/INST] {data.context} """
    print("start processing prompt:\n\n",prompt,'\n...\n')
    result = model(prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    print("-- mir geths noch gut")
    #data.output = result
    return data;




@app.get("/simple")
async def test_get():

    print("--start simple test --")
    llm = Llama(model_path=model_path, n_gpu_layers=30, n_ctx=3584, n_batch=521, verbose=True)
    # adjust n_gpu_layers as per your GPU and model
    output = llm("Q: Name and explain the planets in the solar system? A: ", max_tokens=2000, stop=["Q:", "\n"], echo=True)
    print(output)

    return {"message": "Hello World"}


#@app.post("/simplepost")
#async def test_simplepost(prompt: datamodel.PromptEntity):
#
#    print("--start simple test --")
#    llm = Llama(model_path=model_path, n_gpu_layers=30, n_ctx=3584, n_batch=521, verbose=True)
#    # adjust n_gpu_layers as per your GPU and model
#    # <s>[INST] You are a helpful Java Developer. [/INST] Explain me the Imixs-Workflow engine.
#    output = llm(prompt.instruction, max_tokens=2000, stop=["Q:", "\n"], echo=True)
#    print(output)
#
#    return {"message": "Hello World"}


