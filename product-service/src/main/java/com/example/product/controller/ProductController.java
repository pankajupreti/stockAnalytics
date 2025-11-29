package com.example.product.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final Map<String, String> productStore = new ConcurrentHashMap<>();

    @GetMapping
    public List<String> getAllProducts(@AuthenticationPrincipal Jwt jwt) {
        return productStore.values().stream().toList();
    }

    @PostMapping
    public String addProduct(@RequestBody String name, @AuthenticationPrincipal Jwt jwt) {
        String id = "prod-" + System.currentTimeMillis();
        productStore.put(id, name);
        return "Added product with ID: " + id;
    }

    @DeleteMapping("/{id}")
    public String deleteProduct(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        productStore.remove(id);
        return "Deleted product with ID: " + id;
    }
}