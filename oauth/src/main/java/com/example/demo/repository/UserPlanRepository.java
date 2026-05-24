package com.example.demo.repository;

import com.example.demo.model.UserPlan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserPlanRepository extends JpaRepository<UserPlan, String> {
    Optional<UserPlan> findByUserSub(String userSub);
    Optional<UserPlan> findByEmail(String email);
}
