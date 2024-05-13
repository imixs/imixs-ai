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
# Basis prompt method. This method expects a PromptDefinition dataobject holding the modelid and the prompt  message
# Example: 
# <PromptDefinition>
#	<model>mistral-7b-instruct-v0.2.Q3_K_M.gguf</model>
#	<prompt>What is the Imixs-Workflow engine?</prompt>
# </PromptDefinition>
#
# Note: Option 'logits_all=True' is important here because of bug: https://github.com/abetlen/llama-cpp-python/issues/1326
@app.post("/prompt", response_model=datamodel.PromptDefinition, tags=["Imixs-AI"])
def prompt(data: datamodel.PromptDefinition = XmlBody()) -> datamodel.PromptDefinition:

    global llm
    global model

    # Create a llama model if not yet initialized
    if model is None or (data.model != '' and data.model != model):
        print("--- Init Model...")
        if data.model != '' :
            model=data.model
        print("-- Model Path = "+model_path+model)
        llm = Llama(
            model_path=model_path+model,
            # 30, -1
            n_gpu_layers=-1, 
            #n_ctx=3584, 
            #n_ctx=4096, 
            n_ctx=5120, 
            #n_batch=521, 
            #verbose=True,
            logits_all=True,
            echo=False
        )


    # Model parameters
    print("--- compute prompt....")

    # Open prompt to  file in write mode ('w')
    with open('prompt-input.txt', 'w') as f:
        # Write a string to the file
        f.write(data.prompt)


    max_tokens = 4096
    print("start processing prompt:\n\n",data.prompt,'\n...\n')
    result = llm(data.prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    print(result)

    resultData = datamodel.ResultData(result["choices"][0]["text"])
    return resultData;





#####################
# Extended prompt method supporting embeddings
# Example: 
# <PromptDefinition>
#	<model>mistral-7b-instruct-v0.2.Q3_K_M.gguf</model>
#	<prompt>What is the Imixs-Workflow engine?</prompt>
#   <embeddings><embeddings>
# </PromptDefinition>
#
# Note: Option 'logits_all=True' is important here because of bug: https://github.com/abetlen/llama-cpp-python/issues/1326
@app.post("/prompt-embeddings", response_model=datamodel.PromptDefinitionEmbeddings, tags=["Imixs-AI"])
def prompt(data: datamodel.PromptDefinitionEmbeddings = XmlBody()) -> datamodel.PromptDefinitionEmbeddings:
    
    global llm
    global model

    # Create a llama model if not yet initialized
    if model is None or (data.model != '' and data.model != model):

        start_time = time.time()
        print("--- Init Model...")

        if data.model != '' :
            model=data.model
        print("-- Model Path = "+model_path+model)
        llm = Llama(
            model_path=model_path+model,
            # 30, -1
            n_gpu_layers=-1, 
            #n_ctx=3584, 
            #n_ctx=4096, 
            n_ctx=5120, 
            #n_batch=521, 
            #verbose=True,
            logits_all=True,
            echo=False,
            embedding=True
        )
        end_time = time.time()
        execution_time = end_time - start_time
        print(f"--- Init Model...finished in {execution_time} sec")



    # Model parameters
    print("--- compute prompt....")
    max_tokens = 1000

    print("--- add embeddings...")
    embeddings = llm.create_embedding(["Whats your name?", "My Name is Anna!"])

    print("start processing prompt:\n\n",data.prompt,'\n...\n')
    result = llm(data.prompt, max_tokens=max_tokens, 
                     temperature=0,
                     echo=False
                     )
    print(result)

    resultData = datamodel.ResultData(result["choices"][0]["text"])
    return resultData;




@app.post("model/{model}")
async def do_switchmodel(model_id: Annotated[str, Path(title="The ID of the model")]):
    # load new model
    global model
    print("--- switch to new Model: " + model+"...")
    model_path=model
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


