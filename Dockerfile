# Start from Flink base image
FROM flink:1.18-scala_2.12-java17
# Install dependencies your job needs
RUN apt-get update && apt-get install -y \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Copy your compiled JAR (the .jar file you currently run)
COPY target/scala-2.12/graphrag-pipeline.jar /opt/flink/usrlib/graphrag-job.jar

# Set working directory
WORKDIR /opt/flink
