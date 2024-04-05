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

# Initialize model and cache

model_directory =  "/models/Mistral-7B-Instruct-v0.2-5.0-bpw-exl2/"
print("Loading model: " + model_directory)

config = ExLlamaV2Config(model_directory)
print("debug 1")
model = ExLlamaV2(config)
print("debug 2")
cache = ExLlamaV2Cache(model, lazy = True)
print("debug 3")
model.load_autosplit(cache)
print("debug 4")
tokenizer = ExLlamaV2Tokenizer(config)

# Initialize generator
print("debug 5")

generator = ExLlamaV2BaseGenerator(model, cache, tokenizer)
print("debug 6")

# Generate some text

settings = ExLlamaV2Sampler.Settings()
settings.temperature = 0.85
settings.top_k = 50
settings.top_p = 0.8
settings.token_repetition_penalty = 1.01
settings.disallow_tokens(tokenizer, [tokenizer.eos_token_id])

prompt = "Our story begins in the Scottish town of Auchtermuchty, where once"

max_new_tokens = 150
print("debug 7")

generator.warmup()
time_begin = time.time()
print("debug 8")

output = generator.generate_simple(prompt, settings, max_new_tokens, seed = 1234)

time_end = time.time()
time_total = time_end - time_begin

print(output)
print()
print(f"Response generated in {time_total:.2f} seconds, {max_new_tokens} tokens, {max_new_tokens / time_total:.2f} tokens/second")