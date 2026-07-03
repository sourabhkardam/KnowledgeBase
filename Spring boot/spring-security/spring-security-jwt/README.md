# Spring Boot + Spring Security + JWT

A complete, production-ready JWT authentication implementation using Spring Boot 3, Spring Security 6, and JJWT 0.12.3.

---

## Project Structure

```
src/main/java/com/app/
├── config/
│   ├── ApplicationConfig.java       # Auth provider, PasswordEncoder, AuthManager beans
│   └── SecurityConfig.java          # Filter chain, route permissions, session policy
├── controller/
│   ├── AuthController.java          # /api/auth/register, /api/auth/login
│   └── UserController.java          # /api/users/me, /api/users/all (protected)
├── dto/
│   ├── LoginRequest.java
│   ├── RegisterRequest.java
│   └── AuthResponse.java
├── entity/
│   ├── User.java                    # Implements UserDetails
│   └── Role.java                    # USER, ADMIN enum
├── filter/
│   └── JwtAuthenticationFilter.java # Validates JWT on every request
├── repository/
│   └── UserRepository.java
└── service/
    ├── AuthService.java             # register() and login() logic
    ├── JwtService.java              # generate, validate, extract JWT claims
    └── UserDetailsServiceImpl.java  # loads user from DB by email
```

---

## Prerequisites

- Java 17+
- Maven 3.6+
- MySQL running on localhost:3306

---

## Setup

### 1. Create the database

```sql
CREATE DATABASE securitydb;
```

### 2. Update credentials in application.yml

```yaml
spring:
  datasource:
    username: your_mysql_user
    password: your_mysql_password
```

### 3. Run the application

```bash
mvn spring-boot:run
```

Hibernate will auto-create the `users` table on first run (`ddl-auto: update`).

---

## API Endpoints

### Public

| Method | URL | Description |
|--------|-----|-------------|
| POST | `/api/auth/register` | Register a new user |
| POST | `/api/auth/login` | Login and receive JWT tokens |

### Protected (requires `Authorization: Bearer <token>`)

| Method | URL | Role Required |
|--------|-----|---------------|
| GET | `/api/users/me` | Any authenticated user |
| GET | `/api/users/all` | ADMIN only |

---

## Sample Requests

### Register

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com",
    "password": "secret123"
  }'
```

**Response:**
```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
  "email": "john@example.com",
  "role": "USER"
}
```

### Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "john@example.com",
    "password": "secret123"
  }'
```

### Access Protected Route

```bash
curl http://localhost:8080/api/users/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9..."
```

---

## How It Works

```
POST /api/auth/login
  → AuthService verifies credentials via AuthenticationManager
  → JwtService generates access + refresh tokens
  → Tokens returned to client

GET /api/users/me  (with Authorization: Bearer <token>)
  → JwtAuthenticationFilter intercepts request
  → Extracts and validates JWT
  → Loads user from DB via UserDetailsServiceImpl
  → Sets authentication in SecurityContextHolder
  → Request proceeds to controller
```

---

## Key Design Decisions

| Decision | Reason |
|----------|--------|
| `UserDetailsServiceImpl` as `@Service` | No redundant `@Bean` definition needed in config |
| Constructor injection via `@RequiredArgsConstructor` | Immutable fields, no `@Autowired` needed |
| `SessionCreationPolicy.STATELESS` | JWT is stateless — no server-side sessions |
| CSRF disabled | Stateless APIs don't need CSRF protection |
| `BCryptPasswordEncoder` | Industry standard for password hashing |
| `@PreAuthorize` on methods | Fine-grained role control per endpoint |

---

## Token Configuration (application.yml)

| Property | Default | Description |
|----------|---------|-------------|
| `secret-key` | 64-char hex | HMAC-SHA signing key |
| `expiration` | 86400000 ms | Access token lifetime (1 day) |
| `refresh-expiration` | 604800000 ms | Refresh token lifetime (7 days) |
