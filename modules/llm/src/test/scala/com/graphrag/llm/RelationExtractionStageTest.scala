package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions, ConceptPair, Relation, ChunkWithId, PairWithChunk}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.flink.api.java.functions.KeySelector

class RelationExtractionStageTest extends AnyFlatSpec with Matchers {

  val concept1 = Concept("concept-1", "apache_flink", "Apache Flink", "NER")
  val concept2 = Concept("concept-2", "stream_processing", "stream processing", "NOUN_PHRASE")
  val mention1 = Mentions("chunk-001", concept1)
  val mention2 = Mentions("chunk-001", concept2)

  val sampleChunk = Chunk(
    chunkId = "chunk-001",
    docId = "doc-001",
    span = (0, 100),
    text = "Apache Flink is a stream processing framework.",
    sourceUri = "file:///test.txt",
    hash = "hash123"
  )

  "MentionChunkIdKeySelector" should "extract chunkId as key" in {
    val keySelector = new RelationExtractionStage.MentionChunkIdKeySelector()
    val key = keySelector.getKey(mention1)

    key should be("chunk-001")
  }

  it should "be consistent for same chunkId" in {
    val keySelector = new RelationExtractionStage.MentionChunkIdKeySelector()
    val key1 = keySelector.getKey(mention1)
    val key2 = keySelector.getKey(mention2)

    key1 should be(key2)
  }



  "ChunkWithIdKeySelector" should "extract chunkId from ChunkWithId" in {
    val chunkWithId = ChunkWithId("chunk-001", "text")
    val keySelector = new RelationExtractionStage.ChunkWithIdKeySelector()

    val key = keySelector.getKey(chunkWithId)

    key should be("chunk-001")
  }


  "PairChunkIdKeySelector" should "extract chunkId from PairWithChunk" in {
    val pair = ConceptPair(concept1, concept2, 1, Set("chunk-001"))
    val pairWithChunk = PairWithChunk("chunk-001", pair)
    val keySelector = new RelationExtractionStage.PairChunkIdKeySelector()

    val key = keySelector.getKey(pairWithChunk)

    key should be("chunk-001")
  }


  "ChunkToChunkWithIdMapper" should "convert Chunk to ChunkWithId" in {
    val mapper = new RelationExtractionStage.ChunkToChunkWithIdMapper()
    val result = mapper.map(sampleChunk)

    result.chunkId should be("chunk-001")
    result.text should be(sampleChunk.text)
  }

  it should "preserve text content" in {
    val mapper = new RelationExtractionStage.ChunkToChunkWithIdMapper()
    val result = mapper.map(sampleChunk)

    result.text should be("Apache Flink is a stream processing framework.")
  }



  "PairToPairWithChunkMapper" should "convert ConceptPair to PairWithChunk" in {
    val pair = ConceptPair(concept1, concept2, 1, Set("chunk-001", "chunk-002"))
    val mapper = new RelationExtractionStage.PairToPairWithChunkMapper()

    val result = mapper.map(pair)

    result.chunkId should be("chunk-001")  // Takes first chunk
    result.pair should be(pair)
  }

  it should "use first chunkId from set" in {
    val pair = ConceptPair(concept1, concept2, 1, Set("chunk-001"))
    val mapper = new RelationExtractionStage.PairToPairWithChunkMapper()

    val result = mapper.map(pair)

    result.chunkId should be("chunk-001")
  }


  "ConfidenceFilter" should "filter relations with confidence > 0.5" in {
    val filter = new RelationExtractionStage.ConfidenceFilter()

    val highConfidence = Relation("c1", "c2", "uses", 0.8, "evidence", "LLM")
    val lowConfidence = Relation("c1", "c2", "uses", 0.3, "evidence", "LLM")

    filter.filter(highConfidence) should be(true)
    filter.filter(lowConfidence) should be(false)
  }

  it should "reject relations with confidence = 0.5" in {
    val filter = new RelationExtractionStage.ConfidenceFilter()
    val relation = Relation("c1", "c2", "uses", 0.5, "evidence", "LLM")

    filter.filter(relation) should be(false)
  }

  it should "accept relations with confidence just above 0.5" in {
    val filter = new RelationExtractionStage.ConfidenceFilter()
    val relation = Relation("c1", "c2", "uses", 0.51, "evidence", "LLM")

    filter.filter(relation) should be(true)
  }

  it should "reject relations with confidence = 0.0" in {
    val filter = new RelationExtractionStage.ConfidenceFilter()
    val relation = Relation("c1", "c2", "uses", 0.0, "evidence", "LLM")

    filter.filter(relation) should be(false)
  }

  it should "accept relations with confidence = 1.0" in {
    val filter = new RelationExtractionStage.ConfidenceFilter()
    val relation = Relation("c1", "c2", "uses", 1.0, "evidence", "LLM")

    filter.filter(relation) should be(true)
  }


}