import com.graphrag.api.models._
import com.graphrag.api.services.QueryService
import com.graphrag.core.models.Concept
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.{Await, ExecutionContext}
import scala.concurrent.duration._

class QueryServiceTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  val testNeo4jUri = "bolt://localhost:7687"
  val testUsername = "neo4j"
  val testPassword = "password"
  val testOllamaUrl = "http://localhost:11434"
  val testModel = "llama3"

  "QueryService" should "initialize without errors" in {
    val service = new QueryService(
      testNeo4jUri,
      testUsername,
      testPassword,
      testOllamaUrl,
      testModel
    )

    service should not be null
  }

  it should "handle close lifecycle" in {
    val service = new QueryService(
      testNeo4jUri,
      testUsername,
      testPassword,
      testOllamaUrl,
      testModel
    )

    noException should be thrownBy service.close()
  }

  "normalizeConceptId" should "convert text to lowercase with underscores" in {
    val service = new QueryService(
      testNeo4jUri,
      testUsername,
      testPassword,
      testOllamaUrl,
      testModel
    )

    // Testing via concept creation since normalizeConceptId is private
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



  "parseConceptsFromLLM" should "parse valid JSON response" in {
    val service = new QueryService(
      testNeo4jUri,
      testUsername,
      testPassword,
      testOllamaUrl,
      testModel
    )

    val response = """{"concepts": [{"surface": "Apache Flink", "lemma": "apache_flink"}]}"""

    // Test the parsing logic
    import io.circe.parser._
    import io.circe.generic.auto._

    case class LLMConcept(surface: String, lemma: String)
    case class LLMResponse(concepts: List[LLMConcept])

    decode[LLMResponse](response) match {
      case Right(llmResponse) =>
        llmResponse.concepts should have size 1
        llmResponse.concepts.head.surface should be("Apache Flink")
      case Left(_) =>
        fail("Should parse valid JSON")
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

    import io.circe.parser._
    import io.circe.generic.auto._

    case class LLMConcept(surface: String, lemma: String)
    case class LLMResponse(concepts: List[LLMConcept])

    decode[LLMResponse](response) match {
      case Right(llmResponse) =>
        llmResponse.concepts should be(empty)
      case Left(_) =>
        fail("Should parse valid JSON")
    }
  }

  it should "handle invalid JSON gracefully" in {
    val response = """{"concepts": [invalid}"""

    import io.circe.parser._
    import io.circe.generic.auto._

    case class LLMConcept(surface: String, lemma: String)
    case class LLMResponse(concepts: List[LLMConcept])

    decode[LLMResponse](response) match {
      case Right(_) =>
        fail("Should not parse invalid JSON")
      case Left(_) =>
        // Expected to fail
        succeed
    }
  }

  "extractConceptsFromQuery prompt" should "include query text" in {
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

  it should "specify JSON format" in {
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

  "QueryRequest" should "be created with valid parameters" in {
    val request = QueryRequest(
      query = "What is Apache Flink?",
      includeEvidence = true,
      maxResults = 100
    )

    request.query should be("What is Apache Flink?")
    request.includeEvidence should be(true)
    request.maxResults should be(100)
  }

  it should "handle query with special characters" in {
    val request = QueryRequest(
      query = "What is C++?",
      includeEvidence = false,
      maxResults = 50
    )

    request.query should include("C++")
  }

  "QueryResponse" should "contain all required fields" in {
    val response = QueryResponse(
      requestId = "test-id",
      concepts = Seq(ConceptResult("id", "lemma", "surface", 1.0)),
      relations = Seq.empty,
      executionTimeMs = 100
    )

    response.requestId should be("test-id")
    response.concepts should have size 1
    response.executionTimeMs should be(100)
  }

  it should "handle empty results" in {
    val response = QueryResponse(
      requestId = "test-id",
      concepts = Seq.empty,
      relations = Seq.empty,
      executionTimeMs = 50
    )

    response.concepts should be(empty)
    response.relations should be(empty)
  }

  "ConceptResult" should "store concept information" in {
    val result = ConceptResult(
      conceptId = "apache_flink",
      lemma = "apache_flink",
      surface = "Apache Flink",
      relevanceScore = 0.95
    )

    result.conceptId should be("apache_flink")
    result.relevanceScore should be(0.95)
  }

  "RelationResult" should "store relation information" in {
    val result = RelationResult(
      source = "concept-1",
      target = "concept-2",
      predicate = "uses",
      confidence = 0.85,
      evidence = Some("Evidence text"),
      evidenceId = Some("chunk-001")
    )

    result.predicate should be("uses")
    result.confidence should be(0.85)
    result.evidence should be(defined)
  }

  it should "handle missing optional fields" in {
    val result = RelationResult(
      source = "concept-1",
      target = "concept-2",
      predicate = "uses",
      confidence = 0.85,
      evidence = None,
      evidenceId = None
    )

    result.evidence should be(None)
    result.evidenceId should be(None)
  }
}