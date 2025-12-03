package com.graphrag.neo4j

import com.graphrag.core.models.{GraphNode, GraphEdge}
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.configuration.Configuration
import org.slf4j.LoggerFactory

/**
 * Neo4jNodeSink - Batch writes nodes to Neo4j
 */
class Neo4jNodeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphNode] {

  @transient private lazy val neo4jClient: Neo4jClient = {
    Neo4jClient(uri, username, password)
  }

  @transient private lazy val logger: org.slf4j.Logger = {
    LoggerFactory.getLogger(classOf[Neo4jNodeSink])
  }

  private val buffer = scala.collection.mutable.ListBuffer[GraphNode]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    // Force initialization
    val _ = neo4jClient

    neo4jClient.testConnection() match {
      case scala.util.Success(true) =>
        logger.info("Neo4j connection successful for NodeSink")
      case scala.util.Success(false) =>
        logger.warn("Neo4j connection test FAILED for NodeSink")
      case scala.util.Failure(e) =>
        logger.error(s"Neo4j connection error for NodeSink: ${e.getMessage}")
    }
  }

  override def invoke(node: GraphNode, context: SinkFunction.Context): Unit = {
    buffer += node
    if (buffer.size >= batchSize) {
      flush()
    }
  }

  override def close(): Unit = {
    try {
      if (buffer.nonEmpty) {
        flush()
      }
      logger.info("Closing Neo4j NodeSink")
      neo4jClient.close()
    } catch {
      case e: Exception =>
        try {
          logger.error(s"Error closing Neo4jNodeSink: ${e.getMessage}", e)
        } catch {
          case _: Throwable =>
            System.err.println(s"Error closing Neo4jNodeSink: ${e.getMessage}")
        }
    } finally {
      super.close()
    }
  }

  private def flush(): Unit = {
    if (buffer.nonEmpty) {
      neo4jClient.writeNodesBatch(buffer.toSeq) match {
        case scala.util.Success(count) =>
          logger.debug(s"Wrote $count nodes to Neo4j")
        case scala.util.Failure(e) =>
          logger.error(s"Failed to write nodes: ${e.getMessage}", e)
      }
      buffer.clear()
    }
  }
}

/**
 * Neo4jEdgeSink - Batch writes edges to Neo4j
 */
class Neo4jEdgeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphEdge] {

  @transient private lazy val neo4jClient: Neo4jClient = {
    Neo4jClient(uri, username, password)
  }

  @transient private lazy val logger: org.slf4j.Logger = {
    LoggerFactory.getLogger(classOf[Neo4jEdgeSink])
  }

  private val buffer = scala.collection.mutable.ListBuffer[GraphEdge]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    // Force initialization
    val _ = neo4jClient

    neo4jClient.testConnection() match {
      case scala.util.Success(true) =>
        logger.info("Neo4j connection successful for EdgeSink")
      case scala.util.Success(false) =>
        logger.warn("Neo4j connection test FAILED for EdgeSink")
      case scala.util.Failure(e) =>
        logger.error(s"Neo4j connection error for EdgeSink: ${e.getMessage}")
    }
  }

  override def invoke(edge: GraphEdge, context: SinkFunction.Context): Unit = {
    buffer += edge
    if (buffer.size >= batchSize) {
      flush()
    }
  }

  override def close(): Unit = {
    try {
      if (buffer.nonEmpty) {
        flush()
      }
      logger.info("Closing Neo4j EdgeSink")
      neo4jClient.close()
    } catch {
      case e: Exception =>
        try {
          logger.error(s"Error closing Neo4jEdgeSink: ${e.getMessage}", e)
        } catch {
          case _: Throwable =>
            System.err.println(s"Error closing Neo4jEdgeSink: ${e.getMessage}")
        }
    } finally {
      super.close()
    }
  }

  private def flush(): Unit = {
    if (buffer.nonEmpty) {
      neo4jClient.writeEdgesBatch(buffer.toSeq) match {
        case scala.util.Success(count) =>
          logger.debug(s"Wrote $count edges to Neo4j")
        case scala.util.Failure(e) =>
          logger.error(s"Failed to write edges: ${e.getMessage}", e)
      }
      buffer.clear()
    }
  }
}