package com.graphrag.api

import akka.actor.typed.ActorSystem
import akka.actor.typed.scaladsl.Behaviors
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import com.graphrag.api.controllers._
import com.graphrag.api.services._
import scala.concurrent.ExecutionContextExecutor
import scala.io.StdIn

object GraphRAGApiServer {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem[Nothing] = ActorSystem(Behaviors.empty, "graphrag-api")
    implicit val executionContext: ExecutionContextExecutor = system.executionContext

    // Configuration from environment
    val neo4jUri = sys.env.getOrElse("NEO4J_URI", "neo4j+s://your-instance.databases.neo4j.io")
    val neo4jUsername = sys.env.getOrElse("NEO4J_USERNAME", "neo4j")
    val neo4jPassword = sys.env.getOrElse("NEO4J_PASSWORD", "")
    val ollamaUrl = sys.env.getOrElse("OLLAMA_URL", "http://localhost:11434")
    val ollamaModel = sys.env.getOrElse("OLLAMA_MODEL", "llama3")
    val port = sys.env.getOrElse("PORT", "8080").toInt

    // Initialize services
    val queryService = new QueryService(neo4jUri, neo4jUsername, neo4jPassword, ollamaUrl, ollamaModel)
    val exploreService = new ExploreService(neo4jUri, neo4jUsername, neo4jPassword)
    val evidenceService = new EvidenceService(neo4jUri, neo4jUsername, neo4jPassword)
    val explainService = new ExplainService()

    // Initialize controllers
    val queryController = new QueryController(queryService)
    val exploreController = new ExploreController(exploreService)
    val evidenceController = new EvidenceController(evidenceService)
    val explainController = new ExplainController(explainService)

    // Combine routes
    val routes: Route = concat(
      pathPrefix("health") {
        get {
          complete("OK")
        }
      },
      queryController.routes,
      exploreController.routes,
      evidenceController.routes,
      explainController.routes
    )

    val bindingFuture = Http().newServerAt("0.0.0.0", port).bind(routes)

    println(s"GraphRAG API Server online at http://localhost:$port/")
    println("Press RETURN to stop...")
    StdIn.readLine()

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