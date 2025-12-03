package com.graphrag.llm

import com.graphrag.core.models._
import org.apache.flink.streaming.api.scala._
import org.apache.flink.streaming.api.functions.KeyedProcessFunction
import org.apache.flink.streaming.api.functions.async.{ResultFuture, RichAsyncFunction}
import org.apache.flink.streaming.api.datastream.{AsyncDataStream => JavaAsyncDataStream}
import org.apache.flink.util.Collector
import org.apache.flink.configuration.Configuration
import org.apache.flink.streaming.api.functions.co.CoProcessFunction
import org.apache.flink.api.common.functions.{FilterFunction, MapFunction}
import org.apache.flink.api.java.functions.KeySelector

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}
import java.util.concurrent.TimeUnit

import org.slf4j.LoggerFactory

/**
 * RelationExtractionStage
 *
 * Builds a complete relation extraction pipeline:
 * 1. Find co-occurring concepts in same chunk
 * 2. Join concept pairs with chunk text context
 * 3. Score relations using async LLM calls
 * 4. Filter by confidence threshold
 */
object RelationExtractionStage {

  private val logger = LoggerFactory.getLogger("RelationExtractionStage")

  def extractRelations(
                        mentions: DataStream[Mentions],
                        chunks: DataStream[Chunk],
                        ollamaUrl: String,
                        ollamaModel: String,
                        windowSize: Int = 3,
                        minCooccurrence: Int = 1,
                        maxConcurrentRequests: Int = 1
                      ): DataStream[Relation] = {

    logger.info("Initializing relation extraction pipeline...")

    // Step 1: Find co-occurring concepts
    val conceptPairs = mentions
      .keyBy(new MentionChunkIdKeySelector())
      .process(new CooccurrenceFinder())
      .name("find-cooccurrences")

    // Step 2: Prepare chunks for joining
    val chunkMap = chunks
      .map(new ChunkToChunkWithIdMapper())
      .keyBy(new ChunkWithIdKeySelector())

    // Step 3: Join pairs with context
    val pairsWithContext = conceptPairs
      .map(new PairToPairWithChunkMapper())
      .keyBy(new PairChunkIdKeySelector())
      .connect(chunkMap)
      .process(new ContextJoiner())
      .name("join-context")

    logger.info("Launching asynchronous LLM relation scoring...")

    // Step 4: Async LLM scoring
    val javaStream = pairsWithContext.javaStream

    val asyncResult = JavaAsyncDataStream.unorderedWait(
      javaStream,
      new AsyncRelationScorer(ollamaUrl, ollamaModel, maxConcurrentRequests),
      90000,
      TimeUnit.MILLISECONDS,
      maxConcurrentRequests
    )

    // Step 5: Filter by confidence
    new DataStream[Relation](asyncResult)
      .name("score-relations-async")
      .filter(new ConfidenceFilter())
  }

  // ========================================================================
  // KeySelectors (must be separate classes for Flink serialization)
  // ========================================================================

  class MentionChunkIdKeySelector extends KeySelector[Mentions, String] {
    override def getKey(m: Mentions): String = m.chunkId
  }

  class ChunkWithIdKeySelector extends KeySelector[ChunkWithId, String] {
    override def getKey(c: ChunkWithId): String = c.chunkId
  }

  class PairChunkIdKeySelector extends KeySelector[PairWithChunk, String] {
    override def getKey(p: PairWithChunk): String = p.chunkId
  }

  // ========================================================================
  // Mappers
  // ========================================================================

  class ChunkToChunkWithIdMapper extends MapFunction[Chunk, ChunkWithId] {
    override def map(c: Chunk): ChunkWithId = ChunkWithId(c.chunkId, c.text)
  }

  class PairToPairWithChunkMapper extends MapFunction[ConceptPair, PairWithChunk] {
    override def map(pair: ConceptPair): PairWithChunk =
      PairWithChunk(pair.chunkIds.head, pair)
  }

  class ConfidenceFilter extends FilterFunction[Relation] {
    override def filter(r: Relation): Boolean = r.confidence > 0.5
  }
}

// ============================================================================
// CooccurrenceFinder
//
// Finds concepts that co-occur in the same chunk and emits ConceptPairs.
// Uses a timer to batch concepts by chunk before emitting pairs.
// ============================================================================
class CooccurrenceFinder
  extends KeyedProcessFunction[String, Mentions, ConceptPair] {

  private val logger = LoggerFactory.getLogger(classOf[CooccurrenceFinder])

  // Mutable state (serializable)
  private val conceptsBuffer = new java.util.ArrayList[Concept]()
  private val chunkIdHolder = Array[String](null)

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)
    conceptsBuffer.clear()
    chunkIdHolder(0) = null
    logger.info("CooccurrenceFinder initialized")
  }

  override def processElement(
                               mention: Mentions,
                               ctx: KeyedProcessFunction[String, Mentions, ConceptPair]#Context,
                               out: Collector[ConceptPair]
                             ): Unit = {

    if (chunkIdHolder(0) == null)
      chunkIdHolder(0) = mention.chunkId

    conceptsBuffer.add(mention.concept)

    // Register timer to batch concepts
    ctx.timerService().registerProcessingTimeTimer(
      ctx.timerService().currentProcessingTime() + 50
    )
  }

  override def onTimer(
                        timestamp: Long,
                        ctx: KeyedProcessFunction[String, Mentions, ConceptPair]#OnTimerContext,
                        out: Collector[ConceptPair]
                      ): Unit = {
    emitPairs(out)
  }

  private def emitPairs(out: Collector[ConceptPair]): Unit = {
    import scala.collection.JavaConverters._

    // Deduplicate concepts by conceptId
    val uniqueConcepts =
      conceptsBuffer.asScala.groupBy(_.conceptId).map(_._2.head).toSeq

    if (uniqueConcepts.size >= 2) {
      logger.debug(s"Emitting ${uniqueConcepts.size} co-occurring concepts for chunk ${chunkIdHolder(0)}")

      // Emit all pairs
      uniqueConcepts.combinations(2).foreach {
        case Seq(c1, c2) =>
          out.collect(ConceptPair(c1, c2, 1, Set(chunkIdHolder(0))))
      }
    }

    // Clear buffer
    conceptsBuffer.clear()
    chunkIdHolder(0) = null
  }
}

// ============================================================================
// ContextJoiner
//
// Joins concept pairs with their chunk text context.
// Maintains a map of chunk texts and buffers pending pairs until context arrives.
// ============================================================================
class ContextJoiner
  extends CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate] {

  private val logger = LoggerFactory.getLogger(classOf[ContextJoiner])

  // Mutable state for joining
  private val chunkTexts = scala.collection.mutable.Map[String, String]()
  private val pendingPairs = scala.collection.mutable.ListBuffer[PairWithChunk]()

  override def processElement1(
                                pairWithChunk: PairWithChunk,
                                ctx: CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate]#Context,
                                out: Collector[RelationCandidate]
                              ): Unit = {

    chunkTexts.get(pairWithChunk.chunkId) match {
      case Some(text) =>
        // Context already available - emit immediately
        out.collect(RelationCandidate(pairWithChunk.pair, text))
      case None =>
        // Buffer until context arrives
        pendingPairs += pairWithChunk
    }
  }

  override def processElement2(
                                chunkWithId: ChunkWithId,
                                ctx: CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate]#Context,
                                out: Collector[RelationCandidate]
                              ): Unit = {

    // Store chunk text
    chunkTexts(chunkWithId.chunkId) = chunkWithId.text

    // Emit any pending pairs for this chunk
    pendingPairs
      .filter(_.chunkId == chunkWithId.chunkId)
      .foreach { pairWithChunk =>
        out.collect(RelationCandidate(pairWithChunk.pair, chunkWithId.text))
      }

    // Remove emitted pairs from buffer
    pendingPairs --= pendingPairs.filter(_.chunkId == chunkWithId.chunkId)
  }
}

// ============================================================================
// AsyncRelationScorer
//
// Asynchronously scores relations using LLM.
// Uses thread pool for concurrent LLM requests.
// Returns relations with confidence scores, predicate, and evidence.
// ============================================================================
class AsyncRelationScorer(
                           ollamaUrl: String,
                           model: String,
                           maxConcurrentRequests: Int
                         ) extends RichAsyncFunction[RelationCandidate, Relation] {

  // ✅ Transient lazy vals for non-serializable objects
  @transient private lazy val executionContext: ExecutionContext = {
    ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(maxConcurrentRequests)
    )
  }

  @transient private lazy val ollamaClient: OllamaClient = {
    OllamaClient(ollamaUrl)(executionContext)
  }

  @transient private lazy val logger: org.slf4j.Logger = {
    LoggerFactory.getLogger(classOf[AsyncRelationScorer])
  }

  override def open(parameters: Configuration): Unit = {
    super.open(parameters)

    // Force initialization of lazy vals
    locally { ollamaClient }
    locally { executionContext }
    locally { logger }

    logger.info(s"AsyncRelationScorer initialized with $maxConcurrentRequests concurrent requests")
  }

  override def close(): Unit = {
    try {
      logger.info("Closing AsyncRelationScorer")
      ollamaClient.close()
    } catch {
      case e: Exception =>
        try {
          logger.error(s"Error closing AsyncRelationScorer: ${e.getMessage}", e)
        } catch {
          case _: Throwable =>
            System.err.println(s"Error closing AsyncRelationScorer: ${e.getMessage}")
        }
    } finally {
      super.close()
    }
  }

  override def asyncInvoke(
                            candidate: RelationCandidate,
                            resultFuture: ResultFuture[Relation]
                          ): Unit = {

    implicit val ec: ExecutionContext = executionContext

    // Build prompt for LLM
    val prompt = RelationScorer.buildRelationPrompt(
      candidate.pair.concept1.surface,
      candidate.pair.concept2.surface,
      candidate.context
    )

    // Async LLM call
    val relationFuture =
      ollamaClient
        .generateAsync(model, prompt, temperature = 0.0)
        .map { response =>
          RelationScorer.parseRelationFromResponse(
            response,
            candidate.pair.concept1.conceptId,
            candidate.pair.concept2.conceptId
          )
        }
        .recover {
          case e: Exception =>
            logger.warn(s"Failed to score relation: ${e.getMessage}")
            None
        }

    // Complete result future
    relationFuture.onComplete {
      case Success(Some(rel)) =>
        resultFuture.complete(java.util.Collections.singleton(rel))

      case Success(None) =>
        resultFuture.complete(java.util.Collections.emptyList())

      case Failure(e) =>
        logger.error(s"Relation scoring failed: ${e.getMessage}", e)
        resultFuture.complete(java.util.Collections.emptyList())
    }
  }

  override def timeout(
                        candidate: RelationCandidate,
                        resultFuture: ResultFuture[Relation]
                      ): Unit = {
    logger.warn("Relation scoring timed out")
    resultFuture.complete(java.util.Collections.emptyList())
  }
}