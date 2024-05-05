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
# The PromptDefinition defines the model name and the prompt. 
# 
# The class uses the dataclass decorator (introduced in Python 3.7) that allows an easy definition of classes that only store data, 
# similar to NamedTuples, but with additional features and a familiar class syntax.
##########################################################
@dataclass
class PromptDefinition:

    model: str = field(
        metadata={
            "examples": [""],
            "name": "model", 
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

###########################
# Extended prompt class to support embeddings 
###########################

@dataclass
class EmbeddingData:
    result: str = field(
        metadata={
            "name": "embeddingData",
            "type": "Element"
        }
    )

@dataclass
class PromptDefinitionEmbeddings:

    model: str = field(
        metadata={
            "examples": [""],
            "name": "model", 
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

    embeddings: List[EmbeddingData] = field(
        default_factory=list,
        metadata={
            "name": "embeddings",
            "type": "Element"
        }
    )
    

###########################
# Result class holding the result of a prompt processing
###########################
@dataclass
class ResultData:

    result: str = field(
        metadata={
            "name": "result", 
            "type": "Element"
        }
    )    