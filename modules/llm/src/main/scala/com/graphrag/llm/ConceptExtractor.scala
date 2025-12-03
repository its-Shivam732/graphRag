package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Mentions, Concept}
import edu.stanford.nlp.pipeline.{CoreDocument, StanfordCoreNLP}
import edu.stanford.nlp.ling.CoreAnnotations
import scala.collection.JavaConverters._
import java.util.Properties

/**
 * ConceptExtractor
 *
 * Provides heuristic concept extraction from text using:
 *  - Stanford CoreNLP NER (Named Entity Recognition)
 *  - Functional noun-phrase detection (simple syntactic grouping)
 *
 * This extractor is used when LLM-based concept extraction
 * is disabled OR as a fallback when the LLM extraction fails.
 */
object ConceptExtractor {

  /**
   * Lazy global StanfordCoreNLP pipeline initialization.
   *
   * CoreNLP startup is expensive (normally several hundred ms),
   * so we initialize it once per JVM, and mark it @transient
   * to avoid serialization in Flink.
   */
  @transient lazy val pipeline: StanfordCoreNLP = {
    val props = new Properties()
    props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner")
    props.setProperty("ner.useSUTime", "false") // Disable date/time normalization
    new StanfordCoreNLP(props)
  }

  /**
   * Extract heuristic concepts from a Chunk.
   *
   * The extraction pipeline is:
   *   1. Annotate text using CoreNLP
   *   2. Extract named entities as concepts
   *   3. Extract noun-phrase concepts
   *   4. Merge + deduplicate concepts
   *   5. Wrap each Concept in a Mentions object
   *
   * @return Seq[Mentions] — one mention per concept found.
   */
  def extractHeuristic(chunk: Chunk): Seq[Mentions] =
    try {
      val document = new CoreDocument(chunk.text)
      pipeline.annotate(document)

      // Extract concepts using two heuristics
      val nerConcepts = extractNERConcepts(document)
      val nounPhraseConcepts = extractNounPhrases(document)

      // Deduplicate by conceptId (normalized form)
      val uniqueConcepts =
        (nerConcepts ++ nounPhraseConcepts)
          .groupBy(_.conceptId)
          .map(_._2.head)
          .toSeq

      // Convert Concepts to Mentions
      uniqueConcepts.map(c => Mentions(chunk.chunkId, c))

    } catch {
      case e: Exception =>
        // Prevent failures from breaking the pipeline
        println(s"Error extracting concepts from chunk ${chunk.chunkId}: ${e.getMessage}")
        Seq.empty
    }

  // -------------------------------------------------------------------
  // NER-BASED CONCEPT EXTRACTION
  // -------------------------------------------------------------------

  /**
   * Extract concepts from NER tags (PERSON, ORG, LOC, etc.)
   */
  private def extractNERConcepts(document: CoreDocument): Seq[Concept] = {
    document.entityMentions().asScala.toSeq
      .filter(ent => isValidNERTag(ent.entityType()) && ent.text().length > 2)
      .map { ent =>
        val surface = ent.text()
        Concept(
          conceptId = normalizeConceptId(surface),
          lemma = surface.toLowerCase,
          surface = surface,
          origin = s"NER:${ent.entityType()}"
        )
      }
  }

  // -------------------------------------------------------------------
  // NOUN PHRASE EXTRACTION VIA FUNCTIONAL GROUPING
  // -------------------------------------------------------------------

  /**
   * Extract noun-phrase concepts from POS-tagged tokens.
   *
   * Example:
   *   "operating system kernel" → one noun phrase concept
   */
  private def extractNounPhrases(document: CoreDocument): Seq[Concept] = {
    val nounPhrases =
      for {
        sentence <- document.sentences().asScala.toSeq
        tokens = sentence.tokens().asScala.toSeq
        phrase <- collectNounPhrases(tokens)
      } yield phrase

    // Convert phrases → concepts, remove duplicates
    nounPhrases.distinct.map { surface =>
      Concept(
        conceptId = normalizeConceptId(surface),
        lemma = surface,
        surface = surface,
        origin = "NOUN_PHRASE"
      )
    }
  }

  /**
   * Collect noun phrases using a fold-based grouping strategy.
   *
   * Groups consecutive nouns into candidate phrases:
   *   ["operating", "system", "kernel"] → "operating system kernel"
   */
  private def collectNounPhrases(tokens: Seq[edu.stanford.nlp.ling.CoreLabel]): Seq[String] = {

    val grouped =
      tokens.foldLeft(List[List[String]]()) { (acc, token) =>
        val pos = token.get(classOf[CoreAnnotations.PartOfSpeechAnnotation])
        val lemma = token.lemma()

        acc match {
          // Extend an existing NP group if token is a noun
          case current :: rest if isNounTag(pos) =>
            (lemma :: current) :: rest

          // Close group and start new empty one when encountering a non-noun
          case current :: rest =>
            List() :: current :: rest

          // If first token and noun → start NP group
          case Nil if isNounTag(pos) =>
            List(List(lemma))

          // First token non-noun → just empty group
          case Nil =>
            List(List())
        }
      }

    // Keep only noun groups of size >= 2, e.g., "machine learning"
    grouped.map(_.reverse).filter(_.size >= 2).map(_.mkString(" "))
  }

  // -------------------------------------------------------------------
  // HELPERS
  // -------------------------------------------------------------------

  /** Valid NER labels we treat as meaningful concepts. */
  private def isValidNERTag(tag: String): Boolean =
    Set("PERSON", "ORGANIZATION", "LOCATION", "DATE",
      "MONEY", "PERCENT", "TIME", "MISC").contains(tag)

  /** A POS tag is considered a noun if it starts with "NN". */
  private def isNounTag(pos: String): Boolean =
    pos.startsWith("NN") // catches NN, NNS, NNP, NNPS

  /**
   * Normalize surface text into a conceptId.
   *
   * Example:
   *   "Natural Language Processing" → "natural_language_processing"
   *   "MacOS X" → "macos_x"
   */
  private def normalizeConceptId(text: String): String =
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "") // remove non-alphanumerics
      .trim
      .replaceAll("\\s+", "_")        // collapse whitespace to underscores
}
