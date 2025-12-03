package com.graphrag.neo4j

import com.graphrag.core.models._
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.functions.{MapFunction, ReduceFunction}
import org.apache.flink.api.java.functions.KeySelector
import org.slf4j.LoggerFactory

/**
 * GraphProjectionStage
 *
 * Converts Flink stream outputs (chunks, concepts, mentions, relations)
 * into graph primitives:
 *
 *   - GraphNode  (representing chunks and concepts)
 *   - GraphEdge  (representing mention edges, co-occurrence edges, relations)
 *
 * This stage prepares data for downstream graph storage (Neo4j sinks).
 */
object GraphProjectionStage {

  // ----------------------------------------------------------
  // SLF4J logger (static logger for this object)
  // ----------------------------------------------------------
  private val log = LoggerFactory.getLogger("GraphProjectionStage")

  /**
   * Project raw Chunk records into GraphNode objects.
   * Each chunk becomes a node in the graph.
   */
  def projectChunks(chunks: DataStream[Chunk]): DataStream[GraphNode] = {
    println("Projecting chunks → GraphNode")
    chunks
      .map(new ChunkToNodeMapper())
      .name("project-chunks-to-nodes")
  }

  /**
   * Project unique concepts into GraphNode objects.
   *
   * Steps:
   *   1. Extract concept from Mentions
   *   2. keyBy conceptId to group duplicates
   *   3. reduce to one concept per ID (ConceptDeduplicator)
   *   4. convert to GraphNode
   */
  def projectConcepts(mentions: DataStream[Mentions]): DataStream[GraphNode] = {
    println("Projecting concepts → GraphNode")
    mentions
      .map(new MentionToConceptMapper())
      .keyBy(new ConceptIdKeySelector())
      .reduce(new ConceptDeduplicator())
      .map(new ConceptToNodeMapper())
      .name("project-concepts-to-nodes")
  }

  /**
   * Project mention relationships (Chunk → Concept) into edges.
   * These represent that a concept was mentioned inside a chunk.
   */
  def projectMentions(mentions: DataStream[Mentions]): DataStream[GraphEdge] = {
    println("Projecting mentions → GraphEdge")
    mentions
      .map(new MentionToEdgeMapper())
      .name("project-mentions-to-edges")
  }

  /**
   * Project co-occurring concept pairs into graph edges.
   * These edges often represent semantic proximity or relatedness.
   */
  def projectCooccurrences(pairs: DataStream[ConceptPair]): DataStream[GraphEdge] = {
    println("Projecting co-occurrences → GraphEdge")
    pairs
      .map(new CooccurToEdgeMapper())
      .name("project-cooccurs-to-edges")
  }

  /**
   * Project scored relations (LLM-generated) into graph edges.
   */
  def projectRelations(relations: DataStream[Relation]): DataStream[GraphEdge] = {
    println("Projecting relations → GraphEdge")
    relations
      .map(new RelationToEdgeMapper())
      .name("project-relations-to-edges")
  }

  // -------------------------------------------------------------------
  // MAPPERS AND KEY SELECTORS (NO LAMBDAS ALLOWED)
  // -------------------------------------------------------------------

  /** Convert Chunk → GraphNode. */
  class ChunkToNodeMapper extends MapFunction[Chunk, GraphNode] {
    override def map(chunk: Chunk): GraphNode = {
      GraphNode.fromChunk(chunk)
    }
  }

  /** Extract Concept from Mentions. */
  class MentionToConceptMapper extends MapFunction[Mentions, Concept] {
    override def map(m: Mentions): Concept = m.concept
  }

  /** keyBy helper to group concepts by conceptId. */
  class ConceptIdKeySelector extends KeySelector[Concept, String] {
    override def getKey(c: Concept): String = c.conceptId
  }

  /**
   * Deduplicate concepts after keyBy.
   * Concept models are identical per key so we return the first.
   */
  class ConceptDeduplicator extends ReduceFunction[Concept] {
    override def reduce(c1: Concept, c2: Concept): Concept = c1
  }

  /** Convert Concept → GraphNode. */
  class ConceptToNodeMapper extends MapFunction[Concept, GraphNode] {
    override def map(concept: Concept): GraphNode = GraphNode.fromConcept(concept)
  }

  /** Convert Mentions → GraphEdge (Chunk → Concept). */
  class MentionToEdgeMapper extends MapFunction[Mentions, GraphEdge] {
    override def map(m: Mentions): GraphEdge =
      GraphEdge.mentions(m.chunkId, m.concept.conceptId)
  }

  /** Convert ConceptPair → GraphEdge (co-occurrence). */
  class CooccurToEdgeMapper extends MapFunction[ConceptPair, GraphEdge] {
    override def map(pair: ConceptPair): GraphEdge =
      GraphEdge.cooccurs(
        pair.concept1.conceptId,
        pair.concept2.conceptId,
        pair.cooccurrenceCount
      )
  }

  /** Convert scored relation → GraphEdge. */
  class RelationToEdgeMapper extends MapFunction[Relation, GraphEdge] {
    override def map(relation: Relation): GraphEdge =
      GraphEdge.relatesTo(relation)
  }
}
