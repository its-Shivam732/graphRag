package com.graphrag.ingestion

import com.graphrag.core.models.Chunk
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ChunkNormalizerTest extends AnyFlatSpec with Matchers {

  // Sample test data
  val baseChunk = Chunk(
    chunkId = "chunk-001",
    docId = "doc-001",
    span = (0, 100),
    text = "Sample text",
    sourceUri = "file:///data/sample.txt",
    hash = "abc123"
  )

  "NormalizeMapper" should "trim whitespace from chunk text" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunkWithWhitespace = baseChunk.copy(text = "  Sample text  ")

    val result = mapper.map(chunkWithWhitespace)

    result.text should be("Sample text")
  }

  it should "trim leading whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "   Sample text")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "trim trailing whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "Sample text   ")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "handle text with tabs" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "\t\tSample text\t\t")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "handle text with newlines" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "\nSample text\n")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "handle text with multiple types of whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = " \t\n Sample text \n\t ")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "preserve internal whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  Sample  text  with  spaces  ")

    val result = mapper.map(chunk)

    result.text should be("Sample  text  with  spaces")
  }

  it should "handle empty text" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "")

    val result = mapper.map(chunk)

    result.text should be("")
  }

  it should "handle text with only whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "   \t\n   ")

    val result = mapper.map(chunk)

    result.text should be("")
  }

  it should "handle text that needs no trimming" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "Sample text")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "preserve all other chunk fields" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  Sample text  ")

    val result = mapper.map(chunk)

    result.chunkId should be(chunk.chunkId)
    result.docId should be(chunk.docId)
    result.span should be(chunk.span)
    result.sourceUri should be(chunk.sourceUri)
    result.hash should be(chunk.hash)
  }

  it should "not modify chunkId" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val result = mapper.map(baseChunk)

    result.chunkId should be("chunk-001")
  }

  it should "not modify docId" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val result = mapper.map(baseChunk)

    result.docId should be("doc-001")
  }

  it should "not modify span" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val result = mapper.map(baseChunk)

    result.span should be((0, 100))
  }

  it should "not modify sourceUri" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val result = mapper.map(baseChunk)

    result.sourceUri should be("file:///data/sample.txt")
  }

  it should "not modify hash" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val result = mapper.map(baseChunk)

    result.hash should be("abc123")
  }

  it should "handle Unicode text" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  文本示例 🎉  ")

    val result = mapper.map(chunk)

    result.text should be("文本示例 🎉")
  }

  it should "handle very long text" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val longText = "  " + ("x" * 10000) + "  "
    val chunk = baseChunk.copy(text = longText)

    val result = mapper.map(chunk)

    result.text should be("x" * 10000)
    result.text.length should be(10000)
  }

  it should "handle special characters" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  !@#$%^&*()  ")

    val result = mapper.map(chunk)

    result.text should be("!@#$%^&*()")
  }

  it should "handle mixed content with numbers" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  Sample 123 text  ")

    val result = mapper.map(chunk)

    result.text should be("Sample 123 text")
  }
  

  "normalize function" should "process sequence of chunks" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunks = Seq(
      baseChunk.copy(text = "  chunk1  "),
      baseChunk.copy(text = "  chunk2  "),
      baseChunk.copy(text = "  chunk3  ")
    )

    val results = chunks.map(mapper.map)

    results.map(_.text) should be(Seq("chunk1", "chunk2", "chunk3"))
  }

  it should "handle chunks with varying whitespace amounts" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunks = Seq(
      baseChunk.copy(text = " text1 "),
      baseChunk.copy(text = "   text2   "),
      baseChunk.copy(text = "     text3     ")
    )

    val results = chunks.map(mapper.map)

    results.forall(_.text.startsWith("text")) should be(true)
    results.forall(c => !c.text.startsWith(" ")) should be(true)
  }

  it should "preserve unique chunk IDs" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunks = Seq(
      baseChunk.copy(chunkId = "chunk-001", text = "  text  "),
      baseChunk.copy(chunkId = "chunk-002", text = "  text  "),
      baseChunk.copy(chunkId = "chunk-003", text = "  text  ")
    )

    val results = chunks.map(mapper.map)

    results.map(_.chunkId).toSet.size should be(3)
  }

  "normalize performance" should "handle large batches efficiently" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunks = (1 to 1000).map { i =>
      baseChunk.copy(chunkId = s"chunk-$i", text = s"  text $i  ")
    }

    val start = System.currentTimeMillis()
    val results = chunks.map(mapper.map)
    val duration = System.currentTimeMillis() - start

    results.size should be(1000)
    duration should be < 1000L  // Should complete in less than 1 second
  }

  "edge cases" should "handle chunk with only newline" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "\n")

    val result = mapper.map(chunk)

    result.text should be("")
  }

  it should "handle chunk with only spaces" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "     ")

    val result = mapper.map(chunk)

    result.text should be("")
  }

  it should "handle chunk with carriage returns" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "\r\nSample text\r\n")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
  }

  it should "handle chunk with non-breaking spaces" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "\u00A0Sample text\u00A0")

    val result = mapper.map(chunk)

    // trim() handles non-breaking spaces in Scala
    result.text.length should be <= chunk.text.trim.length
  }

  "regression tests" should "not introduce extra whitespace" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "Sample text")

    val result = mapper.map(chunk)

    result.text should be("Sample text")
    result.text should not contain("  ")
  }

  it should "be idempotent" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  Sample text  ")

    val result1 = mapper.map(chunk)
    val result2 = mapper.map(result1)

    result1.text should be(result2.text)
  }

  it should "handle text with leading/trailing punctuation" in {
    val mapper = new ChunkNormalizer.NormalizeMapper()
    val chunk = baseChunk.copy(text = "  .Sample text.  ")

    val result = mapper.map(chunk)

    result.text should be(".Sample text.")
  }
}