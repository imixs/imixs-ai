# Imixs AI RAG

The Imixs AI RAG module provides a system for retrieval augmented generation (RAG). This system can be used to index workflow business- and metadata (generation) as also to search for similarity (retrieval) for a given text. The system is based on Apache Cassandra 4.0 which provides a distributed database system with high availability and scalability.
This module provides the Cassandra RAG services and also the adapter and plugin classes for an integration into a Imixs-Workflow instance.
For more details about the database schema see the section [Database](./doc/DATABASE.md)

## Integration

To create, update or retrieve embeddings during a workflow live cycle the `RAGIndexPlugin` and the `RAGRetrievalAdapter` can be added into a BPMN model.
These adapter classes interact with the `RAGService` class of the module [imixs-ai-workflow](../imixs-ai-workflow/README.md) to maintain the index in a vector database.

- **RAGIndexPlugin** - the `org.imixs.ai.RAGIndexPlugin` class is used to create, update or delete embeddings.
- **RAGRetrievalAdapter** - the `org.imixs.ai.RAGRetrievalAdapter` is used to retrieve embeddings.

## Indexing

To index a workitem the `org.imixs.ai.RAGIndexPlugin` is used. The plugin expects an `imixs-ai` configuration and optional a BPMN DataObject `PromptDefinition` associated with the corresponding event to index a workitem:

```xml
<imixs-ai name="INDEX">
  <endpoint>https://llama.cpp2.imixs.com/</endpoint>
  <debug>true</debug>
  <!-- optional -->
  <category></category>
</imixs-ai>
```

The `debug` flag is optional and can be set to `true` to log index information.

The PromptDefinition associated by a DataObject defines the text to generate the embeddings.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PromptDefinition>
  <prompt_options>{}</prompt_options>
  <prompt><![CDATA[<itemvalue>$workflowgroup</itemvalue>: <itemvalue>$workflowstatus</itemvalue>
<itemvalue>$workflowsummary</itemvalue>

# Summary
<itemvalue>$workflowabstract</itemvalue>

]]>
    </prompt>
</PromptDefinition>
```

The RAG System does not only store the embeddings but also the following Workflow Meta Information

- ModelVersion - the `$workflowgroup` item assigned to the process instance at the time of the indexing
- TaskId - the `$taskId` item assigned to the process instance at the time of the indexing

**Note:** The workflow meta information is generated at the time of the indexing and does not necessary match the current workflow status!

### Update

To update the workflow metadata only the `org.imixs.ai.rag.RAGIndexlugin` can be run in the 'UPDATE' mode. This is the default mode and does not need an explizit configuration. This means the RAGIndexPlugin automatically updates the workflow metadata in each processing life-cycle.

This plugin updates the $workflowGroup and the $taskId. These attributes can be used during the retrieval phase (see below) The update is only be performed if an index already exists.

### Disable

The index is updated automatically in each workflow processing cycle. To disabled a metadata update in a single specific event you can define the following workflow definition:

```xml
<imixs-ai name="DISABLE"></imixs-ai>
```

In this case the plugin will be disabled.

### Delete

To delete a RAG index the plugin can be used in the DELETE mode:

```xml
<rag-index name="DELETE"></rag-index>
```

This will remove the index for the current workitem.

**Note:** The `RAGService` automatically reacts on a document delete event. This means, if a workitem is deleted from the database, the embeddings index will be deleted too.

## Retrieval

To retrieve indexed embeddings by a given prompt the Signal Adapter `org.imixs.ai.RAGRetievalAdapter` is used .
The adapter expects an `imixs-ai` configuration and a mandatory BPMN DataObject `PromptDefinition` associated with the corresponding event to index a workitem:

```xml
<imixs-ai name="RETRIEVAL">
  <endpoint>https://embeddings.llama.cpp.imixs.com/</endpoint>
  <reference-item>product.ref</reference-item>
  <max-results>5</max-results>
  <modelgroups>Product</modelgroups>
  <tasks>1200</tasks>
  <debug>true</debug>
</imixs-ai>
```

The result is a list of $uniqueIDs stored in the item [reference-item]

The PromptDefinition associated by a DataObject defines the text to be used to retrieve the embeddings.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<PromptDefinition>
  <prompt_options>{}</prompt_options>
  <prompt><![CDATA[<itemvalue>request.subject</itemvalue>

<FILECONTEXT>^.+\.eml$</FILECONTEXT>
]]>
</prompt>
</PromptDefinition>
```

The `debug` flag is optional and can be set to `true` to log index information.
