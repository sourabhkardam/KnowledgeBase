package com.app.service;

import com.app.dto.AuthResponse;
import com.app.dto.LoginRequest;
import com.app.dto.RegisterRequest;
import com.app.entity.Role;
import com.app.entity.User;
import com.app.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationManager authenticationManager;

    // ── Register ──────────────────────────────────────────────────

    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already in use: " + request.getEmail());
        }

        User user = User.builder()
                .firstName(request.getFirstName())
                .lastName(request.getLastName())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword())) // BCrypt hash
                .role(Role.USER)
                .build();

        userRepository.save(user);

        String accessToken  = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }

    // ── Login ─────────────────────────────────────────────────────

    public AuthResponse login(LoginRequest request) {
        // Spring Security verifies credentials against DB — throws if invalid
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        request.getEmail(),
                        request.getPassword()
                )
        );

        // Credentials valid — load user and generate tokens
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow();

        String accessToken  = jwtService.generateToken(user);
        String refreshToken = jwtService.generateRefreshToken(user);

        return AuthResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .email(user.getEmail())
                .role(user.getRole().name())
                .build();
    }
}
