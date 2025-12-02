package com.graphrag.api.controllers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import com.graphrag.api.services.ExplainService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import com.graphrag.api.models.ApiModels._  // ✅ ADD THIS
import scala.concurrent.ExecutionContext

class ExplainController(explainService: ExplainService)(implicit ec: ExecutionContext) {
  val routes: Route = pathPrefix("v1" / "explain" / "trace") {
    path(Segment) { requestId =>
      get {
        onSuccess(explainService.getTrace(requestId)) {
          case Some(trace) => complete(trace)
          case None => complete(StatusCodes.NotFound -> s"Trace not found: $requestId")
        }
      }
    }
  }
}