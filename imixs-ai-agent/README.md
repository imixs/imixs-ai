# Imixs AI AGENT

The Imixs AI AGENT module provides a generic framework to build agentic business application. An agent runs in an autonomous loop — calling the LLM, evaluating responses, executing tools if requested — and continues until it either produces a plain-text answer or completes a workflow action on behalf of the user. An AI Agent can run in a dialog mode with a user to solve a specific problem or in an autonomous way calling tools or processing other workflows.

**In the dialog modde an agent should be able:**

- Understand what the user wants — across languages and phrasings
- Select the right workflow process, action or event from those available to the current user
- Collect all required form field values from the conversation
- Create a new workflow instance, fill in the data, and submit it — in one turn
- Ask the user for any missing required fields and save a draft in the meantime
- Hand the user directly over to the completed workitem
- Complete a task

**In the autonomous mode an agent should be able:**

- To start an agent loop based on a given processing context provided by a compliance workflow
- Select data from existing process instances
- Create new workflow instances with collected data
- Ask the user for any missing required information
- Complete a task

The implementation is open and generic and mainly based on interfaces and CDI events.

---

## Architecture Overview: The AI-Agent-Model

An **Imixs AI Agent** is itself described and embedded in a BPMN model — the **AI-Agent-Model**. This architecture makes the agent adoptable, scalable and transactional in a production-ready way.

When users want to interact with the agent, they start a dedicated AI agent workflow, just like any other workflow in the system. Based on the AI agent model, the process is secure, documented, and every step is fully traceable.

The AI-Agent-Model consists typically of the following tasks:

| Task      | Description                                                                                            |
| --------- | ------------------------------------------------------------------------------------------------------ |
| Ask       | User enters a natural-language prompt and optionally attaches files                                    |
| Thinking  | Agent is running asynchronously; the task can be associated with a System Prompt instructing the agent |
| Completed | Agent completed; user is redirected to the started workflow or sees the agent response                 |
| Error     | Agent loop stopped because of an internal error                                                        |

<img src="../doc/images/bpmn-agent-01.png" />

This approach makes the agent very flexible and easy to adapt on individual enterprise needs:

- Every agent run is a **persisted workflow instance** — nothing is lost on server restart or session timeout
- Multiple users can run agents **in parallel** without any shared state
- The standard Imixs **security model** applies — the agent only ever acts within the permissions of the current user
- **Retry and error handling** are built in via standard BPMN gateways, events and sequence flows.

---

## Asynchronous Processing

**BPMN AI Agents** run in an asynchronous way using the **Imixs EventLog Service**, which implements the **Change Data Capture (CDC)** pattern. Instead of running the agent synchronously inside one single transaction, the BPMN Agent holds the user task in a persistent transactional instance over the complete life cycle.
The user can monitor the agent status as for any other business process. In the way, the agent shows no latency during the workflow processing cycle even if the agent loop calling external LLMs may take seconds to minutes.

---

### AI Agent Configuration

AI Agents are defined and started in EventLog entries processed by the standard **EventLogPlugin**. This allows the configuration directly via the BPMN model. The configuration is defined in the `<document>` element and automatically validated by the `BPMNAgentProcessingHandler` before it is handed over to the `AIAgentOperator`.

```xml
<eventlog name="ai.agent.process">
    <ref><itemvalue>$uniqueid</itemvalue></ref>
    <document>
        <agent.context.item>bpmn.agent.context</agent.context.item>
        <agent.user.item>bpmn.agent.user.input</agent.user.item>
        <agent.endpoint>my-llm</agent.endpoint>
        <agent.timeout>120000</agent.timeout>
        <agent.max-iterations>10</agent.max-iterations>
        <agent.event.success>200</agent.event.success>
        <agent.event.next>100</agent.event.next>
        <agent.event.error>280</agent.event.error>
        <agent.result.type>XML</agent.result.type>
        <agent.debug>true</agent.debug>
    </document>
</eventlog>
```

| Parameter              | Description                                                                                                                                                                  |
| ---------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `agent.context.item`   | Item name in the workitem that holds the agent context. This indirection allows different AI-Tasks to use different context data without code changes.                       |
| `agent.user.item`      | Item name in the workitem that holds the user's natural-language prompt. This indirection allows different AI-Task forms to use different input fields without code changes. |
| `agent.endpoint`       | Logical LLM endpoint ID as registered in `imixs-llm.xml` — never a URL                                                                                                       |
| `agent.timeout`        | Maximum wall-clock time in milliseconds before the agent is aborted                                                                                                          |
| `agent.max-iterations` | Maximum number of LLM calls in one agent run to prevent runaway loops                                                                                                        |
| `agent.event.success`  | BPMN event ID to trigger when the agent completes successfully                                                                                                               |
| `agent.event.next`     | BPMN event ID to trigger when the agent is waiting for more user input — routes the workitem back to Task "Ask" so the conversation can continue in the next turn            |
| `agent.event.error`    | BPMN event ID to trigger when the agent fails or times out                                                                                                                   |
| `agent.result.type`    | An optional result type to process a completion result by a Imixs AI Result handler out                                                                                      |
| `agent.debug`          | 'true' to activate the debug mode                                                                                                                                            |

The workitem item defined by the parameter `agent.context.item` holds the complete conversation state of the AI Agent. The state is persisted with every process step and allows to continue an Agent process at any time. The agent context is a list of message entries consisting of 'system', 'user' and 'assistant' roles in an alternating mode according to the OpenAI API. The agent context also includes a protocol of all tool calls.

## The AIAgentPlugin

The plugin class `org.imixs.ai.agent.AIAgentPlugin` is one way to trigger an agentic business process. The plugin can be used in any compliance workflow event to start a new agent.

```xml
<imixs-ai name="AGENT">
  <debug>true</debug>
  <agent.model>ai-agent-model-de-1.0</agent.model>
  <agent.init.task>100</agent.init.task>
  <agent.init.event>100</agent.init.event>
</imixs-ai>
```

| Parameter          | Description                                               |
| ------------------ | --------------------------------------------------------- |
| `agent.model`      | The model version to run a new Imixs AI Agent             |
| `agent.init.task`  | The initial task to start a new agentic process instance  |
| `agent.init.event` | The initial event ot start a new agentic process instance |

The init event in an agentic model typically start the agent loop by defining a AI Agent Configuration as described before.
