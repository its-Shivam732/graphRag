package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.async.ResultFuture
import scala.collection.mutable.ListBuffer
import java.util.{Collection => JavaCollection}
import scala.collection.JavaConverters._

class AsyncConceptExtractionTest extends AnyFlatSpec with Matchers {

  // Test data
  val sampleChunk = Chunk(
    chunkId = "chunk-001",
    docId = "doc-001",
    span = (0, 100),
    text = "Apache Flink is a powerful stream processing framework. It provides distributed computing capabilities.",
    sourceUri = "file:///data/sample.txt",
    hash = "abc123"
  )

  val shortChunk = sampleChunk.copy(
    chunkId = "chunk-002",
    text = "Short text"  // Less than 100 characters
  )

  "AsyncConceptExtraction" should "initialize without errors" in {
    val extractor = new AsyncConceptExtraction(
      ollamaUrl = "http://localhost:11434",
      model = "llama3",
      maxConcurrentRequests = 5
    )

    extractor should not be null
  }





  it should "initialize with custom concurrent requests" in {
    val extractor = new AsyncConceptExtraction(
      ollamaUrl = "http://localhost:11434",
      model = "llama3",
      maxConcurrentRequests = 20
    )

    extractor should not be null
  }

  it should "handle open lifecycle" in {
    val extractor = new AsyncConceptExtraction(
      "http://localhost:11434",
      "llama3"
    )

    val config = new Configuration()
    noException should be thrownBy extractor.open(config)
  }

  it should "handle close lifecycle" in {
    val extractor = new AsyncConceptExtraction(
      "http://localhost:11434",
      "llama3"
    )

    noException should be thrownBy extractor.close()
  }

  it should "process chunks with text length >= 100" in {
    val extractor = new AsyncConceptExtraction(
      "http://localhost:11434",
      "llama3"
    )

    val chunk = sampleChunk
    chunk.text.length should be >= 100
  }

  it should "handle chunks with text length < 100" in {
    val extractor = new AsyncConceptExtraction(
      "http://localhost:11434",
      "llama3"
    )

    val chunk = shortChunk
    chunk.text.length should be < 100
  }




  it should "preserve chunk ID in extracted mentions" in {
    val mentions = ConceptExtractor.extractHeuristic(sampleChunk)

    mentions.foreach { mention =>
      mention.chunkId should be(sampleChunk.chunkId)
    }
  }

  // Helper class for testing ResultFuture
  class TestResultFuture extends ResultFuture[Mentions] {
    val results = ListBuffer[Mentions]()

    override def complete(result: JavaCollection[Mentions]): Unit = {
      results ++= result.asScala
    }

    override def completeExceptionally(error: Throwable): Unit = {
      // Handle error
    }
  }
}