package com.graphrag.neo4j

import com.graphrag.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Pure unit tests for GraphProjectionStage helper mappers.
 * No Flink runtime, no Neo4j driver needed.
 */
class GraphProjectionStageTest extends AnyFlatSpec with Matchers {

  // ----------------------------------------------------------------------
  // Test Fixtures
  // ----------------------------------------------------------------------

  val sampleChunk = Chunk(
    chunkId = "chunk-001",
    docId = "doc-001",
    span = (0, 100),
    text = "Artificial intelligence is transforming technology.",
    sourceUri = "file:///data/article.txt",
    hash = "abc123def456"
  )

  val sampleConcept = Concept(
    conceptId = "concept-ai",
    lemma = "artificial_intelligence",
    surface = "Artificial Intelligence",
    origin = "NER"
  )

  val sampleMentions = Mentions(
    chunkId = "chunk-001",
    concept = sampleConcept
  )

  val sampleConceptPair = ConceptPair(
    concept1 = sampleConcept,
    concept2 = Concept("concept-ml", "machine_learning", "Machine Learning", "NER"),
    cooccurrenceCount = 5,
    chunkIds = Set("chunk-001", "chunk-002")
  )

  val sampleRelation = Relation(
    source = "concept-ai",
    target = "concept-ml",
    predicate = "isPartOf",
    confidence = 0.92,
    evidence = "AI is a branch of machine learning",
    origin = "LLM"
  )

  // ----------------------------------------------------------------------
  // ChunkToNodeMapper
  // ----------------------------------------------------------------------

  "ChunkToNodeMapper" should "convert Chunk to GraphNode" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(sampleChunk)

    node.id shouldBe "chunk-001"
    node.labels should contain("Chunk")
  }

  it should "include expected properties" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(sampleChunk)

    node.properties should contain key "text"
    node.properties should contain key "docId"
    node.properties should contain key "chunkId"
    node.properties("offset") shouldBe 0
    node.properties("length") shouldBe 100
  }

  // ----------------------------------------------------------------------
  // MentionToConceptMapper
  // ----------------------------------------------------------------------

  "MentionToConceptMapper" should "extract Concept from Mentions" in {
    val mapper = new GraphProjectionStage.MentionToConceptMapper()
    val concept = mapper.map(sampleMentions)

    concept.conceptId shouldBe "concept-ai"
    concept.lemma shouldBe "artificial_intelligence"
    concept.surface shouldBe "Artificial Intelligence"
  }

  // ----------------------------------------------------------------------
  // ConceptIdKeySelector
  // ----------------------------------------------------------------------

  "ConceptIdKeySelector" should "return conceptId" in {
    val keySelector = new GraphProjectionStage.ConceptIdKeySelector()
    keySelector.getKey(sampleConcept) shouldBe "concept-ai"
  }

  // ----------------------------------------------------------------------
  // ConceptDeduplicator
  // ----------------------------------------------------------------------

  "ConceptDeduplicator" should "retain first concept" in {
    val dedup = new GraphProjectionStage.ConceptDeduplicator()
    val c1 = sampleConcept.copy(surface = "First")
    val c2 = sampleConcept.copy(surface = "Second")

    val result = dedup.reduce(c1, c2)
    result.surface shouldBe "First"
  }

  // ----------------------------------------------------------------------
  // ConceptToNodeMapper
  // ----------------------------------------------------------------------

  "ConceptToNodeMapper" should "convert Concept to GraphNode" in {
    val mapper = new GraphProjectionStage.ConceptToNodeMapper()
    val node = mapper.map(sampleConcept)

    node.id shouldBe "concept-ai"
    node.labels should contain("Concept")
    node.properties("lemma") shouldBe "artificial_intelligence"
  }

  // ----------------------------------------------------------------------
  // MentionToEdgeMapper
  // ----------------------------------------------------------------------

  "MentionToEdgeMapper" should "map Mentions to MENTIONS edge" in {
    val mapper = new GraphProjectionStage.MentionToEdgeMapper()
    val edge = mapper.map(sampleMentions)

    edge.sourceId shouldBe "chunk-001"
    edge.targetId shouldBe "concept-ai"
    edge.relationType shouldBe "MENTIONS"
  }

  // ----------------------------------------------------------------------
  // CooccurToEdgeMapper
  // ----------------------------------------------------------------------

  "CooccurToEdgeMapper" should "map ConceptPair to CO_OCCURS edge" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(sampleConceptPair)

    edge.relationType shouldBe "CO_OCCURS"
    edge.properties("count") shouldBe 5
  }

  // ----------------------------------------------------------------------
  // RelationToEdgeMapper
  // ----------------------------------------------------------------------

  "RelationToEdgeMapper" should "map Relation to RELATES_TO edge" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.sourceId shouldBe "concept-ai"
    edge.targetId shouldBe "concept-ml"
    edge.relationType shouldBe "RELATES_TO"
    edge.properties("predicate") shouldBe "isPartOf"
  }

  // ----------------------------------------------------------------------
  // ConceptPair.normalized
  // ----------------------------------------------------------------------

  "ConceptPair.normalized" should "alphabetically order conceptIds" in {
    val unordered = ConceptPair(
      concept1 = sampleConcept.copy(conceptId = "zzz"),
      concept2 = sampleConcept.copy(conceptId = "aaa"),
      2,
      Set.empty
    )

    val normalized = unordered.normalized
    normalized.concept1.conceptId shouldBe "aaa"
    normalized.concept2.conceptId shouldBe "zzz"
  }

}
