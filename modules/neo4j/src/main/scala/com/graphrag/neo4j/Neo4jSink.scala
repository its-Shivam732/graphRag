package com.graphrag.neo4j

import com.graphrag.core.models.{GraphNode, GraphEdge}
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.configuration.Configuration
import org.slf4j.LoggerFactory

/**
 * Neo4jSink: Flink sink function for writing to Neo4j
 */
class Neo4jNodeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphNode] {

  @transient private val log = LoggerFactory.getLogger(classOf[Neo4jNodeSink])

  @transient private val neo4jClientHolder = Array[Neo4jClient](null)
  private val buffer = scala.collection.mutable.ListBuffer[GraphNode]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    log.info(s"Opening Neo4j connection for node sink: $uri")

    neo4jClientHolder(0) = Neo4jClient(uri, username, password)

    neo4jClientHolder(0).testConnection() match {
      case scala.util.Success(true)  => log.info("Neo4j connection successful")
      case scala.util.Success(false) => log.error("Neo4j connection test FAILED")
      case scala.util.Failure(e)     => log.error(s"Neo4j connection error: ${e.getMessage}")
    }
  }

  override def invoke(node: GraphNode, context: SinkFunction.Context): Unit = {
    buffer += node
    if (buffer.size >= batchSize) flush()
  }

  override def close(): Unit = {
    if (buffer.nonEmpty) flush()

    if (neo4jClientHolder(0) != null) {
      log.info("Closing Neo4j client for node sink")
      neo4jClientHolder(0).close()
    }
  }

  private def flush(): Unit = {
    neo4jClientHolder(0).writeNodesBatch(buffer.toSeq) match {
      case scala.util.Success(count) =>
        log.info(s"Wrote $count nodes to Neo4j")
      case scala.util.Failure(e) =>
        log.error(s"Failed to write nodes: ${e.getMessage}")
    }
    buffer.clear()
  }
}

class Neo4jEdgeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphEdge] {

  @transient private val log = LoggerFactory.getLogger(classOf[Neo4jEdgeSink])

  @transient private val neo4jClientHolder = Array[Neo4jClient](null)
  private val buffer = scala.collection.mutable.ListBuffer[GraphEdge]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    log.info(s"Opening Neo4j connection for edge sink: $uri")
    neo4jClientHolder(0) = Neo4jClient(uri, username, password)
  }

  override def invoke(edge: GraphEdge, context: SinkFunction.Context): Unit = {
    buffer += edge
    if (buffer.size >= batchSize) flush()
  }

  override def close(): Unit = {
    if (buffer.nonEmpty) flush()

    if (neo4jClientHolder(0) != null) {
      log.info("Closing Neo4j client for edge sink")
      neo4jClientHolder(0).close()
    }
  }

  private def flush(): Unit = {
    neo4jClientHolder(0).writeEdgesBatch(buffer.toSeq) match {
      case scala.util.Success(count) =>
        log.info(s"Wrote $count edges to Neo4j")
      case scala.util.Failure(e) =>
        log.error(s"Failed to write edges: ${e.getMessage}")
    }
    buffer.clear()
  }
}
