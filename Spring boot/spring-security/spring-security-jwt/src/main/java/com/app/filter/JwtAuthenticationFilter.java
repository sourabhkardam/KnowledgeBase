package com.app.filter;

import com.app.service.JwtService;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService; // injects UserDetailsServiceImpl

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        // 1. Extract Authorization header
        final String authHeader = request.getHeader("Authorization");

        // 2. Skip filter if no Bearer token present
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Extract JWT (remove "Bearer " prefix)
        final String jwt = authHeader.substring(7);
        final String userEmail;

        try {
            userEmail = jwtService.extractUsername(jwt);
        } catch (JwtException e) {
            // Malformed or tampered token
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid JWT token");
            return;
        }

        // 4. Validate and set authentication if not already set
        if (userEmail != null &&
                SecurityContextHolder.getContext().getAuthentication() == null) {

            // 5. Load user from DB
            UserDetails userDetails = userDetailsService.loadUserByUsername(userEmail);

            // 6. Validate token signature and expiry
            if (jwtService.isTokenValid(jwt, userDetails)) {

                // 7. Build authentication token
                UsernamePasswordAuthenticationToken authToken =
                        new UsernamePasswordAuthenticationToken(
                                userDetails,
                                null,
                                userDetails.getAuthorities()
                        );

                // 8. Attach request metadata
                authToken.setDetails(
                        new WebAuthenticationDetailsSource().buildDetails(request)
                );

                // 9. Register authentication in SecurityContext
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // 10. Continue down the filter chain
        filterChain.doFilter(request, response);
    }
}
