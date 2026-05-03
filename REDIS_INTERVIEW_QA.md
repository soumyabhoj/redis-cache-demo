# Redis Interview Questions — Real-World Challenges & Fixes

---

## Table of Contents

1. [Core Concepts](#1-core-concepts)
2. [Data Structures](#2-data-structures)
3. [Caching Patterns](#3-caching-patterns)
4. [Real-World Challenges & Fixes](#4-real-world-challenges--fixes)
5. [Persistence & Durability](#5-persistence--durability)
6. [High Availability & Clustering](#6-high-availability--clustering)
7. [Performance & Tuning](#7-performance--tuning)
8. [Security](#8-security)
9. [Spring Boot + Redis Specific](#9-spring-boot--redis-specific)

---

## 1. Core Concepts

---

### Q1. What is Redis and why is it faster than a relational database?

**Answer:**

Redis (Remote Dictionary Server) is an **in-memory data store** that keeps all data in RAM. A relational database reads from disk (even with buffer pools, cache misses hit disk). Redis never touches disk for reads — data lives in RAM and is accessed via a single-threaded event loop with no lock contention.

| Factor | Redis | RDBMS |
|---|---|---|
| Storage | RAM | Disk (with RAM cache) |
| I/O model | Single-threaded event loop | Multi-threaded with locks |
| Read latency | < 1 ms | 1–100 ms |
| Write latency | < 1 ms | 5–50 ms (with fsync) |
| Data model | Key-value + structures | Tables + relations |

---

### Q2. Redis is single-threaded. How does it handle high concurrency?

**Answer:**

"Single-threaded" refers only to the **command processing loop** — one thread executes commands sequentially, which eliminates lock contention. I/O multiplexing (epoll/kqueue) handles thousands of simultaneous connections on that one thread.

```
Client 1 ──┐
Client 2 ──┤──▶  I/O Multiplexer (epoll)  ──▶  Command Queue  ──▶  Single Worker Thread
Client 3 ──┘
```

Since Redis 6.0, **I/O threads** were added for reading/writing network data, but command execution is still single-threaded. This is why commands must be O(1) or O(log N) — an O(N) command like `KEYS *` blocks everything.

---

### Q3. What is the difference between `EXPIRE`, `EXPIREAT`, `TTL`, and `PERSIST`?

**Answer:**

```bash
SET session:user1 "data"

EXPIRE   session:user1 3600        # expire in 3600 seconds from now
EXPIREAT session:user1 1735689600  # expire at Unix timestamp

TTL  session:user1   # returns seconds remaining (-1 = no expiry, -2 = key gone)
PTTL session:user1   # same but in milliseconds

PERSIST session:user1  # remove the TTL — key lives forever
```

**Real-world use:** `EXPIREAT` is useful for tokens that must expire at a fixed wall-clock time (e.g., "this promo code expires at midnight").

---

### Q4. What is the difference between `DEL` and `UNLINK`?

**Answer:**

```bash
DEL   mykey   # synchronous — blocks the event loop until memory is freed
UNLINK mykey  # asynchronous — marks key as deleted immediately, frees memory in background thread
```

**Challenge:** Deleting a key holding a large hash (millions of fields) with `DEL` can block Redis for hundreds of milliseconds.

**Fix:** Always use `UNLINK` for potentially large keys in production.

---

## 2. Data Structures

---

### Q5. When would you use a Hash instead of a String for storing an object?

**Answer:**

```bash
# String — entire object serialized as JSON
SET user:1  '{"id":1,"name":"Alice","email":"alice@x.com","age":30}'

# Hash — fields stored individually
HSET user:1  name "Alice"  email "alice@x.com"  age 30
```

| | String (JSON) | Hash |
|---|---|---|
| Update one field | Read entire JSON, parse, update, serialize, write | `HSET user:1 age 31` |
| Read one field | Read entire JSON, parse | `HGET user:1 name` |
| Memory | More (JSON overhead) | Less (ziplist for small hashes) |
| Atomic field increment | No | `HINCRBY user:1 age 1` |

**Rule of thumb:** Use Hash when you frequently update individual fields. Use String (JSON) when you always read/write the whole object.

---

### Q6. When would you use a Sorted Set?

**Answer:**

Sorted Sets store members with a **score** (float), kept in order. Perfect for:

```bash
# Leaderboard
ZADD leaderboard 1500 "alice"
ZADD leaderboard 2300 "bob"
ZADD leaderboard 1900 "carol"

ZREVRANGE leaderboard 0 2 WITHSCORES   # top 3: bob, carol, alice

# Rate limiting — sliding window
ZADD ratelimit:user1 <timestamp> <requestId>
ZREMRANGEBYSCORE ratelimit:user1 -inf <windowStart>
ZCARD ratelimit:user1   # count requests in window

# Delayed job queue (score = execute_at timestamp)
ZADD jobs 1735689600 "send-email:42"
ZRANGEBYSCORE jobs -inf <now>   # fetch due jobs
```

---

### Q7. What is the difference between `LPUSH/RPUSH` and when would you use a List vs Stream?

**Answer:**

```bash
LPUSH queue "job3"  # add to LEFT (head)
RPUSH queue "job1"  # add to RIGHT (tail)
LPOP  queue         # remove from head → "job3" (LIFO if using LPUSH+LPOP)
RPOP  queue         # remove from tail → "job1" (FIFO if using RPUSH+LPOP)
```

| | List | Stream |
|---|---|---|
| Consumer groups | No | Yes |
| Message acknowledgement | No | Yes (`XACK`) |
| Replay messages | No | Yes (messages persist) |
| Use case | Simple queues, stacks | Kafka-like event streaming |

**Use List** for a simple job queue where each job is consumed once.
**Use Stream** when you need multiple consumer groups, delivery guarantees, or message replay.

---

## 3. Caching Patterns

---

### Q8. What are the main cache read strategies?

**Answer:**

#### Cache-Aside (Lazy Loading) — most common
```
App → check cache → HIT: return data
                  → MISS: query DB → write to cache → return data
```
```java
// Spring @Cacheable implements this automatically
@Cacheable(value = "product", key = "#id")
public Product getProduct(Long id) {
    return repository.findById(id).orElseThrow();
}
```
**Pro:** Only caches what is actually needed.
**Con:** First request always slow (cold start).

#### Read-Through
```
App → Cache → (on miss, cache itself fetches from DB)
```
Cache sits in front of DB and is responsible for loading. App never talks to DB directly.

#### Write-Through
```
App → writes to Cache → Cache synchronously writes to DB
```
Every write goes to both. Cache is always consistent with DB.
**Con:** Higher write latency.

#### Write-Behind (Write-Back)
```
App → writes to Cache → returns immediately
Cache → asynchronously flushes to DB (batched)
```
**Pro:** Very fast writes.
**Con:** Risk of data loss if Redis crashes before flush.

---

### Q9. What are the three cache failure modes and how do you handle each?

**Answer:**

#### 1. Cache Stampede (Thundering Herd)

**Problem:** A popular cache key expires. 1000 concurrent requests all get a miss and hammer the DB simultaneously.

```
Cache key "products::all" expires
      │
1000 requests arrive simultaneously
      │
All get cache MISS → all hit DB → DB overloaded → timeouts
```

**Fix 1 — Mutex / Lock:**
```java
// Only one thread fetches from DB; others wait
String lockKey = "lock:products::all";
Boolean locked = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "1", Duration.ofSeconds(5));

if (Boolean.TRUE.equals(locked)) {
    // fetch from DB, populate cache, release lock
} else {
    Thread.sleep(100);
    return cache.get("products::all"); // retry
}
```

**Fix 2 — Probabilistic Early Expiration (PER):**
Before TTL hits zero, randomly start refreshing early to avoid synchronized expiry.

**Fix 3 — Stale-While-Revalidate:**
Serve stale data from cache while one background thread refreshes it.

---

#### 2. Cache Penetration

**Problem:** Requests for keys that **never exist** in DB (e.g., `GET /api/products/99999`) bypass cache every time because cache only stores found results, not misses.

```
Request for product id=99999
      │
Cache MISS → DB query → not found → nothing cached
      │
Next request for id=99999 → Cache MISS again → DB hit again → infinitely
```

**Fix 1 — Cache null/empty result:**
```java
// application.properties
spring.cache.redis.cache-null-values=true

// Or manually:
cache.put("product::99999", Optional.empty());
```

**Fix 2 — Bloom Filter:**
```
Before hitting cache, check Bloom Filter.
Bloom Filter says "definitely NOT in DB" → return 404 immediately, no cache/DB hit.
Bloom Filter says "maybe in DB" → proceed to cache/DB lookup.
```
```java
// Using Redisson
RBloomFilter<Long> bloomFilter = redissonClient.getBloomFilter("product-ids");
bloomFilter.tryInit(1_000_000, 0.03); // 1M items, 3% false positive rate

if (!bloomFilter.contains(id)) {
    throw new ProductNotFoundException(id); // never hits DB
}
return getProductById(id);
```

---

#### 3. Cache Avalanche

**Problem:** Many cache keys expire **at the same time** (e.g., after a restart that populated all keys with the same TTL), causing a wave of DB requests.

```
App restarts → all 10,000 keys cached with TTL=300s
At T+300s: all 10,000 keys expire simultaneously → DB flood
```

**Fix — TTL Jitter:**
```java
// Instead of fixed TTL
Duration ttl = Duration.ofMinutes(5);

// Add random jitter ±60 seconds
long jitterSeconds = ThreadLocalRandom.current().nextLong(-60, 60);
Duration ttl = Duration.ofMinutes(5).plusSeconds(jitterSeconds);
```

```java
// In RedisConfig.java
defaults.entryTtl(Duration.ofMinutes(5).plusSeconds(
    ThreadLocalRandom.current().nextLong(0, 60)
));
```

---

## 4. Real-World Challenges & Fixes

---

### Q10. Your Redis memory is full. What happens and how do you handle it?

**Answer:**

When Redis hits `maxmemory`, it applies an **eviction policy**:

```bash
# redis.conf
maxmemory 2gb
maxmemory-policy allkeys-lru   # evict least-recently-used keys across all keys
```

| Policy | Behaviour | Use when |
|---|---|---|
| `noeviction` | Returns error on writes | You cannot afford data loss |
| `allkeys-lru` | Evicts least recently used across all keys | General cache |
| `volatile-lru` | Evicts LRU only among keys with TTL | Mixed cache + persistent data |
| `allkeys-lfu` | Evicts least frequently used | Skewed access patterns |
| `volatile-ttl` | Evicts key with shortest TTL first | You set TTLs carefully |
| `allkeys-random` | Random eviction | Uniform access, don't care which |

**Real-world fix checklist:**
1. Set `maxmemory-policy allkeys-lru` — never let Redis return OOM errors to the app
2. Monitor `used_memory` via `INFO memory`
3. Use `OBJECT ENCODING key` to check if large keys can use more compact structures
4. Scan for big keys: `redis-cli --bigkeys`
5. Set TTLs on everything — never cache without expiry

---

### Q11. How do you implement distributed locking with Redis and what can go wrong?

**Answer:**

#### Naive approach (broken):
```bash
SETNX lock:payment "1"   # set if not exists
# ... do work ...
DEL lock:payment
```

**Problem:** If the process crashes between SETNX and DEL, the lock is never released — **deadlock**.

#### Better approach — SET with NX and EX atomically:
```java
Boolean acquired = redisTemplate.opsForValue()
    .setIfAbsent("lock:payment:" + orderId, "owner-uuid", Duration.ofSeconds(10));

if (!Boolean.TRUE.equals(acquired)) {
    throw new LockNotAcquiredException();
}
try {
    processPayment(orderId);
} finally {
    // Only release if we still own it (use Lua script for atomicity)
    String script = """
        if redis.call('GET', KEYS[1]) == ARGV[1] then
            return redis.call('DEL', KEYS[1])
        else
            return 0
        end
    """;
    redisTemplate.execute(
        new DefaultRedisScript<>(script, Long.class),
        List.of("lock:payment:" + orderId),
        "owner-uuid"
    );
}
```

**Why Lua script for release?** `GET` then `DEL` is not atomic — another thread could acquire the lock between your GET and DEL, and you'd delete their lock.

#### What can still go wrong:
| Problem | Scenario | Fix |
|---|---|---|
| Clock drift | Redis TTL based on system clock | Use Redlock algorithm across 3+ Redis nodes |
| Lock expiry during long operation | Work takes longer than TTL | Extend TTL with a watchdog thread (Redisson does this) |
| Network partition | Client holds lock but Redis thinks it expired | Accept this risk or use Redisson's Redlock |

**Production recommendation:** Use **Redisson** `RLock` — it implements watchdog renewal and Redlock automatically.

```java
RLock lock = redissonClient.getLock("lock:payment:" + orderId);
lock.lock(10, TimeUnit.SECONDS); // auto-renewed by watchdog
try {
    processPayment(orderId);
} finally {
    lock.unlock();
}
```

---

### Q12. How do you implement a rate limiter in Redis?

**Answer:**

#### Fixed Window:
```java
String key = "rate:user:" + userId + ":" + (System.currentTimeMillis() / 60_000);
Long count = redisTemplate.opsForValue().increment(key);
redisTemplate.expire(key, Duration.ofMinutes(2)); // cleanup
if (count > 100) throw new RateLimitExceededException();
```

**Problem:** A user can make 100 requests at 00:59 and 100 more at 01:01 — 200 in 2 seconds.

#### Sliding Window (Sorted Set):
```java
long now = System.currentTimeMillis();
long windowStart = now - 60_000; // 1 minute window
String key = "rate:user:" + userId;

// Lua script for atomicity
String script = """
    redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
    local count = redis.call('ZCARD', KEYS[1])
    if count < tonumber(ARGV[3]) then
        redis.call('ZADD', KEYS[1], ARGV[2], ARGV[2])
        redis.call('EXPIRE', KEYS[1], 60)
        return 1
    end
    return 0
""";

Long allowed = redisTemplate.execute(
    new DefaultRedisScript<>(script, Long.class),
    List.of(key),
    String.valueOf(windowStart),
    String.valueOf(now),
    "100"  // max requests
);

if (allowed == 0) throw new RateLimitExceededException();
```

---

### Q13. Hot Key Problem — one Redis key gets millions of requests per second

**Answer:**

**Scenario:** Your product `id=1` ("iPhone 16") is trending. Every request hits `product::1` in Redis, overwhelming the single Redis node serving that slot (in a cluster).

**Symptoms:**
- One Redis node CPU at 100%
- Other nodes idle
- Latency spikes only for `product::1`

**Fix 1 — Local JVM cache (L1 cache) in front of Redis:**
```java
// Caffeine local cache — 1 second TTL, 1000 max entries
Cache<Long, Product> localCache = Caffeine.newBuilder()
    .maximumSize(1000)
    .expireAfterWrite(Duration.ofSeconds(1))
    .build();

public Product getProduct(Long id) {
    return localCache.get(id, key -> {
        // only hits Redis on local cache miss
        return redisTemplate.opsForValue().get("product::" + key);
    });
}
```

**Fix 2 — Key sharding (replicate across N keys):**
```java
// Write to N replica keys
for (int i = 0; i < 10; i++) {
    redisTemplate.opsForValue().set("product::1::shard::" + i, product);
}

// Read from a random shard
int shard = ThreadLocalRandom.current().nextInt(10);
Product p = redisTemplate.opsForValue().get("product::1::shard::" + shard);
```

---

### Q14. Redis Pipeline vs Transaction — what is the difference?

**Answer:**

#### Pipeline — batch commands, reduce round-trips:
```java
// Without pipeline: 1000 network round-trips
for (int i = 0; i < 1000; i++) {
    redisTemplate.opsForValue().set("key:" + i, "value");
}

// With pipeline: 1 network round-trip
redisTemplate.executePipelined((RedisCallback<Object>) connection -> {
    for (int i = 0; i < 1000; i++) {
        connection.stringCommands().set(
            ("key:" + i).getBytes(), "value".getBytes()
        );
    }
    return null;
});
```

**Pipeline does NOT guarantee atomicity** — commands execute in order but other clients can interleave.

#### Transaction (MULTI/EXEC) — atomic block:
```java
redisTemplate.execute(new SessionCallback<>() {
    public Object execute(RedisOperations ops) {
        ops.multi();                              // BEGIN
        ops.opsForValue().set("balance:alice", 900);
        ops.opsForValue().set("balance:bob",   600);
        return ops.exec();                        // COMMIT — atomic
    }
});
```

**Redis transactions are NOT like SQL transactions:**
- No rollback on command error (if SET fails, EXEC still runs remaining commands)
- Use `WATCH` for optimistic locking

```java
// WATCH — abort transaction if key changed by another client
ops.watch("balance:alice");
ops.multi();
ops.opsForValue().decrement("balance:alice", 100);
ops.opsForValue().increment("balance:bob",   100);
List result = ops.exec(); // returns null if WATCH key was modified
```

| | Pipeline | Transaction |
|---|---|---|
| Reduces round-trips | Yes | No (MULTI/EXEC still 2 trips) |
| Atomic | No | Yes |
| Rollback on error | No | No |
| Use case | Bulk inserts/reads | Balance transfers, counters |

---

### Q15. Redis Pub/Sub vs Streams — which do you use and when?

**Answer:**

#### Pub/Sub:
```bash
SUBSCRIBE notifications:user:1    # subscriber waits
PUBLISH  notifications:user:1  "Order shipped"  # publisher sends
```

**Critical limitation:** If subscriber is offline when message is published — **message is lost forever**.

#### Streams — persistent, consumer groups:
```bash
# Producer
XADD orders * orderId 42 status "shipped"

# Consumer group — multiple consumers share the load
XGROUP CREATE orders delivery-team $ MKSTREAM
XREADGROUP GROUP delivery-team worker1 COUNT 10 STREAMS orders >
XACK orders delivery-team <message-id>   # acknowledge processing
```

| | Pub/Sub | Streams |
|---|---|---|
| Message persistence | No | Yes |
| Offline consumers | Messages lost | Messages wait |
| Consumer groups | No | Yes |
| Message replay | No | Yes |
| Use case | Live notifications, chat | Order processing, event sourcing |

---

## 5. Persistence & Durability

---

### Q16. RDB vs AOF — explain the difference and when to use each

**Answer:**

#### RDB (Redis Database) — point-in-time snapshots:
```bash
# redis.conf
save 900 1      # snapshot if 1 key changed in 900s
save 300 10     # snapshot if 10 keys changed in 300s
save 60 10000   # snapshot if 10000 keys changed in 60s
```

- Compact binary file (`.rdb`)
- Fast restarts (load one file)
- **Data loss:** up to the last snapshot interval (minutes)

#### AOF (Append-Only File) — log every write command:
```bash
appendonly yes
appendfsync everysec   # flush to disk every second (balance of safety vs speed)
# appendfsync always   # flush on every write (safest, slowest)
# appendfsync no       # let OS decide (fastest, most data loss risk)
```

- Logs every `SET`, `HSET`, `DEL` etc.
- **Data loss:** at most 1 second (with `everysec`)
- Larger file, slower restart

#### Use both together (recommended for production):
```bash
save 900 1       # RDB for fast restarts
appendonly yes   # AOF for minimal data loss
appendfsync everysec
```

On restart Redis loads RDB first, then replays AOF delta — fast start + minimal data loss.

---

## 6. High Availability & Clustering

---

### Q17. Redis Sentinel vs Redis Cluster — what is the difference?

**Answer:**

#### Redis Sentinel — HA for a single master:
```
         ┌─────────┐
         │Sentinel1│
         └────┬────┘
     ┌────────┴────────┐
     ▼                 ▼
┌────────┐       ┌──────────┐
│ Master │──────▶│ Replica  │
└────────┘       └──────────┘
     │
  (fails)
     │
Sentinel promotes Replica to Master automatically
```

- **One dataset** replicated across nodes
- Sentinel monitors and auto-failovers
- **Not a scale-out solution** — all data fits on one node

#### Redis Cluster — horizontal sharding:
```
Node A (slots 0–5460)    Node B (slots 5461–10922)    Node C (slots 10923–16383)
  Master + Replica          Master + Replica               Master + Replica
```

- Data split across nodes via **16384 hash slots**
- Key → `CRC16(key) % 16384` → node
- Can scale to hundreds of TB
- Auto-rebalances when nodes are added/removed

| | Sentinel | Cluster |
|---|---|---|
| Purpose | HA / failover | Scale-out + HA |
| Data distribution | Single dataset | Sharded across nodes |
| Multi-key ops | Yes | Only if keys on same slot |
| Complexity | Low | High |
| Use when | Data fits on one node | Data too large for one node |

---

### Q18. What is the `CLUSTER KEYSLOT` command and why do hash tags matter?

**Answer:**

In Redis Cluster, multi-key commands (`MGET`, `MSET`, Lua scripts, transactions) fail if keys are on different nodes.

```bash
CLUSTER KEYSLOT product:1   # → slot 5649 → Node A
CLUSTER KEYSLOT product:2   # → slot 5650 → Node B
MGET product:1 product:2    # ERROR — keys on different nodes
```

**Fix — Hash Tags `{}`:**
Content inside `{}` is the only part used for slot calculation.

```bash
SET {product}:1 "data"   # slot = CRC16("product") % 16384 → same node
SET {product}:2 "data"   # slot = CRC16("product") % 16384 → same node

MGET {product}:1 {product}:2   # OK — same node
```

In Spring:
```java
// Both keys will land on the same cluster node
redisTemplate.opsForValue().set("{product}:1", p1);
redisTemplate.opsForValue().set("{product}:2", p2);
```

---

## 7. Performance & Tuning

---

### Q19. What is the danger of `KEYS *` in production and what is the alternative?

**Answer:**

`KEYS *` is O(N) — it scans every key in the keyspace. On a Redis instance with 10 million keys, this blocks the event loop for seconds, making every other client time out.

**Never use `KEYS *` in production.**

**Fix — `SCAN`:**
```bash
SCAN 0 MATCH "product::*" COUNT 100
# returns: [cursor, [matched keys]]
# repeat with returned cursor until cursor == 0
```

```java
// Spring RedisTemplate
ScanOptions options = ScanOptions.scanOptions()
    .match("product::*")
    .count(100)
    .build();

try (Cursor<byte[]> cursor = redisTemplate.getConnectionFactory()
        .getConnection().keyCommands().scan(options)) {
    while (cursor.hasNext()) {
        String key = new String(cursor.next());
        System.out.println(key);
    }
}
```

`SCAN` is O(1) per call — it returns a small batch and a cursor. Non-blocking, spreads work across many calls.

---

### Q20. How do you find and fix memory-hogging keys?

**Answer:**

```bash
# Find biggest keys (runs SCAN internally, safe for production)
redis-cli --bigkeys

# Check memory used by a specific key
MEMORY USAGE product::all

# Check encoding of a key (compact = good)
OBJECT ENCODING product::1
# → "ziplist" (compact) or "hashtable" (uses more memory)

# Check all memory stats
INFO memory
```

**Common fixes:**

| Problem | Fix |
|---|---|
| Large string (serialized blob) | Switch to Hash — store fields individually |
| Hash with many fields | Shard into multiple hashes |
| Sorted Set with millions of members | Archive old entries with `ZREMRANGEBYSCORE` |
| List with millions of items | Cap with `LTRIM list 0 999` after each push |
| No TTL set | Set TTL on all cache keys |

---

## 8. Security

---

### Q21. How do you secure a Redis instance in production?

**Answer:**

```bash
# 1. Require password
requirepass your-strong-password-here

# 2. Bind to private IP only — never expose to internet
bind 127.0.0.1 10.0.0.5

# 3. Disable dangerous commands
rename-command FLUSHALL ""
rename-command FLUSHDB  ""
rename-command CONFIG   ""
rename-command DEBUG    ""
rename-command KEYS     ""

# 4. Enable TLS (Redis 6+)
tls-port 6380
tls-cert-file /etc/redis/tls/redis.crt
tls-key-file  /etc/redis/tls/redis.key

# 5. Use ACL (Redis 6+) — per-user permissions
ACL SETUSER appuser on >apppassword ~product:* +GET +SET +DEL
# appuser can only GET/SET/DEL keys matching "product:*"
```

```properties
# Spring — with password
spring.data.redis.password=your-strong-password-here

# With TLS
spring.data.redis.ssl.enabled=true
```

---

## 9. Spring Boot + Redis Specific

---

### Q22. Why must a cached object implement `Serializable`?

**Answer:**

Spring's default Redis serializer (Java serialization) requires objects to implement `Serializable`. Without it you get `SerializationException` at runtime.

```java
// Required
public class Product implements Serializable {
    private static final long serialVersionUID = 1L;
    // ...
}
```

**Better fix — switch to JSON serialization** (as done in this project's `RedisConfig`):

```java
// With Jackson2JsonRedisSerializer, Serializable is NOT required
// Objects are stored as JSON in Redis, readable via redis-cli
```

JSON serialization is preferred in production because:
- Human-readable keys/values in `redis-cli`
- No `serialVersionUID` compatibility issues across deployments
- Works across different JVM languages

---

### Q23. What happens when Redis is down and your app has `@Cacheable`?

**Answer:**

By default, Spring throws `RedisConnectionFailureException` and the request fails.

**Fix — graceful degradation (fall through to DB on Redis failure):**

```java
@Configuration
public class CacheErrorConfig implements CachingConfigurer {

    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException e, Cache cache, Object key) {
                log.warn("Redis GET failed for key {}, falling through to DB", key, e);
                // swallow — Spring will treat it as a cache miss and call the method
            }

            @Override
            public void handleCachePutError(RuntimeException e, Cache cache, Object key, Object value) {
                log.warn("Redis PUT failed for key {}", key, e);
                // swallow — result was returned to caller, just not cached
            }

            @Override
            public void handleCacheEvictError(RuntimeException e, Cache cache, Object key) {
                log.error("Redis EVICT failed for key {} — possible stale cache", key, e);
            }
        };
    }
}
```

With this handler:
- Redis is down → `@Cacheable` methods still work, just slower (every call hits DB)
- Redis comes back → caching resumes automatically

---

### Q24. How do you test cache behaviour in a Spring Boot integration test?

**Answer:**

```java
@SpringBootTest
class CacheBehaviourTest {

    @Autowired ProductService service;
    @Autowired CacheManager cacheManager;
    @MockBean  ProductRepository repository;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames()
            .forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void secondCallDoesNotHitDatabase() {
        Product p = Product.builder().id(1L).name("Laptop").build();
        when(repository.findById(1L)).thenReturn(Optional.of(p));

        service.getProductById(1L); // cache miss  — DB called
        service.getProductById(1L); // cache hit   — DB NOT called

        // DB was called exactly once despite two service calls
        verify(repository, times(1)).findById(1L);
    }

    @Test
    void deleteEvictsSingleItemFromCache() {
        Product p = Product.builder().id(1L).name("Laptop").build();
        when(repository.findById(1L)).thenReturn(Optional.of(p));
        when(repository.existsById(1L)).thenReturn(true);

        service.getProductById(1L); // populates cache
        service.deleteProduct(1L);  // should evict

        // cache entry should be gone
        Cache.ValueWrapper cached = cacheManager.getCache("product").get(1L);
        assertThat(cached).isNull();
    }
}
```

---

## Quick Reference — Commands Cheat Sheet

```bash
# Inspect
INFO server          # server info
INFO memory          # memory usage
INFO stats           # hits, misses, connections
INFO replication     # master/replica status

MONITOR              # stream every command in real-time (dev only)
SLOWLOG GET 10       # last 10 slow commands

# Keys
KEYS pattern         # NEVER in production
SCAN 0 MATCH * COUNT 100  # safe alternative

TTL  key             # seconds remaining
PTTL key             # milliseconds remaining
TYPE key             # string / hash / list / set / zset / stream

# Memory
MEMORY USAGE key     # bytes used by one key
OBJECT ENCODING key  # internal encoding (ziplist, hashtable, etc.)
redis-cli --bigkeys  # scan for largest keys

# Admin
FLUSHDB              # clear current database (dev only)
FLUSHALL             # clear ALL databases (NEVER in production)
CONFIG SET maxmemory 2gb
CONFIG SET maxmemory-policy allkeys-lru
DEBUG SLEEP 5        # block Redis for 5s (testing only)
```
