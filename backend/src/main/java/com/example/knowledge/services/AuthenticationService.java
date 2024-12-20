package com.example.knowledge.services;

// Copyright 2024 Ali Bouali
// Licensed under the Apache License, Version 2.0 (the "License");
// You may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// Modified by Katarzyna Badio on 27.10.2024, 2.11.2024


import com.example.knowledge.models.*;
import com.example.knowledge.repositories.PasswordTokenRepository;
import com.example.knowledge.repositories.RoleRepository;
import com.example.knowledge.repositories.TokenRepository;
import com.example.knowledge.repositories.UserRepository;
import com.example.knowledge.requests.AuthenticationRequest;
import com.example.knowledge.requests.RegistrationRequest;
import com.example.knowledge.responses.AuthenticationResponse;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthenticationService {

    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final EmailService emailService;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private String activationUrl = "http://localhost:8099/activate-account";

    private final PasswordTokenRepository passwordTokenRepository;

    public void register(RegistrationRequest request) throws Exception {
        var userRole = roleRepository.findByName("USER")
                .orElseThrow(() -> new IllegalStateException("ROLE USER was not initialized"));

        String encodedPassword = passwordEncoder.encode(request.getPassword());
        var user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(encodedPassword)
                .accountLocked(false)
                .enabled(false)
                .roles(List.of(userRole))
                .build();

        try {
            userRepository.save(user);
        } catch (Exception e){
            throw new Exception("Error saving new user: " + user.getIdUser(), e);
        }
        sendValidationEmail(user);
    }

    private void sendValidationEmail(User user) throws Exception {
        var newToken = generateAndSaveActivationToken(user);

        emailService.sendEmail(
                user.getEmail(),
                user.fullName(),
                EmailTemplateName.ACTIVATE_ACCOUNT,
                activationUrl,
                newToken,
                "Account activation"

        );
    }

    private String generateAndSaveActivationToken(User user) throws Exception {
        // generate a token and save it in a database
        String generatedToken = generateActivationCode(6);
        log.info("Service: token {}", generatedToken);
        var token = Token.builder()
                .token(generatedToken)
                .createdAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusMinutes(15))
                .user(user)
                .build();

        try {
            tokenRepository.save(token);
        } catch (Exception e){
            throw new Exception("Error saving new token", e);
        }
        return generatedToken;
    }

    // generate activation code for email
    private String generateActivationCode(int length){
        String characters = "0123456789";
        StringBuilder codeBuilder = new StringBuilder();
        SecureRandom secureRandom = new SecureRandom();
        for (int i = 0; i < length; i++){
            int randomIndex = secureRandom.nextInt(characters.length()); // 0 .. 9
            codeBuilder.append(characters.charAt(randomIndex));
        }
        return codeBuilder.toString();
    }

    public AuthenticationResponse authenticate(AuthenticationRequest request){
        var auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );
        var claims = new HashMap<String, Object>();
        var user = ((User) auth.getPrincipal());
        claims.put("fullName", user.fullName());
        var jwtToken = jwtService.generateToken(claims, user);
        return AuthenticationResponse.builder().token(jwtToken).build();
    }

    public void activateAccount(String token) throws Exception {
        Token savedToken = tokenRepository.findByToken(token)
                .orElseThrow(() -> new RuntimeException("Invalid token"));

        if (LocalDateTime.now().isAfter(savedToken.getExpiresAt())){
            sendValidationEmail(savedToken.getUser());
            throw new RuntimeException("Activation token has expired." +
                    "A new token has been sent to the same email address");
        }
        var user = userRepository.findById(savedToken.getUser().getIdUser())
                .orElseThrow(
                        () -> new UsernameNotFoundException("User not found")
                );
        user.setEnabled(true);
        userRepository.save(user);
        savedToken.setValidatedAt(LocalDateTime.now());
        tokenRepository.save(savedToken);
    }

    public String validatePasswordResetToken(String token){
        final PasswordResetToken passToken = passwordTokenRepository.findByToken(token);
        System.out.println("Service: Retrieved Password Reset Token: " + (passToken != null ? passToken.getToken() : "null"));

        return !isTokenFound(passToken) ? "invalidToken"
                : isTokenExpired(passToken) ? "expired"
                : null;
    }

    private boolean isTokenFound(PasswordResetToken passToken) {
        return passToken != null;
    }

    private boolean isTokenExpired(PasswordResetToken passToken) {
        final Calendar cal = Calendar.getInstance();
        return passToken.getExpiryDate().before(cal.getTime());
    }
}
