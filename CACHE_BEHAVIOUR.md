# Cache Behaviour — Endpoint by Endpoint

This document explains exactly what happens in Redis on every API call: which annotation fires, what key is read/written/deleted, and what the execution flow looks like on first call vs. repeat call.

---

## How Spring Cache Works (internals in 30 seconds)

Spring wraps every `@Cacheable` / `@CachePut` / `@CacheEvict` method with an **AOP proxy**. The proxy intercepts the call *before* the method executes and decides what to do with Redis. The actual method body only runs when there is a cache miss.

```
Client Request
      │
      ▼
Spring AOP Proxy (CacheInterceptor)
      │
      ├── @Cacheable → Check Redis first
      │       ├── HIT  → return cached value, method body SKIPPED
      │       └── MISS → run method, store result in Redis, return result
      │
      ├── @CachePut  → Run method body always, then write result to Redis
      │
      └── @CacheEvict → Run method body, then DELETE key(s) from Redis
```

---

## Cache Inventory

| Cache name | What it stores | TTL | Example Redis key |
|---|---|---|---|
| `product` | Single `Product` object | **10 minutes** | `product::1` |
| `products` | `List<Product>` (all or by category) | **5 minutes** | `products::all`, `products::Electronics` |

TTLs are configured in [RedisConfig.java](src/main/java/com/demo/redis/config/RedisConfig.java#L53-L56).

---

## Endpoint 1 — `GET /api/products`

**Service method:** `getAllProducts()`  
**Annotation:** `@Cacheable(value = "products", key = "'all'")`  
**Redis key:** `products::all`

### First call (Cache MISS)

```
GET /api/products
      │
      ▼
CacheInterceptor checks Redis:
  GET "products::all"  →  (nil)          ← key does not exist
      │
      ▼
Method body executes:
  Thread.sleep(500)                       ← simulated slow DB
  repository.findAll()                    ← SQL fires: SELECT * FROM product
      │
      ▼
Result (List<Product>) serialized to JSON
  SET "products::all" "<json>" EX 300    ← stored in Redis, TTL = 5 min
      │
      ▼
Response returned to client              ← ~500 ms
```

### Second call (Cache HIT)

```
GET /api/products
      │
      ▼
CacheInterceptor checks Redis:
  GET "products::all"  →  "<json>"       ← key exists!
      │
      ▼
JSON deserialized back to List<Product>
Method body is NEVER called              ← no SQL, no sleep
      │
      ▼
Response returned to client              ← < 5 ms
```

---

## Endpoint 2 — `GET /api/products/{id}`

**Service method:** `getProductById(Long id)`  
**Annotation:** `@Cacheable(value = "product", key = "#id")`  
**Redis key:** `product::{id}` (e.g. `product::1`)

### First call (Cache MISS)

```
GET /api/products/1
      │
      ▼
CacheInterceptor checks Redis:
  GET "product::1"  →  (nil)
      │
      ▼
Method body executes:
  Thread.sleep(500)
  repository.findById(1)                 ← SQL: SELECT * FROM product WHERE id=1
      │
      ├── Found  → SET "product::1" "<json>" EX 600   ← TTL = 10 min
      └── Not found → throw ProductNotFoundException  ← nothing written to Redis
      │
      ▼
Response: 200 Product JSON  (or 404 if missing)
```

### Second call (Cache HIT)

```
GET /api/products/1
      │
      ▼
CacheInterceptor: GET "product::1"  →  "<json>"
Method body skipped, no DB query
Response: 200 Product JSON  ~< 5 ms
```

> **Note:** `cache-null-values=false` means a missing product is never cached. Every call for a non-existent ID always hits the DB.

---

## Endpoint 3 — `GET /api/products/category/{category}`

**Service method:** `getProductsByCategory(String category)`  
**Annotation:** `@Cacheable(value = "products", key = "#category")`  
**Redis key:** `products::{category}` (e.g. `products::Electronics`)

### First call (Cache MISS)

```
GET /api/products/category/Electronics
      │
      ▼
CacheInterceptor: GET "products::Electronics"  →  (nil)
      │
      ▼
Method body:
  Thread.sleep(500)
  repository.findByCategory("Electronics")
                                               ← SQL: SELECT * FROM product WHERE category='Electronics'
      │
      ▼
SET "products::Electronics" "<json>" EX 300   ← TTL = 5 min
Response: 200 filtered list  ~500 ms
```

### Second call (Cache HIT)

```
GET /api/products/category/Electronics
      │
      ▼
CacheInterceptor: GET "products::Electronics"  →  "<json>"
Method body skipped
Response: < 5 ms
```

> **Important:** `products::Electronics` and `products::Furniture` are **separate keys**. Caching "Electronics" does not help "Furniture" queries and vice versa.

---

## Endpoint 4 — `POST /api/products`

**Service method:** `createProduct(Product product)`  
**Annotations:**
- `@CachePut(value = "product", key = "#result.id")` — write new product to single-item cache
- `@CacheEvict(value = "products", allEntries = true)` — wipe all list caches

### Every call (always executes, no miss/hit concept)

```
POST /api/products  { "name": "Smart Watch", ... }
      │
      ▼
Method body executes (always — @CachePut never skips):
  repository.save(product)               ← SQL: INSERT INTO product ...
  returns saved Product (with generated ID, e.g. id=9)
      │
      ▼
@CachePut fires AFTER method returns:
  SET "product::9" "<json>" EX 600      ← new product warmed in cache immediately

@CacheEvict fires AFTER method returns:
  DEL "products::all"                   ← stale "all" list removed
  DEL "products::Electronics"           ← stale category list removed
  DEL "products::Furniture"             ← any other category lists removed
  (allEntries=true removes EVERY key in the "products" cache)
      │
      ▼
Response: 201 Created  { "id": 9, ... }
```

> **Why evict list caches?** The "all products" list and any category list that were cached no longer reflect reality — they are missing the new product. Evicting forces the next `GET` to re-query and re-populate the cache with fresh data.

> **Why `@CachePut` instead of `@Cacheable`?** `@Cacheable` would skip the DB insert if the key already existed — catastrophic for a create operation. `@CachePut` always runs the method.

---

## Endpoint 5 — `PUT /api/products/{id}`

**Service method:** `updateProduct(Long id, Product updated)`  
**Annotation:** `@Caching(...)` combining:
- `@CachePut(value = "product", key = "#id")` — refresh the single-item cache
- `@CacheEvict(value = "products", allEntries = true)` — wipe all list caches

### Every call

```
PUT /api/products/1  { "name": "Laptop Pro 16", "price": 1499.99, ... }
      │
      ▼
Method body executes (always):
  repository.findById(1)                 ← SQL: SELECT (to get existing entity)
  existing.setName("Laptop Pro 16")      ← mutate fields
  repository.save(existing)             ← SQL: UPDATE product SET ...
  returns updated Product
      │
      ▼
@CachePut fires AFTER method returns:
  SET "product::1" "<updated-json>" EX 600   ← single-item cache refreshed

@CacheEvict fires AFTER method returns:
  DEL all keys in "products" cache           ← "products::all", "products::Electronics", etc.
      │
      ▼
Response: 200 Updated Product JSON
```

**Before vs. After in Redis:**

```
Before PUT:
  product::1        → { "name": "Laptop Pro 15", "price": 1299.99, ... }
  products::all     → [ { id:1, name:"Laptop Pro 15" }, ... ]
  products::Electronics → [ { id:1, name:"Laptop Pro 15" }, ... ]

After PUT:
  product::1        → { "name": "Laptop Pro 16", "price": 1499.99, ... }  ← refreshed
  products::all     → (gone)                                               ← evicted
  products::Electronics → (gone)                                           ← evicted
```

---

## Endpoint 6 — `DELETE /api/products/{id}`

**Service method:** `deleteProduct(Long id)`  
**Annotation:** `@Caching(evict = { ... })` combining:
- `@CacheEvict(value = "product", key = "#id")` — remove the specific item
- `@CacheEvict(value = "products", allEntries = true)` — wipe all list caches

### Every call

```
DELETE /api/products/1
      │
      ▼
Method body executes:
  repository.existsById(1)              ← SQL: SELECT COUNT(*) ...
  ├── false → throw ProductNotFoundException  (no eviction happens)
  └── true  →
        repository.deleteById(1)        ← SQL: DELETE FROM product WHERE id=1
      │
      ▼
@CacheEvict fires AFTER method returns:
  DEL "product::1"                      ← single-item entry removed

  DEL all keys in "products" cache:
    DEL "products::all"
    DEL "products::Electronics"
    DEL "products::Furniture"
    (and any other category that was cached)
      │
      ▼
Response: 204 No Content
```

**After DELETE in Redis:**

```
Before:                                  After:
  product::1        → { ... }             product::1        → (gone)
  product::2        → { ... }             product::2        → { ... }  ← untouched
  products::all     → [ ... ]             products::all     → (gone)
  products::Electronics → [ ... ]         products::Electronics → (gone)
```

---

## Side-by-side Summary

| Endpoint | Annotation(s) | Reads Redis? | Writes Redis? | Deletes from Redis? | DB call? |
|---|---|---|---|---|---|
| `GET /api/products` | `@Cacheable` | Yes — `products::all` | On MISS only | Never | On MISS only |
| `GET /api/products/{id}` | `@Cacheable` | Yes — `product::{id}` | On MISS only | Never | On MISS only |
| `GET /api/products/category/{cat}` | `@Cacheable` | Yes — `products::{cat}` | On MISS only | Never | On MISS only |
| `POST /api/products` | `@CachePut` + `@CacheEvict` | Never | Always — `product::{newId}` | Always — all `products::*` | Always |
| `PUT /api/products/{id}` | `@CachePut` + `@CacheEvict` | Never | Always — `product::{id}` | Always — all `products::*` | Always (read + write) |
| `DELETE /api/products/{id}` | `@CacheEvict` x2 | Never | Never | Always — `product::{id}` + all `products::*` | Always |

---

## Annotation Cheat Sheet

| Annotation | Skips method on hit? | Writes to cache? | Deletes from cache? | Typical use |
|---|---|---|---|---|
| `@Cacheable` | **Yes** | On miss | Never | GET / read operations |
| `@CachePut` | **No** (always runs) | Always | Never | Create / update (keep cache warm) |
| `@CacheEvict` | **No** (always runs) | Never | Always | Delete / mutating writes |
| `@Caching` | Depends on inner annotations | Depends | Depends | Combine multiple cache ops on one method |

---

## Key Format

Spring `RedisCacheManager` builds keys as:

```
{cacheName}::{SpEL expression result}
```

| SpEL in annotation | Resolved key |
|---|---|
| `key = "'all'"` | `products::all` |
| `key = "#id"` (id=3) | `product::3` |
| `key = "#category"` (category="Electronics") | `products::Electronics` |
| `key = "#result.id"` (saved id=9) | `product::9` |

---

## What `allEntries = true` Does

Used on `@CacheEvict` for the `"products"` cache in POST, PUT, and DELETE:

```java
@CacheEvict(value = "products", allEntries = true)
```

This tells Redis to delete **every key** whose name starts with `products::`, regardless of what the key suffix is. Without this, you would have to know every category name to evict them individually, which is impossible at write time.

```
Redis before:                     Redis after allEntries evict:
  products::all          → [...]    products::all          → (gone)
  products::Electronics  → [...]    products::Electronics  → (gone)
  products::Furniture    → [...]    products::Furniture    → (gone)
```

---

## TTL Expiry (automatic eviction)

Even without an explicit `@CacheEvict`, Redis automatically removes keys when their TTL expires:

```
product::1        expires after 10 minutes  → next GET hits DB and repopulates
products::all     expires after  5 minutes  → next GET hits DB and repopulates
```

This is the last line of defence against stale data surviving an application restart or a missed eviction.
