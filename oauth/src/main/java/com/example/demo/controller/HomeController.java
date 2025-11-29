package com.example.demo.controller;

import com.example.demo.model.UserToken;
import com.example.demo.repository.UserTokenRepository;
import com.example.demo.service.UserTokenService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.time.Instant;
import java.util.Map;

@Controller
public class HomeController {

    @Autowired
    private UserTokenRepository userTokenRepository;
    @Autowired
    private UserTokenService userTokenService;
    @Autowired
    private OAuth2AuthorizedClientService authorizedClientService;
    @GetMapping("/")
    public String index() {
        return "index";
    }

    @GetMapping("/dashboard")

    public String dashboard(Model model,
                            @AuthenticationPrincipal OidcUser user,
                            Authentication authentication) {

        OAuth2AuthorizedClient client = authorizedClientService
                .loadAuthorizedClient("google", authentication.getName());

        String refreshToken = client.getRefreshToken() != null
                ? client.getRefreshToken().getTokenValue() : null;

        String idToken = user.getIdToken().getTokenValue();
        Instant expiresAt = user.getIdToken().getExpiresAt();

        // Save token logic
        userTokenService.saveOrUpdateToken(user, client);

        // ✅ Extract profile picture safely from attributes
        Map<String, Object> attributes = user.getAttributes();
        String picture = (String) attributes.get("picture");

        model.addAttribute("name", user.getFullName());
        model.addAttribute("email", user.getEmail());
        model.addAttribute("picture", picture);  // ✅ correct field
        return "dashboard";
    }

/*    @GetMapping("/homeController/token")
    @ResponseBody
    public ResponseEntity<Map<String, String>> token(HttpServletRequest request) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication instanceof OAuth2AuthenticationToken oauthToken) {
            OAuth2AuthorizedClient client = authorizedClientService
                    .loadAuthorizedClient(
                            oauthToken.getAuthorizedClientRegistrationId(),
                            oauthToken.getName());

            if (client != null) {
                String tokenValue = client.getAccessToken().getTokenValue();
                return ResponseEntity.ok(Map.of("access_token", tokenValue));
            }
        }

        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("error", "No token found"));
    }*/




}
