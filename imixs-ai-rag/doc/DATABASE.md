# Imixs AI RAG - Database

The retrieval database in the Imixs AI RAG system is based on Apache Cassandra 4.0 which provides a distributed database system with high availability and scalability.

## Database Schema

The schema for the RAG database is created automatically during startup. This includes the Cassandra keyspace as also the data base schema.
The main table has the following definition:

```sql
CREATE TABLE IF NOT EXISTS document_vectors (
    id text,
    chunk_id uuid,
    content_chunk text,
    content_vector VECTOR <FLOAT, " + DIMENSIONS + ">,
    PRIMARY KEY (id, chunk_id)
);
```

The default dimensions for float vector is based on the LLM used for indexing. 'nomic-embed-text-v2-moe' is a sentence-transformers model to map sentences & paragraphs to a 768 dimensional dense vector space. Find more details in the section [RAG](RAG.md).

The following section explains the schema in more detail.

### The Chunk Content

The RAG database stores not only the vectors but also the textual representation of the content chunks. This has several advantages for the retrieval phase:

**Context provision for the LLM**

The vector is only used for similarity searching - it is a numerical representation of text, but not human-readable. Once the most similar documents have been found, the original text (content_chunk) can be used directly as context for a LLM. Note: the LLM cannot work with the vectors, but needs the readable text.

**Efficiency of Queries**

The chunk content also allows an immediate access to the context with no need to make a separate query to the Imixs Workflow instance after the vector search to retrieve the original content. This would mean additional latency and complexity. `content_chunk` stores exactly the text section that corresponds to the vector.

**The typical RAG workflow**

The typical workflow in RAG system can be separated into 3 phases:

1. **Search:** Vector similarity search finds relevant content_vector entries.
2. **Retrieval:** The corresponding content_chunk texts are extracted.
3. **Generation:** These texts from the `content_chunk' is sent as context to the LLM.

Without the stored text, the retrieval part of RAG would not be possible, as you would have no content to provide to the LLM as context.

### The Content Reference

Together with the `content_chunk` the RAG system stores also the UniqueID from to the referred business process instance within the workflow instance. This is a hybrid concept that provides several advantages:

**1. Normalization and Consistency**

- Single Source of Truth: The main Document with the full data exist only once in the imixs-workflow system.
- Changes to data inside a process instance which is not part of the Vector DB are automatically available without the need to update the Vector DB.
- No full data duplication.

**2. Better Integration**

- Easy access not only to text chunks but also the complete business process objects with its metadata for further refinement of a result set.
- Ability to apply additional business logic (permissions, status, etc.).

**3. Flexibility**

- The application can decide dynamically if either the content_chunk or other parts of the referred process instance is used as context.
- Easier updates in case of document changes.
- The `content_chunk` still provide fast access to context during the retrieval process.

### Examples

The following shows the advantage of the hybrid concept of content_chunks and workitem references in some examples.

#### Retrieval Example

The user enters the following question:

_"Show me all service contracts from non-EU countries with a penalty clause of over 1 million EUR."_

**The problem with pure text chunks:**

In this kind of a request the context can be build from the received text chunks like :

- Chunk 1: _"...in case of breach of contract, contractual penalties of 1.5 million EUR are applicable..."_
- Chunk 2: _"...Penalty clause: contractual penalties of 2.3 million EUR..."_
- Chunk 3: _"...penalties of 1.8 million EUR in case of non-fulfillment..."_

What is missing here: contract number, date, contracting party, country, contract value, status, etc.

**The Advantage of reference IDs:**

But using the id the RAG system can access the full business data from the workflow system. This allows to build more relevant context like :

```
Found service contracts:

1. Contract SVC-2024-001 (ABC Corp Singapore)
  - Concluded: 15.03.2024
  - Contract penalty: 1.5 million EUR
  - Status: Active

2. Contract SVC-2024-007 (XYZ Ltd Hong Kong)
  - Concluded: 22.01.2024
  - Contract penalty: 2.3 million EUR
  - Status: Under negotiation
```

Using the combination of content_chunk text and metadata form the process instance allows to build even more complex context.

So the hybrid approach is clearly more sensible for a Business Process Management System, as it combines both the advantages of RAG (semantic search) and structured business data.

#### Complex Example

Next we can examine a more complex use case.

The user enters the following question:

_"Are there incoming invoices in which service fees have been charged that do not correspond to the underlying contracts?"_

This example indeed touches the limits of RAG, but not necessarily insurmountable ones.

The challenges:

1. **Cross-Document Reasoning:**

- RAG finds similar chunks, but the question requires comparisons between different document types (invoices vs. contracts).
- An LLM would need to simultaneously receive invoice chunks AND corresponding contract chunks.

2. **Structured Data Extraction:**

- "Service fees" need to be extracted and compared from unstructured text.
- Numbers, prices, and service descriptions must be semantically mapped.

3. **Relationship Logic:**

- Which invoice belongs to which contract?
- The LLM needs this connection to make meaningful comparisons.

#### Possible Solutions

The following solutions are specific implementations in the RAG workflow:

**Approach 1:** Enhanced RAG with a Multi-Step Retrieval

```
# Pseudo-Code
def complex_query_handler(question):
    # Step 1: Finde relevante Rechnungen
    invoice_chunks = vector_search("Servicegebühren Rechnungen")

    # Step 2: Extrahiere Vertrags-IDs aus gefundenen Rechnungen
    contract_ids = extract_contract_references(invoice_chunks)

    # Step 3: Hole zugehörige Vertrags-Chunks
    contract_chunks = get_contract_chunks(contract_ids)

    # Step 4: LLM mit beiden Kontexten
    return llm.query(question, context=invoice_chunks + contract_chunks)
```

**Approach 2:** Hybrid-System with structured data

```
# Pseudo-Code
def intelligent_agent(question):
    # Agent entscheidet: "Ich brauche Rechnungs- UND Vertragsdaten"
    plan = create_execution_plan(question)

    for step in plan:
        if step.type == "find_invoices":
            invoices = rag_search(step.query)
        elif step.type == "find_contracts":
            contracts = rag_search(step.query)
        elif step.type == "compare":
            result = llm_compare(invoices, contracts)

    return synthesize_answer(result)
```

**Recommendation**

Since we already have a business process management suite, we can follow the hybrid approach:

1.  RAG for content discovery: "Find all documents with service fees"
2.  Structured queries: Use your business data for relationships
3.  LLM for final analysis: With all relevant data as context.

```
# Pseudo-Code
def analyze_service_fee_discrepancies():
    # RAG: Finde relevante Dokumente
    relevant_docs = vector_search("Servicegebühren")

    # Business Logic: Hole strukturierte Daten
    invoices_with_contracts = get_invoice_contract_pairs(relevant_docs)

    # LLM: Analysiere mit vollem Kontext
    return llm.analyze_discrepancies(invoices_with_contracts)
```

**Conclusion:**

A pure RAG solution reaches its limits here, but an intelligent hybrid system consisting of RAG + structured data + multi-step reasoning can certainly answer such complex questions.

## Chunking Business Documents

As the content_chunk can only have a small size below 1KB the RAG system includes a split process to split documents into smaller content_chunks.
The split-process breaks down large business processes into logical chunks. Since the data is usually available in markup or can be prepared for the indexing process within the BPMN model, the split is made based on the structure in the markup. This guarantees a clean separation of text parts without the need to compute overlapping.

## Data Protection

Also the vector db may contain sensitive business data represented in content chunks. To protect also this kind of data the RAG system is tightly integrated into the security layer of Imixs-Workflow. In the retrieval phase each content_chunk returned by the vector db is immediately validated against the ACL of the corresponding process instance. This avoids the affect that unauthorized uses may receive sensitive data in a retrieval phase.  
As the

## Data Deletion

TBD

# Administration

Aside from internal database logic, the Cassandra cluster can also be managed through the Command Line Console (CLI).

## Using the Cassandra CLI

To connect to your cluster you can use the cqlsh tool.
To run cqlsh from your started docker environment run:

```bash
$ docker exec -it cassandra cqlsh
Connected to ImixsAiCluster at 127.0.0.1:9042
[cqlsh 6.2.0 | Cassandra 5.0.4 | CQL spec 3.4.7 | Native protocol v5]
Use HELP for help.
cqlsh>
```

## CQL Examples

The following section contains some basic cqlsh commands. For full description see the [Cassandra CQL refernce](https://docs.datastax.com/en/dse/6.0/cql/).

**show key spaces:**

Show all available keyspaces:

    cqlsh> DESC KEYSPACES;

**Switch to Keysapce:**

Select a keyspace be name to interact with this keyspace:

    cqlsh> use embeddings;

**Show tables in a keyspace:**

Show tables schemas in current keyspace:

    cqlsh:embeddings> DESC TABLES;

**Drop Keyspace:**

Drop the keyspace:

    DROP KEYSPACE IF EXISTS <keyspace_name>;

### Create a dev keyspace with cqlsh

    cqlsh> CREATE KEYSPACE <keyspace_name> WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1};
