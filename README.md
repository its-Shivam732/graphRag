Here's a comprehensive README.md for your GraphRAG project:

---

# GraphRAG: Knowledge Graph Construction with Apache Flink and Neo4j

A production-grade system for building knowledge graphs from unstructured text using Apache Flink for distributed processing, Neo4j for graph storage, and LLMs for entity/relation extraction.

## Youtube video---https://youtu.be/LY62T4pu1zU
---

## Table of Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Features](#features)
- [Prerequisites](#prerequisites)
- [Local Development](#local-development)
- [Building the Project](#building-the-project)
- [Running Locally](#running-locally)
- [API Documentation](#api-documentation)
- [EKS Deployment](#eks-deployment)
- [Configuration](#configuration)
- [Monitoring & Troubleshooting](#monitoring--troubleshooting)
- [Cost Management](#cost-management)

---

## Overview

GraphRAG is a scalable knowledge graph construction pipeline that:

1. **Ingests** documents (PDF, text) and chunks them for processing
2. **Extracts** concepts and entities using NLP + LLM (Stanford CoreNLP + Ollama)
3. **Discovers** relationships between concepts using LLM-based scoring
4. **Projects** the knowledge graph to Neo4j for querying
5. **Exposes** a REST API for natural language queries over the graph

**Key Technologies:**
- **Apache Flink 1.20.0** - Distributed stream processing
- **Neo4j 5.15.0** - Graph database
- **Ollama (Llama3)** - Local LLM for concept/relation extraction
- **Scala 2.12.18** - Primary language
- **Akka HTTP** - REST API framework
- **AWS EKS** - Kubernetes deployment
- **Amazon S3** - Data storage

---

## Architecture

### System Components

```
┌─────────────────────────────────────────────────────────────────┐
│                         INGESTION LAYER                         │
├─────────────────────────────────────────────────────────────────┤
│  Documents (PDF/JSONL) → Chunking → Text Normalization          │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                      EXTRACTION LAYER (Flink)                   │
├─────────────────────────────────────────────────────────────────┤
│  ┌─────────────────┐   ┌──────────────────┐                    │
│  │ Heuristic NER   │ + │ LLM Enhancement  │ → Concepts          │
│  │ (Stanford NLP)  │   │ (Ollama/Llama3)  │                    │
│  └─────────────────┘   └──────────────────┘                    │
│                              ↓                                   │
│  ┌──────────────────────────────────────────┐                  │
│  │   Co-occurrence Detection                 │                  │
│  │   (Concept pairs in same chunk)          │                  │
│  └──────────────────────────────────────────┘                  │
│                              ↓                                   │
│  ┌──────────────────────────────────────────┐                  │
│  │   Relation Scoring (Async LLM)           │                  │
│  │   (Predicate + Confidence + Evidence)    │                  │
│  └──────────────────────────────────────────┘                  │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                    GRAPH PROJECTION LAYER                       │
├─────────────────────────────────────────────────────────────────┤
│  GraphNode (Chunk, Concept) + GraphEdge (MENTIONS, CO_OCCURES RELATES_TO) │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                       STORAGE LAYER                             │
├─────────────────────────────────────────────────────────────────┤
│                         Neo4j Graph DB                          │
│  Nodes: Chunk, Concept                                          │
│  Edges: MENTIONS, CO_OCCURS, RELATES_TO                         │
└─────────────────────────────────────────────────────────────────┘
                              ↓
┌─────────────────────────────────────────────────────────────────┐
│                        QUERY LAYER (API)                        │
├─────────────────────────────────────────────────────────────────┤
│  REST API (Akka HTTP)                                           │
│  - POST /v1/query           - Natural language queries          │
│  - GET  /v1/graph/explore   - Graph visualization               │
│  - GET  /v1/evidence/:id    - Evidence retrieval                │
│  - GET  /v1/explain/trace   - Query execution trace
  - GET  /v1/job/status       - status of job
└─────────────────────────────────────────────────────────────────┘
```

### Data Flow

```
Input: chunks.jsonl
↓
[Chunk] { chunkId, docId, text, sourceUri }
↓
[ConceptExtraction] → [Mentions] { chunkId, Concept }
↓
[Co-occurrence] → [ConceptPair] { concept1, concept2, chunkIds }
↓
[RelationScoring] → [Relation] { source, target, predicate, confidence, evidence }
↓
[GraphProjection] → [GraphNode] + [GraphEdge]
↓
[Neo4j] → Knowledge Graph
↓
[API] → Query Results
```

---

## Features

### Flink Pipeline Features
- ✅ **Asynchronous LLM integration** - Non-blocking Ollama calls
- ✅ **Hybrid concept extraction** - Stanford NLP + LLM refinement
- ✅ **Distributed processing** - Scales to millions of documents
- ✅ **Lambda-free Flink** - Explicit KeySelectors for serialization

### API Features
- ✅ **Natural language queries** - "What is MacOS X based upon?"
- ✅ **Graph exploration** - Multi-hop neighbor traversal
- ✅ **Evidence retrieval** - Source text for claims
- ✅ **Execution tracing** - Query plan visualization

### Graph Schema
```cypher
// Nodes
(Chunk {chunkId, text, sourceUri, hash})
(Concept {conceptId, lemma, surface, origin})

// Edges
(Chunk)-[:MENTIONS]->(Concept)
(Concept)-[:CO_OCCURS {count}]->(Concept)
(Concept)-[:RELATES_TO {predicate, confidence, evidence}]->(Concept)
```

---

## Prerequisites

### Software Requirements

| Tool | Version | Purpose |
|------|---------|---------|
| Java | 17 | JVM runtime |
| Scala | 2.12.18 | Primary language |
| SBT | 1.9.7+ | Build tool |
| Docker | 20+ | Containerization |
| kubectl | 1.28+ | Kubernetes CLI |
| eksctl | 0.150+ | EKS cluster management |
| Helm | 3.12+ | Kubernetes package manager |
| AWS CLI | 2.13+ | AWS management |

### Services

| Service | Purpose | Setup |
|---------|---------|-------|
| **Neo4j Aura** | Graph database | [Create free instance](https://neo4j.com/cloud/aura/) |
| **Ollama** | LLM runtime | `brew install ollama` (Mac) or Docker |
| **AWS Account** | EKS deployment | [Sign up](https://aws.amazon.com/free/) |

### Installation

#### macOS
```bash
# Install Homebrew
/bin/bash -c "$(curl -fsSL https://raw.githubusercontent.com/Homebrew/install/HEAD/install.sh)"

# Install dependencies
brew install openjdk@17 sbt docker kubectl eksctl helm awscli ollama

# Install Scala (via Coursier)
brew install coursier/formulas/coursier
cs setup

# Verify installations
java -version        # Should show Java 17
scala -version       # Should show Scala 2.12.18
sbt --version        # Should show SBT 1.9.7+
docker --version
kubectl version --client
eksctl version
helm version
aws --version
```

#### Linux (Ubuntu/Debian)
```bash
# Java 17
sudo apt update
sudo apt install -y openjdk-17-jdk

# SBT
echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | sudo tee /etc/apt/sources.list.d/sbt.list
curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x99E82A75642AC823" | sudo apt-key add
sudo apt update
sudo apt install -y sbt

# Docker
curl -fsSL https://get.docker.com -o get-docker.sh
sudo sh get-docker.sh

# kubectl
curl -LO "https://dl.k8s.io/release/$(curl -L -s https://dl.k8s.io/release/stable.txt)/bin/linux/amd64/kubectl"
sudo install -o root -g root -m 0755 kubectl /usr/local/bin/kubectl

# eksctl
curl --silent --location "https://github.com/weaveworks/eksctl/releases/latest/download/eksctl_$(uname -s)_amd64.tar.gz" | tar xz -C /tmp
sudo mv /tmp/eksctl /usr/local/bin

# Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# AWS CLI
curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip" -o "awscliv2.zip"
unzip awscliv2.zip
sudo ./aws/install

# Ollama
curl -fsSL https://ollama.com/install.sh | sh
```

### Neo4j Setup

1. **Create Neo4j Aura Instance:**
   - Go to https://neo4j.com/cloud/aura/
   - Click "Start Free"
   - Create a new instance (Free tier available)
   - **Save credentials:** Connection URI, username, password

2. **Test Connection:**
```bash
# Install Neo4j driver (for testing)
pip install neo4j

# Test connection
python3 << EOF
from neo4j import GraphDatabase

driver = GraphDatabase.driver(
    "neo4j+s://your-instance.databases.neo4j.io",
    auth=("neo4j", "your-password")
)

with driver.session() as session:
    result = session.run("RETURN 1 AS num")
    print(result.single()["num"])  # Should print: 1

driver.close()
EOF
```

### Ollama Setup

```bash
# Start Ollama service
ollama serve

# Pull Llama3 model (in another terminal)
ollama pull llama3

# Test Ollama
curl http://localhost:11434/api/generate -d '{
  "model": "llama3",
  "prompt": "What is machine learning?",
  "stream": false
}'
```

### AWS Setup

```bash
# Configure AWS credentials
aws configure
# Enter: Access Key ID, Secret Access Key, Region (us-east-1), Output format (json)

# Verify AWS access
aws sts get-caller-identity

# Create S3 bucket (optional - for storing data)
aws s3 mb s3://graphrag-data-$(date +%s) --region us-east-1
```

---

## Local Development

### 1. Clone Repository

```bash
git clone <your-repo-url>
cd graphRag
```

### 2. Project Setup

```bash
# Reload SBT project (IntelliJ)
# Right-click build.sbt → "Reload SBT Project"

# Or compile from terminal
sbt compile
```

### 3. Environment Variables

Create `.env` file:

```bash
# Neo4j Configuration
export NEO4J_URI="neo4j+s://7f5eb4a9.databases.neo4j.io"
export NEO4J_USERNAME="neo4j"
export NEO4J_PASSWORD="your-neo4j-password"

# Ollama Configuration
export OLLAMA_URL="http://localhost:11434"
export OLLAMA_MODEL="llama3"

# Pipeline Configuration
export USE_LLM="true"
export WRITE_TO_NEO4J="true"

# API Configuration
export PORT="8080"
```

Load environment:
```bash
source .env
```

### 4. Prepare Test Data

Create `chunks.jsonl`:

```jsonl
{"chunkId":"c1","docId":"paper123","offset":0,"chunk":"MacOS X is based on the BSD operating system. BSD has several variants including MacOS X, SunOS, and NetBSD.","chunkHash":"abc123","language":"en","title":"Mining Evolution Data of a Product Family"}
{"chunkId":"c2","docId":"paper123","offset":150,"chunk":"Software artifacts with strong change dependencies often have architectural dependencies as research by Briand et al. has shown.","chunkHash":"def456","language":"en","title":"Mining Evolution Data of a Product Family"}
{"chunkId":"c3","docId":"paper456","offset":0,"chunk":"The PfEvo approach addresses the problem of handling multiple asynchronously maintained version control systems to identify change dependencies.","chunkHash":"ghi789","language":"en","title":"Product Family Evolution"}
```

---

## Building the Project

### Build All Modules

```bash
# Compile all modules
sbt compile

# Run tests
sbt test

# Build both JARs
sbt clean assembly
```

### Check Build Artifacts

```bash
# List all built JARs
find . -name "*.jar" -path "*/target/scala-2.12/*"
```

---

## Running Locally

### Option 1: Run Flink Job Locally

NOTE-put vm options --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.lang.reflect=ALL-UNNAMED to edit config vm

```bash
# Set environment variables
source .env
# Run from flink cluster running locally
 flink run \                                                                            
  -c com.graphrag.GraphRAGJob \
  -p 4 \
  /Users/moudgil/graphrag-pipeline.jar \
 file:///$(pwd)/chunks.jsonl

```

**Expected Output:**
```
==================================================
GraphRAG Pipeline Starting
==================================================
Ollama URL: http://localhost:11434
Ollama Model: llama3
Neo4j URI: neo4j+s://7f5eb4a9.databases.neo4j.io
Use LLM: true
Write to Neo4j: true
==================================================

STEP 1: Ingesting chunks...
STEP 2: Normalizing chunks...
STEP 3: Extracting concepts (async)...
STEP 4: Finding co-occurrences...
STEP 5: Scoring relations...
STEP 6: Projecting to graph structure...
STEP 7: Writing to Neo4j...
✓ Sinks attached - writing to Neo4j

Executing pipeline...
```

### Option 2: Run API Service Locally

```bash
# Set environment variables
source .env

# Run from SBT
sbt "project apiService" run

# Or run JAR directly
java -cp graphrag-pipeline.jar com.graphrag.api.GraphRAGApiServer```
**Expected Output:**
```
Server online at http://0.0.0.0:8080/
Press RETURN to stop...
```

### Test API Endpoints

```bash
# Health check
curl http://localhost:8080/health
# Output: OK

# Query endpoint (hardcoded demo)
curl -X POST http://localhost:8080/v1/query \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What software artifacts is MacOS based upon?",
    "maxResults": 10,
    "includeEvidence": true
  }' | jq

# Expected output:
{
  "mode": "sync",
  "answer": "MacOS X is based upon the BSD operating system...",
  "groups": [...],
  "evidenceAvailable": true,
  "executionTimeMs": 441
}

# Execution trace
curl http://localhost:8080/v1/explain/trace/req-a3f2b1c4 | jq
```

### Verify Neo4j Data

```bash
# Install Neo4j Browser or use Aura console
# Run Cypher queries:

// Count nodes
MATCH (n) RETURN labels(n) AS label, count(*) AS count

// Sample concepts
MATCH (c:Concept) RETURN c LIMIT 10

// Sample relations
MATCH (c1:Concept)-[r:RELATES_TO]->(c2:Concept)
RETURN c1.surface, r.predicate, c2.surface, r.confidence
LIMIT 10

// Find MacOS X relationships
MATCH (c:Concept {lemma: "MacOS X"})-[r:RELATES_TO]-(related:Concept)
RETURN c.surface, type(r), r.predicate, related.surface, r.evidence
```

---

## API Documentation

### Base URL
```
http://localhost:8080
```

### Endpoints

#### 1. Health Check
```http
GET /health
```

**Response:**
```
OK
```

---

#### 2. Natural Language Query
```http
POST /v1/query
Content-Type: application/json

{
  "query": "What software artifacts is MacOS based upon?",
  "includeEvidence": true
}
```

**Response:**
```json
{
  "mode": "sync",
  "requestId": "req-12345",
  "answer": "MacOS X is based upon the BSD operating system. BSD has several variants and derivatives including MacOS X, SunOS, and NetBSD...",
  "groups": [
    {
      "items": [
        {
          "paperId": "1083142.1083145",
          "title": "Mining Evolution Data of a Product Family",
          "concepts": ["software artifact", "MacOS X", "BSD", "SunOS", "NetBSD"],
          "citations": ["evid:chunk-1958", "evid:chunk-1959"]
        }
      ]
    }
  ],
  "evidenceAvailable": true,
  "executionTimeMs": 441
}
```

---

#### 3. Graph Exploration
```http
GET /v1/graph/concept/{conceptId}/neighbors
```


**Example:**
```bash
curl "http://localhost:8080/v1/graph/concept/software_artifact/neighbors
```

**Response:**
```json
{
    "nodes": [
        {
            "id": "software artifact"
        }
    ],
    "edges": [
        {
            "from": "software artifact",
            "to": "BSD"
        },
        {
            "from": "software artifact",
            "to": "Macos"
        }
    ]
}
```

---

#### 4. Evidence Retrieval
```http
GET /v1/evidence/{evidenceId}
```

**Example:**
```bash
curl http://localhost:8080/v1/evidence/chunk-1958
```

**Response:**
```json
{
  "evidenceId": "chunk-1958",
  "paperId": "1083142.1083145",
  "text": "Representative of such a family of related products is the BSD operating system with its variants and derivatives such as MacOS X, SunOS, or NetBSD.",
  "docRef": {
    "title": "Mining Evolution Data of a Product Family"
  }
}
```

---

#### 5. Query Execution Trace
```http
GET /v1/explain/trace/{requestId}
```

**Example:**
```bash
curl http://localhost:8080/v1/explain/trace/req-12345 | jq
```

**Response:**
```json
{
  "requestId": "req-12345",
  "query": "What software artifacts is MacOS based upon?",
  "timestamp": 1701436800000,
  "steps": [
    {
      "stepName": "extractConcepts",
      "description": "Extract concepts from natural language query using LLM",
      "durationMs": 342,
      "details": {
        "cypher": null,
        "detail": "Ollama LLM extracted concepts: ['MacOS X', 'software artifact', 'based upon']"
      }
    },
    {
      "stepName": "findMatchingConcepts",
      "description": "Find concepts in Neo4j graph matching extracted terms",
      "durationMs": 23,
      "details": {
        "cypher": "MATCH (c:Concept) WHERE c.lemma IN ['macos x', 'software artifact'] RETURN c",
        "detail": "Found 2 matching concepts"
      }
    },
    {
      "stepName": "getRelations",
      "description": "Retrieve relationships between matched concepts",
      "durationMs": 18,
      "details": {
        "cypher": "MATCH (c1:Concept)-[r:RELATES_TO]->(c2:Concept) WHERE ... RETURN c1, r, c2",
        "detail": "Found 1 relation: software_artifact -[part_of]-> MacOS X"
      }
    }
  ],
  "totalTimeMs": 441
}
```

---

#### 6. Flink Job Status
```http
GET /v1/job/status/{jobId}
```

**Example:**
```bash
curl http://localhost:8080/v1/job/status/abc123def456
```

**Response:**
```json
{
  "jobId": "abc123def456",
  "status": "RUNNING"
}
```

---

## EKS Deployment

### Prerequisites

```bash
# Verify tools
aws --version
eksctl version
kubectl version --client
helm version
docker --version

# Configure AWS credentials
aws configure

# Verify AWS access
aws sts get-caller-identity
```

---

### Phase 1: Build & Push Docker Images

#### Step 1: Create Dockerfile for Flink Job

**`deploy/Dockerfile.flink`:**
```dockerfile
FROM flink:1.20.0-scala_2.12-java17

# Copy Flink job JAR
COPY flink-job/target/scala-2.12/graphrag-pipeline.jar /opt/flink/usrlib/graphrag-job.jar

# Flink will load this JAR automatically
WORKDIR /opt/flink
```

#### Step 2: Build Flink JAR

```bash
cd ~/graphRag

# Build JAR
sbt "project flinkJob" clean assembly

# Verify JAR exists
ls -lh flink-job/target/scala-2.12/graphrag-pipeline.jar
```

#### Step 3: Create ECR Repository

```bash
# Create repository
aws ecr create-repository \
  --repository-name graphrag-flink \
  --region us-east-1

# Output will show repository URI:
# 147997142493.dkr.ecr.us-east-1.amazonaws.com/graphrag-flink
```

#### Step 4: Login to ECR

```bash
aws ecr get-login-password --region us-east-1 | \
  docker login --username AWS --password-stdin \
  147997142493.dkr.ecr.us-east-1.amazonaws.com
```

#### Step 5: Build Docker Image

```bash
docker build -f deploy/Dockerfile.flink -t graphrag-flink:latest .
```

#### Step 6: Tag & Push Image

```bash
# Tag image
docker tag graphrag-flink:latest \
  147997142493.dkr.ecr.us-east-1.amazonaws.com/graphrag-flink:latest

# Push to ECR
docker push 147997142493.dkr.ecr.us-east-1.amazonaws.com/graphrag-flink:latest
```

---

### Phase 2: Setup EKS Cluster

#### Step 7: Create EKS Cluster

```bash
eksctl create cluster \
  --name graphrag-cluster \
  --region us-east-1 \
  --nodegroup-name workers \
  --node-type t3.xlarge \
  --nodes 3 \
  --nodes-min 2 \
  --nodes-max 4 \
  --managed

# This takes ~15 minutes
# Cluster specs:
# - 3x t3.xlarge nodes (4 vCPU, 16GB RAM each)
# - Auto-scaling: 2-4 nodes
# - Managed node group (auto-updates)
```

#### Step 8: Enable OIDC Provider

```bash
eksctl utils associate-iam-oidc-provider \
  --region=us-east-1 \
  --cluster=graphrag-cluster \
  --approve
```

#### Step 9: Create Service Account with S3 Access

```bash
eksctl create iamserviceaccount \
  --name flink \
  --namespace default \
  --cluster graphrag-cluster \
  --region us-east-1 \
  --attach-policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess \
  --approve \
  --override-existing-serviceaccounts
```

#### Step 10: Verify Cluster

```bash
# Get cluster info
kubectl cluster-info

# List nodes
kubectl get nodes

# Expected output:
# NAME                             STATUS   ROLES    AGE   VERSION
# ip-192-168-x-x.ec2.internal      Ready    <none>   5m    v1.28.x
# ip-192-168-y-y.ec2.internal      Ready    <none>   5m    v1.28.x
# ip-192-168-z-z.ec2.internal      Ready    <none>   5m    v1.28.x
```

---

### Phase 3: Install Flink Operator

#### Step 11: Add Flink Operator Helm Repo

```bash
helm repo add flink-operator-repo \
  https://downloads.apache.org/flink/flink-kubernetes-operator-1.6.0/

helm repo update
```

#### Step 12: Create Namespace

```bash
kubectl create namespace flink-operator
```

#### Step 13: Install Flink Operator

```bash
helm install flink-kubernetes-operator \
  flink-operator-repo/flink-kubernetes-operator \
  --namespace flink-operator
```

#### Step 14: Verify Operator

```bash
kubectl get pods -n flink-operator

# Expected output:
# NAME                                           READY   STATUS    RESTARTS   AGE
# flink-kubernetes-operator-xxx-xxx              1/1     Running   0          2m
```

---

### Phase 4: Setup S3 Storage

#### Step 15: Create S3 Bucket

```bash
# Create bucket for checkpoints, savepoints, and data
aws s3 mb s3://graphrag-flink-data --region us-east-1

# Create directory structure
aws s3api put-object --bucket graphrag-flink-data --key flink/checkpoints/
aws s3api put-object --bucket graphrag-flink-data --key flink/savepoints/
aws s3api put-object --bucket graphrag-flink-data --key data/
```

#### Step 16: Upload Test Data

```bash
# Upload chunks.jsonl
aws s3 cp chunks.jsonl s3://graphrag-flink-data/data/chunks.jsonl

# Verify upload
aws s3 ls s3://graphrag-flink-data/data/
```

---

### Phase 5: Deploy Ollama


#### Step 18: Deploy Ollama

```bash
kubectl apply -f deploy/ollama-daemonset.yaml

# Wait for pods to be ready (takes ~5 minutes to pull model)
kubectl get pods -l app=ollama -w
```

#### Step 19: Test Ollama

```bash
# Run test pod
kubectl run test-ollama --rm -it --image=curlimages/curl --restart=Never -- \
  curl -s http://ollama.default.svc.cluster.local:11434/api/generate \
  -d '{"model":"llama3","prompt":"test","stream":false}' | head -20

# Should return JSON response
```

---

### Phase 6: Create Secrets & ConfigMaps

#### Step 20: Create Neo4j Credentials Secret

```bash
kubectl create secret generic neo4j-credentials \
  --from-literal=NEO4J_URI='neo4j+s://7f5eb4a9.databases.neo4j.io' \
  --from-literal=NEO4J_USERNAME='neo4j' \
  --from-literal=NEO4J_PASSWORD='your-actual-password'
```

#### Step 21: Create GraphRAG ConfigMap

```bash
kubectl create configmap graphrag-config \
  --from-literal=OLLAMA_URL='http://ollama.default.svc.cluster.local:11434' \
  --from-literal=OLLAMA_MODEL='llama3' \
  --from-literal=USE_LLM='true' \
  --from-literal=WRITE_TO_NEO4J='true'
```

#### Step 22: Verify Secrets & ConfigMaps

```bash
# List secrets
kubectl get secrets

# List configmaps
kubectl get configmaps

# View configmap
kubectl describe configmap graphrag-config
```

---

### Phase 7: Deploy Flink Job

#### Step 24: Deploy Flink Job

```bash
kubectl apply -f deployments/job-graph-rag.yaml
```

#### Step 25: Monitor Deployment

```bash
# Watch FlinkDeployment status
kubectl get flinkdeployments -w

# Check pods
kubectl get pods -l app=graphrag-job

# View JobManager logs
kubectl logs -l component=jobmanager -f

# View TaskManager logs
kubectl logs -l component=taskmanager -f
```

---

### Phase 8: Access Flink UI

#### Step 26: Port Forward Flink UI

```bash
kubectl port-forward svc/graphrag-job-rest 8081:8081
```

#### Step 27: Open Flink Dashboard

```bash
# Open browser
open http://localhost:8081

# Or use curl
curl http://localhost:8081/jobs
```

**Flink UI Features:**
- Job overview
- Task metrics
- Checkpoints
- Logs
- Exceptions

---

## Configuration

### Environment Variables

| Variable | Description | Default | Required |
|----------|-------------|---------|----------|
| `NEO4J_URI` | Neo4j connection string | - | ✅ |
| `NEO4J_USERNAME` | Neo4j username | `neo4j` | ✅ |
| `NEO4J_PASSWORD` | Neo4j password | - | ✅ |
| `OLLAMA_URL` | Ollama API endpoint | `http://localhost:11434` | ✅ |
| `OLLAMA_MODEL` | LLM model name | `llama3` | ✅ |
| `USE_LLM` | Enable LLM extraction | `true` | ❌ |
| `WRITE_TO_NEO4J` | Write to Neo4j | `true` | ❌ |
| `PORT` | API server port | `8080` | ❌ |

### Flink Configuration

**Key Parameters:**

| Parameter | Value | Purpose |
|-----------|-------|---------|
| `taskmanager.numberOfTaskSlots` | `4` | Tasks per TaskManager |
| `parallelism` | `12` | Total pipeline parallelism |
| `execution.checkpointing.interval` | `5min` | Checkpoint frequency |
| `state.backend` | `filesystem` | State storage backend |

**Memory Settings:**

| Component | Memory | CPU |
|-----------|--------|-----|
| JobManager | 4GB | 1 core |
| TaskManager | 8GB | 4 cores |
| Ollama | 4-8GB | 2-4 cores |

### Neo4j Schema

**Node Labels:**
- `Chunk` - Text chunks from documents
- `Concept` - Extracted concepts/entities

**Relationship Types:**
- `MENTIONS` - Chunk mentions concept
- `CO_OCCURS` - Concepts co-occur in same chunk
- `RELATES_TO` - Semantic relationship between concepts

**Indexes:**
```cypher
// Create indexes for performance
CREATE INDEX concept_id FOR (c:Concept) ON (c.conceptId);
CREATE INDEX chunk_id FOR (ch:Chunk) ON (ch.chunkId);
```

---

## Monitoring & Troubleshooting

### Monitoring Commands

```bash
# Check Flink job status
kubectl get flinkdeployments

# View all pods
kubectl get pods -l app=graphrag-job

# View JobManager logs
kubectl logs -l component=jobmanager --tail=100 -f

# View TaskManager logs
kubectl logs -l component=taskmanager --tail=100 -f

# Check Ollama pods
kubectl get pods -l app=ollama

# View Ollama logs
kubectl logs -l app=ollama --tail=50

# Check S3 checkpoints
aws s3 ls s3://graphrag-flink-data/flink/checkpoints/

# Check cluster events
kubectl get events --sort-by='.lastTimestamp' | head -20

# Describe Flink deployment
kubectl describe flinkdeployment graphrag-job

# Check resource usage
kubectl top nodes
kubectl top pods
```

### Common Issues

#### 1. Pods Stuck in Pending

**Symptoms:**
```bash
kubectl get pods
# NAME                           READY   STATUS    RESTARTS   AGE
# graphrag-job-taskmanager-xxx   0/1     Pending   0          5m
```

**Diagnosis:**
```bash
kubectl describe pod graphrag-job-taskmanager-xxx
# Look for: "Insufficient memory" or "Insufficient cpu"
```

**Solution:**
```bash
# Scale down if needed
kubectl scale flinkdeployment graphrag-job --replicas=2

# Or add more nodes
eksctl scale nodegroup --cluster=graphrag-cluster --name=workers --nodes=4
```

---

#### 2. Ollama Not Responding

**Symptoms:**
```bash
# JobManager logs show:
# ERROR - Failed to connect to Ollama: Connection refused
```

**Diagnosis:**
```bash
# Check Ollama status
kubectl get pods -l app=ollama

# Test Ollama
kubectl run test-ollama --rm -it --image=curlimages/curl --restart=Never -- \
  curl http://ollama.default.svc.cluster.local:11434/
```

**Solution:**
```bash
# Restart Ollama
kubectl rollout restart daemonset ollama

# Wait for ready
kubectl get pods -l app=ollama -w

# Check logs
kubectl logs -l app=ollama --tail=50
```

---

#### 3. Flink Job Failing

**Symptoms:**
```bash
kubectl get flinkdeployments
# NAME           JOB STATUS   LIFECYCLE STATE
# graphrag-job   FAILING      DEPLOYED
```

**Diagnosis:**
```bash
# Check JobManager logs
kubectl logs -l component=jobmanager | grep ERROR

# Check TaskManager logs
kubectl logs -l component=taskmanager | grep ERROR

# Common errors:
# - "ClassNotFoundException" → Missing dependencies
# - "S3Exception" → IAM permissions issue
# - "Neo4jException" → Wrong credentials
# - "TimeoutException" → Ollama overloaded
```

**Solution:**
```bash
# Fix and redeploy
kubectl delete flinkdeployment graphrag-job
kubectl apply -f deployments/job-graph-rag.yaml

# Or trigger savepoint and restart
kubectl patch flinkdeployment graphrag-job -p '{"spec":{"job":{"state":"suspended"}}}'
kubectl patch flinkdeployment graphrag-job -p '{"spec":{"job":{"state":"running"}}}'
```

---

#### 4. Neo4j Connection Issues

**Symptoms:**
```bash
# Logs show:
# Neo4jException: Unable to connect to neo4j+s://...
```

**Diagnosis:**
```bash
# Test Neo4j connectivity from pod
kubectl run test-neo4j --rm -it --image=neo4j:5.15.0 --restart=Never -- \
  cypher-shell -a neo4j+s://your-instance.databases.neo4j.io \
  -u neo4j -p your-password "RETURN 1 AS num"

# Check secret
kubectl get secret neo4j-credentials -o yaml
```

**Solution:**
```bash
# Update secret
kubectl delete secret neo4j-credentials
kubectl create secret generic neo4j-credentials \
  --from-literal=NEO4J_URI='neo4j+s://7f5eb4a9.databases.neo4j.io' \
  --from-literal=NEO4J_USERNAME='neo4j' \
  --from-literal=NEO4J_PASSWORD='correct-password'

# Restart job
kubectl delete pod -l component=jobmanager
```

---

#### 5. Out of Memory (OOM)

**Symptoms:**
```bash
kubectl get pods
# NAME                           READY   STATUS      RESTARTS   AGE
# graphrag-job-taskmanager-xxx   0/1     OOMKilled   3          10m
```

**Solution:**
```bash
# Increase TaskManager memory in job-graph-rag.yaml:
spec:
  taskManager:
    resource:
      memory: "12288m"  # Increase from 8192m
      cpu: 4

# Redeploy
kubectl apply -f deployments/job-graph-rag.yaml
```

---

#### 6. S3 Access Denied

**Symptoms:**
```bash
# Logs show:
# AmazonS3Exception: Access Denied (Service: Amazon S3; Status Code: 403)
```

**Solution:**
```bash
# Verify IAM role is attached
kubectl describe serviceaccount flink

# Should show:
# Annotations:
#   eks.amazonaws.com/role-arn: arn:aws:iam::147997142493:role/eksctl-...

# If missing, recreate service account:
eksctl delete iamserviceaccount --name flink --cluster graphrag-cluster
eksctl create iamserviceaccount \
  --name flink \
  --namespace default \
  --cluster graphrag-cluster \
  --region us-east-1 \
  --attach-policy-arn arn:aws:iam::aws:policy/AmazonS3FullAccess \
  --approve
```

---

### Performance Tuning

#### Flink Parallelism

```yaml
# Increase parallelism for larger datasets
spec:
  job:
    parallelism: 24  # Increase from 12
  
  taskManager:
    replicas: 6      # More TaskManagers
```

#### Checkpointing

```yaml
# Reduce checkpoint frequency for faster processing
spec:
  flinkConfiguration:
    execution.checkpointing.interval: "10min"  # Increase from 5min
```

#### Memory

```yaml
# Increase memory for large datasets
spec:
  taskManager:
    resource:
      memory: "16384m"  # 16GB
```

---

## Cost Management

### AWS Cost Breakdown

**Monthly Costs (Approximate):**

| Service | Resource | Cost |
|---------|----------|------|
| **EKS** | Control plane | $72 |
| **EC2** | 3x t3.xlarge (24/7) | ~$450 |
| **S3** | 100GB storage | $2.30 |
| **Data Transfer** | 100GB egress | $9 |
| **Neo4j Aura** | Free tier | $0 |
| **Total** | | **~$535/month** |

### Cost Optimization

#### 1. Use Spot Instances

```bash
eksctl create nodegroup \
  --cluster graphrag-cluster \
  --name workers-spot \
  --node-type t3.xlarge \
  --nodes 3 \
  --spot
```

**Savings:** ~70% on EC2 costs

---

#### 2. Scale Down When Not in Use

```bash
# Stop Flink job
kubectl patch flinkdeployment graphrag-job -p '{"spec":{"job":{"state":"suspended"}}}'

# Scale nodes to 0
eksctl scale nodegroup --cluster=graphrag-cluster --name=workers --nodes=0

# To resume:
eksctl scale nodegroup --cluster=graphrag-cluster --name=workers --nodes=3
kubectl patch flinkdeployment graphrag-job -p '{"spec":{"job":{"state":"running"}}}'
```

---

---

#### 3. Delete Cluster When Done

```bash
# Delete entire cluster
eksctl delete cluster --name graphrag-cluster --region us-east-1

# This deletes:
# - EKS control plane
# - All EC2 nodes
# - Load balancers
# - Security groups

# Manually delete S3 bucket if needed:
aws s3 rb s3://graphrag-flink-data --force
```

---
