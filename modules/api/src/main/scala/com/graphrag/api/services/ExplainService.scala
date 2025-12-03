package com.graphrag.api.services

import com.graphrag.api.models.ExplainTrace
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.concurrent.TrieMap

class ExplainService(implicit ec: ExecutionContext) {

  // In-memory storage (can be swapped with Redis or Dynamo later)
  private val traces = TrieMap[String, ExplainTrace]()

  /** Store a trace under a requestId */
  def recordTrace(requestId: String, trace: ExplainTrace): Unit = {
    traces.put(requestId, trace)
  }

  /** Retrieve a trace by requestId */
  def getTrace(requestId: String): Future[Option[ExplainTrace]] = Future {
    traces.get(requestId)
  }

  def close(): Unit = traces.clear()
}
