 (cd "$(git rev-parse --show-toplevel)" && git apply --3way <<'EOF' 
diff --git a/README.md b/README.md
new file mode 100644
index 0000000000000000000000000000000000000000..a53e859ffe7dbdd050a3b47146b115600734016f
--- /dev/null
+++ b/README.md
@@ -0,0 +1,71 @@
+# GraphRAG Flink Pipeline
+
+This repository assembles a streaming GraphRAG pipeline that ingests document chunks, extracts concepts and relationships with an LLM, and projects the results into Neo4j. The pipeline is implemented as an Apache Flink job (`com.graphrag.GraphRAGJob`) and comes with Kubernetes manifests for deployment.
+
+## High-level flow
+
+```text
+[S3/Index Store] --> [Flink Source]
+                        |
+                        v
+                [Chunk Stream] --------------.
+                        |                    |
+                        v                    |
+               [Concept Extraction]          |
+                        |                    |
+                        v                    |
+             [Relation Candidates] <---------'
+                        |
+                        v
+              [LLM Scoring via Ollama]
+                        |
+                        v
+                  [Graph Projection]
+                        |
+                        v
+                    [Neo4j Sink]
+```
+
+## Step-by-step pipeline walkthrough
+
+The `GraphRAGJob` object wires together the streaming stages below. Each step corresponds to the flow diagram above.
+
+1. **Chunk ingestion** – `ChunkSource.fromJsonLines` reads JSONL records of chunks from the configured path (commonly an S3 URL provided as the first CLI argument).【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L32-L41】
+2. **Normalization** – `ChunkNormalizer.normalize` cleans and standardizes the ingested chunks to prepare them for downstream LLM prompts.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L43-L47】
+3. **Concept extraction** – `ConceptExtractionStage.extractConcepts` asynchronously calls the configured Ollama endpoint/model to pull out named entities and concepts from each normalized chunk, yielding mention streams keyed by chunk ID.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L49-L59】【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L92-L97】
+4. **Co-occurrence detection** – Mentions are grouped by chunk via `ChunkIdKeySelector` and fed into `CooccurrenceFinder` to emit concept pairs that co-occur within the same chunk window.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L61-L72】【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L99-L109】
+5. **Relation scoring** – `RelationExtractionStage.extractRelations` reuses the mentions and normalized chunks to score candidate relations with the LLM, controlling window size, minimum co-occurrence, and concurrency via `Config` settings.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L74-L87】
+6. **Graph projection** – `GraphProjectionStage` transforms chunks, concepts, mentions, co-occurrences, and relations into graph nodes and edges suitable for Neo4j storage.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L89-L96】
+7. **Neo4j sink** – When `Config.writeToNeo4j` is enabled, node and edge streams are written using `Neo4jNodeSink` and `Neo4jEdgeSink`. Otherwise, relations are printed via `RelationPrinter` for debugging.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L98-L129】
+8. **Execution** – Finally, Flink executes the assembled job under the name `GraphRAG Pipeline - Complete`.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L131-L135】
+
+### Configuration highlights
+
+`GraphRAGJob` logs key runtime options sourced from `Config` before building the DAG:
+
+- Ollama URL/model used for concept extraction and relation scoring.
+- Neo4j connection URI and credentials.
+- Feature toggles for LLM usage and Neo4j writes.
+- Default chunk input path when no CLI argument is supplied.
+
+These settings are typically injected via environment variables or configuration files resolved by the `com.graphrag.core.config.Config` object.【F:src/main/scala/com/graphrag/GraphRAGJob.scala†L24-L41】
+
+## Building the fat JAR
+
+The root SBT project (`graphrag-job`) depends on modular subprojects (core, ingestion, neo4j, llm, api) and assembles a deployable `graphrag-job.jar` with `sbt assembly`. The main class is `com.graphrag.GraphRAGJob` and the assembly merge strategy discards or concatenates common metadata files to produce a runnable artifact.【F:build.sbt†L1-L87】【F:build.sbt†L107-L136】
+
+## Kubernetes deployment
+
+Two manifests under `deploy/` make it easy to run the pipeline on Kubernetes:
+
+- **Ollama DaemonSet** – `deploy/ollama-daemonset.yaml` runs Ollama on every node (port `11434`) with an `emptyDir` for model storage and a headless service for in-cluster discovery.【F:deploy/ollama-daemonset.yaml†L1-L36】
+- **FlinkDeployment** – `deploy/job-graph-rag.yaml` provisions Flink JobManager/TaskManager pods, configures S3 access (for reading chunk JSONL inputs), and points to the assembled JAR and entry class. The job arguments include the chunk source URL (e.g., `s3urltochunks.jsonl`).【F:deploy/job-graph-rag.yaml†L1-L59】
+
+## Running locally
+
+1. Build the jar: `sbt assembly` (requires Java/Scala toolchain and Flink provided dependencies).
+2. Start Ollama with your chosen model and note the HTTP endpoint.
+3. Run the job with Flink, supplying the chunk JSONL path if different from the default: `flink run -c com.graphrag.GraphRAGJob target/scala-2.12/graphrag-job.jar s3://bucket/chunks.jsonl`.
+4. Ensure Neo4j credentials and URI are available in the environment if graph writing is enabled.
+
+This README reflects the existing code path and Kubernetes assets so that newcomers can understand and operate the GraphRAG pipeline end to end.
 
EOF
)
