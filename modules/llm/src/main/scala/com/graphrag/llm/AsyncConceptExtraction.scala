package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.async.{RichAsyncFunction, ResultFuture}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

/**
 * AsyncConceptExtraction
 *
 * This class performs **asynchronous concept extraction** on incoming Chunk objects
 * using:
 *   1. A lightweight heuristic extractor (fast)
 *   2. A large language model (Ollama) to refine/improve concept extraction (slow)
 *
 * Because LLM calls are slow, Flink uses an async function so the pipeline
 * continues processing other events while the LLM call is in progress.
 */
class AsyncConceptExtraction(
                              ollamaUrl: String,
                              model: String,
                              maxConcurrentRequests: Int = 10
                            ) extends RichAsyncFunction[Chunk, Mentions] {

  /**
   * Thread pool for handling concurrent async requests.
   * This executor backs our Future-based async LLM calls.
   *
   * Lazy ensures it is created only when first accessed (after open()).
   */
  private lazy val executorService =
    java.util.concurrent.Executors.newFixedThreadPool(maxConcurrentRequests)

  /**
   * ExecutionContext for our Futures, backed by the fixed thread pool.
   *
   * Also lazy, initialized only when used.
   */
  private implicit lazy val ec: ExecutionContext =
    ExecutionContext.fromExecutor(executorService)

  /**
   * Ollama client used for contacting the LLM model.
   *
   * Lazy initialization ensures construction happens after open().
   */
  private lazy val ollamaClient: OllamaClient =
    OllamaClient(ollamaUrl)(ec)

  /**
   * Called once per task slot before any elements are processed.
   * Triggers lazy initialization of the executor + Ollama client.
   */
  override def open(parameters: Configuration): Unit = {
    println(
      s"Initialized Async Ollama client: $ollamaUrl with $maxConcurrentRequests concurrent requests"
    )
  }

  /**
   * Called when the operator is being shut down.
   * Ensures the Ollama client and executor service are properly closed.
   */
  override def close(): Unit = {
    Option(ollamaClient).foreach(_.close())
    executorService.shutdown()
  }

  /**
   * asyncInvoke
   *
   * This method is called for every incoming Chunk. It triggers:
   *
   *   1. Heuristic concept extraction (fast)
   *   2. Conditional LLM refinement (slow, async)
   *
   * When the LLM result completes, we merge the heuristic + LLM concepts
   * and emit Mentions for downstream processing.
   */
  override def asyncInvoke(
                            chunk: Chunk,
                            resultFuture: ResultFuture[Mentions]
                          ): Unit = {

    // Step 1: Extract heuristic concepts locally
    val heuristicMentions = ConceptExtractor.extractHeuristic(chunk)
    val heuristicConcepts = heuristicMentions.map(_.concept)

    // Step 2: If the chunk is large enough -> refine via LLM
    val llmFuture: Future[Seq[Concept]] =
      if (chunk.text.length >= 100) {
        val prompt = LLMConceptRefiner.buildPrompt(chunk.text, heuristicConcepts)

        // Asynchronous LLM call via Ollama
        ollamaClient
          .generateAsync(model, prompt, temperature = 0.0)
          .map(LLMConceptRefiner.parseConceptsFromResponse)
          .recover { case _ => Seq.empty[Concept] } // Fail-safe fallback

      } else {
        // Small chunks don't go through LLM — heuristic only
        Future.successful(Seq.empty[Concept])
      }

    // Step 3: Merge heuristic and LLM concepts when LLM completes
    llmFuture.onComplete {
      case Success(llmConcepts) =>
        val allMentions =
          heuristicMentions ++ llmConcepts.map(c => Mentions(chunk.chunkId, c))

        // Send back list of Mentions to Flink as async result
        resultFuture.complete(allMentions.asJava)

      case Failure(_) =>
        // On failure, fallback to heuristic-only extraction
        resultFuture.complete(heuristicMentions.asJava)
    }
  }

  /**
   * Called by Flink when asyncInvoke exceeds the configured timeout.
   *
   * In this case, we safely fall back to heuristic extraction only.
   */
  override def timeout(
                        chunk: Chunk,
                        resultFuture: ResultFuture[Mentions]
                      ): Unit = {
    val heuristicMentions = ConceptExtractor.extractHeuristic(chunk)
    resultFuture.complete(heuristicMentions.asJava)
  }
}
