package com.graphrag.ingestion

import com.graphrag.core.models.Chunk
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.flink.util.Collector
import scala.util.{Try, Success, Failure}

class ChunkSourceTest extends AnyFlatSpec with Matchers {

  "JsonParser" should "parse valid JSON line to Chunk" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Sample text","chunkHash":"abc123","language":"en","title":"Test Document"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(1)
    val chunk = collector.collected.head
    chunk.chunkId should be("chunk-001")
    chunk.docId should be("doc-001")
    chunk.text should be("Sample text")
    chunk.hash should be("abc123")
  }

  it should "handle JSON without optional language field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1","title":"Doc"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(1)
  }

  it should "handle JSON without optional title field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1","language":"en"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(1)
    val chunk = collector.collected.head
    chunk.sourceUri should be("unknown")  // Default when title is missing
  }

  it should "use title as sourceUri when present" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1","title":"My Document"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.sourceUri should be("My Document")
  }

  it should "calculate correct span from offset and chunk length" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":100,"chunk":"Hello World","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.span should be((100, 111))  // offset=100, length=11, so end=111
  }

  it should "handle large offset values" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":1000000,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.span._1 should be(1000000)
    chunk.span._2 should be(1000004)  // 1000000 + "Text".length
  }

  it should "handle empty chunk text" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.text should be("")
    chunk.span should be((0, 0))
  }

  it should "handle chunk text with special characters" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text with \"quotes\" and \n newlines","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.text should include("quotes")
  }

  it should "handle chunk text with Unicode characters" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"文本示例 🎉","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.text should be("文本示例 🎉")
  }

  it should "not collect anything for invalid JSON" in {
    val parser = new ChunkSource.JsonParser()
    val invalidJson = """{"chunkId":"chunk-001", invalid json"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(invalidJson, collector)

    collector.collected.size should be(0)
  }

  it should "not collect anything for JSON missing required fields" in {
    val parser = new ChunkSource.JsonParser()
    val incompleteJson = """{"chunkId":"chunk-001"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(incompleteJson, collector)

    collector.collected.size should be(0)
  }

  it should "not collect anything for empty string" in {
    val parser = new ChunkSource.JsonParser()

    val collector = new TestCollector[Chunk]()
    parser.flatMap("", collector)

    collector.collected.size should be(0)
  }

  it should "handle JSON with extra fields" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1","extraField":"ignored","anotherField":123}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(1)
  }

  it should "preserve all required chunk fields" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-999","docId":"doc-888","offset":50,"chunk":"Test chunk text","chunkHash":"hash999"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.chunkId should be("chunk-999")
    chunk.docId should be("doc-888")
    chunk.text should be("Test chunk text")
    chunk.hash should be("hash999")
    chunk.span._1 should be(50)
  }


  "JsonParser with multiple lines" should "collect all valid chunks" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLines = Seq(
      """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text1","chunkHash":"hash1"}""",
      """{"chunkId":"chunk-002","docId":"doc-001","offset":10,"chunk":"Text2","chunkHash":"hash2"}""",
      """{"chunkId":"chunk-003","docId":"doc-001","offset":20,"chunk":"Text3","chunkHash":"hash3"}"""
    )

    val collector = new TestCollector[Chunk]()
    jsonLines.foreach(line => parser.flatMap(line, collector))

    collector.collected.size should be(3)
    collector.collected.map(_.chunkId) should be(Seq("chunk-001", "chunk-002", "chunk-003"))
  }

  it should "skip invalid lines and continue processing" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLines = Seq(
      """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text1","chunkHash":"hash1"}""",
      """invalid json line""",
      """{"chunkId":"chunk-003","docId":"doc-001","offset":20,"chunk":"Text3","chunkHash":"hash3"}"""
    )

    val collector = new TestCollector[Chunk]()
    jsonLines.foreach(line => parser.flatMap(line, collector))

    collector.collected.size should be(2)
    collector.collected.map(_.chunkId) should be(Seq("chunk-001", "chunk-003"))
  }

  "JSON field validation" should "require chunkId field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(0)
  }

  it should "require docId field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","offset":0,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(0)
  }

  it should "require offset field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(0)
  }

  it should "require chunk field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(0)
  }

  it should "require chunkHash field" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    collector.collected.size should be(0)
  }

  "Offset handling" should "handle zero offset" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.span._1 should be(0)
  }

  it should "convert long offset to int correctly" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":12345,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.span._1 should be(12345)
  }

  "Span calculation" should "correctly compute end position" in {
    val parser = new ChunkSource.JsonParser()
    val texts = Seq(
      ("A", 1),
      ("Hello", 5),
      ("Hello World", 11),
      ("A longer piece of text", 22)
    )

    texts.foreach { case (text, expectedLength) =>
      val jsonLine = s"""{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"$text","chunkHash":"hash1"}"""
      val collector = new TestCollector[Chunk]()
      parser.flatMap(jsonLine, collector)

      val chunk = collector.collected.head
      chunk.span._2 should be(expectedLength)
    }
  }

  it should "handle multi-byte UTF-8 characters correctly" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"文本","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.span._2 should be(2)  // 2 characters, not byte count
  }

  "Error handling" should "not throw exceptions on malformed JSON" in {
    val parser = new ChunkSource.JsonParser()
    val malformedJsons = Seq(
      "{",
      "}",
      "null",
      "[]",
      """{"unclosed": """,
      """{"nested": {"broken": }""",
      "random text"
    )

    malformedJsons.foreach { json =>
      val collector = new TestCollector[Chunk]()
      noException should be thrownBy parser.flatMap(json, collector)
      collector.collected.size should be(0)
    }
  }

  "Performance tests" should "handle large chunk text efficiently" in {
    val parser = new ChunkSource.JsonParser()
    val largeText = "x" * 100000
    val jsonLine = s"""{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"$largeText","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    val start = System.currentTimeMillis()
    parser.flatMap(jsonLine, collector)
    val duration = System.currentTimeMillis() - start

    collector.collected.size should be(1)
    duration should be < 1000L  // Should parse in less than 1 second
  }

  it should "handle many lines efficiently" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLines = (1 to 1000).map { i =>
      s"""{"chunkId":"chunk-$i","docId":"doc-001","offset":$i,"chunk":"Text $i","chunkHash":"hash$i"}"""
    }

    val collector = new TestCollector[Chunk]()
    val start = System.currentTimeMillis()
    jsonLines.foreach(line => parser.flatMap(line, collector))
    val duration = System.currentTimeMillis() - start

    collector.collected.size should be(1000)
    duration should be < 2000L  // Should complete in less than 2 seconds
  }

  "Real-world scenarios" should "handle typical document chunk" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"doc-123-chunk-5","docId":"doc-123","offset":500,"chunk":"This is a sample paragraph from a document. It contains multiple sentences and punctuation marks!","chunkHash":"d41d8cd98f00b204e9800998ecf8427e","language":"en","title":"Sample Document.pdf"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.chunkId should be("doc-123-chunk-5")
    chunk.docId should be("doc-123")
    chunk.sourceUri should be("Sample Document.pdf")
    chunk.text should include("paragraph")
  }

  it should "handle chunks from different documents" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLines = Seq(
      """{"chunkId":"chunk-1","docId":"doc-A","offset":0,"chunk":"Text from doc A","chunkHash":"hash1"}""",
      """{"chunkId":"chunk-1","docId":"doc-B","offset":0,"chunk":"Text from doc B","chunkHash":"hash2"}""",
      """{"chunkId":"chunk-1","docId":"doc-C","offset":0,"chunk":"Text from doc C","chunkHash":"hash3"}"""
    )

    val collector = new TestCollector[Chunk]()
    jsonLines.foreach(line => parser.flatMap(line, collector))

    collector.collected.map(_.docId).toSet.size should be(3)
  }

  "Edge cases" should "handle whitespace in chunk text" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"  Text with  spaces  ","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.text should be("  Text with  spaces  ")  // Preserves whitespace
  }

  it should "handle chunk IDs with special characters" in {
    val parser = new ChunkSource.JsonParser()
    val jsonLine = """{"chunkId":"chunk-001_v2.0","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"hash1"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.chunkId should be("chunk-001_v2.0")
  }

  it should "handle very long hash values" in {
    val parser = new ChunkSource.JsonParser()
    val longHash = "a" * 256
    val jsonLine = s"""{"chunkId":"chunk-001","docId":"doc-001","offset":0,"chunk":"Text","chunkHash":"$longHash"}"""

    val collector = new TestCollector[Chunk]()
    parser.flatMap(jsonLine, collector)

    val chunk = collector.collected.head
    chunk.hash should be(longHash)
  }
}

// Test helper class
class TestCollector[T] extends Collector[T] {
  private val buffer = scala.collection.mutable.ListBuffer[T]()

  override def collect(record: T): Unit = {
    buffer += record
  }

  override def close(): Unit = {}

  def collected: Seq[T] = buffer.toSeq
}