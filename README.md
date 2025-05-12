# Knowledge-Enrichment · RAG micro-service for PDFs  
[![Build](https://img.shields.io/badge/build-Maven_3.9+-blue?logo=apachemaven)](pom.xml)
[![Docker Compose](https://img.shields.io/badge/run-docker--compose-blue?logo=docker)](compose.yaml)
[![License](https://img.shields.io/github/license/aborroy/alfresco-knowledge-enrichment)](LICENSE)

> **v0.8.0 &nbsp;•&nbsp; Java 21 · Spring Boot 3 · Spring AI**  
> Drop-in service that ingests PDFs, stores chunks & captions in Elasticsearch vector search, and answers questions with retrieval-augmented generation (RAG) powered by local LLM(s)

## What it does

| Step             | Detail                                                             | Tech |
|------------------|--------------------------------------------------------------------|------|
| 1. Ingest        | `POST /api/ingest` accepts a PDF, splits pages → 512-token chunks  | `PagePdfDocumentReader` + `TokenTextSplitter` |
| 2. Caption       | Every inline image is described by a vision-capable LLM            | `RagImageExtractor` + LLaVA (via Ollama) |
| 3. Store vectors | Text & captions are embedded and written to an Elasticsearch index | `spring-ai-vector-store-elasticsearch` |
| 4. Chat          | `POST /api/chat` runs a prompt template with the top-K matches     | `ChatClient` + any chat model (default llava) |
| 5. Cite          | The answer returns both the response and the supporting docs       | `ChatResponse` DTO |

Everything is wrapped in a thin Spring-Boot REST API and shipped in a single Docker image.

* The container speaks to Ollama on `http://host.docker.internal:11434` (chat/vision) and to Docker Model Runner embedding service on `http://host.docker.internal:12434/engines` 
* All vectors (1024 dims) live in the single-node Elasticsearch 9 that ships in the compose file

## Quick start

Requirements

* Docker Desktop ≥ 4.24 (20 GiB RAM)
* Docker Compose v2
* Maven 3.x
* Java 21

To use the Knowledge Enrichment service locally, you must install and run both **Ollama** (for chat and image captioning) and an **OpenAI-compatible embedding service** such as the [Docker Model Runner](https://docs.docker.com/model-runner/). 


```bash
# 1. Clone
git clone https://github.com/aborroy/alfresco-knowledge-enrichment.git
cd alfresco-knowledge-enrichment

# 2. Fire up everything
docker compose up --build
````

| Service                      | URL                                                     | Notes        |
| ---------------------------- | ------------------------------------------------------- | ------------ |
| Knowledge-Enrichment API     | [http://localhost:8080/api](http://localhost:8080/api)  | Rest API     |
| Elasticsearch (vector store) | [http://localhost:9200](http://localhost:9200)          | single-node  |
| Kibana                       | [http://localhost:5601](http://localhost:5601)          | optional UI  |

## API reference

### `POST /api/ingest`

| Param  | Type            | Description                             |
| ------ | --------------- | --------------------------------------- |
| `uuid` | form-field      | Logical grouping key (e.g. uuid)        |
| `file` | PDF (multipart) | The document to index (max 100 MB)      |

Returns **HTTP 202** when the file has been chunked, captioned and stored.

```bash
curl -F uuid=demo \
     -F file=@contract.pdf \
     http://localhost:8080/api/ingest
```

### `POST /api/chat`

```jsonc
// request
{ "message": "Who was the first person to break an Enigma-like machine?" }

// response
{
  "response": "Marian Rejewski, a Polish mathematician, was the first person ...",
  "documents": [
    { "id":"uuid#page3-chunk2", "metadata":{ ... } },
    ...
  ]
}
```

## Configuration (excerpt of `application.yml`)

| Property                                         | Default                          | Purpose                         |
| ------------------------------------------------ | -------------------------------- | ------------------------------- |
| `spring.ai.model.embedding`                      | `openai`                         | Name used for embeddings        |
| `spring.ai.openai.base-url`                      | `http://localhost:12434/engines` | Embedding runner                |
| `spring.ai.model.chat`                           | `ollama`                         | Name used for chat              |
| `spring.ai.ollama.base-url`                      | `http://localhost:11434`         | Ollama daemon                   |
| `spring.ai.vectorstore.elasticsearch.index-name` | `alfresco`                       | ES index for vectors            |
| `spring.ai.vectorstore.elasticsearch.dimensions` | `1024`                           | Must match your embedding model |

Override any of them via `SPRING_*` environment variables or a custom `application.yml`.

## Local development

```bash
# prerequisites: JDK 21, Maven 3.9, Elasticsearch 9 running locally
mvn clean package and java -jar target/knowledge-enrichment-0.8.0.jar
```

The app starts on **`localhost:8080`** and will talk to the same model runners you configured above
