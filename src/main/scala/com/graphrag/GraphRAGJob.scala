package com.graphrag

import com.graphrag.core.config.Config
import com.graphrag.core.models.Chunk
import com.graphrag.ingestion.{ChunkNormalizer, ChunkSource}
import com.graphrag.llm.{ConceptExtractionStage, RelationExtractionStage}
import com.graphrag.neo4j.{GraphProjectionStage, Neo4jEdgeSink, Neo4jNodeSink}
import org.apache.flink.api.java.functions.KeySelector
import org.apache.flink.streaming.api.scala._
import org.slf4j.LoggerFactory

/**
 * GraphRAGJob
 *
 * Main Flink pipeline assembly.
 */
object GraphRAGJob {

  // ----------------------------------------------------------
  // Use SLF4J logger instead of println
  // ----------------------------------------------------------
  private val log = LoggerFactory.getLogger("GraphRAGJob")

  def main(args: Array[String]): Unit = {

    val env = StreamExecutionEnvironment.getExecutionEnvironment

    // Log pipeline startup + configuration summary
    log.info("=" * 50)
    log.info("GraphRAG Pipeline Starting")
    log.info("=" * 50)
    log.info(s"Ollama URL: ${Config.ollamaUrl}")
    log.info(s"Ollama Model: ${Config.ollamaModel}")
    log.info(s"Neo4j URI: ${Config.neo4jUri}")
    log.info(s"Use LLM: ${Config.useLLM}")
    log.info(s"Write to Neo4j: ${Config.writeToNeo4j}")
    log.info("=" * 50)

    ///get from arguments or default
    val chunksPath = args.headOption.getOrElse(Config.default_json_file)

    // ---------------------------------------------------------------------
    // STEP 1: Chunk ingestion
    // ---------------------------------------------------------------------
    log.info("STEP 1: Ingesting chunks...")
    val rawChunks: DataStream[Chunk] = ChunkSource.fromJsonLines(env, chunksPath)

    // ---------------------------------------------------------------------
    // STEP 2: Normalization
    // ---------------------------------------------------------------------
    log.info("STEP 2: Normalizing chunks...")
    val normalizedChunks = ChunkNormalizer.normalize(rawChunks)

    // ---------------------------------------------------------------------
    // STEP 3: Concept extraction
    // ---------------------------------------------------------------------
    log.info("STEP 3: Extracting concepts (async)...")
    val mentions = ConceptExtractionStage.extractConcepts(
      normalizedChunks,
      Config.ollamaUrl,
      Config.ollamaModel,
      Config.useLLM
    )

    // ---------------------------------------------------------------------
    // STEP 4: Co-occurrences
    // ---------------------------------------------------------------------
    log.info("STEP 4: Finding co-occurrences...")
    val conceptPairs = mentions
      .keyBy(new ChunkIdKeySelector())
      .process(new com.graphrag.llm.CooccurrenceFinder())
      .name("extract-concept-pairs")

    // ---------------------------------------------------------------------
    // STEP 5: Relation scoring
    // ---------------------------------------------------------------------
    log.info("STEP 5: Scoring relations...")
    val relations = RelationExtractionStage.extractRelations(
      mentions,
      normalizedChunks,
      Config.ollamaUrl,
      Config.ollamaModel,
      windowSize = Config.relationWindowSize,
      minCooccurrence = Config.relationMinCooccurrence,
      maxConcurrentRequests = Config.relationMaxConcurrentLLM
    )

    // ---------------------------------------------------------------------
    // STEP 6: Graph projection
    // ---------------------------------------------------------------------
    log.info("STEP 6: Projecting to graph structure...")
    val chunkNodes     = GraphProjectionStage.projectChunks(normalizedChunks)
    val conceptNodes   = GraphProjectionStage.projectConcepts(mentions)
    val mentionEdges   = GraphProjectionStage.projectMentions(mentions)
    val cooccurEdges   = GraphProjectionStage.projectCooccurrences(conceptPairs)
    val relationEdges  = GraphProjectionStage.projectRelations(relations)

    // ---------------------------------------------------------------------
    // STEP 7: Neo4j writing
    // ---------------------------------------------------------------------
    if (Config.writeToNeo4j) {

      log.info("STEP 7: Writing to Neo4j...")

      val allNodes = chunkNodes.union(conceptNodes)
      val allEdges = mentionEdges.union(cooccurEdges).union(relationEdges)

      val nodeSink = new Neo4jNodeSink(
        Config.neo4jUri, Config.neo4jUsername, Config.neo4jPassword
      )

      val edgeSink = new Neo4jEdgeSink(
        Config.neo4jUri, Config.neo4jUsername, Config.neo4jPassword
      )

      allNodes.addSink(nodeSink).name("write-nodes-to-neo4j")
      allEdges.addSink(edgeSink).name("write-edges-to-neo4j")

      log.info("✓ Sinks attached - writing to Neo4j")

    } else {
      log.info("STEP 7: Printing samples (Neo4j writing disabled)...")
      relations.map(new RelationPrinter()).print()
    }

    // ---------------------------------------------------------------------
    // EXECUTE PIPELINE
    // ---------------------------------------------------------------------
    log.info("Executing pipeline...")
    env.execute("GraphRAG Pipeline - Complete")
  }

  /**
   * Extracts chunkId from Mentions for Flink keyBy
   */
  class ChunkIdKeySelector
    extends KeySelector[com.graphrag.core.models.Mentions, String] {
    override def getKey(m: com.graphrag.core.models.Mentions): String = m.chunkId
  }

  /**
   * Pretty prints relations during debug mode
   */
  class RelationPrinter
    extends org.apache.flink.api.common.functions.MapFunction[
      com.graphrag.core.models.Relation,
      String
    ] {
    override def map(r: com.graphrag.core.models.Relation): String =
      s"Relation: ${r.source.take(30)} --[${r.predicate}]--> ${r.target.take(30)} (conf: ${r.confidence})"
  }
}
