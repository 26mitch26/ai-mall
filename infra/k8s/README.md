# AI-Mall Kubernetes Deployment

## Prerequisites

- A running Kubernetes cluster (v1.19+)
- `kubectl` configured with cluster access
- Helm (optional, for infrastructure components)
- Docker images built and pushed to a container registry

## Quick Start

### 1. Create Namespace

```bash
kubectl apply -f namespace.yaml
```

### 2. Infrastructure

Set up infrastructure components first. For production, we recommend using Helm charts:

**MySQL + Redis (basic):**
```bash
kubectl apply -f infra-deployment.yaml
```

**Other infrastructure (recommended via Helm):**
```bash
# Elasticsearch
helm repo add elastic https://helm.elastic.co
helm install elasticsearch elastic/elasticsearch -n ai-mall

# Prometheus + Grafana
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm install prometheus prometheus-community/kube-prometheus-stack -n ai-mall

# Kafka (via Strimzi or Confluent)
helm repo add strimzi https://strimzi.io/charts/
helm install strimzi-kafka strimzi/strimzi-kafka-operator -n ai-mall

# MinIO
helm repo add minio https://charts.min.io/
helm install minio minio/minio -n ai-mall

# Neo4j
helm repo add neo4j https://helm.neo4j.com/neo4j
helm install neo4j neo4j/neo4j -n ai-mall

# Milvus
helm repo add milvus https://milvus-io.github.io/milvus-helm/
helm install milvus milvus/milvus -n ai-mall
```

### 3. Create Secrets

Create the required secrets before deploying application services:

```bash
kubectl create secret generic db-credentials \
  --namespace ai-mall \
  --from-literal=username='your-db-user' \
  --from-literal=password='your-db-password' \
  --from-literal=root-password='your-root-password' \
  --from-literal=jwt-secret='your-jwt-secret'
```

### 4. Deploy Application Services

Build and push Docker images first:

```bash
# Set your registry
export DOCKER_REGISTRY=your-registry.example.com

# Build and push images (example)
# docker build -t ${DOCKER_REGISTRY}/ai-gateway:latest -f ai-gateway/Dockerfile .
# docker push ${DOCKER_REGISTRY}/ai-gateway:latest
# Repeat for all services: mall-admin, mall-portal, mall-search, agent-customer, agent-ops, agent-test
```

Deploy all services:

```bash
kubectl apply -f .
```

Or deploy individually:

```bash
kubectl apply -f gateway-deployment.yaml
kubectl apply -f admin-deployment.yaml
kubectl apply -f portal-deployment.yaml
kubectl apply -f agents-deployment.yaml
kubectl apply -f ingress.yaml
```

### 5. Verify

```bash
kubectl get all -n ai-mall
kubectl get ingress -n ai-mall
```

## Architecture

```
                        ┌─────────────┐
                        │   Ingress   │
                        │  (nginx)    │
                        └──────┬──────┘
                               │
                        ┌──────▼──────┐
                        │  ai-gateway │
                        │  (Gateway)  │
                        └──┬───┬───┬──┘
                           │   │   │
              ┌────────────┘   │   └────────────┐
              │                │                │
        ┌─────▼─────┐   ┌─────▼─────┐   ┌──────▼─────┐
        │ mall-admin │   │mall-portal│   │mall-search │
        │  (Admin)   │   │ (Portal)  │   │ (Search)   │
        └─────┬──────┘   └─────┬─────┘   └──────┬─────┘
              │                │                │
        ┌─────▼─────┐   ┌─────▼─────┐   ┌──────▼─────┐
        │agent-cust │   │ agent-ops │   │ agent-test │
        │ (Service) │   │  (Ops)    │   │  (Test)    │
        └───────────┘   └───────────┘   └────────────┘

Infrastructure: MySQL, Redis, Kafka, Neo4j, Milvus, ES, MinIO, Prometheus, Grafana
```

## Service Endpoints

| Service          | Internal DNS             | Port | Ingress Path         |
|------------------|--------------------------|------|----------------------|
| ai-gateway       | ai-gateway:8080          | 8080 | (entry point)        |
| mall-admin       | mall-admin:8080          | 8080 | /admin/*             |
| mall-portal      | mall-portal:8081         | 8081 | /api/*               |
| mall-search      | mall-search:8082         | 8082 | /search/*            |
| agent-customer   | agent-customer:8082      | 8082 | /agent/customer/*    |
| agent-ops        | agent-ops:8083           | 8083 | /agent/ops/*         |
| agent-test       | agent-test:8085          | 8085 | /agent/test/*        |
| MySQL            | mysql:3306               | 3306 | —                    |
| Redis            | redis:6379               | 6379 | —                    |

## Image Placeholders

All deployment files use `${DOCKER_REGISTRY}/<service>:latest` as image placeholders. Replace these with your actual container registry before deploying, or set the environment variable and use a templating tool like `envsubst`.