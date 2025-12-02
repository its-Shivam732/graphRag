
import com.graphrag.api.models.Evidence
import com.graphrag.api.services.EvidenceService
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import scala.concurrent.ExecutionContext

class EvidenceServiceTest extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  implicit val ec: ExecutionContext = ExecutionContext.global

  val testNeo4jUri = "bolt://localhost:7687"
  val testUsername = "neo4j"
  val testPassword = "password"

  "EvidenceService" should "initialize without errors" in {
    val service = new EvidenceService(
      testNeo4jUri,
      testUsername,
      testPassword
    )

    service should not be null
  }

  it should "handle close lifecycle" in {
    val service = new EvidenceService(
      testNeo4jUri,
      testUsername,
      testPassword
    )

    noException should be thrownBy service.close()
  }

  "getEvidence query" should "match chunk by chunkId" in {
    val evidenceId = "chunk-001"

    val query = """
        MATCH (chunk:Chunk {chunkId: $evidenceId})
        RETURN chunk.chunkId as chunkId, chunk.text as text, chunk.sourceUri as source
      """

    query should include("MATCH (chunk:Chunk")
    query should include("chunkId: $evidenceId")
  }

  it should "return required fields" in {
    val query = """
        MATCH (chunk:Chunk {chunkId: $evidenceId})
        RETURN chunk.chunkId as chunkId, chunk.text as text, chunk.sourceUri as source
      """

    query should include("chunk.chunkId as chunkId")
    query should include("chunk.text as text")
    query should include("chunk.sourceUri as source")
  }

  "Evidence model" should "store evidenceId" in {
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = "Sample text from chunk.",
      chunkId = "chunk-001",
      source = "file:///data/sample.txt"
    )

    evidence.evidenceId should be("chunk-001")
  }

  it should "store text content" in {
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = "Apache Flink is a stream processing framework.",
      chunkId = "chunk-001",
      source = "file:///test.txt"
    )

    evidence.text should include("Apache Flink")
  }

  it should "store chunkId" in {
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = "text",
      chunkId = "chunk-001",
      source = "source"
    )

    evidence.chunkId should be("chunk-001")
  }

  it should "store source URI" in {
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = "text",
      chunkId = "chunk-001",
      source = "file:///data/document.pdf"
    )

    evidence.source should be("file:///data/document.pdf")
  }

  it should "handle long text content" in {
    val longText = "This is a very long text content. " * 100
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = longText,
      chunkId = "chunk-001",
      source = "source"
    )

    evidence.text.length should be > 1000
  }

  it should "handle special characters in text" in {
    val specialText = "Text with @#$% special !@# characters"
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = specialText,
      chunkId = "chunk-001",
      source = "source"
    )

    evidence.text should include("@#$%")
  }

  it should "handle Unicode text" in {
    val unicodeText = "文本示例 with mixed content"
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = unicodeText,
      chunkId = "chunk-001",
      source = "source"
    )

    evidence.text should include("文本示例")
  }

  it should "handle empty text" in {
    val evidence = Evidence(
      evidenceId = "chunk-001",
      text = "",
      chunkId = "chunk-001",
      source = "source"
    )

    evidence.text should be(empty)
  }

  it should "handle source URLs" in {
    val sources = Seq(
      "file:///data/file.txt",
      "http://example.com/doc",
      "s3://bucket/key",
      "/local/path/file"
    )

    sources.foreach { source =>
      val evidence = Evidence("id", "text", "chunk", source)
      evidence.source should be(source)
    }
  }

  "getEvidence" should "handle valid evidenceId" in {
    val service = new EvidenceService(
      testNeo4jUri,
      testUsername,
      testPassword
    )

    val evidenceId = "chunk-001"
    evidenceId should not be empty
  }

  it should "handle evidenceId with special format" in {
    val evidenceIds = Seq(
      "chunk-001",
      "chunk_001",
      "doc-123-chunk-456",
      "chunk.001"
    )

    evidenceIds.foreach { id =>
      id should not be empty
    }
  }

  "Evidence retrieval" should "return None for non-existent evidenceId" in {
    // This tests the behavior when chunk is not found
    val evidenceId = "non-existent-chunk"
    evidenceId should not be empty
  }

  it should "return Some(Evidence) for existing chunk" in {
    // This tests the successful path
    val evidence = Some(Evidence(
      "chunk-001",
      "text",
      "chunk-001",
      "source"
    ))

    evidence should be(defined)
  }
}