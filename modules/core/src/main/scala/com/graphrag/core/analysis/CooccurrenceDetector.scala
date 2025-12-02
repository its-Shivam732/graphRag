package com.graphrag.core.analysis

import com.graphrag.core.models.{Concept, ConceptPair}

object CooccurrenceDetector {

  /**
   * Find co-occurring concepts within a window of chunks
   */
  def findCooccurrences(
                         mentionsByChunk: Map[String, Seq[Concept]],
                         windowSize: Int = 3,
                         minCooccurrence: Int = 2
                       ): Seq[ConceptPair] = {

    val chunkIds: Seq[String] = mentionsByChunk.keys.toSeq.sorted
    val pairs = scala.collection.mutable.Map[(String, String), ConceptPair]()

    // Sliding window
    chunkIds.sliding(windowSize).foreach { window =>

      // Scala 2.12-friendly: annotate types explicitly
      val conceptsInWindow: Seq[Concept] =
        window.flatMap(id => mentionsByChunk.getOrElse(id, Seq.empty[Concept]))

      // Scala 2.12-friendly distinctBy alternative
      val uniqueConcepts: Seq[Concept] =
        conceptsInWindow
          .groupBy(_.conceptId)
          .map { case (_, cs) => cs.head }
          .toSeq

      // Unordered pairs
      uniqueConcepts.combinations(2).foreach {
        case Seq(c1, c2) =>
          val key = canonicalKey(c1.conceptId, c2.conceptId)

          pairs.get(key) match {
            case Some(existing) =>
              pairs(key) = existing.copy(
                cooccurrenceCount = existing.cooccurrenceCount + 1,
                chunkIds = existing.chunkIds ++ window
              )

            case None =>
              pairs(key) = ConceptPair(c1, c2, 1, window.toSet)
          }
      }
    }

    // Filter & sort
    pairs.values
      .filter(_.cooccurrenceCount >= minCooccurrence)
      .toSeq
      .sortBy(-_.cooccurrenceCount)
  }

  /**
   * Intra-chunk co-occurrence
   */
  def findIntraChunkCooccurrences(
                                   mentionsByChunk: Map[String, Seq[Concept]],
                                   minCooccurrence: Int = 1
                                 ): Seq[ConceptPair] = {

    val pairs = scala.collection.mutable.Map[(String, String), ConceptPair]()

    mentionsByChunk.foreach {
      case (chunkId, concepts) =>

        // Scala 2.12-compatible distinctBy
        val uniqueConcepts: Seq[Concept] =
          concepts.groupBy(_.conceptId).map(_._2.head).toSeq

        uniqueConcepts.combinations(2).foreach {
          case Seq(c1, c2) =>
            val key = canonicalKey(c1.conceptId, c2.conceptId)

            pairs.get(key) match {
              case Some(existing) =>
                pairs(key) = existing.copy(
                  cooccurrenceCount = existing.cooccurrenceCount + 1,
                  chunkIds = existing.chunkIds + chunkId
                )

              case None =>
                pairs(key) = ConceptPair(c1, c2, 1, Set(chunkId))
            }
        }
    }

    pairs.values
      .filter(_.cooccurrenceCount >= minCooccurrence)
      .toSeq
      .sortBy(-_.cooccurrenceCount)
  }


  /** Canonical sorted tuple key */
  private def canonicalKey(id1: String, id2: String): (String, String) =
    if (id1 <= id2) (id1, id2) else (id2, id1)
}
