package com.graphrag.api.controllers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.graphrag.api.services.ExploreService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import scala.concurrent.ExecutionContext
import com.graphrag.api.models.ApiModels._  // ✅ ADD THIS

class ExploreController(exploreService: ExploreService)(implicit ec: ExecutionContext) {

  val routes: Route = pathPrefix("v1" / "graph" / "concept") {
    path(Segment / "neighbors") { conceptId =>
      get {
        parameters(
          "direction".withDefault("both"),
          "depth".as[Int].withDefault(1),
          "limit".as[Int].withDefault(100),
          "edgeTypes".withDefault("RELATES_TO,CO_OCCURS,MENTIONS")
        ) { (direction, depth, limit, edgeTypesStr) =>
          val edgeTypes = edgeTypesStr.split(",").toSeq
          // ✅ FIX: Wrap Future in onSuccess
          onSuccess(exploreService.getNeighborhood(conceptId, direction, depth, limit, edgeTypes)) { neighborhood =>
            complete(neighborhood)
          }
        }
      }
    }
  }
}