package com.example.demo.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "user_plans")
public class UserPlan {

    @Id
    @Column(name = "user_sub", nullable = false, unique = true)
    private String userSub;

    private String email;

    @Column(nullable = false)
    private String plan; // FREE or PRO

    @Column(name = "expires_at")
    private LocalDate expiresAt;

    @Column(name = "granted_by")
    private String grantedBy; // admin email who granted free Pro

    @Column(name = "razorpay_payment_id")
    private String razorpayPaymentId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    public UserPlan() {}

    public UserPlan(String userSub, String email, String plan) {
        this.userSub = userSub;
        this.email = email;
        this.plan = plan;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
        if (updatedAt == null) updatedAt = Instant.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = Instant.now();
    }

    // Getters and setters
    public String getUserSub() { return userSub; }
    public void setUserSub(String userSub) { this.userSub = userSub; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPlan() { return plan; }
    public void setPlan(String plan) { this.plan = plan; }

    public LocalDate getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDate expiresAt) { this.expiresAt = expiresAt; }

    public String getGrantedBy() { return grantedBy; }
    public void setGrantedBy(String grantedBy) { this.grantedBy = grantedBy; }

    public String getRazorpayPaymentId() { return razorpayPaymentId; }
    public void setRazorpayPaymentId(String razorpayPaymentId) { this.razorpayPaymentId = razorpayPaymentId; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
