package com.example.demo.controller;

import com.example.demo.model.UserPlan;
import com.example.demo.model.UserToken;
import com.example.demo.repository.UserTokenRepository;
import com.example.demo.service.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@RestController
public class PlanController {

    private final PlanService planService;
    private final UserTokenRepository userTokenRepo;

    public PlanController(PlanService planService, UserTokenRepository userTokenRepo) {
        this.planService = planService;
        this.userTokenRepo = userTokenRepo;
    }

    @GetMapping("/api/user/plan")
    public ResponseEntity<Map<String, Object>> getUserPlan(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String sub = getSub(authentication);
        String email = getEmail(authentication);

        UserPlan plan = planService.getUserPlan(sub, email);
        boolean isAdmin = planService.isAdmin(email);
        List<String> features = planService.getFeatures(sub, email);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("plan", isAdmin ? "ADMIN" : plan.getPlan());
        result.put("features", features);
        result.put("isAdmin", isAdmin);
        result.put("email", email);
        if (plan.getExpiresAt() != null) {
            result.put("expiresAt", plan.getExpiresAt().toString());
        }
        if (plan.getGrantedBy() != null) {
            result.put("grantedBy", plan.getGrantedBy());
        }
        result.put("freeForAll", planService.isFreeForAll());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/admin/free-for-all")
    public ResponseEntity<Map<String, Object>> toggleFreeForAll(
            @RequestBody Map<String, Object> body,
            Authentication authentication) {

        String adminEmail = getEmail(authentication);
        if (!planService.isAdmin(adminEmail)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        Object enabledObj = body.get("enabled");
        if (enabledObj == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "enabled field is required"));
        }

        boolean enabled = Boolean.parseBoolean(enabledObj.toString());
        planService.setFreeForAll(enabled);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "freeForAll", enabled
        ));
    }

    @GetMapping("/api/admin/free-for-all-status")
    public ResponseEntity<Map<String, Object>> getFreeForAllStatus(Authentication authentication) {
        String adminEmail = getEmail(authentication);
        if (!planService.isAdmin(adminEmail)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        return ResponseEntity.ok(Map.of("freeForAll", planService.isFreeForAll()));
    }

    @PostMapping("/api/admin/grant-pro")
    public ResponseEntity<Map<String, Object>> grantPro(
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        String adminEmail = getEmail(authentication);
        if (!planService.isAdmin(adminEmail)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        String targetSub = body.get("userSub");
        String targetEmail = body.get("email");
        String expiresStr = body.get("expiresAt"); // optional, e.g. "2026-12-31"

        if (targetSub == null || targetSub.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userSub is required"));
        }

        LocalDate expiresAt = null;
        if (expiresStr != null && !expiresStr.isEmpty()) {
            expiresAt = LocalDate.parse(expiresStr);
        }

        UserPlan updated = planService.grantPro(targetSub, targetEmail, adminEmail, expiresAt);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "success");
        result.put("userSub", updated.getUserSub());
        result.put("plan", updated.getPlan());
        if (updated.getExpiresAt() != null) result.put("expiresAt", updated.getExpiresAt().toString());
        return ResponseEntity.ok(result);
    }

    @PostMapping("/api/admin/revoke-pro")
    public ResponseEntity<Map<String, Object>> revokePro(
            @RequestBody Map<String, String> body,
            Authentication authentication) {

        String adminEmail = getEmail(authentication);
        if (!planService.isAdmin(adminEmail)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        String targetSub = body.get("userSub");
        if (targetSub == null || targetSub.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "userSub is required"));
        }

        UserPlan updated = planService.revokePro(targetSub);

        return ResponseEntity.ok(Map.of(
                "status", "success",
                "userSub", updated.getUserSub(),
                "plan", updated.getPlan()
        ));
    }

    @GetMapping("/api/admin/users")
    public ResponseEntity<?> getAllUsers(Authentication authentication) {
        String adminEmail = getEmail(authentication);
        if (!planService.isAdmin(adminEmail)) {
            return ResponseEntity.status(403).body(Map.of("error", "Admin access required"));
        }

        // Merge user_token data with plan data
        List<UserToken> allTokenUsers = userTokenRepo.findAll();
        List<UserPlan> allPlans = planService.getAllPlans();

        Map<String, UserPlan> planMap = allPlans.stream()
                .collect(Collectors.toMap(UserPlan::getUserSub, p -> p, (a, b) -> a));

        List<Map<String, Object>> users = new ArrayList<>();
        for (UserToken ut : allTokenUsers) {
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("userSub", ut.getSub());
            user.put("email", ut.getEmail());
            user.put("name", ut.getName());
            user.put("lastActivity", ut.getLastActivity() != null ? ut.getLastActivity().toString() : null);

            UserPlan plan = planMap.get(ut.getSub());
            if (plan != null) {
                user.put("plan", plan.getPlan());
                user.put("expiresAt", plan.getExpiresAt() != null ? plan.getExpiresAt().toString() : null);
                user.put("grantedBy", plan.getGrantedBy());
            } else {
                user.put("plan", "FREE");
                user.put("expiresAt", null);
                user.put("grantedBy", null);
            }

            user.put("isAdmin", planService.isAdmin(ut.getEmail()));
            users.add(user);
        }

        return ResponseEntity.ok(users);
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
