package com.graphrag.api.services

import com.graphrag.api.models.{ExecutionTrace, TraceStep}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.concurrent.TrieMap

class ExplainService(implicit ec: ExecutionContext) {

  // In-memory trace storage (replace with Redis/DynamoDB in production)
  private val traces = TrieMap[String, ExecutionTrace]()

  def recordTrace(trace: ExecutionTrace): Unit = {
    traces.put(trace.requestId, trace)
  }

  def getTrace(requestId: String): Future[Option[ExecutionTrace]] = Future {
    traces.get(requestId)
  }

  def close(): Unit = {
    traces.clear()
  }
}