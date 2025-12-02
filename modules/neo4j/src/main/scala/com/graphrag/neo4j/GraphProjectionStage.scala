package com.graphrag.neo4j

import com.graphrag.core.models._
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.functions.{MapFunction, ReduceFunction}
import org.apache.flink.api.java.functions.KeySelector

object GraphProjectionStage {

  def projectChunks(chunks: DataStream[Chunk]): DataStream[GraphNode] = {
    chunks
      .map(new ChunkToNodeMapper())
      .name("project-chunks-to-nodes")
  }

  def projectConcepts(mentions: DataStream[Mentions]): DataStream[GraphNode] = {
    mentions
      .map(new MentionToConceptMapper())
      .keyBy(new ConceptIdKeySelector())  // ✅ FIXED: No more lambda!
      .reduce(new ConceptDeduplicator())
      .map(new ConceptToNodeMapper())
      .name("project-concepts-to-nodes")
  }

  def projectMentions(mentions: DataStream[Mentions]): DataStream[GraphEdge] = {
    mentions
      .map(new MentionToEdgeMapper())
      .name("project-mentions-to-edges")
  }

  def projectCooccurrences(pairs: DataStream[ConceptPair]): DataStream[GraphEdge] = {
    pairs
      .map(new CooccurToEdgeMapper())
      .name("project-cooccurs-to-edges")
  }

  def projectRelations(relations: DataStream[Relation]): DataStream[GraphEdge] = {
    relations
      .map(new RelationToEdgeMapper())
      .name("project-relations-to-edges")
  }

  // Mapper classes - NO LAMBDAS!
  class ChunkToNodeMapper extends MapFunction[Chunk, GraphNode] {
    override def map(chunk: Chunk): GraphNode = GraphNode.fromChunk(chunk)
  }

  class MentionToConceptMapper extends MapFunction[Mentions, Concept] {
    override def map(m: Mentions): Concept = m.concept
  }

  // ✅ NEW: KeySelector for Concept
  class ConceptIdKeySelector extends KeySelector[Concept, String] {
    override def getKey(c: Concept): String = c.conceptId
  }

  class ConceptDeduplicator extends ReduceFunction[Concept] {
    override def reduce(c1: Concept, c2: Concept): Concept = c1
  }

  class ConceptToNodeMapper extends MapFunction[Concept, GraphNode] {
    override def map(concept: Concept): GraphNode = GraphNode.fromConcept(concept)
  }

  class MentionToEdgeMapper extends MapFunction[Mentions, GraphEdge] {
    override def map(m: Mentions): GraphEdge =
      GraphEdge.mentions(m.chunkId, m.concept.conceptId)
  }

  class CooccurToEdgeMapper extends MapFunction[ConceptPair, GraphEdge] {
    override def map(pair: ConceptPair): GraphEdge =
      GraphEdge.cooccurs(
        pair.concept1.conceptId,
        pair.concept2.conceptId,
        pair.cooccurrenceCount
      )
  }

  class RelationToEdgeMapper extends MapFunction[Relation, GraphEdge] {
    override def map(relation: Relation): GraphEdge = GraphEdge.relatesTo(relation)
  }
}