import sys, os
sys.path.append(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from exllamav2 import(
    ExLlamaV2,
    ExLlamaV2Config,
    ExLlamaV2Cache,
    ExLlamaV2Tokenizer,
)

from exllamav2.generator import (
    ExLlamaV2BaseGenerator,
    ExLlamaV2Sampler
)

import time
from fastapi import FastAPI
from fastapi_xml import add_openapi_extension
from fastapi_xml import XmlRoute
from fastapi_xml import XmlAppResponse
from fastapi_xml import XmlBody
from imixs.core import datamodel





# Initialize model and cache
#model_directory =  "/models/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2/"
model_directory =  "/models/Mistral-7B-Instruct-2.5bpw/"
generator = None
start_time = time.time()

print("Init model: " + model_directory)

config = ExLlamaV2Config(model_directory)
model = ExLlamaV2(config)
cache = ExLlamaV2Cache(model, lazy = True)
cache.current_seq_len = 0

print("create cache....")
model.load_autosplit(cache)
print("debug 4")
tokenizer = ExLlamaV2Tokenizer(config)

# Initialize generator
print("debug 5")

generator = ExLlamaV2BaseGenerator(model, cache, tokenizer)
print("debug 6")
end_time = time.time()
execution_time = end_time - start_time
print(f"--- Init Model...finished in {execution_time} sec")


# Setup FastAPI with the default XMLAPPResponse class
# 
# app = FastAPI()
app = FastAPI(title="FastAPI::XML", default_response_class=XmlAppResponse)
app.router.route_class = XmlRoute
add_openapi_extension(app)



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
# Note: Option 'logits_all=True' is important here because of bug: https://github.com/abetlen/llama-cpp-python/issues/1326

@app.post("/prompt", response_model=datamodel.XMLPrompt, tags=["Imixs-AI"])
def prompt(data: datamodel.XMLPrompt = XmlBody()) -> datamodel.XMLPrompt:

    global generator
    global tokenizer

    # Model parameters
    print("--- compute prompt....")
    # Generate some text

    settings = ExLlamaV2Sampler.Settings()
    settings.temperature = 0.85
    settings.top_k = 50
    settings.top_p = 0.8
    settings.token_repetition_penalty = 1.01
    settings.disallow_tokens(tokenizer, [tokenizer.eos_token_id])

    # prompt = "Our story begins in the Scottish town of Auchtermuchty, where once"
    prompt = f"""<s>[INST] {data.instruction} [/INST] {data.context} """

    max_new_tokens = 150
    print("debug 7")

    generator.warmup()
    time_begin = time.time()
    print("debug 8")

    result = generator.generate_simple(prompt, settings, max_new_tokens, seed = 1234)

    time_end = time.time()
    time_total = time_end - time_begin

    print(result)
    print()
    print(f"Response generated in {time_total:.2f} seconds, {max_new_tokens} tokens, {max_new_tokens / time_total:.2f} tokens/second")

    data.output = result
    return data;
