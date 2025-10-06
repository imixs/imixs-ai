# Imixs AI RAG

The Imixs AI RAG module provides a system for retrieval augmented generation (RAG). This system can be used to index workflow business- and metadata (generation) as also to search for similarity (retrieval) for a given text. The system is based on Apache Cassandra 4.0 which provides a distributed database system with high availability and scalability.
This module provides the Cassandra RAG services and also the adapter and plugin classes for an integration into a Imixs-Workflow instance.
For more details about the database schema see the section [Database](./doc/DATABASE.md)

## Integration

To create and retrieve embeddings during a workflow live cycle the `RAGAdapter` or the `RAGPlugin` can be added into a BPMN model.
These adapter classes interact with the `OpenAIAPIService` class of the module [imixs-ai-workflow](../imixs-ai-workflow/README.md) to interact with a LLM embedding model.

- **RAGAdapter** - the `org.imixs.ai.RAGAdapter` is used to index, update or delete embeddings within the curren processing lifecycle of a workitem.
- **RAGPlugin** - the `org.imixs.ai.RAGPlugin` class is used to update or delete existing embeddings.

## Indexing

To index a process instance the Signal Adapter `org.imixs.ai.RAGAdapter` is used. The adapter expects a DataObject `PromptDefinition` associated with the corresponding event and a workflow configuration defining the LLM endpoint

```xml
<imixs-ai name="INDEX">
  <endpoint>https://llama.cpp2.imixs.com/</endpoint>
  <debug>true</debug>
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

- ModelVersion - the `$modelversion` item assigned to the process instance at the time of the indexing
- TaskId - the `$taskId` item assigned to the process instance at the time of the indexing

**Note:** The workflow meta information is generated at the time of the indexing and does not necessary match the current workflow status!

### Update

To update the workflow metadata only, plugin class `org.imixs.ai.rag.RAGPlugin` can be used.
This plugin is used to update the workflow model version and the workflow status only but not the embeddings. The update is only be performed if an index already exists.

The update is performed automatically in each workflow processing cycle. To disabled a metadata update in single specific event you can define the following workflow definition:

```xml
<rag-index name="DISABLE"></rag-index>
```

In this case the plugin will be disabled.

### Delete

To delete a RAG index the plugin class `org.imixs.ai.rag.RAGPlugin` can be used in the DELETE mode:

```xml
<rag-index name="DELETE"></rag-index>
```

This will remove the index for the current workitem.

## Retrieval

To retrieve indexed embeddings by a given prompt the Signal Adapter `org.imixs.ai.RAGAdapter` is used in the `RETRIEVAL` mode:

```xml
<imixs-ai name="RETRIEVAL">
    <endpoint><propertyvalue>llm.service.endpoint</propertyvalue></endpoint>
    <reference-item>[ITEMNAME]</reference-item>
    <debug>true</debug>
</imixs-ai>
```

The PromptDefinition associated by a DataObject defines the text to be used to retrieve the embeddings.The result is a list of $uniqueIDs stored in the item [reference-item]

The `debug` flag is optional and can be set to `true` to log index information.

## Deletion

Independent from the `DELETE` mode in the `org.imixs.ai.rag.RAGPlugin` a embedding will also be removed from the index database if a workitem was deleted. For this the `RAGDeletionService` is observing the corresponding Document Event from the Imixs Workflow engine to delete existing entries. This behavior can not be disabled.
