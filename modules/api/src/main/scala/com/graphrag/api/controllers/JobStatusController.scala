package com.graphrag.api.controllers

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model._
import akka.http.scaladsl.Http
import akka.util.ByteString
import com.graphrag.core.config.Config
import io.circe._
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.{ExecutionContext, Future}

/**
 * JobStatusController
 *
 * REST controller for exposing Flink job status via:
 *
 *    GET /v1/job/status/:jobId
 *
 * Responsibilities:
 *   - Forward HTTP request to Flink REST API
 *   - Read JSON response from Flink
 *   - Extract only essential fields (jid, state)
 *   - Return simplified JSON to the API client
 *
 * This avoids exposing raw, noisy Flink REST data.
 */
class JobStatusController(
                           implicit val system: ActorSystem,
                           val ec: ExecutionContext,
                           val mat: Materializer     // Required by Akka HTTP for stream materialization
                         ) {

  /** Base URL for Flink REST API, provided via application config */
  private val flinkBaseUrl = Config.flinkurl;

  /**
   * Akka HTTP routes exposed by this controller.
   *
   * Example:
   *    GET /v1/job/status/<jobId>
   *
   * Calls Flink REST:
   *    GET <flinkBaseUrl>/jobs/<jobId>
   *
   * Transforms:
   *    { "jid": "...", "state": "RUNNING", ... }
   * into a reduced JSON:
   *    { "jobId": "...", "status": "RUNNING" }
   */
  val routes: Route =
    pathPrefix("v1" / "job" / "status" / Segment) { jobId =>
      get {

        // Send request to Flink REST API
        val responseF =
          Http().singleRequest(HttpRequest(uri = s"$flinkBaseUrl/jobs/$jobId"))

        // Parse the response body and reduce JSON structure
        val reducedJson = responseF.flatMap { resp =>
          resp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { body =>
            val jsonStr = body.utf8String

            // Attempt to parse Flink REST JSON
            parse(jsonStr) match {
              case Right(json) =>
                // Build minimal response
                Json.obj(
                  "jobId" -> json.hcursor.get[String]("jid").getOrElse(jobId).asJson,
                  "status" -> json.hcursor.get[String]("state").getOrElse("UNKNOWN").asJson
                )

              case Left(_) =>
                // Fallback response when Flink JSON cannot be parsed
                Json.obj(
                  "jobId" -> jobId.asJson,
                  "status" -> "ERROR_PARSING_RESPONSE".asJson
                )
            }
          }
        }

        // Send final JSON string to client
        complete(reducedJson.map(_.noSpaces))
      }
    }
}
