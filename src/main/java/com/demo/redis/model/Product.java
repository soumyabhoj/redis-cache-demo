package com.demo.redis.model;

import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import lombok.*;

import java.io.Serializable;
import java.math.BigDecimal;

@Entity
@Table(name = "product")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Product implements Serializable {

    // Serializable is required so Redis can serialize/deserialize the object.
    private static final long serialVersionUID = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String name;

    @NotBlank
    @Size(max = 50)
    private String category;

    @NotNull
    @DecimalMin("0.0")
    @Column(precision = 10, scale = 2)
    private BigDecimal price;

    @Min(0)
    @Column(name = "stock_quantity")
    private int stockQuantity;
}
