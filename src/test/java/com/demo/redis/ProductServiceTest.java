package com.demo.redis;

import com.demo.redis.model.Product;
import com.demo.redis.repository.ProductRepository;
import com.demo.redis.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository repository;

    @InjectMocks
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
    @DisplayName("getProductById — returns product from repository")
    void getProductById_found() {
        when(repository.findById(1L)).thenReturn(Optional.of(laptop));

        Product result = service.getProductById(1L);

        assertThat(result.getName()).isEqualTo("Laptop Pro 15");
        verify(repository, times(1)).findById(1L);
    }

    @Test
    @DisplayName("getProductById — throws ProductNotFoundException when missing")
    void getProductById_notFound() {
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductById(99L))
                .isInstanceOf(ProductService.ProductNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("createProduct — saves and returns the new product")
    void createProduct_saves() {
        Product newProduct = Product.builder()
                .name("New Gadget")
                .category("Electronics")
                .price(new BigDecimal("99.99"))
                .stockQuantity(10)
                .build();

        when(repository.save(newProduct)).thenReturn(newProduct);

        Product saved = service.createProduct(newProduct);

        assertThat(saved.getName()).isEqualTo("New Gadget");
        verify(repository).save(newProduct);
    }

    @Test
    @DisplayName("updateProduct — updates fields and saves")
    void updateProduct_updatesFields() {
        Product update = Product.builder()
                .name("Laptop Pro 16")
                .category("Electronics")
                .price(new BigDecimal("1499.99"))
                .stockQuantity(40)
                .build();

        when(repository.findById(1L)).thenReturn(Optional.of(laptop));
        when(repository.save(any(Product.class))).thenAnswer(inv -> inv.getArgument(0));

        Product result = service.updateProduct(1L, update);

        assertThat(result.getName()).isEqualTo("Laptop Pro 16");
        assertThat(result.getPrice()).isEqualByComparingTo("1499.99");
    }

    @Test
    @DisplayName("deleteProduct — calls repository delete")
    void deleteProduct_deletesExisting() {
        when(repository.existsById(1L)).thenReturn(true);

        service.deleteProduct(1L);

        verify(repository).deleteById(1L);
    }

    @Test
    @DisplayName("deleteProduct — throws when product does not exist")
    void deleteProduct_throwsWhenMissing() {
        when(repository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> service.deleteProduct(99L))
                .isInstanceOf(ProductService.ProductNotFoundException.class);

        verify(repository, never()).deleteById(any());
    }

    @Test
    @DisplayName("getAllProducts — returns full list from repository")
    void getAllProducts_returnsList() {
        when(repository.findAll()).thenReturn(List.of(laptop));

        List<Product> products = service.getAllProducts();

        assertThat(products).hasSize(1);
        verify(repository).findAll();
    }

    @Test
    @DisplayName("getProductsByCategory — filters by category")
    void getProductsByCategory_returnsFiltered() {
        when(repository.findByCategory("Electronics")).thenReturn(List.of(laptop));

        List<Product> result = service.getProductsByCategory("Electronics");

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getCategory()).isEqualTo("Electronics");
    }
}
