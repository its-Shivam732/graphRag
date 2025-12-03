package com.graphrag.api.services

import com.graphrag.api.models._
import com.graphrag.api.models.ApiModels._
import org.neo4j.driver._
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

/**
 * ExploreService:
 *
 * Provides a lightweight graph-exploration service used by
 * the `/v1/graph/concept/:id/neighbors` endpoint.
 *
 * The goal is to return a simplified graph:
 *
 *   {
 *     "nodes": [{id: ...}, ...],
 *     "edges": [{from: ..., to: ...}, ...]
 *   }
 *
 * This is used for graph visualization or contextual browsing
 * around a particular concept.
 *
 * Responsibilities:
 *   - Connect to Neo4j
 *   - Query neighbor concepts (1..depth hops away)
 *   - Query edges between center ↔ neighbors
 *   - Convert to ExploreGraph domain model
 */
class ExploreService(
                      neo4jUri: String,
                      neo4jUsername: String,
                      neo4jPassword: String
                    )(implicit ec: ExecutionContext) {

  private val logger = LoggerFactory.getLogger(classOf[ExploreService])

  /** Neo4j driver for Cypher execution */
  private val driver: Driver =
    GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword))

  /**
   * explore()
   *
   * Main entry point for API. Builds a simple concept-neighborhood graph.
   *
   * @param conceptId  Center conceptId for exploration
   * @param direction  Traversal direction: in / out / both
   * @param depth      Max hops from center
   * @param limit      Max neighbor count
   * @param edgeTypes  Relationship types to consider
   *
   * @return ExploreGraph(nodes, edges)
   */
  def explore(
               conceptId: String,
               direction: String = "both",
               depth: Int = 1,
               limit: Int = 100,
               edgeTypes: Seq[String] = Seq("RELATES_TO", "CO_OCCURS", "MENTIONS")
             ): Future[ExploreGraph] = Future {

    val session = driver.session()

    try {
      // ---------------------------------------------------------
      // 1. Center node
      // ---------------------------------------------------------
      val centerNode = ExploreNode(id = conceptId)

      // ---------------------------------------------------------
      // 2. Fetch neighbors from Neo4j
      //    (returns NeighborNode model → we convert to ExploreNode)
      // ---------------------------------------------------------
      val neighbors =
        getNeighbors(conceptId, direction, depth, limit, edgeTypes, session)
          .map(n => ExploreNode(id = n.surface)) // using surface as display ID

      // ---------------------------------------------------------
      // 3. Fetch edges between center ↔ neighbors
      // ---------------------------------------------------------
      val neighborIds = neighbors.map(_.id)

      val edges =
        getEdges(conceptId, neighborIds, edgeTypes, session)
          .map(e => ExploreEdge(from = e.source, to = e.target))

      // ---------------------------------------------------------
      // 4. Build graph response
      // ---------------------------------------------------------
      ExploreGraph(
        nodes = Seq(centerNode) ++ neighbors,
        edges = edges
      )

    } finally {
      session.close()
    }
  }

  // ---------------------------------------------------------------------
  // INTERNAL QUERY METHODS (no changes to logic)
  // ---------------------------------------------------------------------

  /**
   * getNeighbors:
   *
   * Returns a list of NeighborNode objects representing concept neighbors
   * within `depth` hops via selected relationship types.
   *
   * Traversal direction can be:
   *   - "in"   <--
   *   - "out"  -->
   *   - "both" <-- -->
   */
  private def getNeighbors(
                            conceptId: String,
                            direction: String,
                            depth: Int,
                            limit: Int,
                            edgeTypes: Seq[String],
                            session: Session
                          ): Seq[NeighborNode] = {

    val relationPattern = edgeTypes.mkString("|")

    val directionPattern = direction match {
      case "in"  => s"<-[r:$relationPattern*1..$depth]-"
      case "out" => s"-[r:$relationPattern*1..$depth]->"
      case _     => s"-[r:$relationPattern*1..$depth]-"
    }

    val query = s"""
      MATCH (center:Concept {conceptId: $$conceptId})$directionPattern(neighbor:Concept)
      WHERE neighbor.conceptId <> $$conceptId
      RETURN DISTINCT
        neighbor.conceptId AS conceptId,
        neighbor.lemma     AS lemma,
        neighbor.surface   AS surface,
        length(r)          AS distance
      ORDER BY distance, neighbor.surface
      LIMIT $$limit
    """

    logger.info(query)
    val params = Values.parameters(
      "conceptId", conceptId,
      "limit", Integer.valueOf(limit)
    )

    val result = session.run(query, params)

    result.list().asScala.map { record =>
      NeighborNode(
        conceptId = record.get("conceptId").asString(),
        lemma     = record.get("lemma").asString(),
        surface   = record.get("surface").asString(),
        distance  = record.get("distance").asInt()
      )
    }.toSeq
  }

  /**
   * getEdges:
   *
   * Returns raw edges between the center concept and its neighbors.
   *
   * This only returns edges where:
   *   - (center → neighbor) or (neighbor → center)
   *   - relation type in edgeTypes
   */
  private def getEdges(
                        centerId: String,
                        neighborIds: Seq[String],
                        edgeTypes: Seq[String],
                        session: Session
                      ): Seq[NeighborEdge] = {

    if (neighborIds.isEmpty) return Seq.empty

    val relationPattern = edgeTypes.mkString("|")

    val query = s"""
      MATCH (c1:Concept)-[r:$relationPattern]-(c2:Concept)
      WHERE
        (c1.conceptId = $$centerId AND c2.conceptId IN $$neighborIds) OR
        (c2.conceptId = $$centerId AND c1.conceptId IN $$neighborIds)
      RETURN
        c1.surface AS source,
        c2.surface AS target,
        type(r)    AS relationType,
        properties(r) AS props
    """

    val params = Values.parameters(
      "centerId", centerId,
      "neighborIds", neighborIds.asJava
    )

    val result = session.run(query, params)

    /**
     * The ExploreGraph API does not currently expose edge properties.
     * We return only from/to.
     */
    result.list().asScala.map { record =>
      NeighborEdge(
        source       = record.get("source").asString(),
        target       = record.get("target").asString(),
        relationType = record.get("relationType").asString(),
        properties   = Map.empty[String, Any]
      )
    }.toSeq
  }

  /** Close neo4j driver */
  def close(): Unit = driver.close()
}
