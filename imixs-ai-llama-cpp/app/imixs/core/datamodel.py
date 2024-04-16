"""
The data model to send promt data to llama-cpp-python

@author: ralph.soika@imixs.com 
@version:  1.0 
""" 
# This package provides data classes used to exchange prompt and configuration data.
#
from dataclasses import dataclass, field
from typing import List


   
##########################################################
# This dataclass is used to exchange the prompt data between the rest api and the LLM.
# 
# The class uses the dataclass decorator (introduced in Python 3.7) that allows an easy definition of classes that only store data, 
# similar to NamedTuples, but with additional features and a familiar class syntax.
##########################################################
@dataclass
class PromptData:

    model_id: str = field(
        metadata={
            "examples": [""],
            "name": "model_id", 
            "type": "Element"
        }
    )    

    prompt: str = field(
        metadata={
            "examples": ["What is the Imixs-Workflow engine?"],
            "name": "prompt", 
            "type": "Element"
        }
    )  

@dataclass
class ResultData:

    result: str = field(
        metadata={
            "name": "result", 
            "type": "Element"
        }
    )    