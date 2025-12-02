package com.graphrag

import com.graphrag.core.models.Chunk
import com.graphrag.core.config.Config
import com.graphrag.ingestion.{ChunkSource, ChunkNormalizer}
import com.graphrag.llm.{ConceptExtractionStage, RelationExtractionStage}
import com.graphrag.neo4j.{GraphProjectionStage, Neo4jNodeSink, Neo4jEdgeSink}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.java.functions.KeySelector

object GraphRAGJob {

  def main(args: Array[String]): Unit = {
    val env = StreamExecutionEnvironment.getExecutionEnvironment

    println("=" * 50)
    println("GraphRAG Pipeline Starting")
    println("=" * 50)
    println(s"Ollama URL: ${Config.ollamaUrl}")
    println(s"Ollama Model: ${Config.ollamaModel}")
    println(s"Neo4j URI: ${Config.neo4jUri}")
    println(s"Use LLM: ${Config.useLLM}")
    println(s"Write to Neo4j: ${Config.writeToNeo4j}")
    println("=" * 50)

    val chunksPath = args.headOption.getOrElse("file:///Users/moudgil/chunks.jsonl")

    println("\nSTEP 1: Ingesting chunks...")
    val rawChunks: DataStream[Chunk] = ChunkSource.fromJsonLines(env, chunksPath)

    println("STEP 2: Normalizing chunks...")
    val normalizedChunks = ChunkNormalizer.normalize(rawChunks)

    println("STEP 3: Extracting concepts (async)...")
    val mentions = ConceptExtractionStage.extractConcepts(
      normalizedChunks,
      Config.ollamaUrl,
      Config.ollamaModel,
      Config.useLLM
    )

    println("STEP 4: Finding co-occurrences...")
    val conceptPairs = mentions
      .keyBy(new ChunkIdKeySelector())
      .process(new com.graphrag.llm.CooccurrenceFinder())
      .name("extract-concept-pairs")

    println("STEP 5: Scoring relations...")
    val relations = RelationExtractionStage.extractRelations(
      mentions,
      normalizedChunks,
      Config.ollamaUrl,
      Config.ollamaModel,
      windowSize = 3,
      minCooccurrence = 2
    )

    println("STEP 6: Projecting to graph structure...")
    val chunkNodes = GraphProjectionStage.projectChunks(normalizedChunks)
    val conceptNodes = GraphProjectionStage.projectConcepts(mentions)
    val mentionEdges = GraphProjectionStage.projectMentions(mentions)
    val cooccurEdges = GraphProjectionStage.projectCooccurrences(conceptPairs)
    val relationEdges = GraphProjectionStage.projectRelations(relations)

    if (Config.writeToNeo4j) {
      println("STEP 7: Writing to Neo4j...")

      val allNodes = chunkNodes.union(conceptNodes)
      val allEdges = mentionEdges.union(cooccurEdges).union(relationEdges)

      val nodeSink = new Neo4jNodeSink(
        Config.neo4jUri,
        Config.neo4jUsername,
        Config.neo4jPassword
      )

      val edgeSink = new Neo4jEdgeSink(
        Config.neo4jUri,
        Config.neo4jUsername,
        Config.neo4jPassword
      )

      allNodes.addSink(nodeSink).name("write-nodes-to-neo4j")
      allEdges.addSink(edgeSink).name("write-edges-to-neo4j")

      println("✓ Sinks attached - will write to Neo4j")

    } else {
      println("STEP 7: Printing samples (Neo4j writing disabled)...")
      relations
        .map(new RelationPrinter())
        .print()
    }

    println("\nExecuting pipeline...")
    env.execute("GraphRAG Pipeline - Complete")
  }

  // KeySelectors - NO LAMBDAS!
  class ChunkIdKeySelector extends KeySelector[com.graphrag.core.models.Mentions, String] {
    override def getKey(m: com.graphrag.core.models.Mentions): String = m.chunkId
  }

  class RelationPrinter extends org.apache.flink.api.common.functions.MapFunction[
    com.graphrag.core.models.Relation,
    String
  ] {
    override def map(r: com.graphrag.core.models.Relation): String =
      s"Relation: ${r.source.take(30)} --[${r.predicate}]--> ${r.target.take(30)} (conf: ${r.confidence})"
  }
}