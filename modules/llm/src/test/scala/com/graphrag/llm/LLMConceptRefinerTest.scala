package com.graphrag.llm

import com.graphrag.core.models.Concept
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class LLMConceptRefinerTest extends AnyFlatSpec with Matchers {

  val existingConcepts = Seq(
    Concept("concept-1", "apache_flink", "Apache Flink", "NER"),
    Concept("concept-2", "stream_processing", "stream processing", "NOUN_PHRASE")
  )

  "buildPrompt" should "create prompt with existing concepts" in {
    val text = "Apache Flink is a distributed stream processing framework."
    val prompt = LLMConceptRefiner.buildPrompt(text, existingConcepts)

    prompt should include("Apache Flink")
    prompt should include("stream processing")
    prompt should include(text)
  }

  it should "create prompt with no existing concepts" in {
    val text = "Sample text for processing."
    val prompt = LLMConceptRefiner.buildPrompt(text, Seq.empty)

    prompt should include("None identified yet")
    prompt should include(text)
  }

  it should "truncate long text to 800 characters" in {
    val longText = "x" * 1000
    val prompt = LLMConceptRefiner.buildPrompt(longText, Seq.empty)

    // Should only include first 800 characters of text
    prompt should include("x" * 800)
    prompt should not include("x" * 1000)
  }

  it should "include JSON output format" in {
    val text = "Sample text"
    val prompt = LLMConceptRefiner.buildPrompt(text, Seq.empty)

    prompt should include("JSON")
    prompt should include("concepts")
    prompt should include("surface")
    prompt should include("lemma")
  }

  it should "include extraction rules" in {
    val text = "Sample text"
    val prompt = LLMConceptRefiner.buildPrompt(text, Seq.empty)

    prompt should include("RULES")
    prompt should include("technical terms")
  }

  it should "handle special characters in text" in {
    val text = "Text with @#$% special characters!"
    val prompt = LLMConceptRefiner.buildPrompt(text, Seq.empty)

    prompt should include(text)
  }

  it should "handle empty text" in {
    val prompt = LLMConceptRefiner.buildPrompt("", Seq.empty)

    prompt should not be empty
  }

  "parseConceptsFromResponse" should "parse valid JSON response" in {
    val response = """{"concepts": [{"surface": "Kubernetes", "lemma": "kubernetes"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
    concepts.head.surface should be("Kubernetes")
    concepts.head.lemma should be("kubernetes")
    concepts.head.origin should be("LLM")
  }

  it should "parse multiple concepts" in {
    val response =
      """{"concepts": [
        |{"surface": "Kubernetes", "lemma": "kubernetes"},
        |{"surface": "Docker", "lemma": "docker"},
        |{"surface": "YAML", "lemma": "yaml"}
        |]}""".stripMargin

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 3
    concepts.map(_.surface) should contain allOf("Kubernetes", "Docker", "YAML")
  }

  it should "handle response with markdown formatting" in {
    val response = """```json
                     |{"concepts": [{"surface": "Flink", "lemma": "flink"}]}
                     |```""".stripMargin

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
    concepts.head.surface should be("Flink")
  }

  it should "handle empty concepts array" in {
    val response = """{"concepts": []}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should be(empty)
  }

  it should "handle invalid JSON" in {
    val response = """{"concepts": [invalid json}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should be(empty)
  }

  it should "handle malformed JSON structure" in {
    val response = """{"wrong_field": [{"surface": "Test", "lemma": "test"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should be(empty)
  }

  it should "handle response with extra whitespace" in {
    val response = """

      {"concepts": [{"surface": "Test", "lemma": "test"}]}

    """

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
  }

  it should "normalize concept IDs" in {
    val response = """{"concepts": [{"surface": "Test Concept", "lemma": "Test Concept"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts.head.conceptId should fullyMatch regex "[a-z0-9_]+"
    concepts.head.conceptId should be("test_concept")
  }

  it should "handle special characters in concept names" in {
    val response = """{"concepts": [{"surface": "C++", "lemma": "c++"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
    // Special characters should be removed from conceptId
    concepts.head.conceptId should fullyMatch regex "[a-z0-9_]+"
  }

  it should "set origin to LLM" in {
    val response = """{"concepts": [{"surface": "Test", "lemma": "test"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts.foreach(_.origin should be("LLM"))
  }

  it should "handle null or empty response" in {
    val concepts1 = LLMConceptRefiner.parseConceptsFromResponse("")
    val concepts2 = LLMConceptRefiner.parseConceptsFromResponse(null)

    concepts1 should be(empty)
    concepts2 should be(empty)
  }

  it should "handle response with unexpected fields" in {
    val response =
      """{"concepts": [{"surface": "Test", "lemma": "test", "extra": "field"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
  }

  it should "handle Unicode in concepts" in {
    val response = """{"concepts": [{"surface": "文本", "lemma": "text"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts should have size 1
    concepts.head.surface should be("文本")
  }

  it should "lowercase lemmas" in {
    val response = """{"concepts": [{"surface": "Test", "lemma": "TEST"}]}"""

    val concepts = LLMConceptRefiner.parseConceptsFromResponse(response)

    concepts.head.lemma should be("test")
  }
}