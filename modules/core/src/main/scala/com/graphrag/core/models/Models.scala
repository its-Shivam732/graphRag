package com.graphrag.core.models

// Existing models...
// Add these to your models package

case class ChunkWithId(chunkId: String, text: String)
case class PairWithChunk(chunkId: String, pair: ConceptPair)
case class Chunk(
                  chunkId: String,
                  docId: String,
                  span: (Int, Int),
                  text: String,
                  sourceUri: String,
                  hash: String
                )

case class Concept(
                    conceptId: String,
                    lemma: String,
                    surface: String,
                    origin: String
                  )

case class Mentions(
                     chunkId: String,
                     concept: Concept
                   )

// NEW: Co-occurrence and Relation models
case class ConceptPair(
                        concept1: Concept,
                        concept2: Concept,
                        cooccurrenceCount: Int,
                        chunkIds: Set[String]  // Where they co-occur
                      ) {
  // Canonical ordering for deduplication
  def normalized: ConceptPair = {
    if (concept1.conceptId <= concept2.conceptId) this
    else ConceptPair(concept2, concept1, cooccurrenceCount, chunkIds)
  }
}

case class Relation(
                     source: String,      // concept ID
                     target: String,      // concept ID
                     predicate: String,   // relationship type (e.g., "depends_on", "part_of")
                     confidence: Double,  // 0.0 to 1.0
                     evidence: String,    // text snippet proving the relation
                     origin: String       // "LLM" or "HEURISTIC"
                   )

case class RelationCandidate(
                              pair: ConceptPair,
                              context: String  // Combined text from chunks where they co-occur
                            )

sealed trait GraphElement

case class GraphNode(
                      id: String,
                      labels: Set[String],
                      properties: Map[String, Any]
                    ) extends GraphElement

case class GraphEdge(
                      sourceId: String,
                      targetId: String,
                      relationType: String,
                      properties: Map[String, Any]
                    ) extends GraphElement

// Helper constructors
object GraphNode {
  def fromChunk(chunk: Chunk): GraphNode = GraphNode(
    id = chunk.chunkId,
    labels = Set("Chunk", "Document"),
    properties = Map(
      "chunkId" -> chunk.chunkId,
      "docId" -> chunk.docId,
      "text" -> chunk.text,
      "sourceUri" -> chunk.sourceUri,
      "offset" -> chunk.span._1,
      "length" -> (chunk.span._2 - chunk.span._1)
    )
  )

  def fromConcept(concept: Concept): GraphNode = GraphNode(
    id = concept.conceptId,
    labels = Set("Concept"),
    properties = Map(
      "conceptId" -> concept.conceptId,
      "lemma" -> concept.lemma,
      "surface" -> concept.surface,
      "origin" -> concept.origin
    )
  )
}

object GraphEdge {
  def mentions(chunkId: String, conceptId: String): GraphEdge = GraphEdge(
    sourceId = chunkId,
    targetId = conceptId,
    relationType = "MENTIONS",
    properties = Map.empty
  )

  def cooccurs(conceptId1: String, conceptId2: String, count: Int): GraphEdge = GraphEdge(
    sourceId = conceptId1,
    targetId = conceptId2,
    relationType = "CO_OCCURS",
    properties = Map("count" -> count)
  )

  def relatesTo(relation: Relation): GraphEdge = GraphEdge(
    sourceId = relation.source,
    targetId = relation.target,
    relationType = "RELATES_TO",
    properties = Map(
      "predicate" -> relation.predicate,
      "confidence" -> relation.confidence,
      "evidence" -> relation.evidence,
      "origin" -> relation.origin
    )
  )
}