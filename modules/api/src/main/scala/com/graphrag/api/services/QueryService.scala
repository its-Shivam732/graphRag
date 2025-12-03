package com.graphrag.api.services

import com.graphrag.api.models._
import com.graphrag.api.models.ApiModels._
import com.graphrag.core.models.Concept
import com.graphrag.llm.OllamaClient

import org.neo4j.driver._
import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}
import java.util.UUID

/**
 * QueryService:
 *
 * Orchestrates the full query pipeline for the GraphRAG API.
 *
 * Pipeline steps:
 *   1. Extract concepts from natural-language query using LLM
 *   2. Match those concepts against Neo4j nodes
 *   3. Retrieve relationships among matched concepts
 *   4. Pass all collected data back through the LLM to generate a
 *      final structured response (FinalQueryResponse)
 *
 * The service produces an enriched answer with:
 *   - Concepts
 *   - Relations
 *   - Synthesized natural-language answer
 *   - Optional evidence references
 */
class QueryService(
                    neo4jUri: String,
                    neo4jUsername: String,
                    neo4jPassword: String,
                    ollamaUrl: String,
                    ollamaModel: String
                  )(implicit ec: ExecutionContext) {

  /** Neo4j driver for concept + relation lookups */
  private val driver: Driver =
    GraphDatabase.driver(neo4jUri, AuthTokens.basic(neo4jUsername, neo4jPassword))

  /** Ollama client for all LLM interactions */
  private val ollamaClient = OllamaClient(ollamaUrl)

  // ============================================================
  //                     MAIN QUERY PIPELINE
  // ============================================================

  /**
   * Orchestrates the full request → concepts → relations → LLM summarization pipeline.
   *
   * @return FinalQueryResponse (includes synthesized answer, groups, metadata)
   */
  def query(request: QueryRequest): Future[FinalQueryResponse] = {
    val requestId = UUID.randomUUID().toString
    val start = System.currentTimeMillis()

    for {
      concepts  <- extractConceptsFromQuery(request.query)
      matched   <- findMatchingConcepts(concepts)
      relations <- getRelations(matched, request.includeEvidence)
      refined   <- refineWithLLM(request.query, matched, relations)
    } yield refined
  }

  // ============================================================
  //                     LLM: CONCEPT EXTRACTION
  // ============================================================

  /**
   * Generates an LLM prompt asking for JSON-based concept extraction.
   *
   * Returns concepts in internal Concept model format.
   */
  private def extractConceptsFromQuery(query: String): Future[Seq[Concept]] = {
    val prompt =
      s"""
Extract key concepts as JSON.

Query: "$query"

Return JSON:

{
  "concepts": [
    {"surface": "...", "lemma": "..."}
  ]
}
"""

    ollamaClient
      .generateAsync(ollamaModel, prompt, temperature = 0.0)
      .map(parseConceptsJson)
      .recover { case _ => Seq.empty }
  }

  /**
   * Parses the JSON returned by LLM into Concept models.
   */
  private def parseConceptsJson(raw: String): Seq[Concept] = {
    import io.circe.parser._
    import io.circe.generic.auto._

    case class LLMConcept(surface: String, lemma: String)
    case class Response(concepts: List[LLMConcept])

    val cleaned = raw.replace("```json", "").replace("```", "").trim

    decode[Response](cleaned) match {
      case Right(resp) =>
        resp.concepts.map(c =>
          Concept(
            conceptId = normalizeConceptId(c.lemma),
            lemma     = c.lemma,
            surface   = c.surface,
            origin    = "QUERY_LLM"
          )
        )
      case Left(_) => Seq.empty
    }
  }

  /** Normalize a concept into an ID-safe string */
  private def normalizeConceptId(t: String): String =
    t.toLowerCase.replaceAll("[^a-z0-9\\s]", "").trim.replaceAll("\\s+", "_")


  // ============================================================
  //                     NEO4J MATCHING & RELATIONS
  // ============================================================

  /**
   * Finds concepts in Neo4j that match those extracted from LLM.
   */
  private def findMatchingConcepts(q: Seq[Concept]): Future[Seq[Concept]] = Future {
    val session = driver.session()
    try {
      val ids = q.map(_.conceptId)

      val query =
        """
        MATCH (c:Concept)
        WHERE c.conceptId IN $conceptIds
        RETURN
          c.conceptId AS conceptId,
          c.lemma     AS lemma,
          c.surface   AS surface,
          c.origin    AS origin
        """

      val params = Values.parameters("conceptIds", ids.asJava)
      val result = session.run(query, params)

      result.list().asScala.map { r =>
        Concept(
          r.get("conceptId").asString(),
          r.get("lemma").asString(),
          r.get("surface").asString(),
          r.get("origin").asString()
        )
      }.toSeq

    } finally session.close()
  }

  /**
   * Retrieves graph relations among the matched concepts.
   *
   * If includeEvidence=true, it will return the raw evidence text.
   */
  private def getRelations(
                            concepts: Seq[Concept],
                            includeEvidence: Boolean
                          ): Future[Seq[RelationResult]] =
    Future {
      if (concepts.isEmpty) return Future.successful(Seq.empty)

      val session = driver.session()
      try {
        val ids = concepts.map(_.conceptId)

        val query =
          """
          MATCH (c1:Concept)-[r:RELATES_TO]->(c2:Concept)
          WHERE c1.conceptId IN $ids OR c2.conceptId IN $ids
          RETURN
            c1.conceptId AS source,
            c2.conceptId AS target,
            r.predicate  AS predicate,
            r.confidence AS confidence,
            r.evidence   AS evidence
          ORDER BY r.confidence DESC
          LIMIT 100
        """

        val params = Values.parameters("ids", ids.asJava)
        val result = session.run(query, params)

        result.list().asScala.map { r =>
          val evidence =
            if (includeEvidence && !r.get("evidence").isNull)
              Some(r.get("evidence").asString())
            else None

          RelationResult(
            source    = r.get("source").asString(),
            target    = r.get("target").asString(),
            predicate = r.get("predicate").asString(),
            confidence = r.get("confidence").asDouble(),
            evidence   = evidence,
            evidenceId = None
          )
        }.toSeq

      } finally session.close()
    }


  // ============================================================
  //                     LLM: FINAL SYNTHESIS
  // ============================================================

  /**
   * refineWithLLM:
   *
   * Takes:
   *   - Original query string
   *   - Matched concepts
   *   - Relations from Neo4j
   *
   * Let’s the LLM synthesize a final response in the structured
   * FinalQueryResponse format required by the API.
   */
  private def refineWithLLM(
                             query: String,
                             concepts: Seq[Concept],
                             relations: Seq[RelationResult]
                           ): Future[FinalQueryResponse] = {

    import io.circe.parser._
    import io.circe.generic.auto._

    val conceptText = concepts.map(_.surface).mkString(", ")

    val relationText =
      relations
        .map(r => s"${r.source} --${r.predicate} (${r.confidence})--> ${r.target}")
        .mkString("\n")

    val prompt =
      s"""
You are a reasoning engine. Build a structured JSON answer.

Query:
$query

Concepts:
$conceptText

Relations:
$relationText

Return ONLY valid JSON:

{
  "mode": "sync",
  "answer": "string",
  "groups": [
    {
      "items": [
        {
          "paperId": "string",
          "title": "string",
          "concepts": ["string"],
          "citations": ["string"]
        }
      ]
    }
  ],
  "evidenceAvailable": true
}
"""

    ollamaClient
      .generateAsync(ollamaModel, prompt, temperature = 0.0)
      .map { raw =>
        val cleaned = raw.replace("```json", "").replace("```", "").trim

        decode[FinalQueryResponse](cleaned) match {
          case Right(json) => json
          case Left(err) =>
            throw new RuntimeException("Failed to parse LLM response: " + err.getMessage)
        }
      }
  }

  /** Clean-up method for shutting down Neo4j and Ollama client resources. */
  def close(): Unit = {
    driver.close()
    ollamaClient.close()
  }
}
