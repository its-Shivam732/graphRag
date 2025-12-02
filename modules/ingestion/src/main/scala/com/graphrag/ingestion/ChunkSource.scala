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

object ChunkSource {

  def fromJsonLines(
                     env: StreamExecutionEnvironment,
                     path: String
                   ): DataStream[Chunk] = {

    println(s"Reading chunks from: $path")

    val fileSource = FileSource
      .forRecordStreamFormat(
        new TextLineInputFormat(),
        new Path(path)
      )
      .build()

    env.fromSource(
      fileSource,
      WatermarkStrategy.noWatermarks[String](),
      "chunk-source"
    ).flatMap(new JsonParser())
  }

  class JsonParser extends FlatMapFunction[String, Chunk] {
    override def flatMap(line: String, out: Collector[Chunk]): Unit = {
      parseChunkJson(line) match {
        case Success(chunk) => out.collect(chunk)
        case Failure(e) => println(s"Failed to parse line: ${e.getMessage}")
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

    val parsed = parse(json).flatMap(_.as[JsonChunk])

    parsed match {
      case Right(jc) => Chunk(
        chunkId = jc.chunkId,
        docId = jc.docId,
        span = (jc.offset.toInt, jc.offset.toInt + jc.chunk.length),
        text = jc.chunk,
        sourceUri = jc.title.getOrElse("unknown"),
        hash = jc.chunkHash
      )
      case Left(error) => throw new Exception(s"JSON parse error: $error")
    }
  }
}