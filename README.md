# Spring Boot Redis Cache Demo

A production-ready reference project demonstrating how to integrate **Redis** as a distributed cache in a **Spring Boot 3** application. The use case is a **Product Catalog Service** — a read-heavy REST API where caching dramatically reduces database load.

---

## Table of Contents

1. [Use Case & Motivation](#use-case--motivation)
2. [Requirements](#requirements)
3. [Architecture](#architecture)
4. [Cache Strategy](#cache-strategy)
5. [Project Structure](#project-structure)
6. [API Reference](#api-reference)
7. [How to Run](#how-to-run)
8. [How to Test](#how-to-test)
9. [Observing Cache Behaviour](#observing-cache-behaviour)
10. [Configuration Reference](#configuration-reference)

---

## Use Case & Motivation

| Scenario | Without Cache | With Redis Cache |
|---|---|---|
| GET `/api/products` (first call) | ~500 ms (DB) | ~500 ms (DB, cold) |
| GET `/api/products` (repeat call) | ~500 ms (DB) | < 5 ms (Redis) |
| GET `/api/products/1` (repeat) | ~500 ms (DB) | < 5 ms (Redis) |
| PUT/DELETE product | DB write | DB write + cache evict |

The simulated 500 ms `Thread.sleep` in `ProductService` makes the cache benefit immediately observable in response times.

---

## Requirements

### Runtime
| Tool | Version |
|---|---|
| Java | 17 or higher |
| Maven | 3.8+ |
| Redis | 7.x |
| Docker (optional) | 24+ |

### Dependencies (managed by Spring Boot BOM)
| Dependency | Purpose |
|---|---|
| `spring-boot-starter-web` | REST API |
| `spring-boot-starter-cache` | `@EnableCaching`, `@Cacheable`, etc. |
| `spring-boot-starter-data-redis` | Lettuce (Redis client) |
| `spring-boot-starter-data-jpa` | Repository layer |
| `h2` | In-memory database (demo only) |
| `spring-boot-starter-actuator` | Cache & health endpoints |
| `lombok` | Boilerplate reduction |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client (curl / Postman)                 │
└────────────────────────────┬────────────────────────────────────┘
                             │ HTTP
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ProductController                            │
│           (REST endpoints, request validation)                   │
└────────────────────────────┬────────────────────────────────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                      ProductService                              │
│    @Cacheable / @CachePut / @CacheEvict / @Caching              │
│                                                                  │
│   Cache HIT? ──YES──▶ return cached value (Redis)               │
│       │                                                          │
│      NO                                                          │
│       │                                                          │
│       ▼                                                          │
│   Execute method body → store result in Redis → return           │
└──────────┬───────────────────────────────────────────────────────┘
           │ Cache MISS only
           ▼
┌─────────────────────────────────────────────────────────────────┐
│                     ProductRepository                            │
│               (Spring Data JPA + H2 DB)                          │
└─────────────────────────────────────────────────────────────────┘

            Cache Layer (Redis)
┌───────────────────────────────────────┐
│  Cache: "product"  TTL: 10 min        │
│    product::1  → Product{...}         │
│    product::2  → Product{...}         │
│                                       │
│  Cache: "products"  TTL: 5 min        │
│    products::all         → List<...>  │
│    products::Electronics → List<...>  │
│    products::Furniture   → List<...>  │
└───────────────────────────────────────┘
```

### Key Components

| Class | Responsibility |
|---|---|
| `RedisCacheDemoApplication` | Entry point, `@EnableCaching` |
| `RedisConfig` | `CacheManager` with per-cache TTL, JSON serialization |
| `Product` | JPA entity (implements `Serializable` for Redis) |
| `ProductRepository` | Spring Data JPA interface |
| `ProductService` | Business logic + all cache annotations |
| `ProductController` | REST layer, delegates to service |

---

## Cache Strategy

### Annotations used

| Annotation | When | Effect |
|---|---|---|
| `@Cacheable` | GET by ID, GET all, GET by category | Skip DB on cache hit; populate cache on miss |
| `@CachePut` | Create, Update | Always run method AND update cache (keeps cache warm) |
| `@CacheEvict` | Delete, Create, Update | Remove stale entries |
| `@Caching` | Update / Delete | Combine multiple cache operations atomically |

### Cache names & TTL

| Cache | Keys | TTL |
|---|---|---|
| `product` | `product::<id>` | 10 minutes |
| `products` | `products::all`, `products::<category>` | 5 minutes |

---

## Project Structure

```
redis-cache-demo/
├── src/
│   ├── main/
│   │   ├── java/com/demo/redis/
│   │   │   ├── RedisCacheDemoApplication.java   # @SpringBootApplication + @EnableCaching
│   │   │   ├── config/
│   │   │   │   └── RedisConfig.java             # CacheManager, RedisTemplate, JSON serializer
│   │   │   ├── controller/
│   │   │   │   └── ProductController.java       # REST endpoints
│   │   │   ├── model/
│   │   │   │   └── Product.java                 # JPA entity (Serializable)
│   │   │   ├── repository/
│   │   │   │   └── ProductRepository.java       # JpaRepository
│   │   │   └── service/
│   │   │       └── ProductService.java          # Cache annotations + business logic
│   │   └── resources/
│   │       ├── application.properties           # Redis, JPA, cache config
│   │       └── data.sql                         # Seed data for H2
│   └── test/
│       └── java/com/demo/redis/
│           ├── ProductServiceTest.java           # Unit tests (Mockito)
│           └── ProductControllerIntegrationTest.java  # MockMvc integration tests
├── docker-compose.yml                           # Redis container
└── pom.xml
```

---

## API Reference

| Method | URL | Description | Cache action |
|---|---|---|---|
| GET | `/api/products` | List all products | `@Cacheable("products", key="'all'")` |
| GET | `/api/products/{id}` | Get product by ID | `@Cacheable("product", key="#id")` |
| GET | `/api/products/category/{cat}` | Filter by category | `@Cacheable("products", key="#category")` |
| POST | `/api/products` | Create product | `@CachePut("product")` + evict lists |
| PUT | `/api/products/{id}` | Update product | `@CachePut("product")` + evict lists |
| DELETE | `/api/products/{id}` | Delete product | `@CacheEvict` product + lists |

### Request body (POST / PUT)

```json
{
  "name": "Smart Watch",
  "category": "Electronics",
  "price": 299.99,
  "stockQuantity": 75
}
```

### Actuator endpoints

| URL | Description |
|---|---|
| `GET /actuator/health` | App health + Redis connectivity |
| `GET /actuator/caches` | List all registered caches |
| `DELETE /actuator/caches/{cache}` | Manually clear a cache |

---

## How to Run

### Step 1 — Start Redis

**Option A: Docker (recommended)**
```bash
docker-compose up -d
```

**Option B: local Redis**
```bash
# macOS
brew install redis && brew services start redis

# Ubuntu / Debian
sudo apt install redis-server && sudo systemctl start redis

# Windows (WSL2 or scoop)
scoop install redis
redis-server
```

Verify Redis is running:
```bash
redis-cli ping
# Expected: PONG
```

### Step 2 — Build and run the application

```bash
./mvnw spring-boot:run
```

Or build a JAR first:
```bash
./mvnw clean package -DskipTests
java -jar target/redis-cache-demo-1.0.0.jar
```

The app starts on **http://localhost:8080**.

---

## How to Test

### Unit tests (no Redis required)

```bash
./mvnw test -Dtest=ProductServiceTest
```

Tests use Mockito to mock `ProductRepository`. They verify:
- Products are returned correctly on cache miss
- `ProductNotFoundException` is thrown for missing IDs
- Create/Update call `repository.save()`
- Delete calls `repository.deleteById()` and throws when ID missing

### Integration tests (no Redis required)

The integration tests use `@MockBean` for the repository and a real `CacheManager` backed by a **no-op** cache (Spring Boot auto-configures a `NoOpCacheManager` when `spring.cache.type=none` is set in the test profile, or you can rely on the mock).

```bash
./mvnw test -Dtest=ProductControllerIntegrationTest
```

Tests verify HTTP status codes, response JSON structure, and that cache is cleared between tests.

### Run all tests

```bash
./mvnw test
```

### Manual end-to-end testing (Redis must be running)

**1. List all products (first call — cache MISS, ~500 ms)**
```bash
time curl -s http://localhost:8080/api/products | jq length
```

**2. List all products again (cache HIT, < 5 ms)**
```bash
time curl -s http://localhost:8080/api/products | jq length
```

**3. Get single product (cache MISS)**
```bash
time curl -s http://localhost:8080/api/products/1 | jq .
```

**4. Get same product (cache HIT)**
```bash
time curl -s http://localhost:8080/api/products/1 | jq .
```

**5. Filter by category**
```bash
curl -s http://localhost:8080/api/products/category/Electronics | jq length
```

**6. Create a new product (evicts list caches)**
```bash
curl -s -X POST http://localhost:8080/api/products \
  -H "Content-Type: application/json" \
  -d '{"name":"Smart Watch","category":"Electronics","price":299.99,"stockQuantity":75}' | jq .
```

**7. Update a product (refreshes single-item cache, evicts list caches)**
```bash
curl -s -X PUT http://localhost:8080/api/products/1 \
  -H "Content-Type: application/json" \
  -d '{"name":"Laptop Pro 16","category":"Electronics","price":1499.99,"stockQuantity":40}' | jq .
```

**8. Delete a product (evicts all related cache entries)**
```bash
curl -s -X DELETE http://localhost:8080/api/products/6
```

### Inspecting cache contents in Redis CLI

```bash
redis-cli

# List all cache keys
KEYS *

# Inspect a cached product
GET "product::1"

# Inspect the "all products" list cache
GET "products::all"

# Check TTL on a key
TTL "product::1"

# Manually flush everything
FLUSHALL
```

### Clearing a cache via Actuator

```bash
# Clear the "products" list cache
curl -X DELETE http://localhost:8080/actuator/caches/products

# List all caches
curl http://localhost:8080/actuator/caches | jq .
```

---

## Observing Cache Behaviour in Logs

Set `logging.level.org.springframework.cache=TRACE` in `application.properties` (already enabled by default in this project). You will see:

```
TRACE o.s.cache.interceptor.CacheInterceptor - Computed cache key '1' for operation ...
TRACE o.s.cache.interceptor.CacheInterceptor - No cache entry for key '1' in cache(s) [product]
DEBUG com.demo.redis.service.ProductService  - CACHE MISS — fetching product 1 from database
# ... SQL fires ...
TRACE o.s.cache.interceptor.CacheInterceptor - Creating cache entry for key '1' in cache 'product'

# --- second call ---
TRACE o.s.cache.interceptor.CacheInterceptor - Cache entry for key '1' found in cache 'product'
# No SQL, no DEBUG log — method body skipped entirely
```

---

## Configuration Reference

| Property | Default | Description |
|---|---|---|
| `spring.data.redis.host` | `localhost` | Redis host |
| `spring.data.redis.port` | `6379` | Redis port |
| `spring.data.redis.timeout` | `2000ms` | Connection timeout |
| `spring.cache.redis.time-to-live` | `60000` (ms) | Default TTL (overridden per cache in `RedisConfig`) |
| `spring.cache.redis.cache-null-values` | `false` | Prevent caching `null` results |
| `spring.cache.type` | `redis` | Cache provider |

Per-cache TTLs are configured programmatically in [RedisConfig.java](src/main/java/com/demo/redis/config/RedisConfig.java):
- `product` → 10 minutes
- `products` → 5 minutes
