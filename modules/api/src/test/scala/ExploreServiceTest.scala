import com.graphrag.api.models._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ExploreServiceTest extends AnyFlatSpec with Matchers {

  // ----------------------------------------------------
  //  PURE STRING / PATTERN TESTS
  // ----------------------------------------------------

  "direction pattern" should "generate correct pattern for 'in'" in {
    val direction = "in"
    val relationPattern = "RELATES_TO|CO_OCCURS"
    val depth = 2

    val pattern = direction match {
      case "in"  => s"<-[r:$relationPattern*1..$depth]-"
      case "out" => s"-[r:$relationPattern*1..$depth]->"
      case _     => s"-[r:$relationPattern*1..$depth]-"
    }

    pattern should startWith("<-")
    pattern should include("*1..2")
  }

  it should "generate correct pattern for 'out'" in {
    val direction = "out"
    val relationPattern = "RELATES_TO"
    val depth = 1

    val pattern = direction match {
      case "in"  => s"<-[r:$relationPattern*1..$depth]-"
      case "out" => s"-[r:$relationPattern*1..$depth]->"
      case _     => s"-[r:$relationPattern*1..$depth]-"
    }

    pattern should endWith("->")
  }

  it should "generate correct pattern for 'both'" in {
    val direction = "both"
    val relationPattern = "RELATES_TO"
    val depth = 3

    val pattern = direction match {
      case "in"  => s"<-[r:$relationPattern*1..$depth]-"
      case "out" => s"-[r:$relationPattern*1..$depth]->"
      case _     => s"-[r:$relationPattern*1..$depth]-"
    }

    pattern should startWith("-")
    pattern should endWith("-")
  }

  "edge type pattern" should "join multiple types with pipe" in {
    val edgeTypes = Seq("RELATES_TO", "CO_OCCURS", "MENTIONS")
    val pattern = edgeTypes.mkString("|")

    pattern should be("RELATES_TO|CO_OCCURS|MENTIONS")
  }

  it should "handle single edge type" in {
    val edgeTypes = Seq("RELATES_TO")
    val pattern = edgeTypes.mkString("|")

    pattern should be("RELATES_TO")
  }

  it should "handle empty edge types" in {
    val edgeTypes = Seq.empty[String]
    val pattern = edgeTypes.mkString("|")

    pattern should be("")
  }

  // ----------------------------------------------------
  //  PURE MODEL TESTS (NO NEO4J)
  // ----------------------------------------------------

  "Neighborhood" should "contain center concept" in {
    val neighborhood = Neighborhood(
      centerConcept = ConceptResult("id", "lemma", "surface", 1.0),
      neighbors = Seq.empty,
      edges = Seq.empty
    )

    neighborhood.centerConcept.conceptId should be("id")
  }

  it should "contain neighbors" in {
    val neighbors = Seq(
      NeighborNode("id1", "lemma1", "surface1", 1),
      NeighborNode("id2", "lemma2", "surface2", 2)
    )

    val neighborhood = Neighborhood(
      centerConcept = ConceptResult("center", "lemma", "surface", 1.0),
      neighbors = neighbors,
      edges = Seq.empty
    )

    neighborhood.neighbors should have size 2
  }

  it should "contain edges" in {
    val edges = Seq(
      NeighborEdge("id1", "id2", "RELATES_TO", Map.empty)
    )

    val neighborhood = Neighborhood(
      centerConcept = ConceptResult("center", "lemma", "surface", 1.0),
      neighbors = Seq.empty,
      edges = edges
    )

    neighborhood.edges should have size 1
  }

  "NeighborNode" should "store distance information" in {
    val node = NeighborNode(
      conceptId = "concept-1",
      lemma = "lemma",
      surface = "Surface",
      distance = 2
    )

    node.distance should be(2)
  }

  it should "handle distance of 1" in {
    val node = NeighborNode("id", "lemma", "surface", 1)
    node.distance should be(1)
  }

  "NeighborEdge" should "store relation type" in {
    val edge = NeighborEdge(
      source = "concept-1",
      target = "concept-2",
      relationType = "RELATES_TO",
      properties = Map.empty
    )

    edge.relationType should be("RELATES_TO")
  }

  it should "store edge properties" in {
    val properties = Map(
      "confidence" -> 0.95,
      "predicate" -> "uses"
    )

    val edge = NeighborEdge(
      source = "c1",
      target = "c2",
      relationType = "RELATES_TO",
      properties = properties
    )

    edge.properties should contain key "confidence"
    edge.properties should contain key "predicate"
  }

  it should "handle empty properties" in {
    val edge = NeighborEdge("c1", "c2", "CO_OCCURS", Map.empty)
    edge.properties should be(empty)
  }
}
