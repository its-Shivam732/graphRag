package com.graphrag.core

import com.graphrag.core.analysis.CooccurrenceDetector
import com.graphrag.core.models.{Concept, ConceptPair}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class CooccurrenceDetectorTest extends AnyFlatSpec with Matchers {

  // Test data setup
  val concept1 = Concept("concept-1", "artificial_intelligence", "AI", "NER")
  val concept2 = Concept("concept-2", "machine_learning", "ML", "NER")
  val concept3 = Concept("concept-3", "deep_learning", "DL", "NER")
  val concept4 = Concept("concept-4", "neural_networks", "NN", "NER")
  val concept5 = Concept("concept-5", "data_science", "DS", "NER")

  "findCooccurrences" should "detect concepts co-occurring within window" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept3),
      "chunk-3" -> Seq(concept2, concept3)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 3,
      minCooccurrence = 1
    )

    result should not be empty
    // All pairs should be detected within window of 3
    result.map(p => Set(p.concept1.conceptId, p.concept2.conceptId)) should contain allOf(
      Set("concept-1", "concept-2"),
      Set("concept-1", "concept-3"),
      Set("concept-2", "concept-3")
    )
  }

  it should "count co-occurrences correctly with sliding window" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    // concept1 and concept2 appear together in 3 chunks
    // With window=2, we'll see them in windows: [1,2], [2,3]
    val pair = result.find(p =>
      Set(p.concept1.conceptId, p.concept2.conceptId) == Set("concept-1", "concept-2")
    )
    pair should be(defined)
    pair.get.cooccurrenceCount should be >= 1
  }

  it should "respect minimum cooccurrence threshold" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept3, concept4),
      "chunk-4" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 2
    )

    // All pairs should have count >= 2
    result.forall(_.cooccurrenceCount >= 2) should be(true)
  }

  it should "filter out pairs below minimum threshold" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 2
    )

    // No pair appears twice, so result should be empty
    result should be(empty)
  }

  it should "sort results by cooccurrence count descending" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2),
      "chunk-4" -> Seq(concept3, concept4),
      "chunk-5" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 5,
      minCooccurrence = 1
    )

    // Should be sorted by count descending
    result.map(_.cooccurrenceCount) should be(
      result.map(_.cooccurrenceCount).sorted.reverse
    )
  }

  it should "handle empty input" in {
    val result = CooccurrenceDetector.findCooccurrences(
      Map.empty,
      windowSize = 3,
      minCooccurrence = 1
    )

    result should be(empty)
  }

  it should "handle single chunk" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 3,
      minCooccurrence = 1
    )

    result should not be empty
    result.head.chunkIds should contain("chunk-1")
  }

  it should "handle chunk with single concept" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1),
      "chunk-2" -> Seq(concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    // Can't form pairs from single concepts, but they're in same window
    result should not be empty
  }

  it should "deduplicate concepts within window" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept1, concept2),  // Duplicate concept1
      "chunk-2" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    // Should still detect the pair only once per window
    result should not be empty
  }

  it should "accumulate chunk IDs correctly" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 3,
      minCooccurrence = 1
    )

    val pair = result.head
    pair.chunkIds should contain allOf("chunk-1", "chunk-2", "chunk-3")
  }

  it should "use canonical ordering for concept pairs" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept2, concept1)  // Reversed order
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    // Should only have one pair, not two
    val pairs12 = result.filter(p =>
      Set(p.concept1.conceptId, p.concept2.conceptId) == Set("concept-1", "concept-2")
    )
    pairs12.size should be(1)
  }

  it should "handle different window sizes" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1),
      "chunk-2" -> Seq(concept2),
      "chunk-3" -> Seq(concept3),
      "chunk-4" -> Seq(concept1)
    )

    val resultWindow2 = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    val resultWindow4 = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 4,
      minCooccurrence = 1
    )

    // Larger window should potentially detect more pairs
    resultWindow4.size should be >= resultWindow2.size
  }

  it should "handle window size of 1" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 1,
      minCooccurrence = 1
    )

    result should not be empty
  }

  it should "handle very large window size" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 100,
      minCooccurrence = 1
    )

    result should not be empty
  }

  it should "maintain sorted chunk IDs for consistent windows" in {
    val mentionsByChunk = Map(
      "chunk-3" -> Seq(concept1),
      "chunk-1" -> Seq(concept2),
      "chunk-2" -> Seq(concept3)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    // Should process chunks in sorted order: chunk-1, chunk-2, chunk-3
    result should not be empty
  }

  "findIntraChunkCooccurrences" should "detect concepts within same chunk" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2, concept3)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    result should not be empty
    // Should have 3 pairs: (1,2), (1,3), (2,3)
    result.size should be(3)
  }

  it should "count occurrences across multiple chunks" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    val pair = result.head
    pair.cooccurrenceCount should be(3)
  }

  it should "respect minimum cooccurrence threshold" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 2
    )

    // No pair appears twice
    result should be(empty)
  }

  it should "only detect concepts in same chunk" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1),
      "chunk-2" -> Seq(concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // Concepts are in different chunks
    result should be(empty)
  }

  it should "handle chunk with no concepts" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq.empty[Concept],
      "chunk-2" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    result should not be empty
    result.size should be(1)  // Only one pair from chunk-2
  }

  it should "deduplicate concepts within chunk" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept1, concept2, concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // Should only create one pair, not count duplicates multiple times
    result.size should be(1)
  }

  it should "sort results by cooccurrence count descending" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2),
      "chunk-4" -> Seq(concept3, concept4),
      "chunk-5" -> Seq(concept3, concept4)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    result.map(_.cooccurrenceCount) should be(
      result.map(_.cooccurrenceCount).sorted.reverse
    )
  }

  it should "accumulate chunk IDs correctly" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept1, concept2),
      "chunk-3" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    val pair = result.head
    pair.chunkIds should contain allOf("chunk-1", "chunk-2", "chunk-3")
  }

  it should "handle empty input" in {
    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      Map.empty,
      minCooccurrence = 1
    )

    result should be(empty)
  }

  it should "handle chunk with single concept" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // Can't form pairs from single concept
    result should be(empty)
  }

  it should "handle many concepts in one chunk" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2, concept3, concept4, concept5)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // 5 concepts should produce C(5,2) = 10 pairs
    result.size should be(10)
  }

  it should "use canonical ordering for concept pairs" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept2, concept1)  // Reversed alphabetical order
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    result.size should be(1)
    // Pair should be created regardless of input order
  }





  "Integration tests" should "combine windowed and intra-chunk detection" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2),
      "chunk-2" -> Seq(concept2, concept3),
      "chunk-3" -> Seq(concept3, concept4)
    )

    val windowedResult = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1
    )

    val intraResult = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // Both should find pairs, but counts may differ
    windowedResult should not be empty
    intraResult should not be empty
  }

  it should "handle realistic scenario with multiple chunks" in {
    val mentionsByChunk = Map(
      "doc1-chunk1" -> Seq(concept1, concept2),
      "doc1-chunk2" -> Seq(concept1, concept3),
      "doc1-chunk3" -> Seq(concept2, concept3),
      "doc2-chunk1" -> Seq(concept1, concept2),
      "doc2-chunk2" -> Seq(concept2, concept4)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 3,
      minCooccurrence = 2
    )

    result should not be empty
    // concept1 and concept2 appear together in multiple places
    val pair12 = result.find(p =>
      Set(p.concept1.conceptId, p.concept2.conceptId) == Set("concept-1", "concept-2")
    )
    pair12 should be(defined)
  }

  "Performance tests" should "handle large number of chunks efficiently" in {
    val mentionsByChunk = (1 to 100).map { i =>
      s"chunk-$i" -> Seq(concept1, concept2)
    }.toMap

    val start = System.currentTimeMillis()
    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 10,
      minCooccurrence = 5
    )
    val duration = System.currentTimeMillis() - start

    result should not be empty
    duration should be < 2000L  // Should complete in less than 2 seconds
  }

  it should "handle many concepts per chunk efficiently" in {
    val concepts = (1 to 50).map { i =>
      Concept(s"concept-$i", s"lemma-$i", s"Surface $i", "NER")
    }

    val mentionsByChunk = Map(
      "chunk-1" -> concepts
    )

    val start = System.currentTimeMillis()
    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )
    val duration = System.currentTimeMillis() - start

    // C(50,2) = 1225 pairs
    result.size should be(1225)
    duration should be < 1000L
  }

  "Edge cases" should "handle minimum cooccurrence of 0" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 0
    )

    result should not be empty
  }

  it should "handle very high minimum cooccurrence" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept2)
    )

    val result = CooccurrenceDetector.findCooccurrences(
      mentionsByChunk,
      windowSize = 2,
      minCooccurrence = 1000
    )

    result should be(empty)
  }

  it should "handle chunks with same concept multiple times" in {
    val mentionsByChunk = Map(
      "chunk-1" -> Seq(concept1, concept1, concept1, concept2)
    )

    val result = CooccurrenceDetector.findIntraChunkCooccurrences(
      mentionsByChunk,
      minCooccurrence = 1
    )

    // Should deduplicate and create only one pair
    result.size should be(1)
  }

}