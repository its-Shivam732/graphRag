package com.graphrag.api.controllers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model.StatusCodes
import com.graphrag.api.services.{EvidenceService, ExplainService}
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import scala.concurrent.ExecutionContext
import com.graphrag.api.models.ApiModels._  // ✅ ADD THIS

//The primary function of the EvidenceController is to expose a single GET endpoint that allows clients to
// fetch a piece of evidence from the underlying EvidenceService using a unique identifier.
class EvidenceController(evidenceService: EvidenceService)(implicit ec: ExecutionContext) {
  val routes: Route = pathPrefix("v1" / "evidence") {
    path(Segment) { evidenceId =>
      get {
        onSuccess(evidenceService.getEvidence(evidenceId)) {
          case Some(evidence) => complete(evidence)
          case None => complete(StatusCodes.NotFound -> s"Evidence not found: $evidenceId")
        }
      }
    }
  }
}