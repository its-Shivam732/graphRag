package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions}
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.async.{RichAsyncFunction, ResultFuture}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import scala.collection.JavaConverters._

class AsyncConceptExtraction(
                              ollamaUrl: String,
                              model: String,
                              maxConcurrentRequests: Int = 10
                            ) extends RichAsyncFunction[Chunk, Mentions] {

  // Lazily initialized execution context and client
  // They are evaluated only once when first used (after `open` is called)
  private lazy val executorService =
    java.util.concurrent.Executors.newFixedThreadPool(maxConcurrentRequests)

  private implicit lazy val ec: ExecutionContext =
    ExecutionContext.fromExecutor(executorService)

  private lazy val ollamaClient: OllamaClient =
    OllamaClient(ollamaUrl)(ec)

  override def open(parameters: Configuration): Unit = {
    // Accessing lazy values will initialize them here
    println(
      s"Initialized Async Ollama client: $ollamaUrl with $maxConcurrentRequests concurrent requests"
    )
  }

  override def close(): Unit = {
    Option(ollamaClient).foreach(_.close())
    executorService.shutdown()
  }

  override def asyncInvoke(
                            chunk: Chunk,
                            resultFuture: ResultFuture[Mentions]
                          ): Unit = {

    val heuristicMentions = ConceptExtractor.extractHeuristic(chunk)
    val heuristicConcepts = heuristicMentions.map(_.concept)

    val llmFuture: Future[Seq[Concept]] =
      if (chunk.text.length >= 100) {
        val prompt = LLMConceptRefiner.buildPrompt(chunk.text, heuristicConcepts)

        ollamaClient
          .generateAsync(model, prompt, temperature = 0.0)
          .map(LLMConceptRefiner.parseConceptsFromResponse)
          .recover { case _ => Seq.empty[Concept] }

      } else Future.successful(Seq.empty[Concept])

    llmFuture.onComplete {
      case Success(llmConcepts) =>
        val allMentions =
          heuristicMentions ++ llmConcepts.map(c => Mentions(chunk.chunkId, c))
        resultFuture.complete(allMentions.asJava)

      case Failure(_) =>
        resultFuture.complete(heuristicMentions.asJava)
    }
  }

  override def timeout(
                        chunk: Chunk,
                        resultFuture: ResultFuture[Mentions]
                      ): Unit = {
    val heuristicMentions = ConceptExtractor.extractHeuristic(chunk)
    resultFuture.complete(heuristicMentions.asJava)
  }
}
