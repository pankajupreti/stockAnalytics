package com.example.demo.listener;


import com.example.demo.model.User;
import com.example.demo.repository.UserRepository;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.stereotype.Component;

@Component
public class UserProvisioningListener {

    private final UserRepository userRepository;

    public UserProvisioningListener(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @EventListener
    public void onAuthenticationSuccess(AuthenticationSuccessEvent event) {
        if (event.getAuthentication() instanceof OAuth2AuthenticationToken oauthToken) {
            OAuth2User principal = oauthToken.getPrincipal();

            String sub = principal.getName();
            String email = principal.getAttribute("email");
            String name = principal.getAttribute("name");

            userRepository.findBySub(sub).ifPresentOrElse(existing -> {
                existing.setEmail(email);
                existing.setName(name);
                userRepository.save(existing);
            }, () -> {
                User newUser = new User();
                newUser.setSub(sub);
                newUser.setEmail(email);
                newUser.setName(name);
                newUser.setStatus("ACTIVE");
                userRepository.save(newUser);
            });
        }
    }
}
