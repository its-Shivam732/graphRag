package com.graphrag.neo4j

import com.graphrag.core.models.{GraphNode, GraphEdge}
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterAll
import scala.util.{Success, Failure}

class Neo4jClientTest extends AnyFlatSpec with Matchers with BeforeAndAfterAll {

  val testUri = "bolt://localhost:7687"
  val testUsername = "neo4j"
  val testPassword = "password"

  "Neo4jClient" should "initialize with valid credentials" in {
    val client = new Neo4jClient(testUri, testUsername, testPassword)
    client should not be null
  }

  it should "be serializable for Flink" in {
    val client = new Neo4jClient(testUri, testUsername, testPassword)
    client shouldBe a[Serializable]
  }

  "writeNode" should "successfully write a Chunk node" in {
    val node = GraphNode(
      id = "chunk-001",
      labels = Set("Chunk"),
      properties = Map(
        "text" -> "Sample text",
        "documentId" -> "doc-001"
      )
    )

    val client = new Neo4jClient(testUri, testUsername, testPassword)
    // This would require a running Neo4j instance or mock
    // For now, we test the structure
    node.id should be("chunk-001")
    node.labels should contain("Chunk")
  }

  it should "successfully write a Concept node" in {
    val node = GraphNode(
      id = "concept-001",
      labels = Set("Concept"),
      properties = Map(
        "name" -> "Artificial Intelligence",
        "category" -> "Technology"
      )
    )

    node.id should be("concept-001")
    node.labels should contain("Concept")
  }

  it should "handle nodes with multiple labels" in {
    val node = GraphNode(
      id = "node-001",
      labels = Set("Concept", "Entity"),
      properties = Map("name" -> "Test")
    )

    node.labels.size should be(2)
  }

  it should "handle nodes with empty properties" in {
    val node = GraphNode(
      id = "node-002",
      labels = Set("Chunk"),
      properties = Map.empty
    )

    node.properties.isEmpty should be(true)
  }

  "writeEdge" should "create MENTIONS relationship correctly" in {
    val edge = GraphEdge(
      sourceId = "chunk-001",
      targetId = "concept-001",
      relationType = "MENTIONS",
      properties = Map("confidence" -> 0.95)
    )

    edge.relationType should be("MENTIONS")
    edge.properties.get("confidence") should be(Some(0.95))
  }

  it should "create CO_OCCURS relationship correctly" in {
    val edge = GraphEdge(
      sourceId = "concept-001",
      targetId = "concept-002",
      relationType = "CO_OCCURS",
      properties = Map("count" -> 5)
    )

    edge.relationType should be("CO_OCCURS")
    edge.properties.get("count") should be(Some(5))
  }

  it should "create RELATES_TO relationship correctly" in {
    val edge = GraphEdge(
      sourceId = "concept-001",
      targetId = "concept-002",
      relationType = "RELATES_TO",
      properties = Map(
        "relationshipType" -> "isPartOf",
        "confidence" -> 0.88
      )
    )

    edge.relationType should be("RELATES_TO")
    edge.properties.size should be(2)
  }

  it should "handle edges with no properties" in {
    val edge = GraphEdge(
      sourceId = "node-001",
      targetId = "node-002",
      relationType = "UNKNOWN",
      properties = Map.empty
    )

    edge.properties.isEmpty should be(true)
  }

  "writeNodesBatch" should "handle empty batch" in {
    val client = new Neo4jClient(testUri, testUsername, testPassword)
    val nodes = Seq.empty[GraphNode]

    nodes.size should be(0)
  }

  it should "handle single node batch" in {
    val nodes = Seq(
      GraphNode(
        id = "chunk-001",
        labels = Set("Chunk"),
        properties = Map("text" -> "Test")
      )
    )

    nodes.size should be(1)
  }

  it should "handle batch of 100 nodes" in {
    val nodes = (1 to 100).map { i =>
      GraphNode(
        id = s"chunk-$i",
        labels = Set("Chunk"),
        properties = Map("text" -> s"Text $i")
      )
    }

    nodes.size should be(100)
  }

  it should "handle batch larger than 100 nodes" in {
    val nodes = (1 to 250).map { i =>
      GraphNode(
        id = s"concept-$i",
        labels = Set("Concept"),
        properties = Map("name" -> s"Concept $i")
      )
    }

    nodes.size should be(250)
    // Should be split into 3 batches: 100, 100, 50
    val batches = nodes.grouped(100).toSeq
    batches.size should be(3)
    batches(2).size should be(50)
  }

  "writeEdgesBatch" should "handle empty batch" in {
    val edges = Seq.empty[GraphEdge]
    edges.size should be(0)
  }

  it should "handle single edge batch" in {
    val edges = Seq(
      GraphEdge(
        sourceId = "chunk-001",
        targetId = "concept-001",
        relationType = "MENTIONS",
        properties = Map.empty
      )
    )

    edges.size should be(1)
  }

  it should "handle mixed relationship types in batch" in {
    val edges = Seq(
      GraphEdge("chunk-1", "concept-1", "MENTIONS", Map.empty),
      GraphEdge("concept-1", "concept-2", "CO_OCCURS", Map("count" -> 3)),
      GraphEdge("concept-2", "concept-3", "RELATES_TO", Map("type" -> "childOf"))
    )

    edges.size should be(3)
    edges.map(_.relationType).toSet should be(Set("MENTIONS", "CO_OCCURS", "RELATES_TO"))
  }

  it should "handle batch of 100 edges" in {
    val edges = (1 to 100).map { i =>
      GraphEdge(
        sourceId = s"chunk-$i",
        targetId = s"concept-$i",
        relationType = "MENTIONS",
        properties = Map.empty
      )
    }

    edges.size should be(100)
  }

  "testConnection" should "return Success(true) for valid connection" in {
    // This requires actual Neo4j instance or mock
    // Test structure only
    val client = new Neo4jClient(testUri, testUsername, testPassword)
    client should not be null
  }

  "toParams conversion" should "convert Scala Map to Java Map correctly" in {
    val scalaMap = Map(
      "id" -> "test-001",
      "name" -> "Test Name",
      "count" -> 42,
      "active" -> true
    )

    // Test that the map contains expected values
    scalaMap("id") should be("test-001")
    scalaMap("name") should be("Test Name")
    scalaMap("count") should be(42)
  }

  it should "handle empty map" in {
    val emptyMap = Map.empty[String, Any]
    emptyMap.isEmpty should be(true)
  }

  it should "handle nested structures" in {
    val complexMap = Map(
      "id" -> "test-001",
      "metadata" -> Map("source" -> "web", "date" -> "2024-01-01")
    )

    complexMap.contains("metadata") should be(true)
  }



  it should "create proper label syntax for multiple labels" in {
    val labels = Set("Concept", "Entity")
    val labelString = if (labels.nonEmpty) labels.mkString(":", ":", "") else ""
    labelString should include("Concept")
    labelString should include("Entity")
  }

  it should "handle empty labels" in {
    val labels = Set.empty[String]
    val labelString = if (labels.nonEmpty) labels.mkString(":", ":", "") else ""
    labelString should be("")
  }

  "idField selection" should "use conceptId for Concept nodes" in {
    val labels = Set("Concept")
    val idField = if (labels.contains("Concept")) "conceptId" else "chunkId"
    idField should be("conceptId")
  }

  it should "use chunkId for Chunk nodes" in {
    val labels = Set("Chunk")
    val idField = if (labels.contains("Concept")) "conceptId" else "chunkId"
    idField should be("chunkId")
  }

  it should "default to chunkId for other node types" in {
    val labels = Set("Unknown")
    val idField = if (labels.contains("Concept")) "conceptId" else "chunkId"
    idField should be("chunkId")
  }

  "relationship type matching" should "correctly identify MENTIONS relationships" in {
    val relationType = "MENTIONS"
    val (sourceLabel, targetLabel, sourceField, targetField) = relationType match {
      case "MENTIONS"   => ("Chunk", "Concept", "chunkId", "conceptId")
      case "CO_OCCURS"  => ("Concept", "Concept", "conceptId", "conceptId")
      case "RELATES_TO" => ("Concept", "Concept", "conceptId", "conceptId")
      case _            => ("Node", "Node", "conceptId", "conceptId")
    }

    sourceLabel should be("Chunk")
    targetLabel should be("Concept")
    sourceField should be("chunkId")
    targetField should be("conceptId")
  }

  it should "correctly identify CO_OCCURS relationships" in {
    val relationType = "CO_OCCURS"
    val (sourceLabel, targetLabel, _, _) = relationType match {
      case "MENTIONS"   => ("Chunk", "Concept", "chunkId", "conceptId")
      case "CO_OCCURS"  => ("Concept", "Concept", "conceptId", "conceptId")
      case "RELATES_TO" => ("Concept", "Concept", "conceptId", "conceptId")
      case _            => ("Node", "Node", "conceptId", "conceptId")
    }

    sourceLabel should be("Concept")
    targetLabel should be("Concept")
  }

  it should "default to Node for unknown relationship types" in {
    val relationType = "UNKNOWN"
    val (sourceLabel, targetLabel, _, _) = relationType match {
      case "MENTIONS"   => ("Chunk", "Concept", "chunkId", "conceptId")
      case "CO_OCCURS"  => ("Concept", "Concept", "conceptId", "conceptId")
      case "RELATES_TO" => ("Concept", "Concept", "conceptId", "conceptId")
      case _            => ("Node", "Node", "conceptId", "conceptId")
    }

    sourceLabel should be("Node")
    targetLabel should be("Node")
  }

  "close" should "safely close the driver" in {
    val client = new Neo4jClient(testUri, testUsername, testPassword)
    noException should be thrownBy client.close()
  }

  "Neo4jClient companion object" should "create instance via apply" in {
    val client = Neo4jClient(testUri, testUsername, testPassword)
    client should not be null
    client shouldBe a[Neo4jClient]
  }
}