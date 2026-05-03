package com.demo.redis.service;

import com.demo.redis.model.Product;
import com.demo.redis.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.*;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProductService {

    private final ProductRepository repository;

    /**
     * @Cacheable — on cache hit, the method body is skipped entirely.
     * Key: "products::all" (Spring prefix + SpEL key).
     * Observe the DB log: on first call SQL fires; on subsequent calls it is silent.
     */
    @Cacheable(value = "products", key = "'all'")
    public List<Product> getAllProducts() {
        simulateSlowDbCall();
        log.debug("CACHE MISS — fetching ALL products from database");
        return repository.findAll();
    }

    /**
     * Cache a single product by its ID.
     * Key: "product::<id>"
     */
    @Cacheable(value = "product", key = "#id")
    public Product getProductById(Long id) {
        simulateSlowDbCall();
        log.debug("CACHE MISS — fetching product {} from database", id);
        return repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
    }

    /**
     * Cache products filtered by category.
     * Key: "products::<category>"
     */
    @Cacheable(value = "products", key = "#category")
    public List<Product> getProductsByCategory(String category) {
        simulateSlowDbCall();
        log.debug("CACHE MISS — fetching products for category '{}' from database", category);
        return repository.findByCategory(category);
    }

    /**
     * @CachePut — always executes the method AND updates the cache entry.
     * Use for create/update so the cache stays consistent without an evict-then-fetch round-trip.
     */
    @CachePut(value = "product", key = "#result.id")
    @CacheEvict(value = "products", allEntries = true)
    public Product createProduct(Product product) {
        log.debug("Creating product and warming cache");
        return repository.save(product);
    }

    /**
     * @CachePut keeps the single-item cache warm after an update.
     * @CacheEvict removes the list caches because their content changed.
     */
    @Caching(
        put    = { @CachePut(value = "product", key = "#id") },
        evict  = { @CacheEvict(value = "products", allEntries = true) }
    )
    public Product updateProduct(Long id, Product updated) {
        Product existing = repository.findById(id)
                .orElseThrow(() -> new ProductNotFoundException(id));
        existing.setName(updated.getName());
        existing.setCategory(updated.getCategory());
        existing.setPrice(updated.getPrice());
        existing.setStockQuantity(updated.getStockQuantity());
        log.debug("Updating product {} and refreshing cache", id);
        return repository.save(existing);
    }

    /**
     * @CacheEvict removes the entry. allEntries=true on the list cache clears
     * every key so stale list results are not served after deletion.
     */
    @Caching(evict = {
        @CacheEvict(value = "product",   key = "#id"),
        @CacheEvict(value = "products",  allEntries = true)
    })
    public void deleteProduct(Long id) {
        if (!repository.existsById(id)) {
            throw new ProductNotFoundException(id);
        }
        log.debug("Deleting product {} and evicting cache entries", id);
        repository.deleteById(id);
    }

    // Simulates a slow database query so cache benefits are obvious in logs/timing.
    private void simulateSlowDbCall() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public static class ProductNotFoundException extends RuntimeException {
        public ProductNotFoundException(Long id) {
            super("Product not found with id: " + id);
        }
    }
}
