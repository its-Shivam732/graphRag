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

object RelationExtractionStage {

  def extractRelations(
                        mentions: DataStream[Mentions],
                        chunks: DataStream[Chunk],
                        ollamaUrl: String,
                        ollamaModel: String,
                        windowSize: Int = 3,
                        minCooccurrence: Int = 1,
                        maxConcurrentRequests: Int = 1
                      ): DataStream[Relation] = {

    // Step 1: Find co-occurring concepts within chunks
    val conceptPairs = mentions
      .keyBy(new MentionChunkIdKeySelector())  // ✅ FIXED
      .process(new CooccurrenceFinder())
      .name("find-cooccurrences")

    // Step 2: Join with chunks to get context
    val chunkMap = chunks
      .map(new ChunkToChunkWithIdMapper())
      .keyBy(new ChunkWithIdKeySelector())  // ✅ FIXED

    val pairsWithContext = conceptPairs
      .map(new PairToPairWithChunkMapper())
      .keyBy(new PairChunkIdKeySelector())  // ✅ FIXED
      .connect(chunkMap)
      .process(new ContextJoiner())
      .name("join-context")

    // Step 3: Score relations with LLM (ASYNC!)
    val javaStream = pairsWithContext.javaStream
    val asyncResult = JavaAsyncDataStream.unorderedWait(
      javaStream,
      new AsyncRelationScorer(ollamaUrl, ollamaModel, maxConcurrentRequests),
      90000,
      TimeUnit.MILLISECONDS,
      maxConcurrentRequests
    )

    new DataStream[Relation](asyncResult)
      .name("score-relations-async")
      .filter(new ConfidenceFilter())
  }

  // KeySelectors - NO LAMBDAS!
  class MentionChunkIdKeySelector extends KeySelector[Mentions, String] {
    override def getKey(m: Mentions): String = m.chunkId
  }

  class ChunkWithIdKeySelector extends KeySelector[ChunkWithId, String] {
    override def getKey(c: ChunkWithId): String = c.chunkId
  }

  class PairChunkIdKeySelector extends KeySelector[PairWithChunk, String] {
    override def getKey(p: PairWithChunk): String = p.chunkId
  }

  // Mapper classes
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

class CooccurrenceFinder extends KeyedProcessFunction[String, Mentions, ConceptPair] {
  private val conceptsBuffer = new java.util.ArrayList[Concept]()
  private val chunkIdHolder = Array[String](null)

  override def open(parameters: Configuration): Unit = {
    conceptsBuffer.clear()
    chunkIdHolder(0) = null
  }

  override def processElement(
                               mention: Mentions,
                               ctx: KeyedProcessFunction[String, Mentions, ConceptPair]#Context,
                               out: Collector[ConceptPair]
                             ): Unit = {
    if (chunkIdHolder(0) == null) chunkIdHolder(0) = mention.chunkId
    conceptsBuffer.add(mention.concept)

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

    val uniqueConcepts: Seq[Concept] = conceptsBuffer.asScala
      .groupBy(_.conceptId)
      .map(_._2.head)
      .toSeq

    if (uniqueConcepts.size >= 2) {
      uniqueConcepts.combinations(2).foreach {
        case Seq(c1, c2) =>
          out.collect(ConceptPair(c1, c2, 1, Set(chunkIdHolder(0))))
      }
    }

    conceptsBuffer.clear()
    chunkIdHolder(0) = null
  }
}
class ContextJoiner extends CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate] {
  private val chunkTexts = scala.collection.mutable.Map[String, String]()
  private val pendingPairs = scala.collection.mutable.ListBuffer[PairWithChunk]()

  override def processElement1(
                                pairWithChunk: PairWithChunk,
                                ctx: CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate]#Context,
                                out: Collector[RelationCandidate]
                              ): Unit = {
    chunkTexts.get(pairWithChunk.chunkId) match {
      case Some(text) =>
        out.collect(RelationCandidate(pairWithChunk.pair, text))
      case None =>
        pendingPairs += pairWithChunk
    }
  }

  override def processElement2(
                                chunkWithId: ChunkWithId,
                                ctx: CoProcessFunction[PairWithChunk, ChunkWithId, RelationCandidate]#Context,
                                out: Collector[RelationCandidate]
                              ): Unit = {
    chunkTexts(chunkWithId.chunkId) = chunkWithId.text

    pendingPairs.filter(_.chunkId == chunkWithId.chunkId).foreach { pairWithChunk =>
      out.collect(RelationCandidate(pairWithChunk.pair, chunkWithId.text))
    }

    pendingPairs --= pendingPairs.filter(_.chunkId == chunkWithId.chunkId)
  }
}

class AsyncRelationScorer(
                           ollamaUrl: String,
                           model: String,
                           maxConcurrentRequests: Int
                         ) extends RichAsyncFunction[RelationCandidate, Relation] {

  @transient private val ollamaClientHolder = Array[OllamaClient](null)
  @transient implicit private val ecHolder = Array[ExecutionContext](null)

  override def open(parameters: Configuration): Unit = {
    ecHolder(0) = ExecutionContext.fromExecutor(
      java.util.concurrent.Executors.newFixedThreadPool(maxConcurrentRequests)
    )
    ollamaClientHolder(0) = OllamaClient(ollamaUrl)(ecHolder(0))
    println(s"Initialized Async Ollama client for relation scoring: $ollamaUrl with $maxConcurrentRequests concurrent requests")
  }

  override def close(): Unit = {
    Option(ollamaClientHolder(0)).foreach(_.close())
  }

  override def asyncInvoke(
                            candidate: RelationCandidate,
                            resultFuture: ResultFuture[Relation]
                          ): Unit = {
    implicit val ec: ExecutionContext = ecHolder(0)

    val prompt = RelationScorer.buildRelationPrompt(
      candidate.pair.concept1.surface,
      candidate.pair.concept2.surface,
      candidate.context
    )

    val relationFuture: Future[Option[Relation]] = ollamaClientHolder(0)
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
          println(s"LLM relation scoring failed: ${e.getMessage}")
          None
      }

    relationFuture.onComplete {
      case Success(Some(relation)) =>
        resultFuture.complete(java.util.Collections.singleton(relation))
      case Success(None) =>
        resultFuture.complete(java.util.Collections.emptyList())
      case Failure(e) =>
        println(s"Async relation scoring failed: ${e.getMessage}")
        resultFuture.complete(java.util.Collections.emptyList())
    }
  }

  override def timeout(
                        candidate: RelationCandidate,
                        resultFuture: ResultFuture[Relation]
                      ): Unit = {
    println(s"Timeout scoring relation for ${candidate.pair.concept1.surface} - ${candidate.pair.concept2.surface}")
    resultFuture.complete(java.util.Collections.emptyList())
  }
}