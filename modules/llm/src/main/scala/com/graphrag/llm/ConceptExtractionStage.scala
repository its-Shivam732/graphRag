package com.graphrag.llm

import com.graphrag.core.models.{Chunk, Mentions}
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.datastream.{AsyncDataStream => JavaAsyncDataStream}
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.util.Collector
import java.util.concurrent.TimeUnit

/**
 * ConceptExtractionStage
 *
 * This object defines the entry point for concept extraction
 * during ingestion. It supports two modes:
 *
 *  1. Asynchronous LLM-based concept extraction (slow but accurate)
 *  2. Heuristic-only extraction (fast, fallback option)
 *
 * The output of both flows is a stream of Mentions.
 */
object ConceptExtractionStage {

  /**
   * extractConcepts
   *
   * Given a stream of Chunk objects, this function extracts concept mentions
   * based on either:
   *   - AsyncConceptExtraction (LLM-powered)
   *   - HeuristicExtractor (simple rule-based)
   *
   * @param chunks Input DataStream[Chunk]
   * @param ollamaUrl URL of Ollama LLM server
   * @param ollamaModel The model name to use
   * @param useLLM If true → run full async LLM extraction
   * @param maxConcurrentRequests Number of concurrent LLM requests allowed
   * @param timeout Timeout for each async LLM call
   * @return DataStream[Mentions]
   */
  def extractConcepts(
                       chunks: DataStream[Chunk],
                       ollamaUrl: String,
                       ollamaModel: String,
                       useLLM: Boolean = true,
                       maxConcurrentRequests: Int = 1,
                       timeout: Long = 90000
                     ): DataStream[Mentions] = {

    // -------------------------------
    // 1. FULL LLM MODE
    // -------------------------------
    if (useLLM) {

      // Convert to Java DataStream so Flink async API can be used
      val javaStream = chunks.javaStream

      // Apply asynchronous concept extraction using AsyncConceptExtraction
      val asyncResult = JavaAsyncDataStream.unorderedWait(
        javaStream,
        new AsyncConceptExtraction(ollamaUrl, ollamaModel, maxConcurrentRequests),
        timeout,
        TimeUnit.MILLISECONDS,
        maxConcurrentRequests
      )

      // Convert back to Scala DataStream
      new DataStream[Mentions](asyncResult)
        .name("concept-extraction-async")

    } else {

      // -------------------------------
      // 2. HEURISTIC-ONLY MODE
      // -------------------------------
      chunks
        .flatMap(new HeuristicExtractor()) // Extract mentions using local heuristics
        .name("concept-extraction-heuristic")
    }
  }

  /**
   * HeuristicExtractor
   *
   * This is the fast, rule-based extractor that does NOT call an LLM.
   * It expands each Chunk into zero or more Mentions.
   */
  class HeuristicExtractor extends FlatMapFunction[Chunk, Mentions] {
    override def flatMap(chunk: Chunk, out: Collector[Mentions]): Unit = {
      // Emit all heuristic mentions extracted from this chunk
      ConceptExtractor.extractHeuristic(chunk).foreach(out.collect)
    }
  }
}
