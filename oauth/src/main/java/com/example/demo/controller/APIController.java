package com.example.demo.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class APIController {

    @GetMapping("/hello")
    public String sayHello() {
        return "Hello, authenticated user!";
    }

    @GetMapping("/userinfo")
    public Map<String, Object> userInfo(@AuthenticationPrincipal Jwt jwt) {
        Map<String, Object> details = new HashMap<>();
        details.put("email", jwt.getClaimAsString("email"));
        details.put("subject", jwt.getSubject());
        details.put("name", jwt.getClaimAsString("name"));
        return details;
    }


}
