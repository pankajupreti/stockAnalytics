package com.example.demo.service;

import com.example.demo.model.UserPlan;
import com.example.demo.repository.UserPlanRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Service
public class PlanService {

    private static final List<String> ADMIN_EMAILS = List.of("panky070@gmail.com");

    private static final List<String> FREE_FEATURES = List.of(
            "dashboard", "marketbreadth", "portfolio", "52w-breakouts"
    );

    private static final List<String> PRO_FEATURES = List.of(
            "dashboard", "marketbreadth", "portfolio", "52w-breakouts",
            "portfolio-analytics", "pead-scanner", "alerts", "results-analysis",
            "good-results", "pnl-report", "announcements"
    );

    private final UserPlanRepository planRepo;

    @Value("${app.free-for-all:false}")
    private boolean freeForAll;

    public PlanService(UserPlanRepository planRepo) {
        this.planRepo = planRepo;
    }

    public boolean isFreeForAll() {
        return freeForAll;
    }

    public void setFreeForAll(boolean enabled) {
        this.freeForAll = enabled;
    }

    public boolean isAdmin(String email) {
        return email != null && ADMIN_EMAILS.contains(email.toLowerCase());
    }

    public UserPlan getUserPlan(String userSub, String email) {
        Optional<UserPlan> existing = planRepo.findByUserSub(userSub);
        if (existing.isPresent()) {
            return existing.get();
        }
        // Auto-create FREE plan for new users
        UserPlan plan = new UserPlan(userSub, email, "FREE");
        return planRepo.save(plan);
    }

    public boolean isProUser(String userSub, String email) {
        if (freeForAll) return true;
        if (isAdmin(email)) return true;

        UserPlan plan = getUserPlan(userSub, email);
        if (!"PRO".equals(plan.getPlan())) return false;

        // Check expiry
        if (plan.getExpiresAt() != null && plan.getExpiresAt().isBefore(LocalDate.now())) {
            // Expired — downgrade to FREE
            plan.setPlan("FREE");
            plan.setExpiresAt(null);
            plan.setRazorpayPaymentId(null);
            planRepo.save(plan);
            return false;
        }
        return true;
    }

    public List<String> getFeatures(String userSub, String email) {
        if (freeForAll || isAdmin(email) || isProUser(userSub, email)) {
            return PRO_FEATURES;
        }
        return FREE_FEATURES;
    }

    public UserPlan grantPro(String targetUserSub, String targetEmail, String grantedBy, LocalDate expiresAt) {
        UserPlan plan = getUserPlan(targetUserSub, targetEmail);
        plan.setPlan("PRO");
        plan.setGrantedBy(grantedBy);
        plan.setExpiresAt(expiresAt);
        return planRepo.save(plan);
    }

    public UserPlan revokePro(String targetUserSub) {
        Optional<UserPlan> opt = planRepo.findByUserSub(targetUserSub);
        if (opt.isEmpty()) {
            throw new RuntimeException("User not found: " + targetUserSub);
        }
        UserPlan plan = opt.get();
        plan.setPlan("FREE");
        plan.setExpiresAt(null);
        plan.setGrantedBy(null);
        plan.setRazorpayPaymentId(null);
        return planRepo.save(plan);
    }

    public UserPlan upgradeToPro(String userSub, String email, String razorpayPaymentId) {
        UserPlan plan = getUserPlan(userSub, email);
        plan.setPlan("PRO");
        plan.setRazorpayPaymentId(razorpayPaymentId);
        plan.setExpiresAt(LocalDate.now().plusMonths(1));
        return planRepo.save(plan);
    }

    public List<UserPlan> getAllPlans() {
        return planRepo.findAll();
    }
}
