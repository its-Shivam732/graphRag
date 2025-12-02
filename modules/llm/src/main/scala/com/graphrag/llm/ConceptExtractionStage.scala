package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Mentions}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.datastream.{AsyncDataStream => JavaAsyncDataStream}
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.util.Collector
import java.util.concurrent.TimeUnit

object ConceptExtractionStage {

  def extractConcepts(
                       chunks: DataStream[Chunk],
                       ollamaUrl: String,
                       ollamaModel: String,
                       useLLM: Boolean = true,
                       maxConcurrentRequests: Int = 1,
                       timeout: Long = 90000
                     ): DataStream[Mentions] = {

    if (useLLM) {
      val javaStream = chunks.javaStream

      val asyncResult = JavaAsyncDataStream.unorderedWait(
        javaStream,
        new AsyncConceptExtraction(ollamaUrl, ollamaModel, maxConcurrentRequests),
        timeout,
        TimeUnit.MILLISECONDS,
        maxConcurrentRequests
      )

      new DataStream[Mentions](asyncResult)
        .name("concept-extraction-async")

    } else {
      chunks
        .flatMap(new HeuristicExtractor())
        .name("concept-extraction-heuristic")
    }
  }

  class HeuristicExtractor extends FlatMapFunction[Chunk, Mentions] {
    override def flatMap(chunk: Chunk, out: Collector[Mentions]): Unit = {
      ConceptExtractor.extractHeuristic(chunk).foreach(out.collect)
    }
  }
}