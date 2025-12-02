package com.graphrag.neo4j

import com.graphrag.core.models.{GraphNode, GraphEdge}
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction
import org.apache.flink.streaming.api.functions.sink.SinkFunction
import org.apache.flink.configuration.Configuration

/**
 * Neo4jSink: Flink sink function for writing to Neo4j
 */
class Neo4jNodeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphNode] {

  @transient private val neo4jClientHolder = Array[Neo4jClient](null)
  private val buffer = scala.collection.mutable.ListBuffer[GraphNode]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    neo4jClientHolder(0) = Neo4jClient(uri, username, password)
    println(s"Opened Neo4j connection for nodes: $uri")

    // Test connection
    neo4jClientHolder(0).testConnection() match {
      case scala.util.Success(true) => println("✓ Neo4j connection successful")
      case scala.util.Success(false) => println("✗ Neo4j connection test failed")
      case scala.util.Failure(e) => println(s"✗ Neo4j connection error: ${e.getMessage}")
    }
  }

  override def invoke(node: GraphNode, context: SinkFunction.Context): Unit = {
    buffer += node

    if (buffer.size >= batchSize) {
      flush()
    }
  }

  override def close(): Unit = {
    if (buffer.nonEmpty) {
      flush()
    }
    if (neo4jClientHolder(0) != null) {
      neo4jClientHolder(0).close()
    }
  }

  private def flush(): Unit = {
    neo4jClientHolder(0).writeNodesBatch(buffer.toSeq) match {
      case scala.util.Success(count) =>
        println(s"✓ Wrote $count nodes to Neo4j")
      case scala.util.Failure(e) =>
        println(s"✗ Failed to write nodes: ${e.getMessage}")
    }
    buffer.clear()
  }
}

class Neo4jEdgeSink(uri: String, username: String, password: String)
  extends RichSinkFunction[GraphEdge] {

  @transient private val neo4jClientHolder = Array[Neo4jClient](null)
  private val buffer = scala.collection.mutable.ListBuffer[GraphEdge]()
  private val batchSize = 100

  override def open(parameters: Configuration): Unit = {
    neo4jClientHolder(0) = Neo4jClient(uri, username, password)
    println(s"Opened Neo4j connection for edges: $uri")
  }

  override def invoke(edge: GraphEdge, context: SinkFunction.Context): Unit = {
    buffer += edge

    if (buffer.size >= batchSize) {
      flush()
    }
  }

  override def close(): Unit = {
    if (buffer.nonEmpty) {
      flush()
    }
    if (neo4jClientHolder(0) != null) {
      neo4jClientHolder(0).close()
    }
  }

  private def flush(): Unit = {
    neo4jClientHolder(0).writeEdgesBatch(buffer.toSeq) match {
      case scala.util.Success(count) =>
        println(s"✓ Wrote $count edges to Neo4j")
      case scala.util.Failure(e) =>
        println(s"✗ Failed to write edges: ${e.getMessage}")
    }
    buffer.clear()
  }
}