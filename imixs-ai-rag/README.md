# Imixs AI RAG

The Imixs AI RAG module provides a knowledge base for business documents based on **Retrieval Augmented Generation (RAG)**. Business documents are indexed as embeddings during the workflow life cycle and can be retrieved by semantic similarity.

The embeddings are stored in the vector index of the [imixs-workflow-index](https://github.com/imixs/imixs-workflow/tree/master/imixs-workflow-index) module (Apache Lucene). No external database or service is required — the RAG index runs inside the workflow application, next to the full-text index.

This module provides:

- BPMN plugin and adapter classes to index and retrieve business documents
- Asynchronous computation of embeddings, independent of the workflow processing
- Semantic search restricted by the full-text index and the read access of the current user
- Tool calls for agentic workflows (see [imixs-ai-agent](../imixs-ai-agent))

## Architecture

```
 Workflow processing                       Asynchronous (IndexScheduler)
┌────────────────────┐   EventLog        ┌─────────────────────┐         ┌──────────────┐
│ RAGIndexPlugin     │ ────────────────► │ IndexOperator       │ ──────► │ Embeddings   │
│ (INDEX / PROMPT /  │   rag.event.*     │ - chunking          │ ◄────── │ endpoint     │
│  DELETE)           │                   │ - embeddings        │         │ (LLM server) │
└────────────────────┘                   └─────────┬───────────┘         └──────────────┘
                                                   │ vectors
                                                   ▼
┌────────────────────┐   query vector   ┌──────────────────────────────────────────────┐
│ RAGRetrievalAdapter│ ───────────────► │ imixs-workflow-index                         │
│ Agent tool calls   │ ◄─────────────── │  vector index  ⋈  full-text index ($uniqueid)│
└────────────────────┘   $uniqueids     └──────────────────────────────────────────────┘
```

**Indexing**

1. The `RAGIndexPlugin` builds the text of a business document from a BPMN PromptDefinition and writes an EventLog entry (`rag.event.index`). The workflow processing never waits for an LLM call.
2. The `IndexScheduler` processes the EventLog entries asynchronously. The text is split into chunks, an embedding is computed for each chunk, and the vectors are stored in the vector index.

**Retrieval**

1. The embedding of the query text is computed.
2. The vector index returns the most similar documents. The result is restricted by an optional full-text query and by the read access of the current user.
3. The `$uniqueid`s of the matching documents are returned. The business data is always loaded from the workflow engine — the vector index stores no text.

The vector index stores only the `$uniqueid`, the category and the vectors of a document. All business data and metadata (workflow group, task, status, any indexed field) are provided by the full-text index. This way a retrieval can be filtered by any field of the full-text index, and the metadata is always up to date.

## Setup

### Maven

The module requires the `imixs-workflow-index` as the index module of the application:

```xml
<dependency>
    <groupId>org.imixs.workflow</groupId>
    <artifactId>imixs-workflow-index</artifactId>
    <version>${org.imixs.workflow.version}</version>
</dependency>
<dependency>
    <groupId>org.imixs.ai</groupId>
    <artifactId>imixs-ai-rag</artifactId>
    <version>${org.imixs.ai.version}</version>
</dependency>
```

### Configuration

| Variable                         | Required | Description                                                                                                                                            |
| -------------------------------- | -------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `INDEX_DIRECTORY`                | yes      | Location of the index on a persistent volume, e.g. `/opt/jboss/lucene/imixs-workflow-index`. The vector index is stored in `<INDEX_DIRECTORY>_vector`. |
| `INDEX_VECTOR_ENABLED`           | yes      | Must be `true` to enable the vector index.                                                                                                             |
| `INDEX_VECTOR_DIMENSION`         | yes      | Dimension of the embedding model, (default 768)                                                                                                        |
| `RAG_EMBEDDINGS_ENDPOINT`        | yes      | Logical id of the embedding endpoint in `imixs-llm.xml`.                                                                                               |
| `LLM_CONFIG_FILE`                | yes      | Path to the `imixs-llm.xml` file.                                                                                                                      |
| `RAG_EMBEDDINGS_PREFIX_DOCUMENT` | no       | Task prefix for indexed text. See [Task Prefixes](#task-prefixes).                                                                                     |
| `RAG_EMBEDDINGS_PREFIX_QUERY`    | no       | Task prefix for queries. See [Task Prefixes](#task-prefixes).                                                                                          |
| `RAG_SCHEDULER_INTERVAL`         | no       | Interval of the index scheduler in ms (default `10000`).                                                                                               |
| `RAG_SCHEDULER_INITIALDELAY`     | no       | Initial delay of the index scheduler in ms (default `30000`).                                                                                          |
| `RAG_SCHEDULER_DEADLOCK`         | no       | Time in ms after which a failed event is retried (default `60000`).                                                                                    |

A missing or invalid configuration aborts the deployment with a clear log message. If the vector index is disabled, the RAG module stays inactive.

For the recommended JVM options (Vector API, SIMD acceleration) see the section _Deployment on WildFly_ in the README of the imixs-workflow-index.

### Docker Compose Example

```yaml
services:
  app:
    image: my-workflow-app:latest
    environment:
      INDEX_DIRECTORY: "/opt/jboss/lucene/imixs-workflow-index"
      INDEX_VECTOR_ENABLED: "true"
      INDEX_VECTOR_DIMENSION: "768"
      LLM_CONFIG_FILE: "/opt/jboss/imixs-llm.xml"
      RAG_EMBEDDINGS_ENDPOINT: "my-embeddings"
      RAG_EMBEDDINGS_PREFIX_DOCUMENT: "search_document:"
      RAG_EMBEDDINGS_PREFIX_QUERY: "search_query:"
    volumes:
      - index:/opt/jboss/lucene/
      - ./docker/keys/imixs-llm.xml:/opt/jboss/imixs-llm.xml

volumes:
  index:
```

## LLM Endpoint Configuration

All LLM endpoints are registered centrally in `imixs-llm.xml` and referenced by their **logical id**. A typical RAG setup needs one embedding model and — for the PROMPT mode — one completion model:

```xml
<imixs-llm>
    <endpoint id="my-llm">
        <url>https://api.llama.cpp.foo.com/</url>
        <apikey>${env.LLM_API_KEY}</apikey>
        <options>{"model": "llama-3.1-70b-instruct", "max_tokens": 1024}</options>
    </endpoint>
    <endpoint id="my-embeddings">
        <url>https://embeddings.llama.cpp.foo.com/</url>
        <options>{"model": "nomic-embed-text-v2-moe"}</options>
    </endpoint>
</imixs-llm>
```

Refer to the [imixs-ai-workflow README](../imixs-ai-workflow/README.md#llm-endpoint-configuration) for the full `imixs-llm.xml` reference.

### One Embedding Model per Instance

The embedding endpoint is defined once for the whole workflow instance (`RAG_EMBEDDINGS_ENDPOINT`) and is used for indexing and retrieval. Vectors of different models can not be compared — a central configuration guarantees that all vectors are comparable. The BPMN model never defines an embedding endpoint.

The completion endpoint of the PROMPT mode is defined in the BPMN model and can differ per event.

### Embedding Model and Dimensions

The dimension of the vector index (`INDEX_VECTOR_DIMENSION`) must match the embedding model, e.g. `768` for `nomic-embed-text`, `bge-base` or `multilingual-e5-base`. On startup the dimension of an existing vector index is verified — a mismatch aborts the deployment.

If the embedding model is changed, remove the vector index directory (`<INDEX_DIRECTORY>_vector`). There is no rebuild job for the vector index: documents are indexed again by the workflow events which trigger the indexing.

## Task Prefixes

Some embedding models are trained with task prefixes and deliver better results if they are used. For `nomic-embed-text` the prefixes are `search_document:` for indexed text and `search_query:` for search queries (E5 models use `passage:` and `query:`).

- The prefix is only added to the text sent to the embedding endpoint.
- Both prefixes must fit to the same model family.
- If the prefixes are changed, all documents must be indexed again.
- Without a value no prefix is used.

## Categories

A business document can be indexed in several **categories**. A category is a business view of the document and is typically bound to a specific event in the workflow. For example an insurance claim can be indexed in the category `contract` when the contract is signed, and in the category `risk` when the risk assessment is completed.

- Indexing a category replaces only the vectors of this category. Other categories stay untouched.
- A retrieval can search in one, several or all categories.
- The category is required for indexing.

## Integration

To index and retrieve business documents the following classes are added to a BPMN model:

- **RAGIndexPlugin** – `org.imixs.ai.rag.workflow.RAGIndexPlugin`. Runs in the workflow processing life cycle and creates the index requests.
- **RAGRetrievalAdapter** – `org.imixs.ai.rag.workflow.RAGRetrievalAdapter`. A BPMN signal adapter for retrieval.

| Mode        | Handled by          | Purpose                                                    |
| ----------- | ------------------- | ---------------------------------------------------------- |
| `INDEX`     | RAGIndexPlugin      | Index the text of the workitem in a category               |
| `PROMPT`    | RAGIndexPlugin      | Run a completion first, then index the AI-generated result |
| `DELETE`    | RAGIndexPlugin      | Remove all vectors of the workitem                         |
| `RETRIEVAL` | RAGRetrievalAdapter | Find similar documents                                     |

## INDEX

To index a workitem the `RAGIndexPlugin` is configured with an `imixs-rag` element and a BPMN DataObject containing a PromptDefinition associated with the corresponding event.

```xml
<imixs-rag name="INDEX">
  <category>contract</category>
  <debug>true</debug>
</imixs-rag>
```

| Property   | Type    | Description                                        |
| ---------- | ------- | -------------------------------------------------- |
| `category` | Text    | **Required.** The business view of the embeddings. |
| `debug`    | Boolean | Optional, prints index processing information      |

The PromptDefinition defines the text from which the embeddings are computed:

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

Use markdown headings or bold lines (`**Section:**`) to structure the text. The [chunking](#chunking) relies on them.

The embeddings are computed asynchronously. A document is found by a retrieval as soon as the event has been processed by the index scheduler — usually within a few seconds.

## PROMPT

The `PROMPT` mode enables a two-step process: the prompt template is sent to a completion endpoint first, and the AI-generated result is indexed afterwards. This is useful to index AI-generated summaries or analyses rather than raw workitem content.

```xml
<imixs-rag name="PROMPT">
  <endpoint-completion>my-llm</endpoint-completion>
  <category>summary</category>
  <options>{"temperature": 0.2}</options>
  <debug>true</debug>
</imixs-rag>
```

| Property              | Type    | Description                                                     |
| --------------------- | ------- | --------------------------------------------------------------- |
| `endpoint-completion` | Text    | Logical completion endpoint id as registered in `imixs-llm.xml` |
| `category`            | Text    | **Required.** The category of the indexed result                |
| `options`             | JSON    | Optional options applied to the completion call                 |
| `debug`               | Boolean | Optional, prints processing information                         |

The completion call supports the full options stack: endpoint defaults, the `<options>` above, and the `<options>` of the PromptDefinition.

## RETRIEVAL

To find similar documents the signal adapter `RAGRetrievalAdapter` is used. The adapter expects an `imixs-rag` configuration and a BPMN DataObject containing a PromptDefinition for the query text.

```xml
<imixs-rag name="RETRIEVAL">
  <reference-item>similar.claims</reference-item>
  <categories>risk,contract</categories>
  <query>($workflowgroup:"Claim") AND (type:workitem)</query>
  <max-results>5</max-results>
  <debug>true</debug>
</imixs-rag>
```

| Property         | Type    | Description                                                                                                             |
| ---------------- | ------- | ----------------------------------------------------------------------------------------------------------------------- |
| `reference-item` | Text    | **Required.** Item to store the list of matching `$uniqueid` values                                                     |
| `categories`     | Text    | Optional, comma separated list of categories. Empty = all categories                                                    |
| `query`          | Text    | Optional full-text search term (Lucene syntax) to restrict the documents. Any field of the full-text index can be used. |
| `max-results`    | Number  | Maximum number of results (default: 1)                                                                                  |
| `debug`          | Boolean | Optional, prints the matches with their similarity                                                                      |

The PromptDefinition defines the query text:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PromptDefinition>
  <prompt><![CDATA[<itemvalue>request.subject</itemvalue>

<FILECONTEXT>^.+\.eml$</FILECONTEXT>
]]></prompt>
</PromptDefinition>
```

The result is a list of `$uniqueid` values sorted by similarity. Each document is returned at most once (best matching chunk). The current workitem itself is never part of the result.

**Access rights:** A retrieval runs with the rights of the current user. Only documents the user is allowed to read are returned — exactly as for a regular search.

## DELETE

To remove all vectors of a workitem, the plugin is run in the `DELETE` mode:

```xml
<imixs-rag name="DELETE"></imixs-rag>
```

If a workitem is deleted from the database, its vectors are removed automatically. An explicit `DELETE` configuration is only needed when the vectors should be removed without removing the workitem itself.

Vectors of deleted documents are never part of a retrieval result, because a retrieval is always restricted by the full-text index.

## Agent Tool Calls

For agentic workflows the module provides the tools `rag_retrieval` and `rag_select`. The tool `rag_retrieval` searches the knowledge base and returns the matching workitems as a numbered list of candidates to the LLM. The `$uniqueid` of a candidate is never passed to the LLM — the LLM selects a candidate by its number with the tool `rag_select`.

The search of `rag_retrieval` is always restricted by model group and task id, which are given in the task instructions of the agent. An optional list of categories restricts the search to specific business views.

## Chunking

Before the embeddings are computed, the text is split into chunks of at most 1200 characters:

- The text is split into sections at headings (markdown headings `#` to `######`, or lines that are completely bold).
- A heading always stays together with its content.
- Small sections are merged into one chunk.
- A section larger than the chunk size is split at line breaks (then spaces), and the heading is repeated at the start of each part.

Use headings to structure the text of a PromptDefinition — this improves the quality of the retrieval.

## Processing and Error Handling

The `IndexScheduler` processes up to 100 events per run. Each event is processed in three steps: the EventLog entry is locked, the embeddings are computed and stored (without a database transaction), and the EventLog entry is removed.

- **Temporary errors** (e.g. the embedding endpoint is not reachable) — the event stays locked and is retried automatically after `RAG_SCHEDULER_DEADLOCK`.
- **Invalid events** (e.g. a wrong vector dimension) — the event is removed and counted as an error.
