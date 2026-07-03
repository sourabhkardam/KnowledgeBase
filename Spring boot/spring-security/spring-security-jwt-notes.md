# Spring Security — JWT-Based Authentication & Authorization (Reference Notes)

## Table of Contents
1. [Why JWT Differs From Basic Auth](#1-why-jwt-differs-from-basic-auth)
2. [Dependencies](#2-dependencies)
3. [Token Generation & Validation Utility](#3-token-generation--validation-utility)
4. [Login Endpoint (Issues the Token)](#4-login-endpoint-issues-the-token)
5. [Custom JWT Filter (Validates Token Per Request)](#5-custom-jwt-filter-validates-token-per-request)
6. [Security Config (Wiring It Together)](#6-security-config-wiring-it-together)
7. [How Spring Decides Basic Auth vs JWT](#7-how-spring-decides-basic-auth-vs-jwt)
8. [Two Filter Implementation Styles — Comparison](#8-two-filter-implementation-styles--comparison)
9. [Where Role Authorization Actually Happens](#9-where-role-authorization-actually-happens)
10. [Testing via Postman / curl](#10-testing-via-postman--curl)
11. [Encoding vs Signing — JWT's Version of the Basic Auth Distinction](#11-encoding-vs-signing--jwts-version-of-the-basic-auth-distinction)
12. [Full Request Lifecycle Diagram](#12-full-request-lifecycle-diagram)
13. [Interview-Ready Summary Points](#13-interview-ready-summary-points)

---

## 1. Why JWT Differs From Basic Auth

Basic Auth sends `username:password` (Base64-encoded) on **every single request**, and `DaoAuthenticationProvider` re-validates against `UserDetailsService` each time.

JWT flips this: you authenticate **once** (`/login`), get back a signed token, then send that token in `Authorization: Bearer <token>` on subsequent requests. The server validates the token's **signature and expiry** — no DB/`UserDetailsService` lookup needed per request (unless you deliberately choose to add one — see [Section 8](#8-two-filter-implementation-styles--comparison)).

| | Basic Auth | JWT |
|---|---|---|
| Sent per request | Base64(username:password) | Signed token |
| Server work per request | DB/memory lookup + password match | Signature verification (+ optional DB lookup) |
| Statefulness | Stateless by nature, but Spring's default session is stateful unless disabled | Explicitly stateless |
| Credentials exposed | Every request | Only once, at login |
| Expiry | N/A | Built-in (`exp` claim) |

---

## 2. Dependencies

```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.6</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.6</version>
    <scope>runtime</scope>
</dependency>
```

(`spring-boot-starter-security` and `spring-boot-starter-web` as usual.)

---

## 3. Token Generation & Validation Utility

```java
@Component
public class JwtUtil {

    // In production: load from application.properties / env var, never hardcode
    private final SecretKey key = Jwts.SIG.HS256.key().build();
    private final long expirationMs = 3600000; // 1 hour

    public String generateToken(String username, String role) {
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractRole(String token) {
        return parseClaims(token).get("role", String.class);
    }

    public boolean isTokenValid(String token) {
        try {
            parseClaims(token);
            return true; // parsing succeeds only if signature valid & not expired
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

This mirrors `PasswordEncoder`'s role from Basic Auth — `generateToken`/`isTokenValid` are the JWT equivalents of `encode`/`matches`.

---

## 4. Login Endpoint (Issues the Token)

```java
public record LoginRequest(String username, String password) {}
public record LoginResponse(String token) {}

@RestController
public class AuthController {

    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final UserDetailsService userDetailsService;

    public AuthController(AuthenticationManager authenticationManager,
                           JwtUtil jwtUtil,
                           UserDetailsService userDetailsService) {
        this.authenticationManager = authenticationManager;
        this.jwtUtil = jwtUtil;
        this.userDetailsService = userDetailsService;
    }

    @PostMapping("/login")
    public LoginResponse login(@RequestBody LoginRequest request) {
        // Reuses the SAME AuthenticationManager/UserDetailsService/PasswordEncoder
        // pipeline as Basic Auth — this line does the credential check.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password())
        );

        UserDetails user = userDetailsService.loadUserByUsername(request.username());
        String role = user.getAuthorities().iterator().next().getAuthority(); // e.g. ROLE_ADMIN
        String token = jwtUtil.generateToken(user.getUsername(), role);

        return new LoginResponse(token);
    }
}
```

**Key insight:** `/login` still uses the exact same `UserDetailsService` + `PasswordEncoder` machinery already built for Basic Auth/DB approach. JWT doesn't replace that — it sits **on top of it**, only at the login step. Everything after login uses the token instead.

`AuthenticationManager` needs to be exposed as a bean (Spring doesn't expose it by default):

```java
@Bean
public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
}
```

---

## 5. Custom JWT Filter (Validates Token Per Request)

This is the JWT equivalent of `BasicAuthenticationFilter` — except you write it yourself, since Spring Security doesn't ship one for JWT out of the box.

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                      HttpServletResponse response,
                                      FilterChain filterChain) throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);

            if (jwtUtil.isTokenValid(token)) {
                String username = jwtUtil.extractUsername(token);
                String role = jwtUtil.extractRole(token);

                var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));

                var authToken = new UsernamePasswordAuthenticationToken(username, null, authorities);
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
            // if invalid, we simply don't set authentication — request proceeds unauthenticated
            // and gets rejected downstream by the authorization filter
        }

        filterChain.doFilter(request, response);
    }
}
```

Notice: no `UserDetailsService` call here at all. The token itself carries username + role (it's self-contained) — that's the whole point of JWT being stateless. (See [Section 8](#8-two-filter-implementation-styles--comparison) for the alternative, more production-common style.)

---

## 6. Security Config (Wiring It Together)

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login").permitAll()
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/user/**").hasAnyRole("ADMIN", "USER")
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
```

Three things that are **new/different** compared to Basic Auth config:

1. **`SessionCreationPolicy.STATELESS`** — tells Spring not to create/use an `HttpSession`. Every request must carry its own proof of identity (the token). Mandatory for JWT to make sense.
2. **`.requestMatchers("/login").permitAll()`** — the login endpoint itself must be open, since there's no token yet when calling it.
3. **`.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)`** — plugs the custom filter into the chain *before* Spring's default form-login filter, so it runs early enough to populate `SecurityContextHolder` before authorization checks happen.

`UserDetailsService`/`CustomUserDetailsService` from the DB approach is **still required** — it's used inside `AuthenticationManager.authenticate()` at `/login` time, just not necessarily on every subsequent request.

---

## 7. How Spring Decides Basic Auth vs JWT

**Spring doesn't "decide" at runtime — the mechanism is chosen entirely at config time by which filters you register.**

### The mechanism: filter chain composition

`HttpSecurity` is a builder for a list of `Filter` objects. Each config method adds (or doesn't add) a specific filter:

```java
http
    .httpBasic(Customizer.withDefaults())              // adds BasicAuthenticationFilter
    .addFilterBefore(jwtAuthenticationFilter, ...)      // adds YOUR JwtAuthenticationFilter
```

- Calling `.httpBasic(...)` → `BasicAuthenticationFilter` gets added to the chain.
- Not calling it → that filter is simply **absent**. Spring isn't "choosing" JWT over Basic Auth — Basic Auth's filter was never registered.
- `JwtAuthenticationFilter` only exists in the chain because it was manually added with `addFilterBefore`.

So "which auth mechanism is used" is entirely determined by **which filters were registered**, not by any runtime decision-making on Spring's part.

### What happens if both filters are present (mixed setup)

Each filter independently inspects the request and only acts if its own trigger condition matches:

- `BasicAuthenticationFilter` — checks for `Authorization: Basic ...` header. If absent, does nothing, calls `chain.doFilter()` to pass through.
- `JwtAuthenticationFilter` — checks for `Authorization: Bearer ...` header. If absent, same — does nothing, passes through.

They **don't conflict** because they look for different header prefixes (`Basic ` vs `Bearer `). Both could technically be registered simultaneously, and a request would only be handled by whichever filter matches its expected header format. In practice, most real systems pick **one** mechanism and don't mix them, to avoid confusing security models.

### The actual decision point

The chain runs top-to-bottom. Whichever filter successfully populates `SecurityContextHolder` first "wins." Later filters typically check `if (SecurityContextHolder.getContext().getAuthentication() == null)` before doing their own work, to avoid clobbering an already-authenticated request (see the filter style in Section 8).

**Bottom line:** Spring doesn't infer intent from the request. The mechanism is decided at config time by choosing which filters go into the chain. The request's header format (`Basic` vs `Bearer`) then determines which already-registered filter actually does something with it.

---

## 8. Two Filter Implementation Styles — Comparison

Both styles below are valid, commonly-seen patterns. Neither is "wrong" — they make different trade-offs.

### Style 1 — Claims-only (Section 5 above)

```java
if (jwtUtil.isTokenValid(token)) {
    String username = jwtUtil.extractUsername(token);
    String role = jwtUtil.extractRole(token);
    var authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
    var authToken = new UsernamePasswordAuthenticationToken(username, null, authorities);
    SecurityContextHolder.getContext().setAuthentication(authToken);
}
```

### Style 2 — DB-reload per request (commonly seen in tutorials / production code)

```java
@Override
protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && authHeader.startsWith("Bearer ")) {
        String token = authHeader.substring(7);
        String username = jwtUtil.extractUsername(token);
        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);
            if (jwtUtil.isTokenValid(token, username)) {
                UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                        userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }
    }
    chain.doFilter(request, response);
}
```

### Side-by-side comparison

| Aspect | Style 1 (claims-only) | Style 2 (DB-reload) |
|---|---|---|
| Loads `UserDetails` per request? | No — role comes straight from token claims | **Yes** — calls `userDetailsService.loadUserByUsername(username)` every request |
| Checks existing `SecurityContextHolder`? | No | **Yes** — `if (... getAuthentication() == null)` |
| Token validation | `isTokenValid(token)` — signature + expiry only | `isTokenValid(token, username)` — signature + expiry **+ confirms token's subject matches loaded username** |
| Principal stored in `SecurityContextHolder` | Just the username `String` | The full `UserDetails` object |
| Authorities source | From the token's `role` claim | From `UserDetails.getAuthorities()` (fresh DB/memory lookup) |
| DB hit per request? | **No** — true zero-DB-call stateless auth | **Yes** — one lookup per request |

### Why the differences matter

**1. `SecurityContextHolder.getAuthentication() == null` check — good practice, worth always including.**
Prevents this filter from overwriting an authentication already set earlier in the chain (relevant in mixed setups, or if the filter somehow runs more than once).

**2. Loading `UserDetails` from the DB every request — the real trade-off.**
- **Pro:** If a user is disabled, deleted, or has their role changed in the DB *after* the token was issued, Style 2 picks that up immediately, since it re-fetches current state every time. Style 1 wouldn't — a stale token keeps working with its originally-issued role until it expires.
- **Con:** Reintroduces a DB/memory lookup on every request — **partially defeats JWT's main selling point** (avoiding per-request DB hits). You still avoid *session* overhead (no `HttpSession`), but not *data lookup* overhead.

This is a genuine, well-known design trade-off, not a bug:
- **Pure stateless (claims-only)** → faster, but role/status changes only take effect after token expiry (or you need a revocation/blacklist mechanism).
- **DB-lookup-per-request** → fresher/safer, but pays back some of the cost JWT was adopted to avoid. Some teams do this to enforce immediate revocation; others accept staleness for short-lived tokens (e.g., 15 min expiry) paired with refresh tokens.

**3. `isTokenValid(token, username)` — confirming the token's subject matches the loaded username — genuinely good addition.**
Guards against a subtle bug class: reusing `JwtUtil` across multiple token types/purposes, or a crafted token with mismatched subject. Worth adding regardless of which loading strategy is chosen.

**4. Storing `UserDetails` (not just username) as the principal — generally preferred.**
`Authentication.getPrincipal()` returning the full `UserDetails` gives downstream code (`@AuthenticationPrincipal`, custom logic) more to work with than a bare string. This is the more conventional Spring Security pattern.

### Recommended combination

- Keep the `SecurityContextHolder == null` check
- Keep `isTokenValid(token, username)` (subject-matching)
- **Decide deliberately** whether to hit `UserDetailsService` per request based on the actual requirement:
  - Immediate revocation/role-change matters (most business apps) → do the lookup (Style 2)
  - Optimizing for pure throughput with short-lived tokens (e.g., internal microservice-to-microservice calls) → skip it, rely on token claims + short expiry (Style 1)

---

## 9. Where Role Authorization Actually Happens

**Key insight: authorization (role-checking) itself is identical in both Basic Auth and JWT flows. What differs is only *how* the `Authentication` object gets populated before that check runs.**

Spring Security always separates these into two distinct filter stages, regardless of auth mechanism:

1. **Authentication filter** — populates `SecurityContextHolder` with an `Authentication` object (username + authorities)
2. **`AuthorizationFilter`** (formerly `FilterSecurityInterceptor`) — runs *later* in the chain, reads that `Authentication`, and checks its authorities against `hasRole(...)`/`requestMatchers(...)` rules

Step 2 never changes — it doesn't care *how* the `Authentication` object was built, only that it exists with the right `GrantedAuthority` list. This is why the same `@PreAuthorize("hasRole('ADMIN')")` or `requestMatchers("/admin/**").hasRole("ADMIN")` code works unmodified whether using Basic Auth or JWT.

### Basic Auth — where authorities get attached

```
BasicAuthenticationFilter
   → decodes header → username, password
   → AuthenticationManager.authenticate()
      → DaoAuthenticationProvider
         → UserDetailsService.loadUserByUsername(username)   ← authorities fetched HERE
         → PasswordEncoder.matches(rawPassword, storedHash)
      → returns authenticated token WITH authorities from UserDetails
   → SecurityContextHolder.setAuthentication(authToken)
      ↓
AuthorizationFilter (later in chain)
   → reads SecurityContextHolder.getAuthentication().getAuthorities()
   → compares against hasRole("ADMIN") rule for this URL
   → 200 OK or 403
```

`DaoAuthenticationProvider` is the middleman that calls `loadUserByUsername()` and copies its `getAuthorities()` onto the resulting `Authentication` object.

### JWT (Style 1 — claims-only) — where authorities get attached

```
JwtAuthenticationFilter
   → extracts token from "Bearer ..." header
   → jwtUtil.isTokenValid(token) → verifies signature + expiry
   → jwtUtil.extractRole(token)                                ← authorities read directly FROM TOKEN
   → builds authorities: List.of(new SimpleGrantedAuthority("ROLE_" + role))
   → new UsernamePasswordAuthenticationToken(username, null, authorities)
   → SecurityContextHolder.setAuthentication(authToken)
      ↓
AuthorizationFilter (same as Basic Auth — unchanged)
   → reads authorities → checks hasRole("ADMIN") → 200 OK or 403
```

No `UserDetailsService` call. The role was baked into the token at `/login` time (`jwtUtil.generateToken(username, role)`), so it's just extracted from the payload — no DB/memory lookup here.

### JWT (Style 2 — DB-reload) — where authorities get attached

```
JwtAuthenticationFilter
   → extracts token from "Bearer ..." header
   → jwtUtil.extractUsername(token)
   → if SecurityContextHolder is empty:
        → userDetailsService.loadUserByUsername(username)      ← authorities fetched HERE, fresh from DB
        → jwtUtil.isTokenValid(token, username) → verifies signature + expiry + subject match
        → new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities())
   → SecurityContextHolder.setAuthentication(authToken)
      ↓
AuthorizationFilter (same as Basic Auth — unchanged)
   → reads authorities → checks hasRole("ADMIN") → 200 OK or 403
```

Here `loadUserByUsername()` *is* called — same method as Basic Auth — but **`DaoAuthenticationProvider` is not involved**. `UserDetailsService` is called directly inside the custom filter, bypassing the password-matching step entirely (correctly so — no password re-check needed, the token's signature already proved identity).

### Direct summary

**Authorization (the `hasRole` check) happens at exactly the same point in the chain as Basic Auth** — in `AuthorizationFilter`, near the end, after `SecurityContextHolder` has been populated. That part of Spring Security's pipeline is completely reused; it's never configured differently for JWT.

**What differs is only the *authentication* step before it** — specifically, where the `GrantedAuthority` list comes from:

| | Source of authorities |
|---|---|
| Basic Auth | `DaoAuthenticationProvider` → `UserDetailsService.loadUserByUsername()` (per request, always) |
| JWT (claims-only) | Directly decoded from the token payload (no DB call) |
| JWT (DB-reload) | `UserDetailsService.loadUserByUsername()` called directly inside the custom filter (per request, but skipping password verification) |

So role-based `@PreAuthorize`/`requestMatchers` code written for Basic Auth needs **zero changes** for JWT — it's agnostic to how `Authentication` was built. The only place JWT-specific logic lives is inside the custom `JwtAuthenticationFilter`, deciding how to populate authorities before handing off to the same authorization machinery.

---

## 10. Testing via Postman / curl

**Step 1 — Login to get a token:**
```bash
curl -X POST http://localhost:8080/login \
  -H "Content-Type: application/json" \
  -d '{"username":"dbadmin","password":"adminpass"}'

# Response: {"token":"eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJkYmFkbWluIiwicm9sZSI6IkFETUlOIiwi..."}
```

**Step 2 — Use the token on protected endpoints:**
```bash
curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..." \
  http://localhost:8080/admin/dashboard
# -> Welcome Admin
```

**In Postman:**
1. `POST /login` with raw JSON body → copy the `token` from the response
2. On the protected request: Authorization tab → type **Bearer Token** → paste the token
3. Send

**Test matrix:**

| Scenario | Expected |
|---|---|
| Valid token, correct role | 200 OK |
| Valid token, wrong role | 403 Forbidden |
| Expired token | 401 Unauthorized (fails at `isTokenValid`) |
| Tampered token (modified payload) | 401 Unauthorized (signature check fails) |
| No token / malformed header | 401 Unauthorized (never reaches authenticated state) |

**Debugging tip:** paste any token into jwt.io (or decode manually) to inspect header/payload without needing the secret key. The signature can't be verified without the key, but claims (username, role, expiry) are plainly visible — because the JWT payload is Base64-encoded, **not encrypted**.

---

## 11. Encoding vs Signing — JWT's Version of the Basic Auth Distinction

Same underlying confusion as Basic Auth's Base64-vs-hashing distinction, different mechanism.

**A JWT has 3 parts:** `header.payload.signature`

```
eyJhbGciOiJIUzI1NiJ9 . eyJzdWIiOiJkYmFkbWluIn0 . 5mZ2xhg3...
   (Base64 header)      (Base64 payload)         (HMAC signature)
```

- **Header & payload are Base64-encoded, not encrypted.** Anyone can decode and read them — same reversibility caveat as Basic Auth's Base64 header. **Never put secrets (passwords, sensitive PII) in JWT claims.**
- **The signature is what provides security** — an HMAC (or RSA, if asymmetric) computed over `header + payload` using the server's secret key. If anyone tampers with the payload, re-signing it without the key is computationally infeasible, so `isTokenValid()` fails.

So: Base64 in JWT = same transport-encoding role as Base64 in Basic Auth (readable, reversible). The **signature** is JWT's actual security mechanism — the equivalent of BCrypt's role in Basic Auth, just applied differently (verifying integrity of a token rather than matching a stored hash).

> Because the payload is readable, JWT — like Basic Auth — **must run over HTTPS** in production, otherwise tokens can be intercepted and replayed until they expire.

---

## 12. Full Request Lifecycle Diagram

```
[POST /login]                  {"username":"dbadmin","password":"adminpass"}
   ↓
[AuthenticationManager]        authenticate() → DaoAuthenticationProvider
                                 → UserDetailsService.loadUserByUsername()
                                 → PasswordEncoder.matches()
   ↓ (success)
[JwtUtil.generateToken]        builds header.payload.signature, signs with secret key
   ↓
[Response]                     { "token": "eyJ..." }

--- token now used on every subsequent request ---

[GET /admin/dashboard]         Authorization: Bearer eyJ...
   ↓
[JwtAuthenticationFilter]      extracts token → jwtUtil.isTokenValid()
                                 → verifies signature + expiry
                                 → (Style 1: reads role from claims) OR
                                 → (Style 2: calls UserDetailsService.loadUserByUsername())
   ↓ (valid)
[SecurityContextHolder]        sets Authentication with username + role/authorities
   ↓
[AuthorizationFilter]          hasRole(...) evaluated — SAME mechanism as Basic Auth
   ↓
[Controller]                   200 OK, or 403 if role insufficient
```

---

## 13. Interview-Ready Summary Points

- **JWT doesn't replace `UserDetailsService`/`PasswordEncoder`** — it reuses them once, at `/login`. After that, the token is (optionally) self-contained and stateless.
- **`SessionCreationPolicy.STATELESS`** is mandatory — JWT's whole value proposition is not relying on server-side session state.
- **Custom filter, not built-in** — unlike `BasicAuthenticationFilter` (provided by Spring), `JwtAuthenticationFilter` is written manually and wired with `addFilterBefore`.
- **Spring doesn't "choose" auth mechanism at runtime** — it's determined entirely by which filters are registered in `SecurityFilterChain` at config time. Different header prefixes (`Basic` vs `Bearer`) let multiple filters coexist without conflict.
- **Two valid filter styles exist** — claims-only (fast, no DB hit, but stale until token expiry) vs DB-reload-per-request (fresher/safer, but reintroduces the DB cost JWT was meant to avoid). Neither is wrong; it's a deliberate design trade-off.
- **Authorization (`hasRole`/`@PreAuthorize`) happens identically in both Basic Auth and JWT** — via `AuthorizationFilter`, after `SecurityContextHolder` is populated. Only the *source* of the authorities differs (DB lookup vs token claims vs direct `UserDetailsService` call in the filter).
- **Signature ≠ encoding** — JWT payload is readable Base64 (like Basic Auth's header); the HMAC/RSA **signature** is what actually secures the token against tampering.
- **No DB hit per request (Style 1 only)** — JWT's main performance advantage over Basic Auth/session-based auth, since role/identity come from the token's own claims. Style 2 forfeits this in exchange for freshness.
- **Expiry (`exp` claim) is built-in** — Basic Auth has no native expiry concept; every JWT does.
- **HTTPS is still mandatory** — token interception over plain HTTP is just as dangerous as Basic Auth header interception.
