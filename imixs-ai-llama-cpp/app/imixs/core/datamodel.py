"""
The data model to send promt data to llama-cpp-python

@author: ralph.soika@imixs.com 
@version:  1.0 
""" 
# This package provides data classes used to exchange prompt and configuration data.
#
from pydantic import BaseModel
from dataclasses import dataclass, field
from typing import List
import json


class PromptEntity(BaseModel):
    instruction: str
    context: str
   
##########################################################
# This dataclass is used to exchange the prompt data between the rest api and the LLM.
# 
# The class uses the dataclass decorator (introduced in Python 3.7) that allows an easy definition of classes that only store data, 
# similar to NamedTuples, but with additional features and a familiar class syntax.
##########################################################
@dataclass
class XMLPrompt:
    instruction: str = field(
        metadata={
            "example": "What is the Imixs-Workflow engine?",
            "name": "instruction", 
            "type": "Element"
        }
    )    
    context: str = field(
        metadata={
            "example": "You are a helpful code assistant.",
            "name": "context", 
            "type": "Element"
        }
    )

    output: str = field(
        metadata={
            "name": "output", 
            "type": "Element"
        }
    )    