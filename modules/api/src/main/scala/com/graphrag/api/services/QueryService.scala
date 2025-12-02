package com.graphrag.api.services

import com.graphrag.api.models._
import com.graphrag.core.models.Concept
import com.graphrag.llm.OllamaClient
import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID

class QueryService(
                    neo4jUri: String,
                    neo4jUsername: String,
                    neo4jPassword: String,
                    ollamaUrl: String,
                    ollamaModel: String
                  )(implicit ec: ExecutionContext) {

  private val driver: Driver = GraphDatabase.driver(
    neo4jUri,
    AuthTokens.basic(neo4jUsername, neo4jPassword)
  )

  private val ollamaClient = OllamaClient(ollamaUrl)

  def query(request: QueryRequest): Future[QueryResponse] = {
    val requestId = UUID.randomUUID().toString
    val startTime = System.currentTimeMillis()

    for {
      // Step 1: Extract concepts from query using LLM
      queryConcepts <- extractConceptsFromQuery(request.query)

      // Step 2: Find matching concepts in Neo4j
      matchedConcepts <- findMatchingConcepts(queryConcepts)

      // Step 3: Get relations between matched concepts
      relations <- getRelations(matchedConcepts, request.includeEvidence)

    } yield {
      val executionTime = System.currentTimeMillis() - startTime

      QueryResponse(
        requestId = requestId,
        concepts = matchedConcepts.map(c => ConceptResult(
          conceptId = c.conceptId,
          lemma = c.lemma,
          surface = c.surface,
          relevanceScore = 1.0
        )),
        relations = relations,
        executionTimeMs = executionTime
      )
    }
  }

  private def extractConceptsFromQuery(query: String): Future[Seq[Concept]] = {
    val prompt = s"""Extract key concepts from this query as JSON:
Query: "$query"

Return JSON format:
{
  "concepts": [
    {"surface": "concept name", "lemma": "normalized form"}
  ]
}

Respond with JSON only:"""

    ollamaClient.generateAsync(ollamaModel, prompt, temperature = 0.0).map { response =>
      parseConceptsFromLLM(response)
    }.recover {
      case e: Exception =>
        println(s"Failed to extract concepts via LLM: ${e.getMessage}")
        Seq.empty
    }
  }

  private def parseConceptsFromLLM(response: String): Seq[Concept] = {
    import io.circe.parser._
    import io.circe.generic.auto._

    val cleaned = response.replace("```json", "").replace("```", "").trim

    case class LLMConcept(surface: String, lemma: String)
    case class LLMResponse(concepts: List[LLMConcept])

    decode[LLMResponse](cleaned) match {
      case Right(llmResponse) =>
        llmResponse.concepts.map { c =>
          Concept(
            conceptId = normalizeConceptId(c.lemma),
            lemma = c.lemma.toLowerCase,
            surface = c.surface,
            origin = "QUERY_LLM"
          )
        }
      case Left(_) => Seq.empty
    }
  }

  private def normalizeConceptId(text: String): String = {
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")
  }

  private def findMatchingConcepts(queryConcepts: Seq[Concept]): Future[Seq[Concept]] = Future {
    val session = driver.session()
    try {
      val conceptIds = queryConcepts.map(_.conceptId)

      val query = """
        MATCH (c:Concept)
        WHERE c.conceptId IN $conceptIds
        RETURN c.conceptId as conceptId, c.lemma as lemma, c.surface as surface, c.origin as origin
      """

      // ✅ FIX: Use Values.parameters instead of Map
      val params = Values.parameters("conceptIds", conceptIds.asJava)
      val result = session.run(query, params)

      result.list().asScala.map { record =>
        Concept(
          conceptId = record.get("conceptId").asString(),
          lemma = record.get("lemma").asString(),
          surface = record.get("surface").asString(),
          origin = record.get("origin").asString()
        )
      }.toSeq

    } finally {
      session.close()
    }
  }

  private def getRelations(concepts: Seq[Concept], includeEvidence: Boolean): Future[Seq[RelationResult]] = Future {
    if (concepts.isEmpty) {
      Seq.empty
    } else {
      val session = driver.session()
      try {
        val conceptIds = concepts.map(_.conceptId)

        val query = """
          MATCH (c1:Concept)-[r:RELATES_TO]->(c2:Concept)
          WHERE c1.conceptId IN $conceptIds OR c2.conceptId IN $conceptIds
          RETURN c1.conceptId as source, c2.conceptId as target,
                 r.predicate as predicate, r.confidence as confidence,
                 r.evidence as evidence
          ORDER BY r.confidence DESC
          LIMIT 100
        """

        // ✅ FIX: Use Values.parameters
        val params = Values.parameters("conceptIds", conceptIds.asJava)
        val result = session.run(query, params)

        result.list().asScala.map { record =>
          val evidence = if (includeEvidence && !record.get("evidence").isNull) {
            Some(record.get("evidence").asString())
          } else None

          RelationResult(
            source = record.get("source").asString(),
            target = record.get("target").asString(),
            predicate = record.get("predicate").asString(),
            confidence = record.get("confidence").asDouble(),
            evidence = evidence,
            evidenceId = None
          )
        }.toSeq

      } finally {
        session.close()
      }
    }
  }

  def close(): Unit = {
    driver.close()
    ollamaClient.close()
  }
}