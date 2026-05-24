package com.example.demo.controller;

import com.example.demo.service.PlanService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/payment")
public class PaymentController {

    @Value("${razorpay.key.id:rzp_test_placeholder}")
    private String razorpayKeyId;

    @Value("${razorpay.key.secret:placeholder_secret}")
    private String razorpayKeySecret;

    private final PlanService planService;
    private final RestTemplate restTemplate = new RestTemplate();

    public PaymentController(PlanService planService) {
        this.planService = planService;
    }

    @PostMapping("/create-order")
    public ResponseEntity<Map<String, Object>> createOrder(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        int amount = 29900; // Rs 299 in paise
        if (body.containsKey("amount")) {
            amount = ((Number) body.get("amount")).intValue();
        }

        try {
            // Call Razorpay Orders API
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBasicAuth(razorpayKeyId, razorpayKeySecret);

            Map<String, Object> orderRequest = new LinkedHashMap<>();
            orderRequest.put("amount", amount);
            orderRequest.put("currency", "INR");
            orderRequest.put("receipt", "sa_" + System.currentTimeMillis());

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(orderRequest, headers);

            ResponseEntity<Map> response = restTemplate.exchange(
                    "https://api.razorpay.com/v1/orders",
                    HttpMethod.POST,
                    request,
                    Map.class
            );

            Map<String, Object> orderResponse = response.getBody();
            if (orderResponse == null) {
                return ResponseEntity.status(500).body(Map.of("error", "Empty response from Razorpay"));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("orderId", orderResponse.get("id"));
            result.put("amount", orderResponse.get("amount"));
            result.put("currency", orderResponse.get("currency"));
            result.put("razorpayKeyId", razorpayKeyId);
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            System.out.println("Razorpay order creation failed: " + e.getMessage());
            return ResponseEntity.status(500).body(Map.of("error", "Failed to create order: " + e.getMessage()));
        }
    }

    @PostMapping("/verify")
    public ResponseEntity<Map<String, Object>> verifyPayment(
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String orderId = body.get("razorpay_order_id");
        String paymentId = body.get("razorpay_payment_id");
        String signature = body.get("razorpay_signature");

        if (orderId == null || paymentId == null || signature == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Missing payment details"));
        }

        // Verify signature
        String payload = orderId + "|" + paymentId;
        String expectedSignature = hmacSha256(payload, razorpayKeySecret);

        if (!expectedSignature.equals(signature)) {
            System.out.println("Payment signature mismatch! Expected: " + expectedSignature + ", Got: " + signature);
            return ResponseEntity.status(400).body(Map.of("error", "Invalid payment signature"));
        }

        // Signature valid — upgrade user to PRO
        String sub = getSub(authentication);
        String email = getEmail(authentication);

        planService.upgradeToPro(sub, email, paymentId);

        System.out.println("Payment verified for user: " + email + ", paymentId: " + paymentId);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Payment verified. Pro access activated.",
                "paymentId", paymentId
        ));
    }

    private String hmacSha256(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }

    private String getSub(Authentication auth) {
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getSubject();
        }
        return auth.getName();
    }

    private String getEmail(Authentication auth) {
        if (auth.getPrincipal() instanceof Jwt jwt) {
            return jwt.getClaimAsString("email");
        }
        return null;
    }
}
