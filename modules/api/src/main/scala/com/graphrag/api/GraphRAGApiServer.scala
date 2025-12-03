package com.graphrag.api

import akka.actor.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.graphrag.api.controllers._
import com.graphrag.api.services._
import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn

object GraphRAGApiServer {

  def main(args: Array[String]): Unit = {

    // Use CLASSIC ActorSystem (required by Akka HTTP)
    implicit val system: ActorSystem = ActorSystem("graphrag-api")
    implicit val ec: ExecutionContextExecutor = system.dispatcher
    implicit val mat: Materializer = SystemMaterializer(system).materializer

    // ==========================
    // Load environment config
    // ==========================

    val neo4jUri = sys.env.getOrElse("NEO4J_URI", "")
    val neo4jUsername = sys.env.getOrElse("NEO4J_USERNAME", "")
    val neo4jPassword = sys.env.getOrElse("NEO4J_PASSWORD", "")
    val ollamaUrl = sys.env.getOrElse("OLLAMA_URL", "http://localhost:11434")
    val ollamaModel = sys.env.getOrElse("OLLAMA_MODEL", "llama3")
    val port = sys.env.getOrElse("PORT", "8080").toInt

    // ==========================
    // Services
    // ==========================
    val queryService = new QueryService(neo4jUri, neo4jUsername, neo4jPassword, ollamaUrl, ollamaModel)
    val exploreService = new ExploreService(neo4jUri, neo4jUsername, neo4jPassword)
    val evidenceService = new EvidenceService(neo4jUri, neo4jUsername, neo4jPassword)
    val explainService = new ExplainService()

    // ==========================
    // Controllers
    // ==========================
    val queryController = new QueryController(queryService)
    val exploreController = new ExploreController(exploreService)
    val evidenceController = new EvidenceController(evidenceService)
    val explainController = new ExplainController(explainService)
    val jobStatusController = new JobStatusController()  // now works

    // ==========================
    // Routes
    // ==========================
    val routes: Route = concat(
      pathPrefix("health") {
        get { complete("OK") }
      },
      queryController.routes,
      exploreController.routes,
      evidenceController.routes,
      explainController.routes,
      jobStatusController.routes
    )

    val bindingFuture = Http().newServerAt("0.0.0.0", port).bind(routes)

    println(s"GraphRAG API Server online at http://localhost:$port/")
    println("Press RETURN to stop...")
    StdIn.readLine()

    // Graceful shutdown
    bindingFuture
      .flatMap(_.unbind())
      .onComplete { _ =>
        queryService.close()
        exploreService.close()
        evidenceService.close()
        explainService.close()
        system.terminate()
      }
  }
}
