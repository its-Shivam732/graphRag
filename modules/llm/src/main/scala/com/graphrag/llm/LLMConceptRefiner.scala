package com.graphrag.llm

import com.graphrag.core.models.Concept
import io.circe.parser._
import io.circe.generic.auto._

/**
 * LLMConceptRefiner
 *
 * This utility builds prompts and parses responses for LLM-based concept
 * extraction. It is used by AsyncConceptExtraction and other LLM-powered
 * ingestion components.
 */
object LLMConceptRefiner {

  /**
   * Build a high-quality prompt for concept extraction.
   *
   * The prompt includes:
   *   - Existing concepts detected heuristically
   *   - A truncated snippet of the raw text
   *   - Clear instructions to extract ONLY new/missing concepts
   *   - Strict JSON schema for LLM output
   *
   * @param text Input text for concept extraction
   * @param existingConcepts A list of heuristic concepts (to avoid duplicates)
   * @return A full prompt string ready to pass to an LLM
   */
  def buildPrompt(text: String, existingConcepts: Seq[Concept]): String = {
    val existingList = if (existingConcepts.nonEmpty) {
      // Format existing concepts as bullet list
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
   * Parse concepts from an LLM JSON response.
   *
   * Handles cases where:
   *   - The LLM wraps JSON inside markdown fences
   *   - The LLM inserts whitespace or formatting inconsistencies
   *   - The JSON is malformed (returns empty result instead of crashing)
   *
   * @param response Raw text returned by the LLM
   * @return A sequence of normalized Concept objects
   */
  def parseConceptsFromResponse(response: String): Seq[Concept] = {
    try {
      // Strip accidental Markdown formatting inserted by some LLMs
      val cleaned = response
        .replace("```json", "")
        .replace("```", "")
        .trim

      // Internal case classes to match expected JSON schema
      case class LLMConcept(surface: String, lemma: String)
      case class LLMResponse(concepts: List[LLMConcept])

      // Decode JSON using Circe
      decode[LLMResponse](cleaned) match {
        case Right(llmResponse) =>
          // Convert each LLM result to a Concept object
          llmResponse.concepts.map { c =>
            Concept(
              conceptId = normalizeConceptId(c.lemma), // normalized ID
              lemma = c.lemma.toLowerCase,
              surface = c.surface,
              origin = "LLM"
            )
          }

        case Left(error) =>
          // Log failures but avoid breaking the pipeline
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

  /**
   * Normalize a concept name into a stable conceptId:
   *  - lowercase
   *  - remove punctuation
   *  - collapse whitespace to underscores
   *
   * Example:
   *   "Natural Language Processing" → "natural_language_processing"
   */
  private def normalizeConceptId(text: String): String = {
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "") // remove non-alphanumeric chars
      .trim
      .replaceAll("\\s+", "_")        // collapse whitespace
  }
}
