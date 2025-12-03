package com.graphrag.neo4j

import com.graphrag.core.models.{GraphNode, GraphEdge}
import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.util.Try
import org.slf4j.LoggerFactory

/**
 * Neo4jClient: Thread-safe and Flink-safe client for Neo4j operations.
 */
class Neo4jClient(uri: String, username: String, password: String) extends Serializable {

  // Logger
  @transient private lazy val log = LoggerFactory.getLogger(classOf[Neo4jClient])

  @transient private lazy val driver: Driver = {
    println(s"Initializing Neo4j driver for URI: $uri")
    GraphDatabase.driver(uri, AuthTokens.basic(username, password))
  }

  // -----------------------------------------------------------------
  // Convert Scala Map[String, Any] → java.util.Map[String, Object]
  // REQUIRED by Neo4j Java Driver (your version)
  // -----------------------------------------------------------------
  private def toParams(base: Map[String, Any]): java.util.Map[String, Object] =
    base.map { case (k, v) => k -> v.asInstanceOf[Object] }.asJava

  // -----------------------------------------------------------------
  // Write Node
  // -----------------------------------------------------------------
  def writeNode(node: GraphNode): Try[Unit] = Try {
    println(s"Writing node: ${node.id} with labels ${node.labels.mkString(",")}")
    val session = driver.session()
    try {
      val labelString =
        if (node.labels.nonEmpty) node.labels.mkString(":", ":", "")
        else ""

      val idField =
        if (node.labels.contains("Concept")) "conceptId"
        else "chunkId"

      val setClause =
        node.properties.keys.map(k => s"n.$k = $$prop_$k").mkString(", ")

      val query =
        s"""
           MERGE (n$labelString { $idField: $$id })
           SET $setClause
         """

      val params = toParams(
        Map("id" -> node.id) ++
          node.properties.map { case (k, v) => s"prop_$k" -> v }
      )

      session.executeWrite { tx =>
        tx.run(query, params)
        ()
      }
    } finally {
      session.close()
    }
  }

  // -----------------------------------------------------------------
  // Write Edge
  // -----------------------------------------------------------------
  def writeEdge(edge: GraphEdge): Try[Unit] = Try {
    println(s"Writing edge ${edge.sourceId} -[${edge.relationType}]-> ${edge.targetId}")
    val session = driver.session()
    try {
      session.executeWrite { tx =>

        val (sourceLabel, targetLabel, sourceField, targetField) = edge.relationType match {
          case "MENTIONS"   => ("Chunk", "Concept", "chunkId", "conceptId")
          case "CO_OCCURS"  => ("Concept", "Concept", "conceptId", "conceptId")
          case "RELATES_TO" => ("Concept", "Concept", "conceptId", "conceptId")
          case _            => ("Node", "Node", "conceptId", "conceptId")
        }

        val propSetters =
          edge.properties.keys.map(k => s"r.$k = $$edge_$k").mkString(", ")

        val setClause = if (propSetters.nonEmpty) s"SET $propSetters" else ""

        val query =
          s"""
             MERGE (a:$sourceLabel {$sourceField: $$sourceId})
             MERGE (b:$targetLabel {$targetField: $$targetId})
             MERGE (a)-[r:${edge.relationType}]->(b)
             $setClause
           """

        val params = toParams(
          Map(
            "sourceId" -> edge.sourceId,
            "targetId" -> edge.targetId
          ) ++
            edge.properties.map { case (k, v) => s"edge_$k" -> v }
        )

        tx.run(query, params)
        ()
      }
    } finally {
      session.close()
    }
  }

  // -----------------------------------------------------------------
  // Batch Write Nodes
  // -----------------------------------------------------------------
  def writeNodesBatch(nodes: Seq[GraphNode]): Try[Int] = Try {
    println(s"Batch writing ${nodes.size} nodes ...")
    val session = driver.session()
    val countBuffer = Array(0)

    try {
      nodes.grouped(100).foreach { batch =>
        session.executeWrite { tx =>
          batch.foreach { node =>

            val labelString =
              if (node.labels.nonEmpty) node.labels.mkString(":", ":", "")
              else ""

            val idField =
              if (node.labels.contains("Concept")) "conceptId"
              else "chunkId"

            val setClause =
              node.properties.keys.map(k => s"n.$k = $$prop_$k").mkString(", ")

            val query =
              s"""
                 MERGE (n$labelString { $idField: $$id })
                 SET $setClause
               """

            val params = toParams(
              Map("id" -> node.id) ++
                node.properties.map { case (k, v) => s"prop_$k" -> v }
            )

            tx.run(query, params)
            countBuffer(0) += 1
          }
        }
      }
    } finally {
      session.close()
    }

   println(s"Batch write complete: ${countBuffer(0)} nodes")
    countBuffer(0)
  }

  // -----------------------------------------------------------------
  // Batch Write Edges
  // -----------------------------------------------------------------
  def writeEdgesBatch(edges: Seq[GraphEdge]): Try[Int] = Try {
    println(s"Batch writing ${edges.size} edges ...")
    val session = driver.session()
    val countBuffer = Array(0)

    try {
      edges.grouped(100).foreach { batch =>
        session.executeWrite { tx =>
          batch.foreach { edge =>

            val (sourceLabel, targetLabel, sourceField, targetField) = edge.relationType match {
              case "MENTIONS"   => ("Chunk", "Concept", "chunkId", "conceptId")
              case "CO_OCCURS"  => ("Concept", "Concept", "conceptId", "conceptId")
              case "RELATES_TO" => ("Concept", "Concept", "conceptId", "conceptId")
              case _            => ("Node", "Node", "conceptId", "conceptId")
            }

            val propSetters =
              edge.properties.keys.map(k => s"r.$k = $$edge_$k").mkString(", ")

            val setClause = if (propSetters.nonEmpty) s"SET $propSetters" else ""

            val query =
              s"""
                 MERGE (a:$sourceLabel {$sourceField: $$sourceId})
                 MERGE (b:$targetLabel {$targetField: $$targetId})
                 MERGE (a)-[r:${edge.relationType}]->(b)
                 $setClause
               """

            val params = toParams(
              Map(
                "sourceId" -> edge.sourceId,
                "targetId" -> edge.targetId
              ) ++
                edge.properties.map { case (k, v) => s"edge_$k" -> v }
            )

            tx.run(query, params)
            countBuffer(0) += 1
          }
        }
      }
    } finally {
      session.close()
    }

    println(s"Batch write complete: ${countBuffer(0)} edges")
    countBuffer(0)
  }

  // -----------------------------------------------------------------
  // Test Connection
  // -----------------------------------------------------------------
  def testConnection(): Try[Boolean] = Try {
    println("Testing Neo4j connection...")
    val session = driver.session()
    try {
      session.executeRead { tx =>
        val res = tx.run("RETURN 1 AS num")
        val ok = res.single().get("num").asInt() == 1
        if (ok) println("Neo4j connection OK")
        else log.warn("Neo4j connection test returned unexpected value")
        ok
      }
    } finally {
      session.close()
    }
  }

  def close(): Unit = {
    println("Closing Neo4j driver")
    driver.close()
  }
}

object Neo4jClient {
  def apply(uri: String, username: String, password: String): Neo4jClient =
    new Neo4jClient(uri, username, password)
}
