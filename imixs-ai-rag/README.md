# Imixs AI RAG

The Imixs AI RAG module provides a knowledge base system for indexing and retrieving workflow business data and metadata. The system uses **Retrieval Augmented Generation (RAG)** techniques to enable semantic search and similarity matching for given text queries.

The knowledge base is built on Apache Cassandra 5.0 (vector search based on Storage-Attached Indexes), providing a distributed database system with high availability and scalability.

The RAG index runs as an independent **microservice** (`imixs/imixs-ai-rag-index`). The Imixs-Workflow application does not write to the vector database directly. Instead it hands over index requests to the RAG service, which processes them asynchronously.

This module provides:

- A RAG microservice for knowledge storage and retrieval (Cassandra based)
- Adapter and plugin classes for seamless integration into Imixs-Workflow instances
- Semantic search capabilities for workflow data

For more details about the database schema see the section [Database](./doc/DATABASE.md).

## Architecture

```
┌──────────────────────────┐   EventLog entries    ┌────────────────────────┐      ┌───────────┐
│  Imixs-Workflow App      │ ────────────────────► │  RAG Service           │ ───► │ Cassandra │
│  - RAGIndexPlugin        │   (rag.event.*)       │  (imixs-ai-rag-index)  │      │ 5.0       │
│  - RAGRetrievalAdapter   │ ◄──────────────────── │  - IndexOperator       │ ◄─── │           │
└──────────────────────────┘   POST /api/search    │  - SearchResource      │      └───────────┘
                                                   └────────────────────────┘
```

**Indexing**

1. The `RAGIndexPlugin` in the workflow application builds the text from the BPMN PromptDefinition and writes an EventLog entry (`rag.event.index`, `rag.event.update`, `rag.event.delete`).
2. The RAG service polls the EventLog entries of the workflow instance (see `WORKFLOW_SERVICE_*`), splits the text into chunks, computes an embedding for each chunk and stores it in Cassandra.

**Retrieval**

1. The `RAGRetrievalAdapter` computes the embedding of the query text.
2. The vector is sent to the REST endpoint `POST /api/search` of the RAG service, which returns the best matching documents.

## Deployment

The RAG service needs a Cassandra 5.0 database (vector search requires Cassandra 5.0 or later) and an embeddings model reachable via an OpenAI compatible endpoint, e.g. a llama.cpp server.

### Docker Compose Example:

```yaml
services:
  cassandra:
    image: cassandra:5.0.8
    mem_limit: 2G
    mem_reservation: 1G
    environment:
      TZ: "Europe/Berlin"
      JVM_OPTS: "-Xms1G -Xmx1G"
      CASSANDRA_CLUSTER_NAME: "dynamixsAiCluster"
      CASSANDRA_ENDPOINT_SNITCH: "GossipingPropertyFileSnitch"
      CASSANDRA_DC: "datacenter1"
      CASSANDRA_RACK: "rack1"
    ports:
      - "9042:9042" # CQL port
    volumes:
      - cassandra_data:/var/lib/cassandra

  rag:
    image: imixs/imixs-ai-rag-index:latest
    depends_on:
      - cassandra
    environment:
      DEBUG: "true"
      TZ: "Europe/Berlin"
      LANG: "en_US.UTF-8"
      # LLM Integration
      LLM_CONFIG_FILE: /opt/jboss/imixs-llm.xml
      # Cassandra Embeddings
      EMBEDDINGS_CLUSTER_CONTACTPOINTS: "cassandra"
      EMBEDDINGS_CLUSTER_KEYSPACE: "embeddings"
      # Workflow Instance
      WORKFLOW_SERVICE_ENDPOINT: "http://app:8080/api"
      WORKFLOW_SERVICE_USER: "admin"
      WORKFLOW_SERVICE_PASSWORD: "adminadmin"
      WORKFLOW_SERVICE_AUTHMETHOD: "form"
      # Embeddings (optional, model specific)
      EMBEDDINGS_PREFIX_DOCUMENT: "search_document:"
    ports:
      - "8081:8080"
    volumes:
      - ./docker/keys/imixs-llm.xml:/opt/jboss/imixs-llm.xml

volumes:
  cassandra_data:
```

The keyspace and the table schema are created automatically on the first start. If Cassandra is not yet ready, the service retries the connection every 10 seconds.

### Environment Variables

| Variable                                | Required | Description                                                               |
| --------------------------------------- | -------- | ------------------------------------------------------------------------- |
| `EMBEDDINGS_CLUSTER_CONTACTPOINTS`      | yes      | Comma separated list of Cassandra hosts (port 9042)                       |
| `EMBEDDINGS_CLUSTER_KEYSPACE`           | yes      | Keyspace name (lower case letters and `_`, created automatically)         |
| `EMBEDDINGS_CLUSTER_AUTH_USER`          | no       | Cassandra user                                                            |
| `EMBEDDINGS_CLUSTER_AUTH_PASSWORD`      | no       | Cassandra password                                                        |
| `EMBEDDINGS_CLUSTER_REPLICATION_CLASS`  | no       | Replication class for a new keyspace (default: `SimpleStrategy`)          |
| `EMBEDDINGS_CLUSTER_REPLICATION_FACTOR` | no       | Replication factor for a new keyspace (default: `1`)                      |
| `LLM_CONFIG_FILE`                       | yes      | Path to the `imixs-llm.xml` file with the embeddings endpoints            |
| `WORKFLOW_SERVICE_ENDPOINT`             | yes      | REST API of the Imixs-Workflow instance providing the EventLog entries    |
| `WORKFLOW_SERVICE_USER`                 | yes      | User to access the workflow instance                                      |
| `WORKFLOW_SERVICE_PASSWORD`             | yes      | Password of the workflow user                                             |
| `WORKFLOW_SERVICE_AUTHMETHOD`           | yes      | Authentication method (e.g. `form`)                                       |
| `EMBEDDINGS_PREFIX_DOCUMENT`            | no       | Task prefix for chunks to be indexed. See [Task Prefixes](#task-prefixes) |
| `EMBEDDINGS_PREFIX_QUERY`               | no       | Task prefix for retrieval queries. Set in the **workflow application**    |

SSL variables (`EMBEDDINGS_CLUSTER_SSL`, `..._TRUSTSTOREPATH`, `..._TRUSTSTOREPASSWORD`, `..._KEYSTOREPATH`, `..._KEYSTOREPASSWORD`) are available for encrypted cluster connections.

## LLM Endpoint Configuration

All LLM endpoints used by the RAG module are registered centrally in `imixs-llm.xml` and resolved via the `LLMConfigService`. The BPMN configuration of every RAG mode references endpoints by their **logical id** – not by URL.

A typical RAG setup needs two endpoints: one completion model and one embeddings model. They are usually different services:

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

Refer to the [imixs-ai-workflow README](../imixs-ai-workflow/README.md#llm-endpoint-configuration) for the full `imixs-llm.xml` reference, the `LLM_CONFIG_FILE` property, and the three-layer options layering. RAG-specific notes:

- **Completion calls** (used in PROMPT mode) support all three options layers: endpoint defaults → BPMN event override → prompt definition override.
- **Embedding calls** (used in INDEX, PROMPT and RETRIEVAL modes) support two layers: endpoint defaults → BPMN event override. The `<options>` from a PromptDefinition is **not** applied to embedding requests.

### Embedding Model and Dimensions

The vector column of the database is created with a fixed size of **768 dimensions**. The embeddings model must therefore deliver vectors with exactly 768 floats (e.g. `nomic-embed-text`, `bge-base`, `multilingual-e5-base`). A model with a different dimension (e.g. `all-MiniLM-L6-v2` with 384) is rejected on insert. If the model is changed, the keyspace must be recreated and all documents re-indexed.

## Task Prefixes

Some embedding models are trained with task prefixes and deliver better results if they are used. For `nomic-embed-text` the prefixes are `search_document:` for indexed text and `search_query:` for search queries (E5 models use `passage:` and `query:`).

| Variable                     | Where to set         | Used for                                           |
| ---------------------------- | -------------------- | -------------------------------------------------- |
| `EMBEDDINGS_PREFIX_DOCUMENT` | RAG service          | Chunks before the embedding is computed (indexing) |
| `EMBEDDINGS_PREFIX_QUERY`    | Workflow application | Query text before the embedding is computed        |

- The prefix is only added to the text sent to the embeddings endpoint. The chunk stored in the database stays unchanged.
- Both sides must fit together. Use the same model family for indexing and for retrieval.
- If the prefixes are changed, all documents must be re-indexed.
- Without a value no prefix is used.

## Integration

To create, update or retrieve embeddings during a workflow life cycle the `RAGIndexPlugin` and the `RAGRetrievalAdapter` are added to a BPMN model. These classes run in the workflow application and interact with the `OpenAIAPIService` of the [imixs-ai-workflow](../imixs-ai-workflow/README.md) module.

- **RAGIndexPlugin** – `org.imixs.ai.RAGIndexPlugin`. Runs passively in the workflow processing life cycle and creates the index requests for the RAG service.
- **RAGRetrievalAdapter** – `org.imixs.ai.rag.RAGRetrievalAdapter`. Triggered as a BPMN signal adapter for retrieval.

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

Use markdown headings or bold lines (`**Section:**`) to structure the text. The [chunking](#chunking) relies on them.

The RAG service stores the embeddings together with the following workflow metadata:

- **ModelVersion** – the `$workflowgroup` item assigned to the process instance at the time of indexing
- **TaskId** – the `$taskId` item assigned to the process instance at the time of indexing

**Note:** This metadata is captured at the time of indexing and may not match the current workflow status of the process instance later on. The metadata can be refreshed independently using the [UPDATE mode](#update).

Re-indexing a workitem replaces only the entries of the same category. Entries of other categories stay untouched.

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

The completion call uses the full three-layer options stack: endpoint defaults, the `<options>` above as BPMN event override, and `<options>` from the PromptDefinition. The subsequent embedding call uses the embeddings endpoint defaults only.

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

Only the best matching chunk of each document is considered. Each document is returned at most once, sorted by similarity.

### Search REST API

The RAG service provides the search as a REST endpoint. The adapter computes the query vector in the workflow application and sends it to the service:

```
POST /api/search?maxResults=3
Content-Type: application/json | application/xml
```

The payload is an Imixs `XMLDocument` with the following items:

| Item          | Description                                                          |
| ------------- | -------------------------------------------------------------------- |
| `embeddings`  | The query vector (exactly 768 numbers)                               |
| `categories`  | Optional. Missing = all categories, empty string = primary data only |
| `modelGroups` | Optional filter on workflow groups                                   |
| `tasks`       | Optional filter on task IDs                                          |

The response is a collection of documents with the items `id`, `similarity` and `contentChunk`. `maxResults` must be between 1 and 100.

| Status | Meaning                                                       |
| ------ | ------------------------------------------------------------- |
| 200    | Search result (can be empty)                                  |
| 400    | Invalid request (missing body, wrong vector size, maxResults) |
| 503    | The RAG service is not yet connected to Cassandra             |
| 500    | The database query failed                                     |

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

The RAG service also reacts automatically on document delete events: if a workitem is deleted from the database, its embeddings index is removed as well. An explicit `DELETE` configuration is only needed when the index should be removed without removing the workitem itself.

### DISABLED

The index is updated automatically in each workflow processing cycle. To disable the metadata update for a specific event, define:

```xml
<imixs-rag name="DISABLED"></imixs-rag>
```

In this case the plugin is skipped for the corresponding event.
