package com.graphrag.llm

import com.graphrag.core.models.{RelationCandidate, Relation}
import io.circe.parser._
import io.circe.generic.auto._
import scala.util.{Try, Success, Failure}

/**
 * RelationScorer
 *
 * Responsible for:
 *   - Building prompts for LLM-based relation extraction
 *   - Sending prompts to the LLM
 *   - Parsing the returned JSON into a Relation object
 *
 * Used both synchronously (scoreRelation) and asynchronously (AsyncRelationScorer).
 */
object RelationScorer {

  /**
   * scoreRelation
   *
   * Synchronous method for scoring a relation candidate.
   * Primarily used outside Flink async contexts (e.g., unit tests, offline jobs).
   *
   * @param candidate The concept pair + chunk context
   * @param ollamaClient Synchronous Ollama client
   * @param model LLM model name (default: "llama3")
   * @return Option[Relation] if a relationship is identified
   */
  def scoreRelation(
                     candidate: RelationCandidate,
                     ollamaClient: OllamaClient,
                     model: String = "llama3"
                   ): Option[Relation] = {

    val prompt = buildRelationPrompt(
      candidate.pair.concept1.surface,
      candidate.pair.concept2.surface,
      candidate.context
    )

    // Perform synchronous LLM call
    ollamaClient.generate(model, prompt, temperature = 0.0) match {
      case Success(response) =>
        parseRelationFromResponse(
          response,
          candidate.pair.concept1.conceptId,
          candidate.pair.concept2.conceptId
        )

      case Failure(e) =>
        println(s"Failed to score relation: ${e.getMessage}")
        None
    }
  }

  /**
   * buildRelationPrompt
   *
   * Constructs a detailed and structured prompt for the LLM.
   * Provides:
   *   - Two concept names
   *   - Context from the chunk text
   *   - Precise task description
   *   - Strict JSON schema
   *
   * @return LLM prompt string
   */
  def buildRelationPrompt(
                           concept1: String,
                           concept2: String,
                           context: String
                         ): String = {
    s"""You are a knowledge graph expert. Given two concepts and context text, determine if there is a meaningful relationship between them.

CONCEPT 1: $concept1
CONCEPT 2: $concept2

CONTEXT:
${context.take(1000)}

TASK:
Analyze if there is a meaningful relationship between "$concept1" and "$concept2" based on the context.

If a relationship exists, provide:
1. predicate: The relationship type (e.g., "uses", "depends_on", "part_of", "implements", "extends", "calls", "contains")
2. confidence: A score from 0.0 to 1.0 indicating confidence
3. evidence: A brief text snippet from context that proves the relationship
4. direction: Either "forward" (concept1 -> concept2) or "reverse" (concept2 -> concept1)

If NO meaningful relationship exists, return null predicate.

RULES:
- Use domain-appropriate predicates (technical terms for code, general terms for text)
- Only extract relationships explicitly stated or strongly implied
- Confidence should reflect how clear the relationship is
- Keep evidence brief (1-2 sentences max)

OUTPUT FORMAT (strict JSON only):
{
  "predicate": "relationship_type" or null,
  "confidence": 0.85,
  "evidence": "text snippet",
  "direction": "forward"
}

RESPOND WITH JSON ONLY. NO EXPLANATIONS:""".stripMargin
  }

  /**
   * parseRelationFromResponse
   *
   * Converts LLM JSON output into a Relation object.
   * Handles:
   *   - Markdown formatting cleanup
   *   - JSON decode errors
   *   - Null predicate (no relationship)
   *   - Direction handling (forward or reverse)
   *
   * @param response Raw LLM response text
   * @param conceptId1 Normalized conceptId for concept 1
   * @param conceptId2 Normalized conceptId for concept 2
   * @return Option[Relation]
   */
  def parseRelationFromResponse(
                                 response: String,
                                 conceptId1: String,
                                 conceptId2: String
                               ): Option[Relation] = {
    try {
      // Strip markdown fences if the LLM inserted them
      val cleaned = response
        .replace("```json", "")
        .replace("```", "")
        .trim

      // Expected JSON structure from LLM
      case class LLMRelation(
                              predicate: Option[String],
                              confidence: Double,
                              evidence: String,
                              direction: String
                            )

      decode[LLMRelation](cleaned) match {
        case Right(llmRel) if llmRel.predicate.isDefined =>
          // Determine source and target based on direction field
          val (source, target) = llmRel.direction match {
            case "reverse" => (conceptId2, conceptId1)
            case _         => (conceptId1, conceptId2)
          }

          Some(
            Relation(
              source = source,
              target = target,
              predicate = llmRel.predicate.get,
              confidence = llmRel.confidence,
              evidence = llmRel.evidence,
              origin = "LLM"
            )
          )

        case Right(_) =>
          // Valid JSON but no relationship detected
          None

        case Left(error) =>
          println(s"Failed to parse relation response: $error")
          println(s"Response was: ${cleaned.take(200)}")
          None
      }
    } catch {
      case e: Exception =>
        println(s"Error parsing relation response: ${e.getMessage}")
        None
    }
  }
}
