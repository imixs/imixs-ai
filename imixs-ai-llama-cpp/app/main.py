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

model_path = "/models/"
model_options = None
model = "mistral-7b-instruct-v0.2.Q3_K_M.gguf"
llm = None

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
    global model_options

    # Init model (using a cache)
    llm=initModel(data.model,data.model_options);
    
    print("start processing \n\n")
    print("      model_options=",model_options,'\n')


    # Convert the prompt_options...
    print(data.prompt_options)
    prompt_options = json.loads(data.prompt_options)
    
    print("      prompt_options=\n",prompt_options,'\n')
    print("      prompt=\n",data.prompt,'\n\n')    
    result = llm(data.prompt, 
                 **prompt_options
    )    
    print(result)
    resultData = datamodel.ResultData(result["choices"][0]["text"])
    return resultData;


#####################
# This helper method initializes the new llm instance with the given modelid and model options.
# 
# The method uses a caching mechanism to initialize the model only if not yet done. 
# For this reason the model name and the model options are stored in global variables
# which allows us to verify if model or options have changed during the last call.
######################
def initModel(_model: str, _model_options: str):

    global llm
    global model
    global model_options

    # Convert options string into a dict...
    model_options_dict=json.loads(_model_options)

    # Verify if the model or options have changed?
    if (llm is None) or (model != _model) or (model_options != model_options_dict):
        model_options=model_options_dict
        model=_model
        # create a new Llama instance
        print("--- Init new llm - Model ID = "+model)
        llm = Llama(
            model_path=model_path+model,
            **model_options
        )
    
    return llm


