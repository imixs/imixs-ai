# Imixs AI RAG

The Imixs AI RAG module provides a knowledge base system for indexing and retrieving workflow business data and metadata. The system uses **Retrieval Augmented Generation (RAG)** techniques to enable semantic search and similarity matching for given text queries.

The knowledge base is built on Apache Cassandra 4.0, providing a distributed database system with high availability and scalability. This architecture ensures that the knowledge base can grow with increasing enterprise data demands while maintaining fast query performance.

This module provides:

- Cassandra-based RAG services for knowledge storage and retrieval
- Adapter and plugin classes for seamless integration into Imixs-Workflow instances
- Semantic search capabilities for workflow data

For more details about the database schema see the section [Database](./doc/DATABASE.md).

## LLM Endpoint Configuration

All LLM endpoints used by the RAG module are registered centrally in `imixs-llm.xml` and resolved via the `LLMConfigService`. The BPMN configuration of every RAG mode references endpoints by their **logical id** – not by URL.

A typical RAG setup needs two endpoints: one completion model and one embeddings model. They are usually different services:

```xml
<imixs-llm>
    <endpoint id="my-llm">
        <url>https://api.llama.cpp.imixs.com/</url>
        <apikey>${env.LLM_API_KEY}</apikey>
        <options>{"model": "llama-3.1-70b-instruct", "max_tokens": 1024}</options>
    </endpoint>
    <endpoint id="my-embeddings">
        <url>https://embeddings.llama.cpp.imixs.com/</url>
        <options>{"model": "all-MiniLM-L6-v2"}</options>
    </endpoint>
</imixs-llm>
```

Refer to the [imixs-ai-workflow README](../imixs-ai-workflow/README.md#llm-endpoint-configuration) for the full `imixs-llm.xml` reference, the `LLM_CONFIG_FILE` property, and the three-layer options layering. RAG-specific notes:

- **Completion calls** (used in PROMPT mode) support all three options layers: endpoint defaults → BPMN event override → prompt definition override.
- **Embedding calls** (used in INDEX, PROMPT and RETRIEVAL modes) support two layers: endpoint defaults → BPMN event override. The `<prompt_options>` from a PromptDefinition is **not** applied to embedding requests.

## Integration

To create, update or retrieve embeddings during a workflow life cycle the `RAGIndexPlugin` and the `RAGRetrievalAdapter` are added to a BPMN model. These classes interact with the `OpenAIAPIService` of the [imixs-ai-workflow](../imixs-ai-workflow/README.md) module to maintain the index in the vector database.

- **RAGIndexPlugin** – `org.imixs.ai.RAGIndexPlugin`. Runs passively in the workflow processing life cycle and handles all indexing operations.
- **RAGRetrievalAdapter** – `org.imixs.ai.RAGRetrievalAdapter`. Triggered as a BPMN signal adapter for retrieval.

The imixs-rag integration supports the following modes:

| Mode        | Handled by          | Purpose                                                    |
| ----------- | ------------------- | ---------------------------------------------------------- |
| `INDEX`     | RAGIndexPlugin      | Index the workitem text and metadata as embeddings         |
| `PROMPT`    | RAGIndexPlugin      | Run a completion first, then index the AI-generated result |
| `RETRIEVAL` | RAGRetrievalAdapter | Retrieve indexed embeddings based on a given prompt        |
| `UPDATE`    | RAGIndexPlugin      | Update workitem metadata only (default behavior)           |
| `DELETE`    | RAGIndexPlugin      | Remove an existing index for the current workitem          |
| `DISABLED`  | RAGIndexPlugin      | Suppress automatic metadata updates for a specific event   |

## INDEX

To index a workitem the `RAGIndexPlugin` is configured with an `imixs-rag` element and a BPMN DataObject containing a PromptDefinition associated with the corresponding event.

```xml
<imixs-rag name="INDEX">
  <endpoint-embeddings>my-embeddings</endpoint-embeddings>
  <options>{"dimensions": 384}</options>
  <category>customer-data</category>
  <debug>true</debug>
</imixs-rag>
```

| Property              | Type    | Description                                                                        |
| --------------------- | ------- | ---------------------------------------------------------------------------------- |
| `endpoint-embeddings` | Text    | Logical embeddings endpoint id as registered in `imixs-llm.xml`                    |
| `options`             | JSON    | Optional embedding options merged on top of the endpoint defaults                  |
| `category`            | Text    | Optional category to namespace the index. Empty value indexes against primary data |
| `debug`               | Boolean | Optional, prints index processing information                                      |

The PromptDefinition in the BPMN DataObject defines the text from which the embedding is generated:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PromptDefinition>
  <prompt>
    <itemvalue>$workflowgroup</itemvalue>: <itemvalue>$workflowstatus</itemvalue>
    <itemvalue>$workflowsummary</itemvalue>
    # Summary
    <itemvalue>$workflowabstract</itemvalue>
  </prompt>
</PromptDefinition>
```

The RAG system stores the embeddings together with the following workflow metadata:

- **ModelVersion** – the `$workflowgroup` item assigned to the process instance at the time of indexing
- **TaskId** – the `$taskId` item assigned to the process instance at the time of indexing

**Note:** This metadata is captured at the time of indexing and may not match the current workflow status of the process instance later on. The metadata can be refreshed independently using the [UPDATE mode](#update).

## PROMPT

The `PROMPT` mode enables a two-step process: text generation followed by automatic indexing. The plugin first sends the prompt template to the configured completion endpoint, then automatically generates an embedding from the AI-generated text and stores it in the vector database.

This is useful for indexing AI-generated summaries or analyses rather than raw workitem content.

```xml
<imixs-rag name="PROMPT">
  <endpoint-completion>my-llm</endpoint-completion>
  <endpoint-embeddings>my-embeddings</endpoint-embeddings>
  <options>{"temperature": 0.2}</options>
  <debug>true</debug>
</imixs-rag>
```

| Property              | Type    | Description                                                        |
| --------------------- | ------- | ------------------------------------------------------------------ |
| `endpoint-completion` | Text    | Logical completion endpoint id as registered in `imixs-llm.xml`    |
| `endpoint-embeddings` | Text    | Logical embeddings endpoint id as registered in `imixs-llm.xml`    |
| `options`             | JSON    | Optional options applied to the completion call (BPMN event layer) |
| `debug`               | Boolean | Optional, prints processing information                            |

The completion call uses the full three-layer options stack: endpoint defaults, the `<options>` above as BPMN event override, and `<prompt_options>` from the PromptDefinition. The subsequent embedding call uses the embeddings endpoint defaults only.

## RETRIEVAL

To retrieve indexed embeddings by a given prompt the signal adapter `RAGRetrievalAdapter` is used. The adapter expects an `imixs-rag` configuration and a mandatory BPMN DataObject containing a PromptDefinition.

```xml
<imixs-rag name="RETRIEVAL">
  <endpoint-embeddings>my-embeddings</endpoint-embeddings>
  <reference-item>product.ref</reference-item>
  <max-results>5</max-results>
  <modelgroups>Product</modelgroups>
  <tasks>1200</tasks>
  <categories></categories>
  <debug>true</debug>
</imixs-rag>
```

| Property              | Type    | Description                                                                           |
| --------------------- | ------- | ------------------------------------------------------------------------------------- |
| `endpoint-embeddings` | Text    | Logical embeddings endpoint id as registered in `imixs-llm.xml`                       |
| `reference-item`      | Text    | Item name where the list of matching `$uniqueID` values is stored                     |
| `max-results`         | Number  | Maximum number of results returned (default: 1)                                       |
| `modelgroups`         | Text    | Optional filter on workflow groups, supports wildcards (e.g. `customer*`)             |
| `tasks`               | Text    | Optional filter on task IDs, supports lists and ranges (e.g. `1400, 1410, 1000:1300`) |
| `categories`          | Text    | Optional filter on index categories                                                   |
| `debug`               | Boolean | Optional, prints retrieval information                                                |

The result is a list of `$uniqueID` values stored in the item specified by `reference-item`. The PromptDefinition defines the text used to compute the query embedding:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PromptDefinition>
  <prompt><![CDATA[<itemvalue>request.subject</itemvalue>

<FILECONTEXT>^.+\.eml$</FILECONTEXT>
]]></prompt>
</PromptDefinition>
```

## Helper Modes

The following modes do not generate or retrieve embeddings but help to control the index lifecycle.

### UPDATE

To update the workflow metadata only, the `RAGIndexPlugin` runs in the `UPDATE` mode. This is the default mode and does not need an explicit configuration – the plugin automatically updates the workflow metadata in each processing life cycle.

The mode updates `$workflowGroup` and `$taskId` only. These attributes can be used during the retrieval phase via the `modelgroups` and `tasks` filters.

**Note:** An update is only performed if an index already exists for the workitem.

### DELETE

To delete a RAG index, the plugin can be run in the `DELETE` mode:

```xml
<imixs-rag name="DELETE"></imixs-rag>
```

This removes the index for the current workitem.

The `RAGService` also reacts automatically on document delete events: if a workitem is deleted from the database, its embeddings index is removed as well. An explicit `DELETE` configuration is only needed when the index should be removed without removing the workitem itself.

### DISABLED

The index is updated automatically in each workflow processing cycle. To disable the metadata update for a specific event, define:

```xml
<imixs-rag name="DISABLED"></imixs-rag>
```

In this case the plugin is skipped for the corresponding event.
