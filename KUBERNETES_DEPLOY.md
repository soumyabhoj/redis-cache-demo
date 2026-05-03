# Kubernetes Deployment Guide — Redis Cache Demo

Deploy the Spring Boot Redis Cache application to Kubernetes using GitHub Actions CI/CD.

---

## Table of Contents

1. [Architecture Overview](#architecture-overview)
2. [Prerequisites](#prerequisites)
3. [Project Files Created](#project-files-created)
4. [Step-by-Step Setup](#step-by-step-setup)
   - [Step 1 — Push to GitHub](#step-1--push-to-github)
   - [Step 2 — Set GitHub Secrets](#step-2--set-github-secrets)
   - [Step 3 — Prepare a Kubernetes Cluster](#step-3--prepare-a-kubernetes-cluster)
   - [Step 4 — Generate KUBE_CONFIG secret](#step-4--generate-kube_config-secret)
   - [Step 5 — Trigger the Pipeline](#step-5--trigger-the-pipeline)
   - [Step 6 — Verify Deployment](#step-6--verify-deployment)
5. [GitHub Actions Pipeline Explained](#github-actions-pipeline-explained)
6. [Kubernetes Manifests Explained](#kubernetes-manifests-explained)
7. [Environment Variable Injection](#environment-variable-injection)
8. [Zero-Downtime Rolling Update](#zero-downtime-rolling-update)
9. [Auto-Scaling (HPA)](#auto-scaling-hpa)
10. [Rollback Strategy](#rollback-strategy)
11. [Local Testing with Minikube](#local-testing-with-minikube)
12. [Troubleshooting](#troubleshooting)

---

## Architecture Overview

```
Developer → git push → GitHub
                          │
              ┌───────────▼────────────────────────────────┐
              │           GitHub Actions                    │
              │                                            │
              │  Job 1: test                               │
              │    └─ mvn test                             │
              │                                            │
              │  Job 2: build-and-push                     │
              │    ├─ mvn package                          │
              │    ├─ docker build                         │
              │    └─ docker push → ghcr.io                │
              │                                            │
              │  Job 3: deploy                             │
              │    ├─ kubectl apply (manifests)            │
              │    ├─ kubectl set image (new SHA tag)      │
              │    ├─ smoke test                           │
              │    └─ rollback on failure                  │
              └──────────────────────┬─────────────────────┘
                                     │  kubectl
                                     ▼
              ┌──────────────────────────────────────────────┐
              │           Kubernetes Cluster                  │
              │   Namespace: redis-cache-demo                 │
              │                                              │
              │  ┌──────────────┐    ┌───────────────────┐  │
              │  │ Redis Pod    │    │ App Pod 1         │  │
              │  │ (redis:7.2)  │◀───│ redis-cache-demo  │  │
              │  └──────────────┘    └───────────────────┘  │
              │   ClusterIP Svc       ┌───────────────────┐  │
              │   port 6379           │ App Pod 2         │  │
              │                      │ redis-cache-demo  │  │
              │                      └───────────────────┘  │
              │                       LoadBalancer Svc       │
              │                       port 80 → 8080        │
              │                       HPA: 2–6 replicas     │
              └──────────────────────────────────────────────┘
                                     │
                              External Traffic
                              port 80 / Ingress
```

---

## Prerequisites

| Tool | Version | Purpose |
|---|---|---|
| Git | any | Source control |
| GitHub account | — | CI/CD + Container Registry |
| Docker | 24+ | Build images locally (optional) |
| kubectl | 1.29+ | Interact with cluster |
| Kubernetes cluster | 1.28+ | Where the app runs |
| Java 17 | 17+ | Build locally (optional) |

**Cluster options:**
- Local: [minikube](https://minikube.sigs.k8s.io) / [kind](https://kind.sigs.k8s.io)
- Cloud: AWS EKS / GKE / Azure AKS (all work identically after kubeconfig setup)

---

## Project Files Created

```
redis-cache-demo/
├── Dockerfile                              ← Multi-stage image build
├── .dockerignore                           ← Exclude unnecessary files
├── .github/
│   └── workflows/
│       └── deploy.yml                      ← CI/CD pipeline (3 jobs)
├── k8s/
│   ├── namespace.yaml                      ← Isolated namespace
│   ├── redis-secret.yaml                   ← Redis password (Secret)
│   ├── redis-deployment.yaml               ← Redis Pod + ClusterIP Service
│   ├── app-configmap.yaml                  ← Non-sensitive app config
│   ├── app-deployment.yaml                 ← App Pods + LoadBalancer Service
│   ├── app-hpa.yaml                        ← Auto-scaler (2–6 replicas)
│   └── ingress.yaml                        ← Optional: domain-based routing
└── src/main/resources/
    └── application.properties              ← Updated to read env vars
```

---

## Step-by-Step Setup

### Step 1 — Push to GitHub

```bash
cd redis-cache-demo

git init
git add .
git commit -m "Initial commit — Spring Boot Redis Cache Demo"

# Create a repo on GitHub named "redis-cache-demo", then:
git remote add origin https://github.com/YOUR_USERNAME/redis-cache-demo.git
git branch -M main
git push -u origin main
```

---

### Step 2 — Set GitHub Secrets

Go to your GitHub repository → **Settings → Secrets and variables → Actions → New repository secret**.

| Secret name | Value | How to get it |
|---|---|---|
| `KUBE_CONFIG` | base64-encoded kubeconfig | See Step 4 below |

> `GITHUB_TOKEN` is **automatically provided** by GitHub — you do not add it manually. It is used to push Docker images to `ghcr.io`.

---

### Step 3 — Prepare a Kubernetes Cluster

#### Option A — Minikube (local, free)

```bash
# Install minikube
brew install minikube        # macOS
# or download from https://minikube.sigs.k8s.io/docs/start/

# Start cluster
minikube start --cpus=2 --memory=4096

# Enable ingress addon
minikube addons enable ingress

# Enable metrics-server (needed for HPA)
minikube addons enable metrics-server
```

#### Option B — AWS EKS

```bash
eksctl create cluster \
  --name redis-cache-demo \
  --region us-east-1 \
  --nodegroup-name workers \
  --node-type t3.medium \
  --nodes 2
```

#### Option C — GKE

```bash
gcloud container clusters create redis-cache-demo \
  --zone us-central1-a \
  --num-nodes 2 \
  --machine-type e2-medium
```

#### Option D — Azure AKS

```bash
az aks create \
  --resource-group myRG \
  --name redis-cache-demo \
  --node-count 2 \
  --node-vm-size Standard_B2s \
  --generate-ssh-keys
```

---

### Step 4 — Generate KUBE_CONFIG secret

After your cluster is running, your kubeconfig is at `~/.kube/config`. Base64-encode it:

```bash
# macOS / Linux
cat ~/.kube/config | base64 | pbcopy       # copies to clipboard (macOS)
cat ~/.kube/config | base64                # print to terminal (Linux)

# Windows PowerShell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$HOME\.kube\config")) | Set-Clipboard
```

Paste the output as the value of the `KUBE_CONFIG` secret in GitHub.

> **Security note:** The kubeconfig gives full cluster access. Use a **service account** with limited RBAC in production instead of the admin kubeconfig.

#### Create a limited service account (recommended for production):

```bash
# Create service account
kubectl create serviceaccount github-deployer -n redis-cache-demo

# Bind to edit role (deploy, update, but not delete namespaces)
kubectl create rolebinding github-deployer-binding \
  --clusterrole=edit \
  --serviceaccount=redis-cache-demo:github-deployer \
  -n redis-cache-demo

# Get token (Kubernetes 1.24+)
kubectl create token github-deployer -n redis-cache-demo --duration=8760h
```

---

### Step 5 — Trigger the Pipeline

Every `git push` to `main` triggers the pipeline automatically.

```bash
# Make any change and push
git add .
git commit -m "Deploy to Kubernetes"
git push origin main
```

Watch the pipeline run at:
`https://github.com/YOUR_USERNAME/redis-cache-demo/actions`

**Pipeline stages:**

```
┌─────────────────┐     ┌──────────────────────┐     ┌──────────────────┐
│   test (~2 min) │────▶│  build-and-push       │────▶│  deploy (~3 min) │
│                 │     │  (~4 min)             │     │                  │
│ mvn test        │     │  mvn package          │     │ kubectl apply    │
│ upload report   │     │  docker build+push    │     │ kubectl set image│
└─────────────────┘     └──────────────────────┘     │ smoke test       │
                                                      │ auto-rollback    │
                                                      └──────────────────┘
```

---

### Step 6 — Verify Deployment

```bash
# Check all pods are Running
kubectl get pods -n redis-cache-demo

# Expected output:
# NAME                                READY   STATUS    RESTARTS
# redis-cache-demo-7d9f8b-xk2pq      1/1     Running   0
# redis-cache-demo-7d9f8b-mn8rv      1/1     Running   0
# redis-58c9d6-wqplt                  1/1     Running   0

# Check services
kubectl get services -n redis-cache-demo

# Get external IP (LoadBalancer)
kubectl get svc redis-cache-demo-service -n redis-cache-demo

# Test the API
curl http://<EXTERNAL-IP>/api/products

# Check HPA status
kubectl get hpa -n redis-cache-demo

# View logs
kubectl logs -l app=redis-cache-demo -n redis-cache-demo --tail=50

# Stream logs from all app pods
kubectl logs -f -l app=redis-cache-demo -n redis-cache-demo
```

---

## GitHub Actions Pipeline Explained

### Job 1 — `test`

```yaml
- uses: actions/setup-java@v4      # Install Java 17 with Maven cache
- run: ./mvnw test                 # Run unit + integration tests
- uses: actions/upload-artifact@v4 # Save surefire HTML report as artifact
```

Runs on **every push and every PR**. A failed test blocks the image build.

---

### Job 2 — `build-and-push`

```yaml
needs: test                        # Only runs if test job passed
if: github.ref == 'refs/heads/main' # Only on main, not PRs
```

```yaml
- uses: docker/metadata-action@v5  # Generates tags:
                                   #   sha-a1b2c3d  (commit SHA)
                                   #   latest       (on main branch)
                                   #   v1.0.0       (on git tags)

- uses: docker/build-push-action@v5
  with:
    cache-from: type=gha           # Reuse Docker layer cache between runs
    cache-to:   type=gha,mode=max  # Store new layers for next run
```

The image is pushed to `ghcr.io/YOUR_USERNAME/redis-cache-demo` with two tags:
- `sha-a1b2c3d` — immutable, tied to exact commit
- `latest` — moves with every main push

---

### Job 3 — `deploy`

```yaml
environment:
  name: production                 # GitHub environment — can require manual approval
```

**Exact image pinning:**
```yaml
- name: Derive image tag from commit SHA
  run: |
    SHA_TAG="sha-$(echo ${{ github.sha }} | cut -c1-7)"
    FULL_IMAGE="ghcr.io/.../${SHA_TAG}"
```

The deploy always uses the **SHA-tagged image** (not `latest`) so the deployed version is traceable to an exact commit.

**Automatic rollback:**
```yaml
- name: Rollback on failure
  if: failure()
  run: kubectl rollout undo deployment/redis-cache-demo -n redis-cache-demo
```

If the smoke test or readiness check fails, the previous ReplicaSet is immediately restored.

---

## Kubernetes Manifests Explained

### namespace.yaml

Creates an isolated `redis-cache-demo` namespace. All resources live here — no collision with other apps in the cluster.

---

### redis-secret.yaml

```yaml
kind: Secret
stringData:
  redis-password: "changeme123"
```

Stores the Redis password. Injected into both:
- The **Redis pod** via `$(REDIS_PASSWORD)` in the start command
- The **app pod** via `SPRING_DATA_REDIS_PASSWORD` env var

> **Production:** Never commit real passwords. Use [Sealed Secrets](https://github.com/bitnami-labs/sealed-secrets), AWS Secrets Manager, or Vault instead.

---

### redis-deployment.yaml

```yaml
type: ClusterIP   # Redis is NOT exposed outside the cluster
```

Redis is internal-only. The app connects to it via service DNS name `redis-service:6379` — no external exposure.

Liveness + Readiness probes run `redis-cli ping` to confirm Redis is responsive before traffic is sent.

---

### app-configmap.yaml

Non-sensitive configuration injected as environment variables:

```yaml
SPRING_DATA_REDIS_HOST: "redis-service"   # Kubernetes DNS resolves this
SPRING_DATA_REDIS_PORT: "6379"
```

Spring Boot maps `SPRING_DATA_REDIS_HOST` → `spring.data.redis.host` automatically (relaxed binding).

---

### app-deployment.yaml

```yaml
replicas: 2                        # Always 2 pods (HPA manages scale-up)
strategy:
  type: RollingUpdate
  rollingUpdate:
    maxSurge: 1                    # Spin up 1 extra pod during update
    maxUnavailable: 0              # Never take a pod down before new one is ready
```

**Probes:**
```yaml
livenessProbe:
  httpGet:
    path: /actuator/health/liveness   # JVM is alive
readinessProbe:
  httpGet:
    path: /actuator/health/readiness  # App + Redis connection ready to serve traffic
```

These are enabled by `management.endpoint.health.probes.enabled=true` in `application.properties`.

---

### app-hpa.yaml

```yaml
minReplicas: 2
maxReplicas: 6
metrics:
  - cpu    > 70% → scale up
  - memory > 80% → scale up
```

Kubernetes adds/removes pods automatically based on load. Requires `metrics-server` installed in the cluster.

---

## Environment Variable Injection

Spring Boot properties are overridden in Kubernetes via environment variables using relaxed binding:

| Kubernetes source | Env var | Spring property |
|---|---|---|
| ConfigMap | `SPRING_DATA_REDIS_HOST` | `spring.data.redis.host` |
| ConfigMap | `SPRING_DATA_REDIS_PORT` | `spring.data.redis.port` |
| ConfigMap | `SPRING_CACHE_TYPE` | `spring.cache.type` |
| Secret | `SPRING_DATA_REDIS_PASSWORD` | `spring.data.redis.password` |

In `application.properties`:
```properties
# Falls back to localhost when running locally without env vars
spring.data.redis.host=${SPRING_DATA_REDIS_HOST:localhost}
spring.data.redis.password=${SPRING_DATA_REDIS_PASSWORD:}
```

This means the **same JAR** runs locally (with H2 + local Redis) and in Kubernetes (with cluster Redis) — no profile switching needed.

---

## Zero-Downtime Rolling Update

```
Before update:            During update:             After update:
┌──────────┐              ┌──────────┐               ┌──────────┐
│  Pod v1  │              │  Pod v1  │               │  Pod v2  │
│  (old)   │              │  (old)   │               │  (new)   │
└──────────┘              └──────────┘               └──────────┘
┌──────────┐   push  →    ┌──────────┐   ready →     ┌──────────┐
│  Pod v1  │              │  Pod v2  │               │  Pod v2  │
│  (old)   │              │  (new)   │               │  (new)   │
└──────────┘              └──────────┘               └──────────┘
                          ┌──────────┐
                          │  Pod v2  │  ← maxSurge=1 temporary extra
                          │  (new)   │
                          └──────────┘
```

`maxUnavailable: 0` means traffic is never interrupted — old pods receive traffic until new ones pass their readiness probe.

---

## Auto-Scaling (HPA)

```bash
# Simulate load to trigger scale-up
kubectl run -it --rm load-test --image=busybox -n redis-cache-demo -- \
  sh -c "while true; do wget -q -O- http://redis-cache-demo-service/api/products; done"

# Watch HPA in action (in another terminal)
watch kubectl get hpa -n redis-cache-demo

# Expected: REPLICAS column increases from 2 toward 6
```

---

## Rollback Strategy

### Automatic rollback (pipeline)

If the smoke test in Job 3 fails, the pipeline runs:
```bash
kubectl rollout undo deployment/redis-cache-demo -n redis-cache-demo
```

### Manual rollback

```bash
# View rollout history
kubectl rollout history deployment/redis-cache-demo -n redis-cache-demo

# Roll back to previous version
kubectl rollout undo deployment/redis-cache-demo -n redis-cache-demo

# Roll back to specific revision
kubectl rollout undo deployment/redis-cache-demo \
  -n redis-cache-demo --to-revision=3

# Check rollback status
kubectl rollout status deployment/redis-cache-demo -n redis-cache-demo
```

---

## Local Testing with Minikube

```bash
# 1. Start minikube
minikube start

# 2. Point Docker to minikube's daemon (no registry push needed)
eval $(minikube docker-env)

# 3. Build the image directly into minikube
docker build -t redis-cache-demo:local .

# 4. Update app-deployment.yaml image line temporarily
#    image: redis-cache-demo:local
#    imagePullPolicy: Never         ← add this so k8s uses local image

# 5. Apply all manifests
kubectl apply -f k8s/

# 6. Wait for pods
kubectl rollout status deployment/redis-cache-demo -n redis-cache-demo

# 7. Access the service via minikube tunnel
minikube service redis-cache-demo-service -n redis-cache-demo

# 8. Or use port-forward
kubectl port-forward svc/redis-cache-demo-service 8080:80 -n redis-cache-demo
curl http://localhost:8080/api/products
```

---

## Troubleshooting

### Pod stuck in `Pending`

```bash
kubectl describe pod <pod-name> -n redis-cache-demo
# Look for: "Insufficient cpu" or "Insufficient memory"
# Fix: reduce resource requests in app-deployment.yaml
```

### Pod in `CrashLoopBackOff`

```bash
kubectl logs <pod-name> -n redis-cache-demo --previous
# Common cause: Redis connection refused — check redis-service is running
kubectl get pods -n redis-cache-demo | grep redis
```

### `ImagePullBackOff`

```bash
kubectl describe pod <pod-name> -n redis-cache-demo
# Cause: ghcr.io image is private
# Fix: ensure GITHUB_TOKEN has packages:read permission
# Or make the package public in GitHub → Packages → Package settings → Public
```

### Redis authentication failure

```bash
# Verify the secret is mounted correctly
kubectl exec -it <app-pod> -n redis-cache-demo -- \
  env | grep REDIS

# Test Redis connection manually from app pod
kubectl exec -it <app-pod> -n redis-cache-demo -- \
  sh -c 'redis-cli -h redis-service -p 6379 -a $SPRING_DATA_REDIS_PASSWORD ping'
```

### HPA not scaling (`<unknown>` metrics)

```bash
# metrics-server must be installed
kubectl top pods -n redis-cache-demo
# If this command fails, install metrics-server:
kubectl apply -f https://github.com/kubernetes-sigs/metrics-server/releases/latest/download/components.yaml
```

### Check pipeline logs

GitHub → Actions → select the failed run → click the failed job → expand the failed step.

---

## Quick Command Reference

```bash
# Full cluster state
kubectl get all -n redis-cache-demo

# Follow app logs (all pods)
kubectl logs -f -l app=redis-cache-demo -n redis-cache-demo

# Shell into running app pod
kubectl exec -it $(kubectl get pod -l app=redis-cache-demo \
  -n redis-cache-demo -o jsonpath='{.items[0].metadata.name}') \
  -n redis-cache-demo -- sh

# Shell into Redis pod
kubectl exec -it $(kubectl get pod -l app=redis \
  -n redis-cache-demo -o jsonpath='{.items[0].metadata.name}') \
  -n redis-cache-demo -- redis-cli -a changeme123

# Inside redis-cli — verify cached keys
KEYS *
TTL product::1

# Delete all resources (teardown)
kubectl delete namespace redis-cache-demo
```
