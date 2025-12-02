package com.graphrag.ingestion

import com.graphrag.core.models.Chunk
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.functions.MapFunction

object ChunkNormalizer {

  def normalize(chunks: DataStream[Chunk]): DataStream[Chunk] = {
    chunks
      .map(new NormalizeMapper())
      .name("normalize-chunks")
  }

  class NormalizeMapper extends MapFunction[Chunk, Chunk] {
    override def map(chunk: Chunk): Chunk = {
      chunk.copy(text = chunk.text.trim)
    }
  }
}