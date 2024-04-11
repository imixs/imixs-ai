#from typing import Union
#from dataclasses import dataclass, field
from typing import Annotated
from fastapi import FastAPI, Path
from fastapi_xml import add_openapi_extension
from fastapi_xml import XmlRoute
from fastapi_xml import XmlAppResponse
from fastapi_xml import XmlBody

from llama_cpp import Llama
from imixs.core import datamodel
import time
from dataclasses import dataclass, field

# Setup FastAPI with the default XMLAPPResponse class
# 
# app = FastAPI()
app = FastAPI(title="FastAPI::XML", default_response_class=XmlAppResponse)
app.router.route_class = XmlRoute
add_openapi_extension(app)

model = None

model_path = "/models/"
model_id= "mistral-7b-instruct-v0.2.Q3_K_M.gguf"

#####################
# Basis prompt method. This method expects a PromptData dataobject holding the system and user message
# The output is stored in the tag 'output'.
#
# Example: 
# <PromptData>
#	<system_message>Du bist ein hilfreicher Java Code Assistent.</system_message>
#	<user_message>Was ist die Imixs-Workflow engine?</user_message>
#   <output></output>
# </PromptData>
#
# Note: Option 'logits_all=True' is important here because of bug: https://github.com/abetlen/llama-cpp-python/issues/1326
@app.post("/prompt", response_model=datamodel.PromptData, tags=["Imixs-AI"])
def prompt(data: datamodel.PromptData = XmlBody()) -> datamodel.PromptData:

    global model
    global model_id

    # Create a llama model if not yet initialized
    if model is None or (data.model_id != '' and data.model_id != model_id):

     
        start_time = time.time()
        print("--- Init Model...")

        if data.model_id != '' :
            model_id=data.model_id
        print("-- Model Path = "+model_path+model_id)
        model = Llama(
            model_path=model_path+model_id,
            # 99
            n_gpu_layers=99, 
            n_ctx=3584, 
            #n_batch=521, 
            #verbose=True,
            #logits_all=True,
            echo=False
        )
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"--- Init Model...finished in {execution_time} sec")



    # Model parameters
    print("--- compute prompt....")
    max_tokens = 1000
    print("start processing prompt:\n\n",data.prompt,'\n...\n')
    result = model(data.prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    print(result)
    data.output = result
    return data;


@app.post("model/{model_id}")
async def do_switchmodel(model_id: Annotated[str, Path(title="The ID of the model")]):
    # load new model
    global model
    print("--- switch to new Model: " + model_id+"...")
    model_path=model_id
    start_time = time.time()
    print("--- Init Model...")
    model = Llama(
        model_path=model_path,
        n_gpu_layers=30, 
        n_ctx=3584, 
        n_batch=521, 
        verbose=True,
        logits_all=True,
        echo=False
    )
    end_time = time.time()
    execution_time = end_time - start_time
    print(f"--- Init Model...finished in {execution_time} sec")


