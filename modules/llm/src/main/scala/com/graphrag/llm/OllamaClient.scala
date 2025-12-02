package com.graphrag.llm

import sttp.client3._
import sttp.client3.circe._
import io.circe.generic.auto._
import scala.util.{Try, Success, Failure}
import scala.concurrent.{Future, ExecutionContext}

/**
 * OllamaClient: Async HTTP client for Ollama API
 */
class OllamaClient(baseUrl: String)(implicit ec: ExecutionContext) {

  private val backend = HttpURLConnectionBackend()

  /**
   * Async generate - returns Future
   */
  def generateAsync(
                     model: String,
                     prompt: String,
                     temperature: Double = 0.0
                   ): Future[String] = {
    Future {
      generate(model, prompt, temperature).get
    }
  }

  /**
   * Synchronous generate (for backward compatibility)
   */
  def generate(
                model: String,
                prompt: String,
                temperature: Double = 0.0
              ): Try[String] = Try {

    val requestBody = OllamaGenerateRequest(
      model = model,
      prompt = prompt,
      stream = false,
      options = Some(OllamaOptions(temperature = temperature))
    )

    val request = basicRequest
      .post(uri"$baseUrl/api/generate")
      .body(requestBody)
      .response(asJson[OllamaGenerateResponse])
      .readTimeout(scala.concurrent.duration.Duration(60, "seconds"))

    val response = request.send(backend)

    response.body match {
      case Right(ollamaResponse) => ollamaResponse.response
      case Left(error) => throw new Exception(s"Ollama API error: $error")
    }
  }

  def close(): Unit = {
    backend.close()
  }
}

// Request/Response models
case class OllamaOptions(temperature: Double)

case class OllamaGenerateRequest(
                                  model: String,
                                  prompt: String,
                                  stream: Boolean,
                                  options: Option[OllamaOptions] = None
                                )

case class OllamaGenerateResponse(
                                   model: String,
                                   response: String,
                                   done: Boolean
                                 )

object OllamaClient {
  def apply(baseUrl: String)(implicit ec: ExecutionContext): OllamaClient =
    new OllamaClient(baseUrl)
}