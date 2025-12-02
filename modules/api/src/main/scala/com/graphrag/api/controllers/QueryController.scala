package com.graphrag.api.controllers

import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.graphrag.api.models._
import com.graphrag.api.services.QueryService
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import io.circe.generic.auto._
import com.graphrag.api.models.ApiModels._  // ✅ ADD THIS
import scala.concurrent.ExecutionContext

class QueryController(queryService: QueryService)(implicit ec: ExecutionContext) {

  val routes: Route = pathPrefix("v1" / "query") {
    post {
      entity(as[QueryRequest]) { request =>
        complete(queryService.query(request))
      }
    }
  }
}
