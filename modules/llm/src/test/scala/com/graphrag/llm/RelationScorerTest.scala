package com.graphrag.llm

import com.graphrag.core.models.{Concept, ConceptPair, RelationCandidate, Relation}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RelationScorerTest extends AnyFlatSpec with Matchers {

  val concept1 = Concept("concept-1", "apache_flink", "Apache Flink", "NER")
  val concept2 = Concept("concept-2", "stream_processing", "stream processing", "NOUN_PHRASE")
  val conceptPair = ConceptPair(concept1, concept2, 3, Set("chunk-1"))
  val context = "Apache Flink is a framework for stream processing."

  "buildRelationPrompt" should "create prompt with concepts and context" in {
    val prompt = RelationScorer.buildRelationPrompt(
      concept1.surface,
      concept2.surface,
      context
    )

    prompt should include("Apache Flink")
    prompt should include("stream processing")
    prompt should include(context)
  }

  it should "truncate context to 1000 characters" in {
    val longContext = "x" * 2000
    val prompt = RelationScorer.buildRelationPrompt(
      "Concept1",
      "Concept2",
      longContext
    )

    prompt should include("x" * 1000)
    prompt should not include("x" * 2000)
  }

  it should "include relationship types" in {
    val prompt = RelationScorer.buildRelationPrompt("A", "B", "context")

    prompt should include("uses")
    prompt should include("depends_on")
    prompt should include("part_of")
  }

  it should "include confidence scoring" in {
    val prompt = RelationScorer.buildRelationPrompt("A", "B", "context")

    prompt should include("confidence")
    prompt should include("0.0 to 1.0")
  }

  it should "include direction specification" in {
    val prompt = RelationScorer.buildRelationPrompt("A", "B", "context")

    prompt should include("forward")
    prompt should include("reverse")
    prompt should include("direction")
  }

  it should "include JSON format" in {
    val prompt = RelationScorer.buildRelationPrompt("A", "B", "context")

    prompt should include("JSON")
    prompt should include("predicate")
    prompt should include("evidence")
  }

  it should "handle empty context" in {
    val prompt = RelationScorer.buildRelationPrompt("A", "B", "")

    prompt should not be empty
  }

  it should "handle special characters in concepts" in {
    val prompt = RelationScorer.buildRelationPrompt("C++", "Java", context)

    prompt should include("C++")
    prompt should include("Java")
  }

  "parseRelationFromResponse" should "parse valid JSON with relationship" in {
    val response =
      """{"predicate": "uses", "confidence": 0.85, "evidence": "Flink uses stream processing", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
    relation.get.predicate should be("uses")
    relation.get.confidence should be(0.85)
    relation.get.evidence should be("Flink uses stream processing")
    relation.get.source should be("concept-1")
    relation.get.target should be("concept-2")
    relation.get.origin should be("LLM")
  }

  it should "parse relationship with reverse direction" in {
    val response =
      """{"predicate": "part_of", "confidence": 0.9, "evidence": "Stream processing is part of Flink", "direction": "reverse"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
    relation.get.source should be("concept-2")
    relation.get.target should be("concept-1")
  }

  it should "handle null predicate" in {
    val response =
      """{"predicate": null, "confidence": 0.1, "evidence": "No relationship", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(None)
  }

  it should "handle response with markdown" in {
    val response =
      """```json
        |{"predicate": "implements", "confidence": 0.75, "evidence": "Evidence text", "direction": "forward"}
        |```""".stripMargin

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
    relation.get.predicate should be("implements")
  }

  it should "handle invalid JSON" in {
    val response = """{"predicate": "uses", invalid json}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(None)
  }

  it should "handle empty response" in {
    val relation = RelationScorer.parseRelationFromResponse(
      "",
      "concept-1",
      "concept-2"
    )

    relation should be(None)
  }

  it should "handle response with extra whitespace" in {
    val response = """

      {"predicate": "uses", "confidence": 0.8, "evidence": "text", "direction": "forward"}

    """

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
  }

  it should "set origin to LLM" in {
    val response =
      """{"predicate": "uses", "confidence": 0.8, "evidence": "text", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation.get.origin should be("LLM")
  }

  it should "handle high confidence values" in {
    val response =
      """{"predicate": "uses", "confidence": 1.0, "evidence": "text", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation.get.confidence should be(1.0)
  }

  it should "handle low confidence values" in {
    val response =
      """{"predicate": "uses", "confidence": 0.01, "evidence": "text", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation.get.confidence should be(0.01)
  }

  it should "handle various predicate types" in {
    val predicates = Seq("uses", "depends_on", "part_of", "implements", "extends", "calls", "contains")

    predicates.foreach { predicate =>
      val response =
        s"""{"predicate": "$predicate", "confidence": 0.8, "evidence": "text", "direction": "forward"}"""

      val relation = RelationScorer.parseRelationFromResponse(
        response,
        "concept-1",
        "concept-2"
      )

      relation should be(defined)
      relation.get.predicate should be(predicate)
    }
  }

  it should "handle long evidence text" in {
    val longEvidence = "This is a very long evidence text that spans multiple sentences. " * 10
    val response =
      s"""{"predicate": "uses", "confidence": 0.8, "evidence": "$longEvidence", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
    relation.get.evidence should include("very long evidence")
  }

  it should "handle Unicode in evidence" in {
    val response =
      """{"predicate": "uses", "confidence": 0.8, "evidence": "文本证据", "direction": "forward"}"""

    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(defined)
    relation.get.evidence should be("文本证据")
  }

  it should "handle missing optional fields gracefully" in {
    val response = """{"predicate": "uses", "confidence": 0.8}"""

    // Should fail parsing as evidence and direction are required
    val relation = RelationScorer.parseRelationFromResponse(
      response,
      "concept-1",
      "concept-2"
    )

    relation should be(None)
  }
}