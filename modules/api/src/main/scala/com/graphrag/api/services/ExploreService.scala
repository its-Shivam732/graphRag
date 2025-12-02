package com.graphrag.api.services

import com.graphrag.api.models._
import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class ExploreService(
                      neo4jUri: String,
                      neo4jUsername: String,
                      neo4jPassword: String
                    )(implicit ec: ExecutionContext) {

  private val driver: Driver = GraphDatabase.driver(
    neo4jUri,
    AuthTokens.basic(neo4jUsername, neo4jPassword)
  )

  def getNeighborhood(
                       conceptId: String,
                       direction: String = "both",
                       depth: Int = 1,
                       limit: Int = 100,
                       edgeTypes: Seq[String] = Seq("RELATES_TO", "CO_OCCURS", "MENTIONS")
                     ): Future[Neighborhood] = Future {
    val session = driver.session()
    try {
      val centerConcept = getConcept(conceptId, session)
      val neighbors = getNeighbors(conceptId, direction, depth, limit, edgeTypes, session)
      val edges = getEdges(conceptId, neighbors.map(_.conceptId), edgeTypes, session)

      Neighborhood(
        centerConcept = centerConcept,
        neighbors = neighbors,
        edges = edges
      )

    } finally {
      session.close()
    }
  }

  private def getConcept(conceptId: String, session: Session): ConceptResult = {
    val query = """
      MATCH (c:Concept {conceptId: $conceptId})
      RETURN c.conceptId as conceptId, c.lemma as lemma, c.surface as surface
    """

    // ✅ FIX: Use Values.parameters
    val params = Values.parameters("conceptId", conceptId)
    val result = session.run(query, params)

    if (result.hasNext) {
      val record = result.single()
      ConceptResult(
        conceptId = record.get("conceptId").asString(),
        lemma = record.get("lemma").asString(),
        surface = record.get("surface").asString(),
        relevanceScore = 1.0
      )
    } else {
      throw new RuntimeException(s"Concept not found: $conceptId")
    }
  }

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
      case "in" => s"<-[r:$relationPattern*1..$depth]-"
      case "out" => s"-[r:$relationPattern*1..$depth]->"
      case _ => s"-[r:$relationPattern*1..$depth]-"
    }

    val query = s"""
      MATCH (center:Concept {conceptId: $$conceptId})$directionPattern(neighbor:Concept)
      WHERE neighbor.conceptId <> $$conceptId
      RETURN DISTINCT neighbor.conceptId as conceptId,
             neighbor.lemma as lemma,
             neighbor.surface as surface,
             length(r) as distance
      ORDER BY distance, neighbor.surface
      LIMIT $$limit
    """

    // ✅ FIX: Use Values.parameters
    val params = Values.parameters(
      "conceptId", conceptId,
      "limit", Integer.valueOf(limit)
    )
    val result = session.run(query, params)

    result.list().asScala.map { record =>
      NeighborNode(
        conceptId = record.get("conceptId").asString(),
        lemma = record.get("lemma").asString(),
        surface = record.get("surface").asString(),
        distance = record.get("distance").asInt()
      )
    }.toSeq
  }

  private def getEdges(
                        centerConceptId: String,
                        neighborIds: Seq[String],
                        edgeTypes: Seq[String],
                        session: Session
                      ): Seq[NeighborEdge] = {
    if (neighborIds.isEmpty) return Seq.empty

    val relationPattern = edgeTypes.mkString("|")

    val query = s"""
      MATCH (c1:Concept)-[r:$relationPattern]-(c2:Concept)
      WHERE (c1.conceptId = $$centerId AND c2.conceptId IN $$neighborIds)
         OR (c2.conceptId = $$centerId AND c1.conceptId IN $$neighborIds)
      RETURN c1.conceptId as source, c2.conceptId as target,
             type(r) as relationType, properties(r) as props
    """

    // ✅ FIX: Use Values.parameters
    val params = Values.parameters(
      "centerId", centerConceptId,
      "neighborIds", neighborIds.asJava
    )
    val result = session.run(query, params)

    result.list().asScala.map { record =>
      val props = record.get("props").asMap().asScala.toMap.map {
        case (k, v) => k -> (v: Any)
      }

      NeighborEdge(
        source = record.get("source").asString(),
        target = record.get("target").asString(),
        relationType = record.get("relationType").asString(),
        properties = props
      )
    }.toSeq
  }

  def close(): Unit = {
    driver.close()
  }
}