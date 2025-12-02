package com.graphrag.api.services

import com.graphrag.api.models.Evidence
import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class EvidenceService(
                       neo4jUri: String,
                       neo4jUsername: String,
                       neo4jPassword: String
                     )(implicit ec: ExecutionContext) {

  private val driver: Driver = GraphDatabase.driver(
    neo4jUri,
    AuthTokens.basic(neo4jUsername, neo4jPassword)
  )

  def getEvidence(evidenceId: String): Future[Option[Evidence]] = Future {
    val session = driver.session()
    try {
      val query = """
        MATCH (chunk:Chunk {chunkId: $evidenceId})
        RETURN chunk.chunkId as chunkId, chunk.text as text, chunk.sourceUri as source
      """

      // ✅ FIX: Use Values.parameters
      val params = Values.parameters("evidenceId", evidenceId)
      val result = session.run(query, params)

      if (result.hasNext) {
        val record = result.single()
        Some(Evidence(
          evidenceId = record.get("chunkId").asString(),
          text = record.get("text").asString(),
          chunkId = record.get("chunkId").asString(),
          source = record.get("source").asString()
        ))
      } else None

    } finally {
      session.close()
    }
  }

  def close(): Unit = {
    driver.close()
  }
}