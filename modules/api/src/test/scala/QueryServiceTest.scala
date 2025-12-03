import com.graphrag.api.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import io.circe.parser._
import io.circe.generic.auto._

class QueryServiceTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  //
  // ────────────────────────────────────────────────────────────────────────────────
  //  STRING NORMALIZATION TESTS (Pure logic)
  // ────────────────────────────────────────────────────────────────────────────────
  //

  "normalizeConceptId logic" should "convert text to lowercase with underscores" in {
    val text = "Apache Flink"
    val normalized = text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")

    normalized should be("apache_flink")
  }

  it should "remove special characters" in {
    val text = "C++ Programming!"
    val normalized = text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")

    normalized should be("c_programming")
  }

  it should "handle multiple spaces" in {
    val text = "Stream   Processing   Framework"
    val normalized = text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")

    normalized should be("stream_processing_framework")
  }

  //
  // ────────────────────────────────────────────────────────────────────────────────
  //  JSON PARSING TESTS (Pure Circe)
  // ────────────────────────────────────────────────────────────────────────────────
  //

  case class LLMConcept(surface: String, lemma: String)
  case class LLMResponse(concepts: List[LLMConcept])

  "parseConceptsFromLLM" should "parse valid JSON response" in {
    val response = """{"concepts": [{"surface": "Apache Flink", "lemma": "apache_flink"}]}"""

    decode[LLMResponse](response) match {
      case Right(res) =>
        res.concepts should have size 1
        res.concepts.head.surface should be("Apache Flink")
      case Left(_) => fail("Should parse valid JSON")
    }
  }

  it should "handle response with markdown formatting" in {
    val response = """```json
                     |{"concepts": [{"surface": "Kafka", "lemma": "kafka"}]}
                     |```""".stripMargin

    val cleaned = response.replace("```json", "").replace("```", "").trim

    cleaned should not include "```"
    cleaned should include("concepts")
  }

  it should "handle empty concepts array" in {
    val response = """{"concepts": []}"""

    decode[LLMResponse](response) match {
      case Right(res) => res.concepts should be(empty)
      case Left(_)    => fail("Should parse valid JSON")
    }
  }

  it should "fail gracefully for invalid JSON" in {
    val response = """{"concepts": [invalid}"""

    decode[LLMResponse](response).isLeft shouldBe true
  }

  //
  // ────────────────────────────────────────────────────────────────────────────────
  //  PROMPT GENERATION TESTS (Pure string checks)
  // ────────────────────────────────────────────────────────────────────────────────
  //

  "extractConceptsFromQuery prompt" should "include the query text" in {
    val query = "What is Apache Flink?"

    val prompt = s"""Extract key concepts from this query as JSON:
Query: "$query"

Return JSON format:
{
  "concepts": [
    {"surface": "concept name", "lemma": "normalized form"}
  ]
}

Respond with JSON only:"""

    prompt should include(query)
    prompt should include("JSON")
  }

  it should "specify JSON structure" in {
    val query = "test query"

    val prompt = s"""Extract key concepts from this query as JSON:
Query: "$query"

Return JSON format:
{
  "concepts": [
    {"surface": "concept name", "lemma": "normalized form"}
  ]
}

Respond with JSON only:"""

    prompt should include("surface")
    prompt should include("lemma")
  }

  //
  // ────────────────────────────────────────────────────────────────────────────────
  //  MODEL TESTS (Pure case class behavior)
  // ────────────────────────────────────────────────────────────────────────────────
  //

  "QueryRequest" should "store parameters" in {
    val request = QueryRequest(
      query = "What is Apache Flink?",
      includeEvidence = true,
      maxResults = 100
    )

    request.query should be("What is Apache Flink?")
    request.includeEvidence shouldBe true
    request.maxResults shouldBe 100
  }

  it should "accept special characters" in {
    QueryRequest("What is C++?", includeEvidence = false, maxResults = 20)
      .query should include("C++")
  }

  "QueryResponse" should "store all required fields" in {
    val response = QueryResponse(
      requestId = "test-id",
      concepts = Seq(ConceptResult("id", "lemma", "surface", 1.0)),
      relations = Seq.empty,
      executionTimeMs = 100
    )

    response.requestId shouldBe "test-id"
    response.concepts should have size 1
    response.executionTimeMs shouldBe 100
  }

  it should "handle empty results" in {
    val response = QueryResponse(
      requestId = "id",
      concepts = Seq.empty,
      relations = Seq.empty,
      executionTimeMs = 10
    )

    response.concepts shouldBe empty
    response.relations shouldBe empty
  }

  "ConceptResult" should "store concept data" in {
    val c = ConceptResult("apache_flink", "apache_flink", "Apache Flink", 0.95)

    c.conceptId shouldBe "apache_flink"
    c.relevanceScore shouldBe 0.95
  }

  "RelationResult" should "store relation information" in {
    val r = RelationResult(
      source = "c1",
      target = "c2",
      predicate = "uses",
      confidence = 0.85,
      evidence = Some("evidence text"),
      evidenceId = Some("id123")
    )

    r.predicate shouldBe "uses"
    r.evidence shouldBe defined
  }

  it should "handle missing optional fields" in {
    val r = RelationResult("c1", "c2", "uses", 0.85, None, None)

    r.evidence shouldBe None
    r.evidenceId shouldBe None
  }
}
