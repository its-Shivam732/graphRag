package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Concept, Mentions}
import edu.stanford.nlp.pipeline.{CoreDocument, StanfordCoreNLP}
import edu.stanford.nlp.ling.CoreAnnotations
import scala.collection.JavaConverters._
import java.util.Properties

object ConceptExtractor {

  // Initialize Stanford CoreNLP pipeline (expensive, do once)
  @transient lazy val pipeline: StanfordCoreNLP = {
    val props = new Properties()
    props.setProperty("annotators", "tokenize,ssplit,pos,lemma,ner")
    props.setProperty("ner.useSUTime", "false")
    new StanfordCoreNLP(props)
  }

  def extractHeuristic(chunk: Chunk): Seq[Mentions] =
    try {
      val document = new CoreDocument(chunk.text)
      pipeline.annotate(document)

      val nerConcepts = extractNERConcepts(document)
      val nounPhraseConcepts = extractNounPhrases(document)

      // Dedup by conceptId
      val uniqueConcepts =
        (nerConcepts ++ nounPhraseConcepts)
          .groupBy(_.conceptId)
          .map(_._2.head)
          .toSeq

      uniqueConcepts.map(c => Mentions(chunk.chunkId, c))

    } catch {
      case e: Exception =>
        println(s"Error extracting concepts from chunk ${chunk.chunkId}: ${e.getMessage}")
        Seq.empty
    }

  // -------------------------------
  //   NER EXTRACTION
  // -------------------------------

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

  // -------------------------------
  //   NOUN-PHRASE EXTRACTION (functional)
  // -------------------------------

  private def extractNounPhrases(document: CoreDocument): Seq[Concept] = {
    val nounPhrases =
      for {
        sentence <- document.sentences().asScala.toSeq
        tokens = sentence.tokens().asScala.toSeq
        phrase <- collectNounPhrases(tokens)
      } yield phrase

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
   * Pure functional consecutive noun grouping
   */
  private def collectNounPhrases(tokens: Seq[edu.stanford.nlp.ling.CoreLabel]): Seq[String] = {

    val grouped =
      tokens.foldLeft(List[List[String]]()) { (acc, token) =>
        val pos = token.get(classOf[CoreAnnotations.PartOfSpeechAnnotation])
        val lemma = token.lemma()

        acc match {
          // If noun, append to current group
          case current :: rest if isNounTag(pos) =>
            (lemma :: current) :: rest

          // If not noun, close current group, start empty
          case current :: rest =>
            List() :: current :: rest

          // First token, noun creates group
          case Nil if isNounTag(pos) =>
            List(List(lemma))

          // First token, not noun → just empty group
          case Nil =>
            List(List())
        }
      }

    // Extract only noun groups with size >= 2
    grouped.map(_.reverse).filter(_.size >= 2).map(_.mkString(" "))
  }

  // -------------------------------
  //   HELPERS
  // -------------------------------

  private def isValidNERTag(tag: String): Boolean =
    Set("PERSON", "ORGANIZATION", "LOCATION", "DATE",
      "MONEY", "PERCENT", "TIME", "MISC").contains(tag)

  private def isNounTag(pos: String): Boolean =
    pos.startsWith("NN") // NN, NNS, NNP, NNPS

  private def normalizeConceptId(text: String): String =
    text.toLowerCase
      .replaceAll("[^a-z0-9\\s]", "")
      .trim
      .replaceAll("\\s+", "_")
}
