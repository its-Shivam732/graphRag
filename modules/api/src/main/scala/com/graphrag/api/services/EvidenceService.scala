package com.graphrag.api.services

import com.graphrag.api.models._
import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import org.slf4j.LoggerFactory

/**
 * EvidenceService
 *
 * Provides expanded evidence lookup for API queries.
 *
 * Responsibilities:
 *   - Connect to Neo4j
 *   - Retrieve a Chunk node + its parent Paper node
 *   - Convert the result into ExpandedEvidence model
 *
 * This is used by the API endpoint: GET /v1/evidence/:id
 */
class EvidenceService(
                       neo4jUri: String,
                       neo4jUsername: String,
                       neo4jPassword: String
                     )(implicit ec: ExecutionContext) {

  /** SLF4J logger */
  private val logger = LoggerFactory.getLogger(classOf[EvidenceService])

  /**
   * Neo4j driver shared per service instance.
   */
  private val driver: Driver =
    GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword))

  /**
   * getEvidence
   *
   * Fetches a chunk and its associated paper record.
   */
  def getEvidence(evidenceId: String): Future[Option[ExpandedEvidence]] = Future {

    logger.info(s"Fetching evidence for id=$evidenceId")

    val session = driver.session()

    try {
      val query =
        """
        MATCH (p:Paper)-[:HAS_CHUNK]->(chunk:Chunk {chunkId: $evidenceId})
        RETURN
          chunk.chunkId AS evidenceId,
          chunk.text AS text,
          p.paperId AS paperId,
          p.title AS title
        """

      logger.debug(s"Executing Cypher query for evidenceId=$evidenceId")

      val params = Values.parameters("evidenceId", evidenceId)
      val result = session.run(query, params)

      if (result.hasNext) {
        val record = result.single()

        logger.info(s"Evidence found for id=$evidenceId in paper ${record.get("paperId").asString()}")

        Some(
          ExpandedEvidence(
            evidenceId = record.get("evidenceId").asString(),
            paperId = record.get("paperId").asString(),
            text = record.get("text").asString(),
            docRef = EvidenceDocRef(
              title = record.get("title").asString()
            )
          )
        )

      } else {
        logger.warn(s"No evidence found for id=$evidenceId")
        None
      }

    } catch {
      case e: Exception =>
        logger.error(s"Error while querying evidence id=$evidenceId: ${e.getMessage}", e)
        None
    } finally {
      session.close()
    }
  }

  /** Close Neo4j driver */
  def close(): Unit = {
    logger.info("Closing Neo4j driver in EvidenceService")
    driver.close()
  }
}
