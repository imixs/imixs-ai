# Imixs AI AGENT

The Imixs AI AGENT module provides a generic framework to build agentic business application. An agent runs in an autonomous loop — calling the LLM, evaluating responses, executing tools if requested — and continues until it either produces a plain-text answer or completes a workflow action on behalf of the user.

An agent should be able:

- Understand what the user wants — across languages and phrasings
- Select the right workflow process from those available to the current user
- Collect all required form field values from the conversation
- Create a new workflow instance, fill in the data, and submit it — in one turn
- Ask the user for any missing required fields and save a draft in the meantime
- Hand the user directly over to the completed workitem

The implementation is open and generic and mainly based on interfaces and CDI events.
