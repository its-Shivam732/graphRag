package com.graphrag.api.models

import io.circe.{Encoder, Json}
import io.circe.generic.semiauto._

// ------------------------------------------------------------
// EXISTING MODELS
// ------------------------------------------------------------

case class QueryRequest(
                         query: String,
                         maxResults: Int = 10,
                         includeEvidence: Boolean = true
                       )

case class QueryResponse(
                          requestId: String,
                          concepts: Seq[ConceptResult],
                          relations: Seq[RelationResult],
                          executionTimeMs: Long
                        )

case class ConceptResult(
                          conceptId: String,
                          lemma: String,
                          surface: String,
                          relevanceScore: Double
                        )

case class RelationResult(
                           source: String,
                           target: String,
                           predicate: String,
                           confidence: Double,
                           evidence: Option[String],
                           evidenceId: Option[String]
                         )

case class Neighborhood(
                         centerConcept: ConceptResult,
                         neighbors: Seq[NeighborNode],
                         edges: Seq[NeighborEdge]
                       )

case class NeighborNode(
                         conceptId: String,
                         lemma: String,
                         surface: String,
                         distance: Int
                       )

case class NeighborEdge(
                         source: String,
                         target: String,
                         relationType: String,
                         properties: Map[String, Any]
                       )

case class Evidence(
                     evidenceId: String,
                     text: String,
                     chunkId: String,
                     source: String
                   )

case class ExecutionTrace(
                           requestId: String,
                           query: String,
                           timestamp: Long,
                           steps: Seq[TraceStep],
                           totalTimeMs: Long
                         )

case class TraceStep(
                      stepName: String,
                      description: String,
                      durationMs: Long,
                      details: Map[String, Any]
                    )

case class ErrorResponse(
                          error: String,
                          message: String,
                          requestId: Option[String] = None
                        )


// ------------------------------------------------------------
// NEW EXPLAIN TRACE MODELS (MATCH EXACT JSON FORMAT)
// ------------------------------------------------------------
case class EvidenceDocRef(
                           title: String
                         )

case class ExpandedEvidence(
                             evidenceId: String,
                             paperId: String,
                             text: String,
                             docRef: EvidenceDocRef
                           )

case class PlanStep(
                     step: String,
                     description: String,
                     cypher: Option[String],
                     detail: String,
                     durationMs: Long
                   )

case class ExplainTrace(
                         query: String,
                         plan: Seq[PlanStep],
                         totalTimeMs: Long,
                         conceptsFound: Int,
                         relationsFound: Int,
                         evidenceChunks: Int
                       )


// ------------------------------------------------------------
// FINAL LLM RESPONSE MODELS
// ------------------------------------------------------------

case class FinalItem(
                      paperId: String,
                      title: String,
                      concepts: Seq[String],
                      citations: Seq[String]
                    )

case class FinalGroup(
                       items: Seq[FinalItem]
                     )

case class FinalQueryResponse(
                               mode: String,
                               answer: String,
                               groups: Seq[FinalGroup],
                               evidenceAvailable: Boolean
                             )

case class ExploreNode(
                        id: String
                      )

case class ExploreEdge(
                        from: String,
                        to: String
                      )

case class ExploreGraph(
                         nodes: Seq[ExploreNode],
                         edges: Seq[ExploreEdge]
                       )


// ------------------------------------------------------------
// ENCODERS
// ------------------------------------------------------------

object ApiModels {

  // Encoder for Map[String, Any]
  implicit val anyEncoder: Encoder[Any] = Encoder.instance {
    case s: String       => Json.fromString(s)
    case i: Int          => Json.fromInt(i)
    case l: Long         => Json.fromLong(l)
    case d: Double       => Json.fromDoubleOrNull(d)
    case b: Boolean      => Json.fromBoolean(b)
    case m: Map[_, _]    => Json.fromFields(m.asInstanceOf[Map[String, Any]].map {
      case (k, v) => k -> anyEncoder(v)
    })
    case seq: Seq[_]     => Json.fromValues(seq.map(anyEncoder.apply))
    case null            => Json.Null
    case other           => Json.fromString(other.toString)
  }

  implicit val mapEncoder: Encoder[Map[String, Any]] =
    Encoder.instance(map => Json.fromFields(map.map { case (k, v) => k -> anyEncoder(v) }))

  // Existing Models Encoders
  implicit val conceptResultEncoder: Encoder[ConceptResult] = deriveEncoder
  implicit val relationResultEncoder: Encoder[RelationResult] = deriveEncoder
  implicit val neighborNodeEncoder: Encoder[NeighborNode] = deriveEncoder
  implicit val neighborEdgeEncoder: Encoder[NeighborEdge] = deriveEncoder
  implicit val neighborhoodEncoder: Encoder[Neighborhood] = deriveEncoder
  implicit val evidenceEncoder: Encoder[Evidence] = deriveEncoder
  implicit val traceStepEncoder: Encoder[TraceStep] = deriveEncoder
  implicit val executionTraceEncoder: Encoder[ExecutionTrace] = deriveEncoder
  implicit val queryRequestEncoder: Encoder[QueryRequest] = deriveEncoder
  implicit val queryResponseEncoder: Encoder[QueryResponse] = deriveEncoder
  implicit val errorResponseEncoder: Encoder[ErrorResponse] = deriveEncoder

  // NEW Explain Trace Encoders
  implicit val planStepEncoder: Encoder[PlanStep] = deriveEncoder
  implicit val explainTraceEncoder: Encoder[ExplainTrace] = deriveEncoder

  // LLM Final Response Encoders
  implicit val finalItemEncoder: Encoder[FinalItem] = deriveEncoder
  implicit val finalGroupEncoder: Encoder[FinalGroup] = deriveEncoder
  implicit val finalQueryResponseEncoder: Encoder[FinalQueryResponse] = deriveEncoder
  implicit val evidenceDocRefEncoder: Encoder[EvidenceDocRef] = deriveEncoder
  implicit val expandedEvidenceEncoder: Encoder[ExpandedEvidence] = deriveEncoder
  implicit val exploreNodeEncoder: Encoder[ExploreNode] = deriveEncoder
  implicit val exploreEdgeEncoder: Encoder[ExploreEdge] = deriveEncoder
  implicit val exploreGraphEncoder: Encoder[ExploreGraph] = deriveEncoder


}
