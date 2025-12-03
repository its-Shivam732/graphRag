package com.graphrag.core.config

object Config {

  val ollamaUrl: String = sys.env.getOrElse("OLLAMA_URL", "http://localhost:11434")
  val ollamaModel: String = sys.env.getOrElse("OLLAMA_MODEL", "llama3")
  val flinkurl: String = sys.env.getOrElse("FLINK_URL", "http://localhost:8081")


  val neo4jUri: String = sys.env.getOrElse("NEO4J_URI", "YOUR_URI")
  val neo4jUsername: String = sys.env.getOrElse("NEO4J_USERNAME", "neo4j")
 val neo4jPassword: String = sys.env.getOrElse("NEO4J_PASSWORD", "YOUR_PASSWORD")

  // Feature flags
  val useLLM: Boolean = sys.env.getOrElse("USE_LLM", "true").toBoolean
  val writeToNeo4j: Boolean = sys.env.getOrElse("WRITE_TO_NEO4J", "true").toBoolean

  val relationWindowSize: Int = sys.env.getOrElse("RELATION_WINDOW_SIZE", "3").toInt
  val relationMinCooccurrence: Int = sys.env.getOrElse("RELATION_MIN_COOCCURRENCE", "2").toInt
  val relationMaxConcurrentLLM: Int = sys.env.getOrElse("RELATION_MAX_CONCURRENT_LLM", "1").toInt

  val default_json_file: String = sys.env.getOrElse("FILE", "YOUR_JSON_FILE")

}