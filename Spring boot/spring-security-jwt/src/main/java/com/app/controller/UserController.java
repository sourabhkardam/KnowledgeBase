package com.app.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    /**
     * GET /api/users/me
     * Accessible by any authenticated user (USER or ADMIN).
     * @AuthenticationPrincipal injects the currently logged-in user.
     */
    @GetMapping("/me")
    public ResponseEntity<String> getCurrentUser(
            @AuthenticationPrincipal UserDetails userDetails) {
        return ResponseEntity.ok(
            "Hello, " + userDetails.getUsername() +
            " | Roles: " + userDetails.getAuthorities()
        );
    }

    /**
     * GET /api/users/all
     * Accessible only by ADMIN role.
     * Requires @EnableMethodSecurity in SecurityConfig.
     */
    @GetMapping("/all")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<String> getAllUsers() {
        return ResponseEntity.ok("List of all users — admin only endpoint");
    }

    /**
     * GET /api/admin/dashboard
     * Another admin-only example via URL pattern (configured in SecurityConfig).
     */
    @GetMapping("/admin/dashboard")
    public ResponseEntity<String> adminDashboard() {
        return ResponseEntity.ok("Admin dashboard — secured by SecurityConfig URL rule");
    }
}
