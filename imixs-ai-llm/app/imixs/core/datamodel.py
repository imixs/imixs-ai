"""
The .....

@author: ralph.soika@imixs.com 
@version:  1.0 
""" 
# This package provides data classes used to exchange prompt and configuration data.
#
from pydantic import BaseModel



class PromptEntity(BaseModel):
    instruction: str
    context: str
 