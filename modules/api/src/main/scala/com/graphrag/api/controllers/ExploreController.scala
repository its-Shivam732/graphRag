package com.graphrag.api.controllers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.graphrag.api.services.ExploreService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import scala.concurrent.ExecutionContext
import com.graphrag.api.models.ApiModels._

//The primary function of the ExploreController is to provide a flexible REST
// endpoint for navigating and retrieving subgraph data (neighbors) around a
// specific conceptual node in your graph.
// It allows clients to control the depth and scope of the exploration using query parameters.
class ExploreController(exploreService: ExploreService)(implicit ec: ExecutionContext) {

  val routes: Route =
    pathPrefix("v1" / "graph" / "concept") {
      path(Segment / "neighbors") { conceptId =>
        get {
          parameters(
            "direction".withDefault("both"),
            "depth".as[Int].withDefault(1),
            "limit".as[Int].withDefault(100),
            "edgeTypes".withDefault("RELATES_TO,CO_OCCURS,MENTIONS")
          ) { (direction, depth, limit, edgeTypesStr) =>

            val edgeTypes = edgeTypesStr.split(",").toSeq

            onSuccess(
              exploreService.explore(conceptId, direction, depth, limit, edgeTypes)
            ) { graph =>
              complete(graph)
            }
          }
        }
      }
    }
}
