#from typing import Union
#from dataclasses import dataclass, field
from typing import Annotated
from fastapi import FastAPI, Path
from fastapi_xml import add_openapi_extension
from fastapi_xml import XmlRoute
from fastapi_xml import XmlAppResponse
from fastapi_xml import XmlBody
import json
from llama_cpp import Llama
from imixs.core import datamodel
import time
from dataclasses import dataclass, field

# Setup FastAPI with the default XMLAPPResponse class
# 
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
#   <model_options>{"n_ctx": 1024, "n_batch": 521}</model_options>
#   <prompt_options>{"max_tokens": 128}</prompt_options>
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

        # convert the options JSON-String into a dict....
        print(data.model_options)
        model_options = json.loads(data.model_options)
    
        # llm = Llama(
        #     model_path=model_path+model,
        #     # 30, -1
        #     n_gpu_layers=-1, 
        #     #n_ctx=3584, 
        #     #n_ctx=4096, 
        #     #n_ctx=5120, 
        #     n_ctx=8192, 
        #     #n_batch=521, 
        #     #verbose=True,
        #     logits_all=True,
        #     echo=False
        # )
        llm = Llama(
            model_path=model_path+model,
            **model_options
        )

    max_tokens = 4096
    print("start processing prompt:\n\n",data.prompt,'\n...\n')

    # Convert the prompt_options...
    print(data.prompt_options)
    prompt_options = json.loads(data.prompt_options)
    
    # result = llm(data.prompt, max_tokens=max_tokens, 
    #                  temperature=0,
    #                  echo=False
    #                  )
    result = llm(data.prompt, 
                 **prompt_options
    )    
    print(result)
    resultData = datamodel.ResultData(result["choices"][0]["text"])
    return resultData;

