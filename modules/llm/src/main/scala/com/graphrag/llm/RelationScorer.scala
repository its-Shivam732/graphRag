package com.graphrag.llm

import com.graphrag.core.models.{RelationCandidate, Relation}
import io.circe.parser._
import io.circe.generic.auto._
import scala.util.{Try, Success, Failure}

/**
 * RelationScorer: Uses LLM to score and extract relationships
 */
object RelationScorer {

  /**
   * Score a relation candidate using LLM
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
   * Build prompt for relation extraction
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
   * Parse relation from LLM JSON response
   */
  def parseRelationFromResponse(
                                 response: String,
                                 conceptId1: String,
                                 conceptId2: String
                               ): Option[Relation] = {
    try {
      val cleaned = response
        .replace("```json", "")
        .replace("```", "")
        .trim

      case class LLMRelation(
                              predicate: Option[String],
                              confidence: Double,
                              evidence: String,
                              direction: String
                            )

      decode[LLMRelation](cleaned) match {
        case Right(llmRel) if llmRel.predicate.isDefined =>
          val (source, target) = llmRel.direction match {
            case "reverse" => (conceptId2, conceptId1)
            case _ => (conceptId1, conceptId2)
          }

          Some(Relation(
            source = source,
            target = target,
            predicate = llmRel.predicate.get,
            confidence = llmRel.confidence,
            evidence = llmRel.evidence,
            origin = "LLM"
          ))

        case Right(_) =>
          // No relationship found
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