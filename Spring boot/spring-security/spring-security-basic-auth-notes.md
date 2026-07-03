# Spring Security — Basic Auth, Role-Based Access & Testing (Reference Notes)

## Table of Contents
1. [Shared Project Setup](#1-shared-project-setup)
2. [Three Ways to Configure Basic Auth](#2-three-ways-to-configure-basic-auth)
3. [Do We Always Need a `SecurityFilterChain` Bean?](#3-do-we-always-need-a-securityfilterchain-bean)
4. [How Authentication Actually Works Internally](#4-how-authentication-actually-works-internally)
5. [Role-Based Access Control (RBAC)](#5-role-based-access-control-rbac)
6. [Testing via Postman / curl](#6-testing-via-postman--curl)
7. [Encoding vs Hashing — The Two Layers Explained](#7-encoding-vs-hashing--the-two-layers-explained)
8. [Full Request Lifecycle Diagram](#8-full-request-lifecycle-diagram)
9. [Interview-Ready Summary Points](#9-interview-ready-summary-points)

---

## 1. Shared Project Setup

### `pom.xml` dependencies

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <!-- Only needed for DB-backed approach -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>com.h2database</groupId>
        <artifactId>h2</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

### Base controller (used throughout)

```java
@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() {
        return "Authenticated successfully!";
    }
}
```

> **Rule of thumb followed throughout these notes:** only one `SecurityFilterChain` bean can be active in the app context at a time. When testing a different approach, comment out / remove the others.

---

## 2. Three Ways to Configure Basic Auth

### i) Default Basic Auth (zero code)

Just having `spring-boot-starter-security` on the classpath is enough.

- Username: `user`
- Password: a random UUID printed on startup —
  `Using generated security password: ...`
- Every request to any endpoint returns **401** until Basic Auth credentials are supplied.

No configuration class needed at all.

---

### ii) Custom Username & Password

**Option A — `application.properties` (simplest, single in-memory user)**

```properties
spring.security.user.name=admin
spring.security.user.password=admin123
spring.security.user.roles=USER
```

No Java config required — this just overrides the auto-generated credentials.

**Option B — `SecurityFilterChain` + `InMemoryUserDetailsManager` (supports multiple users)**

```java
@Configuration
public class SecurityConfig {

    @Bean
    public InMemoryUserDetailsManager userDetailsService() {
        UserDetails user = User.withUsername("admin")
                .password("{noop}admin123") // {noop} = no encoding, dev/test only
                .roles("USER")
                .build();
        return new InMemoryUserDetailsManager(user);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

> `{noop}` disables password encoding — fine for learning/testing, **never** for production.

---

### iii) Username & Password from DB

**Entity**

```java
@Entity
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password; // stored as BCrypt hash
    private String role;
    // getters/setters
}
```

**Repository**

```java
public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsername(String username);
}
```

**Custom `UserDetailsService`**

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;

    public CustomUserDetailsService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser appUser = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return User.withUsername(appUser.getUsername())
                .password(appUser.getPassword())
                .roles(appUser.getRole())
                .build();
    }
}
```

**Security config**

```java
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

Spring auto-wires `CustomUserDetailsService` via the `UserDetailsService` interface — no manual `AuthenticationManager` construction needed. `DaoAuthenticationProvider` picks it up automatically, along with the `PasswordEncoder` bean.

**Seed a user for testing**

```java
@Bean
CommandLineRunner seedUser(AppUserRepository repo, PasswordEncoder encoder) {
    return args -> repo.save(new AppUser(null, "dbuser", encoder.encode("dbpass123"), "USER"));
}
```

---

## 3. Do We Always Need a `SecurityFilterChain` Bean?

**Short answer: No.** Spring Boot auto-configures one for free when `spring-boot-starter-security` is on the classpath and you don't define your own (via `SpringBootWebSecurityConfiguration`). The default already includes:

- `anyRequest().authenticated()`
- `httpBasic()` enabled
- CSRF enabled (session-based protection)

This default chain works fine even with a custom `UserDetailsService`/`PasswordEncoder` (approach iii) — Spring wires them in automatically.

### When you *do* need to define it explicitly
- Disabling CSRF (common for REST APIs tested via Postman)
- Permitting specific endpoints without auth (`/actuator/health`, `/public/**`)
- Adding custom filters (e.g., JWT filter)
- Changing session management (`SessionCreationPolicy.STATELESS`)
- Defining **role-based** URL rules (`requestMatchers(...).hasRole(...)`)

---

## 4. How Authentication Actually Works Internally

Step-by-step flow when a request hits an endpoint with `Authorization: Basic <base64>`:

1. **`BasicAuthenticationFilter`** intercepts the request, decodes the Base64 header into `username:password`, and wraps it into an unauthenticated `UsernamePasswordAuthenticationToken`.

2. This token goes to the **`AuthenticationManager`** (`ProviderManager`), which delegates to an **`AuthenticationProvider`** — by default, **`DaoAuthenticationProvider`**.

3. `DaoAuthenticationProvider` does two things:
   - Calls **`UserDetailsService.loadUserByUsername(username)`** — your code runs here (in-memory manager, or `CustomUserDetailsService` hitting the DB).
   - Takes the returned `UserDetails` (with the **stored/hashed password**) and compares it to the **raw password from the request** via `PasswordEncoder.matches(rawPassword, storedHash)`.

4. If `matches()` returns `true`, a fully authenticated `Authentication` object (username + authorities, no password) is stored in the `SecurityContextHolder`.

5. If `loadUserByUsername` throws `UsernameNotFoundException`, or `matches()` returns `false` → `BadCredentialsException` → **401**.

### Key contract to remember
| Component | Responsibility |
|---|---|
| `UserDetailsService` | **Fetch** the user (your job — DB or in-memory lookup) |
| `PasswordEncoder` | **Compare** passwords (Spring's job, via the bean you registered) |

You never manually compare passwords yourself. This separation is exactly why `{noop}` breaks in real use — `DaoAuthenticationProvider` still calls `matches()`, but `NoOpPasswordEncoder` just does a plain string comparison instead of BCrypt hashing.

---

## 5. Role-Based Access Control (RBAC)

Roles come from whatever `UserDetailsService` returns as `GrantedAuthority` list. Access can be enforced at the **URL level** or the **method level**.

### 5a. Without DB (in-memory, custom users with roles)

```java
@Configuration
@EnableMethodSecurity // only needed if using @PreAuthorize
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.withUsername("admin")
                .password(encoder.encode("admin123"))
                .roles("ADMIN")          // becomes ROLE_ADMIN internally
                .build();

        UserDetails user = User.withUsername("john")
                .password(encoder.encode("john123"))
                .roles("USER")           // becomes ROLE_USER internally
                .build();

        return new InMemoryUserDetailsManager(admin, user);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/user/**").hasAnyRole("ADMIN", "USER")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

**Controller**

```java
@RestController
public class DemoController {

    @GetMapping("/admin/dashboard")
    public String adminOnly() {
        return "Welcome Admin";
    }

    @GetMapping("/user/profile")
    public String userAccess() {
        return "Welcome User";
    }
}
```

- Login as `john` → `/admin/dashboard` → **403 Forbidden**
- Login as `admin` → `/admin/dashboard` → **200 OK**

> **Gotcha:** `.roles("ADMIN")` auto-prefixes `ROLE_` internally. Never write `.roles("ROLE_ADMIN")` — it becomes `ROLE_ROLE_ADMIN` and silently breaks matching. If using `.authorities(...)` directly instead of `.roles(...)`, you must prefix `ROLE_` yourself.

---

### 5b. With DB Lookup

**Entity (role field added)**

```java
@Entity
public class AppUser {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String username;
    private String password; // BCrypt hash
    private String role;     // e.g. "ADMIN", "USER" — without ROLE_ prefix
}
```

**`CustomUserDetailsService` — role attached to `UserDetails` here**

```java
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private final AppUserRepository repository;

    public CustomUserDetailsService(AppUserRepository repository) {
        this.repository = repository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) {
        AppUser appUser = repository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));

        return User.withUsername(appUser.getUsername())
                .password(appUser.getPassword())
                .roles(appUser.getRole()) // pulled straight from DB column
                .build();
    }
}
```

**Security config** — identical pattern, no `InMemoryUserDetailsManager` bean needed (Spring auto-detects `CustomUserDetailsService`):

```java
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/admin/**").hasRole("ADMIN")
                .requestMatchers("/user/**").hasAnyRole("ADMIN", "USER")
                .anyRequest().authenticated()
            )
            .httpBasic(Customizer.withDefaults())
            .csrf(AbstractHttpConfigurer::disable);
        return http.build();
    }
}
```

**Seed data**

```java
@Bean
CommandLineRunner seedUsers(AppUserRepository repo, PasswordEncoder encoder) {
    return args -> {
        repo.save(new AppUser(null, "dbadmin", encoder.encode("adminpass"), "ADMIN"));
        repo.save(new AppUser(null, "dbuser", encoder.encode("userpass"), "USER"));
    };
}
```

Same controller works unchanged — authorization logic only ever talks to the `UserDetails` abstraction, regardless of whether roles came from memory or DB.

---

### 5c. Method-Level Role Checks (alternative to URL-based)

```java
@RestController
public class DemoController {

    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping("/admin/dashboard")
    public String adminOnly() {
        return "Welcome Admin";
    }

    @PreAuthorize("hasAnyRole('ADMIN', 'USER')")
    @GetMapping("/user/profile")
    public String userAccess() {
        return "Welcome User";
    }
}
```

Requires `@EnableMethodSecurity` on the config class. If using this, the `SecurityFilterChain` can simplify back to `.anyRequest().authenticated()` — no `requestMatchers` role rules needed since annotations handle it.

**When to prefer which:**

| Style | Best for |
|---|---|
| **URL-based** (`requestMatchers`) | Broad, structural rules (e.g., "everything under `/admin/**` needs ADMIN"), checked early in the filter chain, slightly more performant |
| **Method-based** (`@PreAuthorize`) | Fine-grained, business-logic-tied rules (e.g., "only the owner or an ADMIN can edit this resource" via SpEL — `hasRole('ADMIN') or #id == authentication.principal.id`); considered the more Spring-idiomatic approach for service-layer security |

> Interview note: `@PostAuthorize` and `@PreFilter`/`@PostFilter` are less common but occasionally asked as "advanced" method security follow-ups.

---

## 6. Testing via Postman / curl

### Using Postman
1. Create request: `GET http://localhost:8080/admin/dashboard`
2. **Authorization** tab → type **Basic Auth**
3. Enter plain **Username** and **Password** — Postman auto-generates the header
4. Send

**In-memory approach test matrix**

| Username | Password | Endpoint | Expected |
|---|---|---|---|
| `admin` | `admin123` | `/admin/dashboard` | 200 OK |
| `admin` | `admin123` | `/user/profile` | 200 OK (ADMIN has access via `hasAnyRole`) |
| `john` | `john123` | `/admin/dashboard` | 403 Forbidden |
| `john` | `john123` | `/user/profile` | 200 OK |
| `john` | `wrongpass` | any | 401 Unauthorized |

**DB approach:** same matrix using `dbadmin`/`adminpass` and `dbuser`/`userpass` (or whatever was seeded).

> Tip: Save these as a Postman collection ("Admin - Valid", "User - Valid", "Wrong Password", "User accessing Admin route") as a quick regression suite while iterating on config.

### Using curl

```bash
curl -u admin:admin123 http://localhost:8080/admin/dashboard
# -> Welcome Admin

curl -u john:john123 http://localhost:8080/admin/dashboard
# -> 403 Forbidden

curl -u john:wrongpass http://localhost:8080/admin/dashboard
# -> 401 Unauthorized

curl -i -u john:john123 http://localhost:8080/user/profile
# -i also shows response headers/status
```

### Debugging Tips

**401 vs 403 tells you *where* the failure is:**
- **401 Unauthorized** → authentication failed (bad credentials, or `UsernameNotFoundException` from `UserDetailsService`)
- **403 Forbidden** → authentication succeeded, but the user lacks the required role/authority

If a role-check test unexpectedly returns 401 instead of 403, the credentials themselves are wrong — check that before suspecting the role logic.

**For the DB approach**, verify persisted data directly:
```sql
-- e.g. via H2 console at http://localhost:8080/h2-console (if enabled)
SELECT * FROM app_user;
```
Confirm the `role` column is exactly `ADMIN`/`USER` — no `ROLE_` prefix, no typos, no trailing whitespace. This is a very common silent bug.

**Enable Spring Security debug logging:**
```properties
logging.level.org.springframework.security=DEBUG
```
Prints resolved authorities per request (e.g. `Authorities: [ROLE_ADMIN]`) — useful when a role check misbehaves.

**Temporary sanity-check endpoint** (remove after testing):
```java
@GetMapping("/whoami")
public String whoAmI(Authentication authentication) {
    return authentication.getName() + " -> " + authentication.getAuthorities();
}
```
Confirms exactly what Spring Security resolved for the logged-in user — good for verifying DB role mapping before testing protected endpoints.

---

## 7. Encoding vs Hashing — The Two Layers Explained

A common point of confusion: **"encoding"** in Basic Auth (transport) is completely different from **password encoding/hashing** (storage). Two separate mechanisms, two separate layers.

### 7a. Base64 Encoding (transport layer — reversible, NOT security)

Just a format transformation for packaging credentials into the `Authorization` header — **not encryption**, trivially reversible.

**Client side (before sending) — done automatically by Postman/curl:**
```
username:password  →  "admin:admin123"
Base64 encode       →  "YWRtaW46YWRtaW4xMjM="
Header sent         →  Authorization: Basic YWRtaW46YWRtaW4xMjM=
```

Manual equivalent, if constructing the header yourself:
```bash
echo -n "admin:admin123" | base64
# YWRtaW46YWRtaW4xMjM=

curl -H "Authorization: Basic YWRtaW46YWRtaW4xMjM=" http://localhost:8080/admin/dashboard
```

**Server side (automatic, inside `BasicAuthenticationFilter.doFilterInternal()`):**
```
Header received   →  Authorization: Basic YWRtaW46YWRtaW4xMjM=
Base64 decode      →  "admin:admin123"
Split on ":"        →  username="admin", password="admin123"
```

You never write this decode logic yourself — Spring Security handles it before `UserDetailsService` is even called.

> **Security implication:** Since Base64 is reversible with zero effort, Basic Auth **must always run over HTTPS** in production. Over plain HTTP, anyone sniffing traffic can decode the header instantly. This is why production systems layer TLS underneath, or move to token-based auth (JWT/OAuth) where the transported artifact isn't the raw password itself.

### 7b. Password Hashing/Encoding (storage + comparison layer — one-way, actual security)

Handled by `PasswordEncoder` (BCrypt) — entirely separate from Base64.

- **At registration/seed time:** raw password → `encoder.encode(rawPassword)` → BCrypt hash → stored in DB / in-memory `UserDetails`
- **At login time:** the plain password decoded from the Base64 header is compared to the stored hash via `encoder.matches(rawPasswordFromRequest, storedHash)` inside `DaoAuthenticationProvider`

BCrypt is one-way — a hash cannot be decoded back to plain text. `matches()` re-hashes the input with the same salt and compares digests.

### 7c. Direct Answer
**You always send plain text credentials** from Postman/curl (they Base64-encode for you). **BCrypt only ever runs server-side**, comparing the decoded plain password against the stored hash — it's never sent or transmitted as a hash by the client.

---

## 8. Full Request Lifecycle Diagram

```
[Postman]                    "admin" / "admin123"  (typed as plain text)
   ↓ Base64 encode (Postman does this)
[HTTP Request]                Authorization: Basic YWRtaW46YWRtaW4xMjM=
   ↓ network (should be HTTPS in prod)
[BasicAuthenticationFilter]   Base64 decode → "admin", "admin123"
   ↓
[DaoAuthenticationProvider]   calls UserDetailsService.loadUserByUsername("admin")
                               → gets stored BCrypt hash
                               → encoder.matches("admin123", storedHash) → true/false
   ↓
[SecurityContextHolder]       authenticated Authentication object stored
   ↓
[Authorization check]         hasRole(...) / hasAnyRole(...) evaluated
   ↓
[Controller]                  200 OK response, or 403 if role check fails
```

---

## 9. Interview-Ready Summary Points

- **Default vs custom vs DB-backed auth** — differ only in *where* `UserDetailsService` sources users from; the filter/provider mechanics are identical in all three.
- **`SecurityFilterChain` is optional** unless you need custom rules (CSRF, public endpoints, role-based URL matching, stateless sessions, custom filters).
- **`UserDetailsService` fetches, `PasswordEncoder` compares** — never write manual password comparison logic.
- **`{noop}` vs BCrypt** — `{noop}` is plaintext comparison (dev-only); BCrypt is one-way hashing (production-safe).
- **`.roles("ADMIN")` auto-prefixes `ROLE_`** — a very common silent bug source if mismatched.
- **URL-based vs method-based authorization** — structural/broad rules vs fine-grained/business-logic rules; `@PreAuthorize` needs `@EnableMethodSecurity`.
- **401 vs 403** — authentication failure vs authorization (role) failure; use this distinction to debug quickly.
- **Base64 encoding ≠ password hashing** — Base64 is reversible transport packaging (client-side, automatic); BCrypt is one-way storage/comparison security (server-side). Basic Auth must run over HTTPS because of this distinction.
