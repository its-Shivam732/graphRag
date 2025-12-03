package com.graphrag.ingestion

import com.graphrag.core.models.Chunk
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.functions.MapFunction

/**
 * Utility object containing Flink transformations for normalizing Chunk data.
 */
object ChunkNormalizer {

  /**
   * Normalize a stream of Chunk objects.
   *
   * Currently performs simple text normalization (trim), but can be extended in future
   * to include additional normalization steps (lowercasing, cleaning punctuation, etc.)
   *
   * @param chunks Incoming DataStream[Chunk]
   * @return Normalized DataStream[Chunk]
   */
  def normalize(chunks: DataStream[Chunk]): DataStream[Chunk] = {
    chunks
      // Apply the NormalizeMapper to trim the chunk text
      .map(new NormalizeMapper())
      .name("normalize-chunks")
  }

  /**
   * A simple Flink MapFunction that trims whitespace in chunk text.
   *
   * Does not modify any other attributes of the Chunk.
   */
  class NormalizeMapper extends MapFunction[Chunk, Chunk] {
    override def map(chunk: Chunk): Chunk = {
      // Creates a copy of the original chunk, replacing only the text field
      chunk.copy(text = chunk.text.trim)
    }
  }
}
