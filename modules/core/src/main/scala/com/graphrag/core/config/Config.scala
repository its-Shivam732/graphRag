package com.graphrag.core.config

object Config {

  // Ollama configuration
  val ollamaUrl: String = sys.env.getOrElse("OLLAMA_URL", "http://localhost:11434")
  val ollamaModel: String = sys.env.getOrElse("OLLAMA_MODEL", "llama3")

  // Neo4j configuration (use your Aura credentials)
  //val neo4jUri: String = sys.env.getOrElse("NEO4J_URI", "neo4j+s://7f5eb4a9.databases.neo4j.io")
  val neo4jUri: String = sys.env.getOrElse("NEO4J_URI", "neo4j+s://4342d162.databases.neo4j.io")
  val neo4jUsername: String = sys.env.getOrElse("NEO4J_USERNAME", "neo4j")
//  val neo4jPassword: String = sys.env.getOrElse("NEO4J_PASSWORD", "M7nOvHWiho7N7jbfhTiX0loVURKAfRekaGwaCdjhh5g")
val neo4jPassword: String = sys.env.getOrElse("NEO4J_PASSWORD", "4mBpZPs8P7jId1BvZb2xem3sUMcaCL0kjnK4x6os7XA")

  // Feature flags
  val useLLM: Boolean = sys.env.getOrElse("USE_LLM", "true").toBoolean
  val writeToNeo4j: Boolean = sys.env.getOrElse("WRITE_TO_NEO4J", "true").toBoolean
}