# JWT (JSON Web Token) — Complete Guide

---

## Table of Contents

1. [What is JWT?](#1-what-is-jwt)
2. [JWT Structure Overview](#2-jwt-structure-overview)
3. [Part 1 — Header](#3-part-1--header)
4. [Part 2 — Payload (Claims)](#4-part-2--payload-claims)
5. [Part 3 — Signature](#5-part-3--signature)
6. [How the Signature is Built — Step by Step](#6-how-the-signature-is-built--step-by-step)
7. [Signing Algorithms in Detail](#7-signing-algorithms-in-detail)
8. [JWT in Spring Boot — Full Implementation](#8-jwt-in-spring-boot--full-implementation)
9. [How Tamper Protection Works — Valid vs Tampered Token](#9-how-tamper-protection-works--valid-vs-tampered-token)
10. [Security Considerations](#10-security-considerations)
11. [JWT vs JWE](#11-jwt-vs-jwe)
12. [Quick Reference Summary](#12-quick-reference-summary)

---

## 1. What is JWT?

JWT (JSON Web Token) is an open standard (RFC 7519) for securely transmitting information between parties as a JSON object. This information can be verified and trusted because it is **digitally signed**.

Key characteristics:
- **Stateless** — the server does not need to store session information
- **Self-contained** — the token carries all necessary information about the user
- **Portable** — the same token format works across any language or framework (Java, Python, Node.js, etc.)
- **Verifiable** — the signature ensures the token has not been tampered with
- **NOT encrypted by default** — the payload is only Base64URL encoded, not hidden

---

## 2. JWT Structure Overview

A JWT consists of **three Base64URL-encoded parts** separated by dots (`.`):

```
header.payload.signature
```

**Example token:**
```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlNvdXJhYmgifQ
.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

Each part has a distinct role:

| Part | Content | Encoded? | Encrypted? |
|------|---------|----------|------------|
| Header | Algorithm and token type | Base64URL | No |
| Payload | Claims (user data) | Base64URL | No |
| Signature | Cryptographic proof | Base64URL | N/A |

> **Important:** Base64URL encoding is NOT the same as encryption. Anyone can decode the header and payload. The signature is what prevents tampering.

---

## 3. Part 1 — Header

### Raw JSON

```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### Fields

| Field | Full Name | Meaning |
|-------|-----------|---------|
| `alg` | Algorithm | The signing algorithm used — `HS256`, `RS256`, `ES256`, etc. |
| `typ` | Type | Always `JWT` for JSON Web Tokens |
| `kid` | Key ID | Optional. Hints which key to use when multiple keys exist |

### After Base64URL Encoding

```
{"alg":"HS256","typ":"JWT"}
        ↓ Base64URL encode
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
```

### Supported Algorithms

| Algorithm | Type | Description |
|-----------|------|-------------|
| `HS256` | Symmetric | HMAC with SHA-256 — one shared secret for both signing and verifying |
| `RS256` | Asymmetric | RSA with SHA-256 — private key signs, public key verifies |
| `ES256` | Asymmetric | ECDSA with SHA-256 — more compact than RSA, modern choice |
| `none` | None | No signature — **NEVER use in production** |

---

## 4. Part 2 — Payload (Claims)

### Raw JSON

```json
{
  "sub": "1234567890",
  "name": "Sourabh",
  "role": "ADMIN",
  "iat": 1716000000,
  "exp": 1716086400,
  "iss": "myapp.com",
  "aud": "myapp-client"
}
```

### After Base64URL Encoding

```
{"sub":"1234567890","name":"Sourabh","role":"ADMIN",...}
        ↓ Base64URL encode
eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IlNvdXJhYmgifQ
```

### Types of Claims

Claims are grouped into three categories:

#### Registered Claims (Standard — predefined by RFC 7519)

| Claim | Full Name | Meaning |
|-------|-----------|---------|
| `sub` | Subject | Who the token is about — typically a user ID |
| `iss` | Issuer | Who created the token — e.g., your auth server |
| `aud` | Audience | Who the token is intended for — e.g., your API |
| `iat` | Issued At | Unix timestamp of when the token was created |
| `exp` | Expiration | Unix timestamp of when the token expires |
| `nbf` | Not Before | Token is not valid before this Unix timestamp |
| `jti` | JWT ID | Unique identifier for the token — prevents replay attacks |

#### Public Claims

Custom claims registered with IANA to avoid naming collisions:

```json
{
  "email": "sourabh@example.com",
  "website": "https://sourabh.dev"
}
```

#### Private Claims

App-specific claims agreed between both parties:

```json
{
  "role": "ADMIN",
  "tenantId": "org-42",
  "permissions": ["READ", "WRITE"]
}
```

### What Should You Store in the Payload?

| Data | Store in JWT? | Reason |
|------|--------------|--------|
| User ID (`sub`) | ✅ Yes | Stable, non-sensitive identifier |
| Role / Permissions | ✅ Yes | Needed for authorization |
| Username | ⚠️ Optional | Can change; fine if low-risk |
| Email | ⚠️ Optional | Avoid if PII regulations apply |
| Password | ❌ Never | Payload is publicly readable |
| Sensitive PII | ❌ Never | Payload is only encoded, not encrypted |

> **Rule of thumb:** Store the **minimum required** — usually just `sub` (user ID) + `role`. Fetch full user details from DB using the ID when needed.

---

## 5. Part 3 — Signature

### What is it?

The signature is a **cryptographic hash** computed over the header and payload using a secret key. It is the only part of the JWT that provides actual security.

The signature ensures:
- ✅ The token was issued by a trusted party (authenticity)
- ✅ The token content was not modified after signing (integrity)
- ❌ Does NOT hide or encrypt the payload

### Formula

```
signature = HMACSHA256(
    base64url(header) + "." + base64url(payload),
    secretKey
)
```

The key insight here is that **only the header and payload** are passed into the HMAC function — the result of that function is the signature. The signature is then appended as the third part to form the complete JWT.

---

## 6. How the Signature is Built — Step by Step

This section explains exactly what happens when `.signWith()` is called in jjwt.

### Step A — Encode the Header

```
Input:  {"alg":"HS256","typ":"JWT"}
Output: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
```

### Step B — Encode the Payload

```
Input:  {"sub":"sourabh","role":"USER","iat":1716000000,"exp":1716086400}
Output: eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
```

### Step C — Join Header and Payload with a Dot

This is the **signing input** — the data that will be passed into the HMAC function. Note that this is only **two parts**, not the final token. The signature does not sign itself.

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
```

### Step D — Apply HMACSHA256 to Produce the Signature

This step produces **only the third part** (the signature). It does NOT produce the full token.

```
Input:  eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
        + secretKey

Operation: HMACSHA256(signingInput, secretKey)
           → raw bytes
           → Base64URL encode

Output (signature only): SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

### Step E — Assemble the Final JWT

All three parts are joined with dots. This is where the complete token is formed.

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9          ← Step A (header)
+  "."  +
eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ    ← Step B (payload)
+  "."  +
SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c   ← Step D (signature)

Final JWT:
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

### Visual Layout

```
Step A: encode(header)   → Part 1
Step B: encode(payload)  → Part 2
Step C: Part1 + "." + Part2  → signing input (TWO parts only)
Step D: HMAC(signing input, secret) → Part 3 (signature ONLY)
Step E: Part1 + "." + Part2 + "." + Part3 → Full JWT (THREE parts)

Analogy:
Step C = fold the letter (header + payload together)
Step D = apply the wax seal (signature) on top of the folded letter
Step E = now you have the sealed envelope (the full JWT)

The seal is created FROM the letter content, then ATTACHED to it.
The seal itself is never re-signed.
```

> **Common confusion clarified:**
> - Step C has only **two parts** — that is intentional. It is the signing input, not the final token.
> - Step D produces **only the signature** (third part) — not the full token.
> - Step E assembles the **complete JWT** by joining all three parts.

---

## 7. Signing Algorithms in Detail

### HS256 — Symmetric (Shared Secret)

```
Sign:   HMACSHA256(header.payload, secretKey)
Verify: HMACSHA256(header.payload, secretKey) == receivedSignature
```

```
Auth Server ──── same secret ────► API Server
```

- The same secret key is used to both sign and verify
- If the secret leaks, anyone can forge tokens
- Best for: **single server** or monolith applications

### RS256 — Asymmetric (Public/Private Key Pair)

```
Sign:   RSA_SHA256(header.payload, privateKey)   ← only the auth server knows this
Verify: RSA_SHA256(header.payload, publicKey)    ← shared with anyone who needs to verify
```

```
Auth Server (private key) ──── issues token ────►
API Server  (public key)  ──── verifies token ──►
```

- Private key signs, public key verifies
- The public key can be distributed freely
- Even if the public key is stolen, an attacker cannot forge tokens
- Best for: **microservices**, distributed systems, third-party verification (e.g., Google Sign-In)

### ES256 — Elliptic Curve (Asymmetric)

```
Sign:   ECDSA_SHA256(header.payload, privateKey)
Verify: ECDSA_SHA256(header.payload, publicKey)
```

- Same concept as RS256 but produces **shorter signatures**
- More computationally efficient
- Best for: **mobile apps**, IoT, performance-sensitive systems

---

## 8. JWT in Spring Boot — Full Implementation

### 8.1 — Maven Dependencies

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.11.5</version>
</dependency>

<!-- Runtime only — contains the actual HMAC/RSA implementations -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>

<!-- Jackson integration for JSON parsing of claims -->
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.11.5</version>
    <scope>runtime</scope>
</dependency>
```

### 8.2 — Application Properties

```properties
# application.properties

# Generate with: openssl rand -base64 32
# Must be at least 256 bits (32 bytes) for HS256
jwt.secret=7Xn2pL9mK4vQ8rT1wY6uI3oE5sA0bC/dF+gHjKlMnOpQrStUvWxYz==

# Token expiry in milliseconds (86400000 = 24 hours)
jwt.expiration=86400000
```

### 8.3 — Secret Key Configuration

```java
// JwtKeyConfig.java
@Component
public class JwtKeyConfig {

    // ❌ Wrong — weak, hardcoded string
    private String secret = "mySecret";

    // ✅ Correct — strong 256-bit key loaded from config
    @Value("${jwt.secret}")
    private String base64Secret;

    public SecretKey getSigningKey() {
        // 1. Decode the base64 string into raw bytes
        byte[] keyBytes = Decoders.BASE64.decode(base64Secret);

        // 2. Wrap raw bytes into a SecretKey object suitable for HS256
        // Keys.hmacShaKeyFor() validates that the key is strong enough
        return Keys.hmacShaKeyFor(keyBytes);
    }
}
```

### 8.4 — JWT Service (Signing and Verification)

```java
// JwtService.java
@Service
public class JwtService {

    private final JwtKeyConfig keyConfig;

    // ─── TOKEN GENERATION (SIGNING) ──────────────────────────────────────────

    public String generateToken(UserDetails userDetails) {
        return generateToken(new HashMap<>(), userDetails);
    }

    public String generateToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return buildToken(extraClaims, userDetails);
    }

    private String buildToken(Map<String, Object> extraClaims, UserDetails userDetails) {
        return Jwts.builder()
            .setClaims(extraClaims)                        // custom claims (role, tenantId, etc.)
            .setSubject(userDetails.getUsername())         // "sub" claim — user identifier
            .setIssuedAt(new Date())                       // "iat" claim — current timestamp
            .setExpiration(new Date(
                System.currentTimeMillis() + 86400000      // "exp" claim — now + 24 hours
            ))
            .signWith(                                     // ← SIGNING HAPPENS HERE
                keyConfig.getSigningKey(),                 //   SecretKey from config
                SignatureAlgorithm.HS256                   //   Algorithm explicitly specified
            )
            .compact();                                    // builds the final header.payload.signature string
    }

    // ─── TOKEN VALIDATION (VERIFICATION) ─────────────────────────────────────

    public boolean isTokenValid(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);  // reads the "sub" claim
    }

    private boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    private Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    // Generic claim extractor using a function reference
    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parserBuilder()
            .setSigningKey(keyConfig.getSigningKey())  // used to recompute and compare signature
            .build()
            .parseClaimsJws(token)   // ← VERIFICATION HAPPENS HERE
            // Throws ExpiredJwtException     if token is past its "exp"
            // Throws SignatureException      if signature does not match (tampered)
            // Throws MalformedJwtException   if token format is invalid
            // Throws UnsupportedJwtException if algorithm is not supported (e.g. "none")
            .getBody();              // returns the Claims object if verification passed
    }
}
```

### 8.5 — JWT Auth Filter

```java
// JwtAuthFilter.java
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {

        // 1. Read the Authorization header
        final String authHeader = request.getHeader("Authorization");

        // 2. Skip if header is missing or not a Bearer token
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        // 3. Strip the "Bearer " prefix to get the raw JWT string
        final String jwt = authHeader.substring(7);

        String username;
        try {
            // 4. Extract username — internally verifies the signature
            username = jwtService.extractUsername(jwt);

        } catch (SignatureException e) {
            // Token was tampered — signature mismatch
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid token: signature verification failed");
            return;

        } catch (ExpiredJwtException e) {
            // Token is valid but expired
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Token expired");
            return;

        } catch (JwtException e) {
            // Any other JWT error (malformed, unsupported, etc.)
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.getWriter().write("Invalid token");
            return;
        }

        // 5. If username extracted and user not yet authenticated in this request
        if (username != null &&
            SecurityContextHolder.getContext().getAuthentication() == null) {

            // 6. Load full user details from DB
            UserDetails userDetails = userDetailsService.loadUserByUsername(username);

            // 7. Validate token — checks signature (already done) + expiry
            if (jwtService.isTokenValid(jwt, userDetails)) {

                // 8. Build an authentication object with user's granted authorities
                UsernamePasswordAuthenticationToken authToken =
                    new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        userDetails.getAuthorities()
                    );

                // 9. Attach request details (IP, session, etc.)
                authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));

                // 10. Store in security context — marks this request as authenticated
                SecurityContextHolder.getContext().setAuthentication(authToken);
            }
        }

        // 11. Continue the filter chain — request proceeds to the controller
        filterChain.doFilter(request, response);
    }
}
```

### 8.6 — Exception Handling

```java
// JwtService.java — detailed exception handling
public TokenValidationResult validateToken(String token) {
    try {
        Jwts.parserBuilder()
            .setSigningKey(keyConfig.getSigningKey())
            .build()
            .parseClaimsJws(token);
        return TokenValidationResult.VALID;

    } catch (ExpiredJwtException e) {
        // Token structure and signature are valid, but it is past the "exp" time
        return TokenValidationResult.EXPIRED;

    } catch (SignatureException e) {
        // The recomputed signature does not match the received signature
        // This means the header or payload was modified after the token was issued
        return TokenValidationResult.TAMPERED;

    } catch (MalformedJwtException e) {
        // The token is not a valid JWT format (wrong number of parts, bad encoding, etc.)
        return TokenValidationResult.MALFORMED;

    } catch (UnsupportedJwtException e) {
        // The algorithm specified in the header is not supported
        // For example, "alg":"none" attack would be caught here
        return TokenValidationResult.UNSUPPORTED;
    }
}

enum TokenValidationResult {
    VALID, EXPIRED, TAMPERED, MALFORMED, UNSUPPORTED
}
```

---

## 9. How Tamper Protection Works — Valid vs Tampered Token

### 9.1 — Valid Token Flow

#### Step 1: User Logs In

```java
@PostMapping("/login")
public ResponseEntity<AuthResponse> login(@RequestBody LoginRequest request) {

    // Verify credentials against the database
    authenticationManager.authenticate(
        new UsernamePasswordAuthenticationToken(
            request.getUsername(),
            request.getPassword()
        )
    );

    // Load user details
    UserDetails user = userDetailsService.loadUserByUsername(request.getUsername());

    // Generate JWT
    String token = jwtService.generateToken(user);

    return ResponseEntity.ok(new AuthResponse(token));
}
```

#### Step 2: Server Builds the Token Internally

When `.signWith().compact()` is called, jjwt performs these steps:

```
Step A: Build header JSON and Base64URL encode
        {"alg":"HS256","typ":"JWT"}
        → eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9

Step B: Build payload JSON and Base64URL encode
        {"sub":"sourabh","role":"USER","iat":1716000000,"exp":1716086400}
        → eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ

Step C: Create signing input (two parts joined by a dot)
        eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ

Step D: Apply HMACSHA256 — produces ONLY the signature (third part)
        HMACSHA256(signingInput, secretKey) → raw bytes → Base64URL encode
        → SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

Step E: Assemble final JWT (all three parts joined by dots)
        eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
        .eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
        .SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

#### Step 3: Client Sends Token in Subsequent Requests

```
GET /api/data HTTP/1.1
Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
                      .eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
                      .SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
```

#### Step 4: JwtAuthFilter Intercepts the Request

```java
// Filter reads the Authorization header
String jwt = authHeader.substring(7); // removes "Bearer " prefix

// Passes to JwtService — verification happens inside this call
String username = jwtService.extractUsername(jwt);
```

#### Step 5: Server Verifies the Signature (Valid Token)

When `parseClaimsJws(token)` is called, jjwt internally does:

```
Received token:
header    = eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
payload   = eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ
signature = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

Step A: Split token by "." to get three parts

Step B: Recompute expected signature using server's secretKey
        expected = HMACSHA256(header + "." + payload, secretKey)
                 = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

Step C: Compare received vs expected
        received = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
        expected = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
                   ✅ MATCH — Token is authentic and unmodified
```

#### Step 6: Security Context is Set and Request Proceeds

```java
// Authentication object is created with the user's authorities
UsernamePasswordAuthenticationToken authToken =
    new UsernamePasswordAuthenticationToken(
        userDetails,
        null,
        userDetails.getAuthorities()
    );

// Stored in the security context for this request
SecurityContextHolder.getContext().setAuthentication(authToken);

// Request continues to the controller ✅
filterChain.doFilter(request, response);
```

---

### 9.2 — Tampered Token Flow

#### Attacker's Goal

```
Original payload: {"sub": "sourabh", "role": "USER"}
Attacker wants:   {"sub": "sourabh", "role": "ADMIN"}
```

#### Tamper Step 1: Attacker Decodes the Token

The payload is only Base64URL encoded — anyone can decode it:

```
Encoded payload: eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ

Base64URL decode:
→ {"sub":"sourabh","role":"USER"}

The attacker can now read the payload in plain text.
```

#### Tamper Step 2: Attacker Modifies the Payload

```
Original: {"sub":"sourabh","role":"USER"}
Modified: {"sub":"sourabh","role":"ADMIN"}   ← changed USER to ADMIN

Re-encode modified payload:
→ eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IkFETUlOIn0   ← different value!
```

#### Tamper Step 3: Attacker Cannot Produce a Valid Signature

To forge a valid token the attacker needs:

```
HMACSHA256(header + "." + newPayload, secretKey) → forged signature

But:
├── secretKey is only on the server — attacker does not have it
├── HMAC is a one-way function — cannot reverse-engineer the key from the signature
├── Brute force on 256-bit key = 2²⁵⁶ combinations
│   = more attempts than atoms in the observable universe
└── Therefore: forging a valid signature is computationally impossible
```

So the attacker assembles a tampered token using the **old original signature** with the **new modified payload**:

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9          ← original header (unchanged)
.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IkFETUlOIn0   ← NEW payload (ADMIN)
.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c   ← ORIGINAL signature (stale/invalid for new payload)
```

#### Tamper Step 4: Server Verifies the Tampered Token

```
Received tampered token:
header    = eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
payload   = eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IkFETUlOIn0   ← ADMIN
signature = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c   ← original (no longer valid)

Step A: Split token by "." to get three parts

Step B: Recompute expected signature using the received payload
        expected = HMACSHA256(header + "." + newPayload, secretKey)
                 = Xk9mP2qL7nR4vT1wY8uI5oE3sA6bC0dF   ← COMPLETELY DIFFERENT

Step C: Compare received vs expected
        received = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c
        expected = Xk9mP2qL7nR4vT1wY8uI5oE3sA6bC0dF
                   ❌ MISMATCH — Token has been tampered with
```

#### Tamper Step 5: Exception is Thrown and Request is Rejected

```java
// jjwt throws this automatically inside parseClaimsJws()
throw new SignatureException("JWT signature does not match locally computed signature");

// Caught in JwtAuthFilter
} catch (SignatureException e) {
    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);  // 401
    response.getWriter().write("Invalid token: signature verification failed");
    return;  // Stop the filter chain — request is rejected ❌
}
```

---

### 9.3 — Side by Side Comparison

```
VALID TOKEN
─────────────────────────────────────────────────────────────────
Header:    eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
Payload:   eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ   ← role: USER
Signature: SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

Recomputed: HMACSHA256(header.payload, secret)
          = SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c

Result: ✅ MATCH → Request authenticated as USER


TAMPERED TOKEN
─────────────────────────────────────────────────────────────────
Header:    eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9
Payload:   eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IkFETUlOIn0   ← role: ADMIN (changed!)
Signature: SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c   ← original (now stale)

Recomputed: HMACSHA256(header.newPayload, secret)
          = Xk9mP2qL7nR4vT1wY8uI5oE3sA6bC0dF

Result: ❌ MISMATCH → 401 Unauthorized
```

---

## 10. Security Considerations

### Common Attacks and Defenses

| Attack | How It Works | Defense |
|--------|-------------|---------|
| **Algorithm confusion (`alg:none`)** | Attacker changes `alg` header to `none` and removes signature, hoping server skips verification | Always use `.parseClaimsJws()` (not `.parseClaimsJwt()`); never trust the `alg` field from the token |
| **HS256 key confusion** | Attacker switches RS256 to HS256 and uses the public key as the HMAC secret | Explicitly specify the expected algorithm using `setSigningKey()` in jjwt |
| **Secret brute force** | Guess weak HS256 secrets (e.g., "secret", "password") | Use a minimum 256-bit randomly generated secret |
| **Token replay** | Steal a valid token and reuse it | Use short expiry (`exp`), combine with `jti` claim and a token blacklist |
| **Payload snooping** | Decode the Base64URL payload to read sensitive data | Never store passwords, secrets, or sensitive PII in payload |

### Algorithm Confusion Attack in Detail

```java
// Attacker modifies header:
{"alg":"none"}

// And sends token with no signature:
eyJhbGciOibm9uZSJ9.eyJyb2xlIjoiQURNSU4ifQ.

// ❌ Vulnerable code — trusts alg from token header
Jwts.parserBuilder()
    .build()
    .parse(token);  // "none" algorithm accepted!

// ✅ Safe code — setSigningKey() forces jjwt to require a valid signature
Jwts.parserBuilder()
    .setSigningKey(secretKey)   // jjwt rejects "alg:none" automatically
    .build()
    .parseClaimsJws(token);     // uses ClaimsJws (signed), not ClaimsJwt (unsigned)
```

### Token Expiry Best Practices

```java
// Short-lived access token (15 minutes)
.setExpiration(new Date(System.currentTimeMillis() + 900_000))

// Long-lived refresh token (7 days) — stored securely, used only to get new access tokens
.setExpiration(new Date(System.currentTimeMillis() + 604_800_000))
```

### Using `jti` to Prevent Replay Attacks

```java
// Add a unique ID to each token
.setId(UUID.randomUUID().toString())  // sets "jti" claim

// On the server side, maintain a blacklist of used jti values
// If a jti has been seen before → reject the token
```

---

## 11. JWT vs JWE

| Feature | JWT (default) | JWE (JSON Web Encryption) |
|---------|--------------|--------------------------|
| Payload | Encoded (readable by anyone) | Encrypted (private) |
| Signature | Yes — integrity guaranteed | Yes — integrity guaranteed |
| Use case | Auth tokens, role claims, session info | Sensitive data in the token itself |
| Complexity | Simple | More complex to implement |
| Performance | Fast | Slower (encryption overhead) |

Use **JWE** when:
- The payload contains sensitive data (PII, financial info)
- The token is passed through untrusted intermediaries
- Regulatory requirements mandate encryption at rest

---

## 12. Quick Reference Summary

### JWT Structure

```
eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9   ← Header (algorithm + type)
.eyJzdWIiOiJzb3VyYWJoIiwicm9sZSI6IlVTRVIifQ  ← Payload (claims)
.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c  ← Signature (tamper proof)
```

### Signature Formula

```
signature = HMACSHA256(base64url(header) + "." + base64url(payload), secretKey)
```

### Build Steps

```
A → encode(header)         = Part 1
B → encode(payload)        = Part 2
C → Part1 + "." + Part2   = signing input (TWO parts — not the final token)
D → HMAC(C, secretKey)    = Part 3 (signature ONLY — not the full token)
E → Part1.Part2.Part3     = Final JWT (THREE parts — complete token)
```

### Security Guarantee

```
To forge a valid token:
  Requires: HMACSHA256(anyPayload, secretKey)
  Problem:  secretKey never leaves the server
            HMAC is one-way — cannot reverse to recover the key
            Brute force = 2²⁵⁶ attempts
  Result:   Forging is computationally impossible
```

### Spring Boot Quick Reference

```java
// Generate
Jwts.builder()
    .setSubject(username)
    .signWith(secretKey, SignatureAlgorithm.HS256)
    .compact();

// Verify
Jwts.parserBuilder()
    .setSigningKey(secretKey)
    .build()
    .parseClaimsJws(token)   // throws on any failure
    .getBody();
```
