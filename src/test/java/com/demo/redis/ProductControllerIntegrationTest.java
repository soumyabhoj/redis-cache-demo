package com.demo.redis;

import com.demo.redis.model.Product;
import com.demo.redis.repository.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private ProductRepository repository;

    @Autowired
    private CacheManager cacheManager;

    private Product laptop;

    @BeforeEach
    void setUp() {
        laptop = Product.builder()
                .id(1L)
                .name("Laptop Pro 15")
                .category("Electronics")
                .price(new BigDecimal("1299.99"))
                .stockQuantity(50)
                .build();

        // Clear caches between tests for a clean slate
        cacheManager.getCacheNames().forEach(name -> {
            var cache = cacheManager.getCache(name);
            if (cache != null) cache.clear();
        });
    }

    @Test
    @DisplayName("GET /api/products — returns 200 with product list")
    void getAllProducts_returns200() throws Exception {
        when(repository.findAll()).thenReturn(List.of(laptop));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Laptop Pro 15"));
    }

    @Test
    @DisplayName("GET /api/products/{id} — returns 200 with single product")
    void getById_returns200() throws Exception {
        when(repository.findById(1L)).thenReturn(Optional.of(laptop));

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Pro 15"))
                .andExpect(jsonPath("$.price").value(1299.99));
    }

    @Test
    @DisplayName("GET /api/products/{id} — returns 404 when not found")
    void getById_returns404() throws Exception {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("POST /api/products — creates product and returns 201")
    void createProduct_returns201() throws Exception {
        Product newProduct = Product.builder()
                .name("Smart Watch")
                .category("Electronics")
                .price(new BigDecimal("299.99"))
                .stockQuantity(75)
                .build();

        when(repository.save(any(Product.class))).thenReturn(
                Product.builder().id(9L).name("Smart Watch").category("Electronics")
                        .price(new BigDecimal("299.99")).stockQuantity(75).build()
        );

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(newProduct)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.name").value("Smart Watch"));
    }

    @Test
    @DisplayName("DELETE /api/products/{id} — returns 204 on success")
    void deleteProduct_returns204() throws Exception {
        when(repository.existsById(1L)).thenReturn(true);

        mockMvc.perform(delete("/api/products/1"))
                .andExpect(status().isNoContent());

        verify(repository).deleteById(1L);
    }
}
