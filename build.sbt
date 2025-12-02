name := "graphrag"
ThisBuild / version := "0.1.0"
ThisBuild / scalaVersion := "2.12.18"
ThisBuild / organization := "com.graphrag"

val flinkVersion     = "1.20.0"
val neo4jVersion     = "5.15.0"
val circeVersion     = "0.14.6"
val sttpVersion      = "3.9.1"
val akkaVersion      = "2.8.5"
val akkaHttpVersion  = "10.5.3"

// ------------------------------------------------------
// Shared settings (used by all modules)
// ------------------------------------------------------
lazy val commonSettings = Seq(
  scalacOptions ++= Seq("-deprecation","-feature","-unchecked","-Xlint"),

  libraryDependencies ++= Seq(
    // Logging
    "org.slf4j" % "slf4j-api" % "2.0.9",

    // Testing
    "org.scalatest" %% "scalatest" % "3.2.17" % Test,
    "org.mockito" % "mockito-core" % "5.7.0" % Test,
    "org.mockito" % "mockito-inline" % "5.2.0" % Test
  )
)

// ------------------------------------------------------
// Module: core (models)
// ------------------------------------------------------
lazy val core = (project in file("modules/core"))
  .settings(
    name := "graphrag-core",
    commonSettings,
    assembly / skip := true, // no jars for modules
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
    )
  )

// ------------------------------------------------------
// Module: ingestion
// ------------------------------------------------------
lazy val ingestion = (project in file("modules/ingestion"))
  .dependsOn(core)
  .settings(
    name := "graphrag-ingestion",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % Provided,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion % Provided,
      "org.apache.flink" % "flink-clients" % flinkVersion % Provided,
      "org.apache.flink" % "flink-connector-files" % flinkVersion % Provided,
      "org.apache.parquet" % "parquet-avro" % "1.12.3",
      "org.apache.hadoop" % "hadoop-common" % "3.3.4" % Provided,
      "org.apache.hadoop" % "hadoop-aws" % "3.3.4"
    )
  )

// ------------------------------------------------------
// Module: neo4j
// ------------------------------------------------------
lazy val neo4j = (project in file("modules/neo4j"))
  .dependsOn(core)
  .settings(
    name := "graphrag-neo4j",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % Provided,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion % Provided,
      "org.neo4j.driver" % "neo4j-java-driver" % neo4jVersion
    )
  )

// ------------------------------------------------------
// Module: llm
// ------------------------------------------------------
lazy val llm = (project in file("modules/llm"))
  .dependsOn(core)
  .settings(
    name := "graphrag-llm",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % Provided,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion % Provided,
      "com.softwaremill.sttp.client3" %% "core" % sttpVersion,
      "com.softwaremill.sttp.client3" %% "circe" % sttpVersion,
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "edu.stanford.nlp" % "stanford-corenlp" % "4.5.7",
      ("edu.stanford.nlp" % "stanford-corenlp" % "4.5.7" classifier "models") % Provided
    )
  )

// ------------------------------------------------------
// Module: api
// ------------------------------------------------------
lazy val api = (project in file("modules/api"))
  .dependsOn(
    core  % "compile->compile",
    neo4j % "compile->compile",
    llm   % "compile->compile"
  )
  .settings(
    name := "graphrag-api",
    commonSettings,
    assembly / skip := true,
    libraryDependencies ++= Seq(
      "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
      "com.typesafe.akka" %% "akka-stream" % akkaVersion,
      "com.typesafe.akka" %% "akka-actor-typed" % akkaVersion,
      "de.heikoseeberger" %% "akka-http-circe" % "1.39.2",
      "org.neo4j.driver" % "neo4j-java-driver" % neo4jVersion,
      "ch.qos.logback" % "logback-classic" % "1.4.14",
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % Test,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion % Test,
      "org.apache.flink" % "flink-clients" % flinkVersion % Test,
      "org.apache.flink" % "flink-connector-files" % flinkVersion % Test
    )
  )


// ------------------------------------------------------
// Module: pipeline (your Flink + RAG job)
// ------------------------------------------------------
lazy val pipeline = (project in file("modules/pipeline"))
  .dependsOn(core, ingestion, neo4j, llm, api)
  .settings(
    name := "graphrag-pipeline",
    commonSettings,
    assembly / skip := true, // don't produce an assembly here
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion,
      "org.apache.flink" % "flink-clients" % flinkVersion
    ),
    Compile / mainClass := Some("com.graphrag.GraphRAGJob")
  )


// ------------------------------------------------------
// ROOT PROJECT – final deployable fat jar
// ------------------------------------------------------
lazy val root = (project in file("."))
  .dependsOn(core, ingestion, neo4j, llm, api, pipeline)
  .settings(
    name := "graphrag-job",
    commonSettings,

    // Add provided dependencies so tests can run
    libraryDependencies ++= Seq(
      "org.apache.flink" %% "flink-streaming-scala" % flinkVersion % Test,
      "org.apache.flink" % "flink-streaming-java" % flinkVersion % Test,
      "org.apache.flink" % "flink-clients" % flinkVersion % Test,
      "org.apache.flink" % "flink-connector-files" % flinkVersion % Test,
      "org.apache.hadoop" % "hadoop-common" % "3.3.4" % Test
    ),

    // Assembly settings
    Compile / mainClass := Some("com.graphrag.GraphRAGJob"),
    assembly / mainClass := Some("com.graphrag.GraphRAGJob"),
    assembly / assemblyJarName := "graphrag-job.jar",
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", xs @ _*) => MergeStrategy.discard
      case "reference.conf" | "application.conf" => MergeStrategy.concat
      case "module-info.class" => MergeStrategy.discard
      case x if x.endsWith(".proto") => MergeStrategy.first
      case x if x.endsWith(".properties") => MergeStrategy.concat
      case _ => MergeStrategy.first
    }
  )
