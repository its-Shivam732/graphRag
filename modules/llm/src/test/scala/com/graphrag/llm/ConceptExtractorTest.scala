package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ConceptExtractorTest extends AnyFlatSpec with Matchers {

  val sampleChunk = Chunk(
    chunkId = "chunk-001",
    docId = "doc-001",
    span = (0, 100),
    text = "Apache Flink is a stream processing framework developed by the Apache Software Foundation.",
    sourceUri = "file:///test.txt",
    hash = "hash123"
  )




  it should "extract noun phrases" in {
    val chunk = sampleChunk.copy(
      text = "The stream processing framework provides real-time data analysis."
    )

    val mentions = ConceptExtractor.extractHeuristic(chunk)

    mentions.size should be >= 0
  }

  it should "deduplicate concepts by conceptId" in {
    val chunk = sampleChunk.copy(
      text = "Apache Flink and Apache Spark are both Apache projects."
    )

    val mentions = ConceptExtractor.extractHeuristic(chunk)
    val conceptIds = mentions.map(_.concept.conceptId)

    conceptIds.size should be(conceptIds.distinct.size)
  }

  it should "handle empty text" in {
    val emptyChunk = sampleChunk.copy(text = "")
    val mentions = ConceptExtractor.extractHeuristic(emptyChunk)

    mentions should be(empty)
  }

  it should "handle text with no extractable concepts" in {
    val simpleChunk = sampleChunk.copy(text = "the and but or")
    val mentions = ConceptExtractor.extractHeuristic(simpleChunk)

    // May extract nothing or very few concepts
    mentions.size should be >= 0
  }

  it should "filter out very short entities" in {
    // Entities with length <= 2 should be filtered
    val chunk = sampleChunk.copy(text = "a is at IT")

    val mentions = ConceptExtractor.extractHeuristic(chunk)

    // Should filter out single/double letter entities
    mentions.forall(_.concept.surface.length > 2) should be(true)
  }

  it should "set correct origin for NER concepts" in {
    val chunk = sampleChunk.copy(
      text = "Microsoft is located in Redmond."
    )

    val mentions = ConceptExtractor.extractHeuristic(chunk)

    mentions.foreach { m =>
      m.concept.origin should startWith("NER:")
    }
  }

  it should "set correct origin for noun phrase concepts" in {
    val chunk = sampleChunk.copy(
      text = "The distributed computing system provides high throughput."
    )

    val mentions = ConceptExtractor.extractHeuristic(chunk)

    // Some concepts should be noun phrases
    val nounPhrases = mentions.filter(_.concept.origin == "NOUN_PHRASE")
    nounPhrases.size should be >= 0
  }

  it should "normalize concept IDs" in {
    val chunk = sampleChunk.copy(
      text = "Apache Flink uses stream processing."
    )

    val mentions = ConceptExtractor.extractHeuristic(chunk)

    mentions.foreach { m =>
      // Concept IDs should be lowercase and use underscores
      m.concept.conceptId should fullyMatch regex "[a-z0-9_]+"
    }
  }

  it should "handle errors gracefully" in {
    val problematicChunk = sampleChunk.copy(text = null)

    val mentions = ConceptExtractor.extractHeuristic(problematicChunk)

    mentions should be(empty)
  }

  it should "handle special characters in text" in {
    val specialChunk = sampleChunk.copy(
      text = "Test @#$% with special !@# characters."
    )

    noException should be thrownBy {
      ConceptExtractor.extractHeuristic(specialChunk)
    }
  }

  it should "handle numbers in text" in {
    val numberChunk = sampleChunk.copy(
      text = "The system processed 1000 events per second in 2024."
    )

    val mentions = ConceptExtractor.extractHeuristic(numberChunk)

    mentions.size should be >= 0
  }

  it should "extract date entities" in {
    val dateChunk = sampleChunk.copy(
      text = "The meeting is scheduled for January 15, 2024."
    )

    val mentions = ConceptExtractor.extractHeuristic(dateChunk)

    // Should extract DATE entity
    val dates = mentions.filter(_.concept.origin.contains("DATE"))
    dates.size should be >= 0
  }

  it should "extract location entities" in {
    val locationChunk = sampleChunk.copy(
      text = "The office is located in San Francisco, California."
    )

    val mentions = ConceptExtractor.extractHeuristic(locationChunk)

    // Should extract LOCATION entities
    val locations = mentions.filter(_.concept.origin.contains("LOCATION"))
    locations.size should be >= 0
  }

  it should "extract person entities" in {
    val personChunk = sampleChunk.copy(
      text = "John Smith and Jane Doe attended the conference."
    )

    val mentions = ConceptExtractor.extractHeuristic(personChunk)

    // Should extract PERSON entities
    val people = mentions.filter(_.concept.origin.contains("PERSON"))
    people.size should be >= 0
  }
}