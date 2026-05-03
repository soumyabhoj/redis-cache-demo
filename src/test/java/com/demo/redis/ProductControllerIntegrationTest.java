package com.demo.redis;

import com.demo.redis.controller.ProductController;
import com.demo.redis.model.Product;
import com.demo.redis.service.ProductService;
import com.demo.redis.service.ProductService.ProductNotFoundException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// @WebMvcTest loads ONLY the web layer (controller + MockMvc).
// No Redis, no JPA, no full Spring context — tests run without any infrastructure.
@WebMvcTest(ProductController.class)
class ProductControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // Mock the service — controller tests verify HTTP behaviour, not business logic
    @MockBean
    private ProductService service;

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
    }

    @Test
    @DisplayName("GET /api/products — returns 200 with product list")
    void getAllProducts_returns200() throws Exception {
        when(service.getAllProducts()).thenReturn(List.of(laptop));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("Laptop Pro 15"));
    }

    @Test
    @DisplayName("GET /api/products/{id} — returns 200 with single product")
    void getById_returns200() throws Exception {
        when(service.getProductById(1L)).thenReturn(laptop);

        mockMvc.perform(get("/api/products/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Pro 15"))
                .andExpect(jsonPath("$.price").value(1299.99));
    }

    @Test
    @DisplayName("GET /api/products/{id} — returns 404 when not found")
    void getById_returns404() throws Exception {
        when(service.getProductById(99L)).thenThrow(new ProductNotFoundException(99L));

        mockMvc.perform(get("/api/products/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    @DisplayName("GET /api/products/category/{cat} — returns 200 with filtered list")
    void getByCategory_returns200() throws Exception {
        when(service.getProductsByCategory("Electronics")).thenReturn(List.of(laptop));

        mockMvc.perform(get("/api/products/category/Electronics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].category").value("Electronics"));
    }

    @Test
    @DisplayName("POST /api/products — creates product and returns 201")
    void createProduct_returns201() throws Exception {
        Product created = Product.builder()
                .id(9L).name("Smart Watch").category("Electronics")
                .price(new BigDecimal("299.99")).stockQuantity(75)
                .build();

        when(service.createProduct(any(Product.class))).thenReturn(created);

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(created)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(9))
                .andExpect(jsonPath("$.name").value("Smart Watch"));
    }

    @Test
    @DisplayName("PUT /api/products/{id} — returns 200 with updated product")
    void updateProduct_returns200() throws Exception {
        Product updated = Product.builder()
                .id(1L).name("Laptop Pro 16").category("Electronics")
                .price(new BigDecimal("1499.99")).stockQuantity(40)
                .build();

        when(service.updateProduct(eq(1L), any(Product.class))).thenReturn(updated);

        mockMvc.perform(put("/api/products/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updated)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Laptop Pro 16"));
    }

    @Test
    @DisplayName("DELETE /api/products/{id} — returns 204 on success")
    void deleteProduct_returns204() throws Exception {
        doNothing().when(service).deleteProduct(1L);

        mockMvc.perform(delete("/api/products/1"))
                .andExpect(status().isNoContent());

        verify(service).deleteProduct(1L);
    }

    @Test
    @DisplayName("DELETE /api/products/{id} — returns 404 when not found")
    void deleteProduct_returns404() throws Exception {
        doThrow(new ProductNotFoundException(99L)).when(service).deleteProduct(99L);

        mockMvc.perform(delete("/api/products/99"))
                .andExpect(status().isNotFound());
    }
}
