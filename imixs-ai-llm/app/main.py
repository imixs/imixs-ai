from typing import Union
from dataclasses import dataclass, field
from fastapi import FastAPI
from fastapi_xml import add_openapi_extension
from fastapi_xml import XmlRoute
from fastapi_xml import XmlAppResponse
from fastapi_xml import XmlBody

from llama_cpp import Llama
from imixs.core import datamodel

# Setup FastAPI with the default XMLAPPResponse class
# 
# app = FastAPI()
app = FastAPI(title="FastAPI::XML", default_response_class=XmlAppResponse)
app.router.route_class = XmlRoute
add_openapi_extension(app)

model = None

#model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_M.gguf"
model_path = "/models/mistral-7b-instruct-v0.2.Q4_K_S.gguf"



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
        print("--- Init Model...")
        model = Llama(
            model_path=model_path,
            temperature=0.1,
            max_tokens=200,
            n_ctx=3048,
            ctx_size=3000,
            seed=-1, 
            n_threads=8,
            verbose=True,
            echo=False,
            n_gpu_layers=30
        )
        print("--- Init Model...finished!")



    # Model parameters
    print("--- compute prompt....")
    max_tokens = 2000
    prompt = f"""<s>[INST] {data.instruction} [/INST] {data.context} """
    print("start processing prompt:\n\n",prompt,'\n...\n')
    data.output = model(prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    return data;



@app.post("/test", response_model=datamodel.XMLPrompt, tags=["Imixs-AI"])
def prompt(x: datamodel.XMLPrompt = XmlBody()) -> datamodel.XMLPrompt:


    # Model parameters
    max_tokens = 100


    prompt = f"""{x.instruction}"""
    print("start processing prompt:\n\n",prompt,'\n...\n')
    x.output = model(prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    print("--- Output ---")
    print(x.output)
    return x;

