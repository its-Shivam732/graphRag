package com.graphrag.ingestion

import com.graphrag.core.models.Chunk
import org.apache.flink.streaming.api.scala._
import org.apache.flink.api.common.eventtime.WatermarkStrategy
import org.apache.flink.connector.file.src.FileSource
import org.apache.flink.connector.file.src.reader.TextLineInputFormat
import org.apache.flink.core.fs.Path
import org.apache.flink.api.common.functions.FlatMapFunction
import org.apache.flink.util.Collector

import scala.util.{Try, Success, Failure}
import org.slf4j.LoggerFactory

object ChunkSource {

  private val log = LoggerFactory.getLogger("ChunkSource")

  /**
   * Reads a JSONL file stream from one of:
   *   • local path  (file:/// or /Users/…)
   *   • S3 path     (s3://, s3a://, s3n://)
   */
  def fromJsonLines(
                     env: StreamExecutionEnvironment,
                     path: String
                   ): DataStream[Chunk] = {

    log.info(s"Reading chunks from: $path")

    // Detect if this is an S3 path
    val normalizedPath =
      if (isS3Path(path)) {
        log.info(s"Detected S3 path: $path")
        new Path(path) // S3 paths must be passed directly to Flink
      } else {
        log.info(s"Detected local filesystem path: $path")
        if (path.startsWith("file://")) new Path(path)
        else new Path("file://" + path)
      }

    // Construct a FileSource that works for both local & S3
    val fileSource =
      FileSource
        .forRecordStreamFormat(new TextLineInputFormat(), normalizedPath)
        .build()

    env.fromSource(
      fileSource,
      WatermarkStrategy.noWatermarks[String](),
      "chunk-source"
    ).flatMap(new JsonParser())
  }

  /** Detect if the path belongs to S3 family */
  private def isS3Path(path: String): Boolean =
    path.startsWith("s3://") ||
      path.startsWith("s3a://") ||
      path.startsWith("s3n://")

  // ---------------------------------------------------------
  // JSON Parsing (unchanged)
  // ---------------------------------------------------------
  class JsonParser extends FlatMapFunction[String, Chunk] {
    override def flatMap(line: String, out: Collector[Chunk]): Unit = {
      parseChunkJson(line) match {
        case Success(chunk) => out.collect(chunk)
        case Failure(e)     => log.warn(s"Failed to parse chunk JSON: ${e.getMessage}")
      }
    }
  }

  private def parseChunkJson(json: String): Try[Chunk] = Try {
    import io.circe.parser._
    import io.circe.generic.auto._

    case class JsonChunk(
                          chunkId: String,
                          docId: String,
                          offset: Long,
                          chunk: String,
                          chunkHash: String,
                          language: Option[String],
                          title: Option[String]
                        )

    parse(json).flatMap(_.as[JsonChunk]) match {
      case Right(jc) =>
        Chunk(
          chunkId = jc.chunkId,
          docId = jc.docId,
          span = (jc.offset.toInt, jc.offset.toInt + jc.chunk.length),
          text = jc.chunk,
          sourceUri = jc.title.getOrElse("unknown"),
          hash = jc.chunkHash
        )

      case Left(err) =>
        throw new Exception(s"JSON parse error: $err")
    }
  }
}
