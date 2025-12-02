package com.graphrag.llm

import com.graphrag.core.models.Concept
import io.circe.parser._
import io.circe.generic.auto._

/**
 * LLMConceptRefiner: Prompt building and response parsing for concept extraction
 */
object LLMConceptRefiner {

  /**
   * Build prompt for concept extraction (now public for async usage)
   */
  def buildPrompt(text: String, existingConcepts: Seq[Concept]): String = {
    val existingList = if (existingConcepts.nonEmpty) {
      existingConcepts.map(c => s"- ${c.surface}").mkString("\n")
    } else {
      "None identified yet"
    }

    s"""You are a concept extraction expert. Given a text passage, identify key concepts, entities, and technical terms.
       |
       |EXISTING CONCEPTS FOUND:
       |$existingList
       |
       |TEXT:
       |${text.take(800)}
       |
       |TASK:
       |1. Review the existing concepts
       |2. Identify any MISSING important concepts (technical terms, domain concepts, key entities)
       |3. Return ONLY the new/missing concepts in JSON format
       |
       |RULES:
       |- Extract only meaningful, specific concepts (not generic words like "system", "data")
       |- Focus on technical terms, methodologies, tools, proper nouns
       |- Each concept should be 1-4 words max
       |- Return ONLY valid JSON, nothing else
       |
       |OUTPUT FORMAT (strict JSON only):
       |{
       |  "concepts": [
       |    {"surface": "concept name", "lemma": "normalized form"},
       |    {"surface": "another concept", "lemma": "normalized form"}
       |  ]
       |}
       |
       |RESPOND WITH JSON ONLY. DO NOT ADD ANY EXPLANATION OR TEXT OUTSIDE THE JSON:""".stripMargin
  }

  /**
   * Parse concepts from LLM JSON response (now public for async usage)
   */
  def parseConceptsFromResponse(response: String): Seq[Concept] = {
    try {
      // Clean response - sometimes LLM adds markdown formatting
      val cleaned = response
        .replace("```json", "")
        .replace("```", "")
        .trim

      // Parse JSON
      case class LLMConcept(surface: String, lemma: String)
      case class LLMResponse(concepts: List[LLMConcept])

      decode[LLMResponse](cleaned) match {
        case Right(llmResponse) =>
          llmResponse.concepts.map { c =>
            Concept(
              conceptId = normalizeConceptId(c.lemma),
              lemma = c.lemma.toLowerCase,
              surface = c.surface,
              origin = "LLM"
            )
          }
        case Left(error) =>
          println(s"Failed to parse LLM response as JSON: $error")
          println(s"Response was: ${cleaned.take(200)}")
          Seq.empty
      }
    } catch {
      case e: Exception =>
        println(s"Error parsing LLM response: ${e.getMessage}")
        Seq.empty
    }
  }

  private def normalizeConceptId(text: String): String = {
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")
  }
}