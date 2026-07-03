package com.app.config;

import com.app.filter.JwtAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity                   // enables @PreAuthorize on controller methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthFilter;
    private final AuthenticationProvider authenticationProvider;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF — stateless JWT APIs don't need it
            .csrf(AbstractHttpConfigurer::disable)

            // Define public and protected routes
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()        // public endpoints
                .requestMatchers("/api/admin/**").hasRole("ADMIN")  // admin only
                .anyRequest().authenticated()                       // all others need auth
            )

            // Stateless session — Spring will never create an HttpSession
            .sessionManagement(session -> session
                .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )

            // Use our DaoAuthenticationProvider
            .authenticationProvider(authenticationProvider)

            // Run JWT filter before Spring's username/password filter
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
