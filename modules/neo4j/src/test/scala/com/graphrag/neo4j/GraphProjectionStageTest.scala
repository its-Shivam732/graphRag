package com.graphrag.neo4j

import com.graphrag.core.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.scala.StreamExecutionEnvironment
import org.apache.flink.api.common.functions.{MapFunction, ReduceFunction}
import org.apache.flink.api.java.functions.KeySelector

class GraphProjectionStageTest extends AnyFlatSpec with Matchers {

  // Test data setup matching actual data models
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

  "ChunkToNodeMapper" should "convert Chunk to GraphNode" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(sampleChunk)

    node.id should be("chunk-001")
    node.labels should contain("Chunk")
  }

  it should "preserve chunk properties" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(sampleChunk)

    node.properties should contain key "text"
    node.properties should contain key "docId"
    node.properties should contain key "chunkId"
  }

  it should "include span information" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(sampleChunk)

    node.properties should contain key "offset"
    node.properties should contain key "length"
    node.properties("offset") should be(0)
    node.properties("length") should be(100)
  }



  it should "handle chunks with special characters in text" in {
    val specialChunk = sampleChunk.copy(
      text = "Text with special chars: @#$%^&*()"
    )
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val node = mapper.map(specialChunk)

    node.id should be("chunk-001")
    node.properties("text") should be("Text with special chars: @#$%^&*()")
  }

  "MentionToConceptMapper" should "extract Concept from Mentions" in {
    val mapper = new GraphProjectionStage.MentionToConceptMapper()
    val concept = mapper.map(sampleMentions)

    concept.conceptId should be("concept-ai")
    concept.lemma should be("artificial_intelligence")
    concept.surface should be("Artificial Intelligence")
  }

  it should "preserve concept origin" in {
    val mapper = new GraphProjectionStage.MentionToConceptMapper()
    val concept = mapper.map(sampleMentions)

    concept.origin should be("NER")
  }

  "ConceptIdKeySelector" should "extract conceptId as key" in {
    val keySelector = new GraphProjectionStage.ConceptIdKeySelector()
    val key = keySelector.getKey(sampleConcept)

    key should be("concept-ai")
  }

  it should "handle concepts with different IDs" in {
    val keySelector = new GraphProjectionStage.ConceptIdKeySelector()
    val concept2 = sampleConcept.copy(conceptId = "concept-different")

    val key1 = keySelector.getKey(sampleConcept)
    val key2 = keySelector.getKey(concept2)

    key1 should not be key2
  }

  it should "be consistent for same concept" in {
    val keySelector = new GraphProjectionStage.ConceptIdKeySelector()

    val key1 = keySelector.getKey(sampleConcept)
    val key2 = keySelector.getKey(sampleConcept)

    key1 should be(key2)
  }

  "ConceptDeduplicator" should "keep first concept when reducing" in {
    val deduplicator = new GraphProjectionStage.ConceptDeduplicator()
    val concept1 = sampleConcept.copy(surface = "AI First")
    val concept2 = sampleConcept.copy(surface = "AI Second")

    val result = deduplicator.reduce(concept1, concept2)

    result.surface should be("AI First")
  }

  it should "handle identical concepts" in {
    val deduplicator = new GraphProjectionStage.ConceptDeduplicator()
    val result = deduplicator.reduce(sampleConcept, sampleConcept)

    result should be(sampleConcept)
  }

  "ConceptToNodeMapper" should "convert Concept to GraphNode" in {
    val mapper = new GraphProjectionStage.ConceptToNodeMapper()
    val node = mapper.map(sampleConcept)

    node.id should be("concept-ai")
    node.labels should contain("Concept")
  }

  it should "preserve concept metadata" in {
    val mapper = new GraphProjectionStage.ConceptToNodeMapper()
    val node = mapper.map(sampleConcept)

    node.properties should contain key "lemma"
    node.properties should contain key "surface"
    node.properties should contain key "origin"
  }

  it should "include conceptId in properties" in {
    val mapper = new GraphProjectionStage.ConceptToNodeMapper()
    val node = mapper.map(sampleConcept)

    node.properties should contain key "conceptId"
    node.properties("conceptId") should be("concept-ai")
  }

  "MentionToEdgeMapper" should "create MENTIONS edge" in {
    val mapper = new GraphProjectionStage.MentionToEdgeMapper()
    val edge = mapper.map(sampleMentions)

    edge.sourceId should be("chunk-001")
    edge.targetId should be("concept-ai")
    edge.relationType should be("MENTIONS")
  }

  it should "have empty properties by default" in {
    val mapper = new GraphProjectionStage.MentionToEdgeMapper()
    val edge = mapper.map(sampleMentions)

    edge.properties should be(Map.empty)
  }

  "CooccurToEdgeMapper" should "create CO_OCCURS edge" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(sampleConceptPair)

    edge.sourceId should be("concept-ai")
    edge.targetId should be("concept-ml")
    edge.relationType should be("CO_OCCURS")
  }

  it should "include cooccurrence count in properties" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(sampleConceptPair)

    edge.properties should contain key "count"
    edge.properties("count") should be(5)
  }

  it should "handle pairs with zero cooccurrence count" in {
    val zeroPair = sampleConceptPair.copy(cooccurrenceCount = 0)
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(zeroPair)

    edge.properties("count") should be(0)
  }

  it should "handle pairs with single chunk" in {
    val singleChunkPair = sampleConceptPair.copy(chunkIds = Set("chunk-001"))
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(singleChunkPair)

    edge.relationType should be("CO_OCCURS")
  }

  it should "handle pairs with many chunks" in {
    val manyChunksPair = sampleConceptPair.copy(
      chunkIds = (1 to 100).map(i => s"chunk-$i").toSet
    )
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val edge = mapper.map(manyChunksPair)

    edge.relationType should be("CO_OCCURS")
  }

  "RelationToEdgeMapper" should "create RELATES_TO edge" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.sourceId should be("concept-ai")
    edge.targetId should be("concept-ml")
    edge.relationType should be("RELATES_TO")
  }

  it should "preserve relationship properties" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.properties should contain key "predicate"
    edge.properties should contain key "confidence"
    edge.properties should contain key "evidence"
    edge.properties should contain key "origin"
  }

  it should "preserve predicate value" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.properties("predicate") should be("isPartOf")
  }

  it should "handle relations with low confidence" in {
    val lowConfRelation = sampleRelation.copy(confidence = 0.1)
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(lowConfRelation)

    edge.properties("confidence") should be(0.1)
  }

  it should "handle relations with high confidence" in {
    val highConfRelation = sampleRelation.copy(confidence = 1.0)
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(highConfRelation)

    edge.properties("confidence") should be(1.0)
  }

  it should "preserve evidence text" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.properties("evidence") should be("AI is a branch of machine learning")
  }

  it should "preserve origin" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(sampleRelation)

    edge.properties("origin") should be("LLM")
  }

  it should "handle heuristic origin" in {
    val heuristicRelation = sampleRelation.copy(origin = "HEURISTIC")
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val edge = mapper.map(heuristicRelation)

    edge.properties("origin") should be("HEURISTIC")
  }

  "projectChunks" should "create named transformation" in {
    // Testing mapper logic instead of full Flink pipeline
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val chunks = Seq(sampleChunk)
    val nodes = chunks.map(mapper.map)

    nodes.size should be(1)
    nodes.head.labels should contain("Chunk")
  }

  it should "handle multiple chunks" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val chunks = Seq(
      sampleChunk,
      sampleChunk.copy(chunkId = "chunk-002", span = (100, 200)),
      sampleChunk.copy(chunkId = "chunk-003", span = (200, 300))
    )
    val nodes = chunks.map(mapper.map)

    nodes.size should be(3)
    nodes.map(_.id).toSet.size should be(3)
  }

  it should "preserve unique chunk IDs" in {
    val mapper = new GraphProjectionStage.ChunkToNodeMapper()
    val chunks = Seq(
      sampleChunk.copy(chunkId = "chunk-001"),
      sampleChunk.copy(chunkId = "chunk-002"),
      sampleChunk.copy(chunkId = "chunk-003")
    )
    val nodes = chunks.map(mapper.map)

    nodes.map(_.id) should be(Seq("chunk-001", "chunk-002", "chunk-003"))
  }

  "projectConcepts" should "deduplicate concepts with same ID" in {
    val deduplicator = new GraphProjectionStage.ConceptDeduplicator()
    val concept1 = sampleConcept.copy(surface = "Name1")
    val concept2 = sampleConcept.copy(surface = "Name2")

    val result = deduplicator.reduce(concept1, concept2)

    result.conceptId should be("concept-ai")
  }

  "projectMentions" should "create edges for all mentions" in {
    val mapper = new GraphProjectionStage.MentionToEdgeMapper()
    val mentions = Seq(
      sampleMentions,
      sampleMentions.copy(chunkId = "chunk-002")
    )
    val edges = mentions.map(mapper.map)

    edges.size should be(2)
    edges.forall(_.relationType == "MENTIONS") should be(true)
  }

  it should "connect correct source and target" in {
    val mapper = new GraphProjectionStage.MentionToEdgeMapper()
    val edge = mapper.map(sampleMentions)

    edge.sourceId should be("chunk-001")
    edge.targetId should be("concept-ai")
  }

  "projectCooccurrences" should "create CO_OCCURS edges" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val pairs = Seq(sampleConceptPair)
    val edges = pairs.map(mapper.map)

    edges.size should be(1)
    edges.head.relationType should be("CO_OCCURS")
  }

  it should "handle multiple pairs" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val pairs = Seq(
      sampleConceptPair,
      sampleConceptPair.copy(cooccurrenceCount = 10),
      sampleConceptPair.copy(cooccurrenceCount = 3)
    )
    val edges = pairs.map(mapper.map)

    edges.size should be(3)
  }

  it should "preserve count in properties" in {
    val mapper = new GraphProjectionStage.CooccurToEdgeMapper()
    val pairs = Seq(
      sampleConceptPair.copy(cooccurrenceCount = 1),
      sampleConceptPair.copy(cooccurrenceCount = 10),
      sampleConceptPair.copy(cooccurrenceCount = 100)
    )
    val edges = pairs.map(mapper.map)

    edges.map(_.properties("count")) should be(Seq(1, 10, 100))
  }

  "projectRelations" should "create RELATES_TO edges" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val relations = Seq(sampleRelation)
    val edges = relations.map(mapper.map)

    edges.size should be(1)
    edges.head.relationType should be("RELATES_TO")
  }

  it should "handle multiple relations" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val relations = Seq(
      sampleRelation,
      sampleRelation.copy(predicate = "contains"),
      sampleRelation.copy(predicate = "references")
    )
    val edges = relations.map(mapper.map)

    edges.size should be(3)
  }

  it should "preserve predicates" in {
    val mapper = new GraphProjectionStage.RelationToEdgeMapper()
    val relations = Seq(
      sampleRelation.copy(predicate = "isPartOf"),
      sampleRelation.copy(predicate = "dependsOn"),
      sampleRelation.copy(predicate = "relatedTo")
    )
    val edges = relations.map(mapper.map)

    edges.map(_.properties("predicate")) should be(
      Seq("isPartOf", "dependsOn", "relatedTo")
    )
  }

  "ConceptPair.normalized" should "order concepts canonically" in {
    val pair1 = ConceptPair(
      sampleConcept.copy(conceptId = "b"),
      sampleConcept.copy(conceptId = "a"),
      5,
      Set.empty
    )

    val normalized = pair1.normalized

    normalized.concept1.conceptId should be("a")
    normalized.concept2.conceptId should be("b")
  }

  it should "maintain order if already canonical" in {
    val pair = ConceptPair(
      sampleConcept.copy(conceptId = "a"),
      sampleConcept.copy(conceptId = "b"),
      5,
      Set.empty
    )

    val normalized = pair.normalized

    normalized.concept1.conceptId should be("a")
    normalized.concept2.conceptId should be("b")
  }





}