# Microservices Design Patterns — Spring Boot Reference Guide

> A complete reference covering 8 essential microservices patterns with benefits, implementation code, and configuration examples using Spring Boot / Spring Cloud.

---

## Table of Contents

1. [API Gateway](#1-api-gateway)
2. [Service Discovery](#2-service-discovery)
3. [Circuit Breaker](#3-circuit-breaker)
4. [Saga Pattern](#4-saga-pattern)
5. [Database per Service](#5-database-per-service)
6. [CQRS](#6-cqrs-command-query-responsibility-segregation)
7. [Event Sourcing](#7-event-sourcing)
8. [Outbox Pattern](#8-outbox-pattern)

---

## 1. API Gateway

### What it does
Acts as the **single entry point** for all client requests. Handles routing, authentication, rate limiting, SSL termination, CORS, and request transformation — so downstream services don't have to.

### Benefits
| Concern | Without Gateway | With Gateway |
|---|---|---|
| Auth | Every service validates tokens | Done once at the edge |
| Rate limiting | Complex per-service logic | Centralized Redis buckets |
| CORS | Configured in every service | One global config |
| Observability | Scattered logs | Unified correlation IDs |
| Routing | Hardcoded URLs in clients | Dynamic Eureka lookup |
| Fault tolerance | Client-side handling | Circuit breaker + fallback at the edge |

### Request flow
```
Client
  → JwtAuthenticationFilter     (validate token, inject X-User-Id header)
  → LoggingFilter               (attach X-Correlation-Id)
  → RequestRateLimiter          (check Redis token bucket)
  → Route predicate match       (/api/orders/** → ORDER-SERVICE)
  → StripPrefix filter          (/api/orders/123 → /orders/123)
  → CircuitBreaker filter       (check CB state; if OPEN → /fallback/orders)
  → LoadBalancer                (pick healthy instance via Eureka)
  → Downstream service
```

### Dependencies

```xml
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis-reactive</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
```

> ⚠️ Do **not** include `spring-boot-starter-web` — Spring Cloud Gateway uses a reactive Netty server and the two are incompatible.

### Main class

```java
@SpringBootApplication
@EnableDiscoveryClient
public class ApiGatewayApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
```

### Route configuration (`application.yml`)

```yaml
server:
  port: 8080

spring:
  application:
    name: API-GATEWAY

  cloud:
    gateway:
      discovery:
        locator:
          enabled: true
          lower-case-service-id: true

      routes:
        - id: order-service
          uri: lb://ORDER-SERVICE        # lb:// = load-balanced via Eureka
          predicates:
            - Path=/api/orders/**
            - Method=GET,POST,PUT,DELETE
          filters:
            - StripPrefix=1
            - name: CircuitBreaker
              args:
                name: orderServiceCB
                fallbackUri: forward:/fallback/orders
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 20
                redis-rate-limiter.burstCapacity: 40
                key-resolver: "#{@userKeyResolver}"

        - id: user-service
          uri: lb://USER-SERVICE
          predicates:
            - Path=/api/users/**
          filters:
            - StripPrefix=1
            - AddRequestHeader=X-Gateway-Source, api-gateway

      globalcors:
        cors-configurations:
          '[/**]':
            allowedOrigins: "http://localhost:3000,https://myapp.com"
            allowedMethods: GET,POST,PUT,DELETE,OPTIONS
            allowedHeaders: "*"
            allowCredentials: true

resilience4j:
  circuitbreaker:
    instances:
      orderServiceCB:
        slidingWindowSize: 10
        failureRateThreshold: 50
        waitDurationInOpenState: 15s
        permittedNumberOfCallsInHalfOpenState: 3
  timelimiter:
    instances:
      orderServiceCB:
        timeoutDuration: 3s
```

### JWT authentication filter

```java
@Component
@Order(1)
public class JwtAuthenticationFilter implements GlobalFilter {

    @Value("${jwt.secret}")
    private String jwtSecret;

    private static final List<String> PUBLIC_PATHS = List.of(
        "/api/users/login", "/api/users/register", "/actuator/health"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getPath().toString();

        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest()
            .getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorizedResponse(exchange);
        }

        try {
            Claims claims = Jwts.parserBuilder()
                .setSigningKey(Keys.hmacShaKeyFor(jwtSecret.getBytes()))
                .build()
                .parseClaimsJws(authHeader.substring(7))
                .getBody();

            ServerWebExchange modified = exchange.mutate()
                .request(r -> r
                    .header("X-User-Id", claims.getSubject())
                    .header("X-User-Role", claims.get("role", String.class))
                ).build();

            return chain.filter(modified);

        } catch (JwtException e) {
            return unauthorizedResponse(exchange);
        }
    }

    private Mono<Void> unauthorizedResponse(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap("{\"error\":\"Unauthorized\"}".getBytes());
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
```

### Rate limiter key resolver

```java
@Configuration
public class RateLimiterConfig {

    @Bean
    @Primary
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null) return Mono.just(userId);

            InetSocketAddress addr = exchange.getRequest().getRemoteAddress();
            return Mono.just(addr != null ? addr.getAddress().getHostAddress() : "unknown");
        };
    }
}
```

### Fallback controller

```java
@RestController
@RequestMapping("/fallback")
public class FallbackController {

    @GetMapping("/orders")
    public ResponseEntity<Map<String, Object>> ordersFallback() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .body(Map.of(
                "status", 503,
                "message", "Order service is temporarily unavailable.",
                "timestamp", Instant.now().toString()
            ));
    }
}
```

### Logging + correlation ID filter

```java
@Component
@Order(2)
public class LoggingFilter implements GlobalFilter {

    private static final Logger log = LoggerFactory.getLogger(LoggingFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();

        ServerHttpRequest request = exchange.getRequest().mutate()
            .header("X-Correlation-Id", correlationId).build();

        log.info("[{}] → {} {}", correlationId, request.getMethod(), request.getURI());

        return chain.filter(exchange.mutate().request(request).build())
            .then(Mono.fromRunnable(() ->
                log.info("[{}] ← {} {}ms", correlationId,
                    exchange.getResponse().getStatusCode(),
                    System.currentTimeMillis() - start)
            ));
    }
}
```

---

## 2. Service Discovery

### What it does
Services **register** themselves at startup with a central registry. Other services **discover** them dynamically at runtime — no hardcoded URLs, no manual config updates when instances scale.

### Benefits
- Eliminates hardcoded host:port configuration
- Enables elastic scaling — new instances are picked up automatically
- Enables seamless failover — dead instances are evicted and traffic reroutes
- Supports multiple instances with automatic load balancing

### How it works
```
Startup:
  ORDER-SERVICE boots
  → registers with Eureka: { host, port, status: UP }
  → sends heartbeat every 10s to stay alive

Runtime (ORDER-SERVICE calling USER-SERVICE):
  RestTemplate("http://USER-SERVICE/users/42")
  → Spring intercepts lb:// URI
  → fetches cached registry (refreshed every 10s)
  → round-robins across healthy instances
  → rewrites URL → http://10.0.1.8:8090/users/42

Instance goes down:
  → heartbeats stop
  → Eureka evicts after lease-expiration-duration (30s)
  → removed from client caches on next refresh
```

### Dependencies

```xml
<!-- Eureka Server -->
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-netflix-eureka-server</artifactId>
</dependency>

<!-- Eureka Client (every microservice) -->
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>

<!-- Feign (optional, declarative HTTP client) -->
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-openfeign</artifactId>
</dependency>
```

### Eureka Server

```java
@SpringBootApplication
@EnableEurekaServer
public class ServiceRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(ServiceRegistryApplication.class, args);
    }
}
```

```yaml
# application.yml — Eureka Server
server:
  port: 8761

spring:
  application:
    name: SERVICE-REGISTRY

eureka:
  instance:
    hostname: localhost
  client:
    registerWithEureka: false
    fetchRegistry: false
    serviceUrl:
      defaultZone: http://${eureka.instance.hostname}:${server.port}/eureka/
  server:
    eviction-interval-timer-in-ms: 15000
    enable-self-preservation: false   # Disable in dev only
```

Dashboard available at: `http://localhost:8761`

### Eureka Client (microservice)

```java
@SpringBootApplication
@EnableDiscoveryClient
public class OrderServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
```

```yaml
# application.yml — any microservice
server:
  port: 8081

spring:
  application:
    name: ORDER-SERVICE   # ← becomes the service ID in the registry

eureka:
  instance:
    prefer-ip-address: true
    instance-id: ${spring.application.name}:${server.port}
    lease-renewal-interval-in-seconds: 10
    lease-expiration-duration-in-seconds: 30
    metadata-map:
      version: "1.2.0"
      zone: "us-east-1"
  client:
    service-url:
      defaultZone: http://localhost:8761/eureka/
    registry-fetch-interval-seconds: 10
```

### Option A — `@LoadBalanced RestTemplate`

```java
@Configuration
public class RestTemplateConfig {
    @Bean
    @LoadBalanced
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
```

```java
@Service
public class OrderService {

    private final RestTemplate restTemplate;

    public UserDto getUser(String userId) {
        // Spring resolves USER-SERVICE → actual host:port
        return restTemplate.getForObject(
            "http://USER-SERVICE/users/" + userId, UserDto.class
        );
    }
}
```

### Option B — `WebClient` (reactive)

```java
@Configuration
public class WebClientConfig {
    @Bean
    @LoadBalanced
    public WebClient.Builder webClientBuilder() {
        return WebClient.builder();
    }
}
```

```java
public Mono<UserDto> getUser(String userId) {
    return webClientBuilder.build()
        .get()
        .uri("http://USER-SERVICE/users/" + userId)
        .retrieve()
        .bodyToMono(UserDto.class);
}
```

### Option C — OpenFeign (recommended)

```java
@SpringBootApplication
@EnableFeignClients
public class OrderServiceApplication { ... }
```

```java
@FeignClient(name = "USER-SERVICE", fallback = UserServiceFallback.class)
public interface UserServiceClient {

    @GetMapping("/users/{userId}")
    UserDto getUserById(@PathVariable String userId);

    @GetMapping("/users")
    List<UserDto> getAllUsers(@RequestParam(defaultValue = "0") int page);

    @PostMapping("/users")
    UserDto createUser(@RequestBody CreateUserRequest request);
}
```

```java
@Component
public class UserServiceFallback implements UserServiceClient {

    @Override
    public UserDto getUserById(String userId) {
        return UserDto.builder().id(userId).name("Unknown").status("UNAVAILABLE").build();
    }

    @Override
    public List<UserDto> getAllUsers(int page) { return Collections.emptyList(); }

    @Override
    public UserDto createUser(CreateUserRequest request) {
        throw new ServiceUnavailableException("User service is down");
    }
}
```

### High-availability Eureka cluster (production)

```yaml
# eureka-peer1
eureka:
  instance:
    hostname: eureka-peer1
  client:
    serviceUrl:
      defaultZone: http://eureka-peer2:8762/eureka/,http://eureka-peer3:8763/eureka/

# Client pointing to all peers
eureka:
  client:
    service-url:
      defaultZone: >
        http://eureka-peer1:8761/eureka/,
        http://eureka-peer2:8762/eureka/,
        http://eureka-peer3:8763/eureka/
```

### Client comparison

| | `DiscoveryClient` | `RestTemplate` | `WebClient` | Feign |
|---|---|---|---|---|
| Style | Manual | Imperative | Reactive | Declarative |
| Boilerplate | High | Low | Low | Minimal |
| Best for | Custom routing logic | Simple REST calls | Async/streaming | Clean service interfaces |
| Fallback support | Manual | Manual | Manual | Built-in |

---

## 3. Circuit Breaker

### What it does
Monitors calls to downstream services. When failures exceed a threshold, it **opens** the circuit and immediately returns a fallback response — preventing cascade failures from propagating through the system.

### States
```
CLOSED   → Normal operation. All calls go through. Failure rate tracked.
           ↓ (failure rate > threshold)
OPEN     → All calls fail immediately with fallback. No calls to downstream.
           ↓ (after waitDurationInOpenState)
HALF-OPEN → Limited calls allowed through to test if service recovered.
           ↓ success               ↓ failure
         CLOSED                  OPEN
```

### Benefits
- Prevents cascade failures across services
- Enables fast failure instead of waiting for timeouts
- Keeps the system responsive under partial outages
- Automatic recovery detection via HALF-OPEN state

### Dependencies

```xml
<dependency>
  <groupId>io.github.resilience4j</groupId>
  <artifactId>resilience4j-spring-boot3</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-circuitbreaker-resilience4j</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-aop</artifactId>
</dependency>
```

### Configuration (`application.yml`)

```yaml
resilience4j:
  circuitbreaker:
    instances:
      inventoryService:
        slidingWindowSize: 10
        failureRateThreshold: 50          # Open if 50% of last 10 calls fail
        waitDurationInOpenState: 15s
        permittedNumberOfCallsInHalfOpenState: 3
        slowCallDurationThreshold: 2s
        slowCallRateThreshold: 80
        recordExceptions:
          - java.io.IOException
          - java.util.concurrent.TimeoutException
        ignoreExceptions:
          - com.example.BusinessException   # Don't count business errors
  retry:
    instances:
      inventoryService:
        maxAttempts: 3
        waitDuration: 500ms
        exponentialBackoffMultiplier: 2
  timelimiter:
    instances:
      inventoryService:
        timeoutDuration: 3s
```

### Service with Circuit Breaker

```java
@Service
public class OrderService {

    private final InventoryClient inventoryClient;

    @CircuitBreaker(name = "inventoryService", fallbackMethod = "inventoryFallback")
    @Retry(name = "inventoryService")
    @TimeLimiter(name = "inventoryService")
    public CompletableFuture<Boolean> checkInventory(String productId) {
        return CompletableFuture.supplyAsync(() ->
            inventoryClient.isAvailable(productId)
        );
    }

    public CompletableFuture<Boolean> inventoryFallback(String productId, Exception e) {
        log.warn("Inventory service down, using fallback for product: {}", productId, e);
        return CompletableFuture.completedFuture(false);  // Assume out of stock
    }

    // Fallback with specific exception type
    public CompletableFuture<Boolean> inventoryFallback(
            String productId, CallNotPermittedException e) {
        log.error("Circuit OPEN for inventory service");
        return CompletableFuture.completedFuture(false);
    }
}
```

### Programmatic Circuit Breaker

```java
@Service
public class PaymentService {

    private final CircuitBreakerRegistry registry;

    public PaymentResponse processPayment(PaymentRequest request) {
        CircuitBreaker cb = registry.circuitBreaker("paymentGateway");

        return cb.executeSupplier(() -> {
            // Call to external payment gateway
            return paymentGatewayClient.process(request);
        });
    }
}
```

### Monitoring circuit breaker events

```java
@Component
public class CircuitBreakerMonitor {

    @EventListener
    public void onStateTransition(CircuitBreakerOnStateTransitionEvent event) {
        log.info("CB [{}] state: {} → {}",
            event.getCircuitBreakerName(),
            event.getStateTransition().getFromState(),
            event.getStateTransition().getToState()
        );
        // Send alert to monitoring system
    }
}
```

```yaml
# Expose CB metrics via actuator
management:
  endpoints:
    web:
      exposure:
        include: health,circuitbreakers,circuitbreakerevents
  health:
    circuitbreakers:
      enabled: true
```

---

## 4. Saga Pattern

### What it does
Manages **distributed transactions** across multiple services as a sequence of local transactions. Each step publishes an event triggering the next. On failure, **compensating transactions** roll back previous steps.

### Two styles

#### Choreography (event-driven, decentralized)
Each service listens for events and reacts — no central coordinator.
```
OrderService      → publishes OrderCreated
InventoryService  → listens, reserves stock  → publishes StockReserved
PaymentService    → listens, charges card    → publishes PaymentProcessed
ShippingService   → listens, creates shipment

On failure:
PaymentService    → publishes PaymentFailed
InventoryService  → listens, releases stock  → publishes StockReleased
OrderService      → listens, cancels order
```

#### Orchestration (centralized coordinator)
A saga orchestrator tells each service what to do and handles failures.
```
SagaOrchestrator
  → calls InventoryService.reserve()
  → calls PaymentService.charge()
  → calls ShippingService.ship()
  On failure at any step → calls compensating actions in reverse
```

### Benefits
- Maintains data consistency without distributed locks or 2PC
- Services remain loosely coupled
- Each step is independently retryable
- Full audit trail of saga steps

### Dependencies

```xml
<!-- Kafka for event-driven choreography -->
<dependency>
  <groupId>org.springframework.kafka</groupId>
  <artifactId>spring-kafka</artifactId>
</dependency>

<!-- Axon Framework for orchestration -->
<dependency>
  <groupId>org.axonframework</groupId>
  <artifactId>axon-spring-boot-starter</artifactId>
</dependency>
```

### Choreography implementation (Kafka)

```java
// Order Service — initiates the saga
@Service
@Transactional
public class OrderService {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Order createOrder(OrderRequest request) {
        Order order = orderRepository.save(
            new Order(request, OrderStatus.PENDING)
        );
        kafkaTemplate.send("order-created",
            new OrderCreatedEvent(order.getId(), request.getProductId(), request.getUserId())
        );
        return order;
    }

    // Compensating transaction — called if payment fails
    @KafkaListener(topics = "payment-failed")
    public void handlePaymentFailed(PaymentFailedEvent event) {
        orderRepository.updateStatus(event.getOrderId(), OrderStatus.CANCELLED);
        kafkaTemplate.send("order-cancelled",
            new OrderCancelledEvent(event.getOrderId(), "Payment failed")
        );
    }
}
```

```java
// Inventory Service — reacts to order-created
@Service
public class InventoryService {

    @KafkaListener(topics = "order-created")
    @Transactional
    public void handleOrderCreated(OrderCreatedEvent event) {
        try {
            inventoryRepository.reserve(event.getProductId(), event.getQuantity());
            kafkaTemplate.send("stock-reserved", new StockReservedEvent(event.getOrderId()));
        } catch (InsufficientStockException e) {
            kafkaTemplate.send("stock-reservation-failed",
                new StockReservationFailedEvent(event.getOrderId(), e.getMessage())
            );
        }
    }

    // Compensating transaction
    @KafkaListener(topics = "order-cancelled")
    @Transactional
    public void handleOrderCancelled(OrderCancelledEvent event) {
        inventoryRepository.release(event.getOrderId());
    }
}
```

### Orchestration implementation (Axon)

```java
@Saga
public class OrderSaga {

    @Autowired
    private transient CommandGateway commandGateway;

    @StartSaga
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(OrderCreatedEvent event) {
        SagaLifecycle.associateWith("orderId", event.getOrderId().toString());
        commandGateway.send(new ReserveInventoryCommand(
            event.getOrderId(), event.getProductId(), event.getQuantity()
        ));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void handle(InventoryReservedEvent event) {
        commandGateway.send(new ProcessPaymentCommand(
            event.getOrderId(), event.getAmount()
        ));
    }

    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentProcessedEvent event) {
        commandGateway.send(new CompleteOrderCommand(event.getOrderId()));
        SagaLifecycle.end();
    }

    // Compensation on inventory failure
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(InventoryReservationFailedEvent event) {
        commandGateway.send(new CancelOrderCommand(event.getOrderId(), "No stock"));
        SagaLifecycle.end();
    }

    // Compensation on payment failure
    @SagaEventHandler(associationProperty = "orderId")
    public void handle(PaymentFailedEvent event) {
        commandGateway.send(new ReleaseInventoryCommand(event.getOrderId()));
        commandGateway.send(new CancelOrderCommand(event.getOrderId(), "Payment failed"));
        SagaLifecycle.end();
    }
}
```

### Kafka configuration

```yaml
spring:
  kafka:
    bootstrap-servers: localhost:9092
    producer:
      key-serializer: org.apache.kafka.common.serialization.StringSerializer
      value-serializer: org.springframework.kafka.support.serializer.JsonSerializer
      acks: all                       # Wait for all replicas
      retries: 3
    consumer:
      group-id: order-service-group
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.example.events"
```

### Choosing choreography vs orchestration

| | Choreography | Orchestration |
|---|---|---|
| Coupling | Low — services only know events | Medium — services know the orchestrator |
| Visibility | Hard to trace full flow | Easy — orchestrator owns the state |
| Complexity | Grows with number of services | Centralized, easier to reason about |
| Best for | Simple, linear flows | Complex flows with many branches |

---

## 5. Database per Service

### What it does
Each microservice **owns its own database** exclusively. No shared schemas, no cross-service joins. Services communicate through APIs or events, never directly via DB.

### Benefits
- Independent deployability — schema changes don't break other services
- Technology freedom — each service can use the best DB for its needs
- Failure isolation — one DB going down doesn't affect others
- Independent scaling — scale DB resources per service's load

### Design principle

```
❌ Wrong — shared database
  Order Service  ──┐
  User Service   ──┼──→ shared_db
  Product Service──┘

✅ Correct — database per service
  Order Service   → orders_db   (PostgreSQL)
  User Service    → users_db    (PostgreSQL)
  Product Service → products_db (MongoDB)
  Cart Service    → carts_db    (Redis)
```

### Example — different DB types per service

```java
// Order Service — PostgreSQL (relational, ACID transactions)
@Entity
@Table(name = "orders")
public class Order {
    @Id @GeneratedValue
    private UUID id;
    private String customerId;   // Just the ID — never a @ManyToOne join
    private BigDecimal total;
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL)
    private List<OrderItem> items;
}
```

```java
// Product Service — MongoDB (flexible schema, nested documents)
@Document(collection = "products")
public class Product {
    @Id
    private String id;
    private String name;
    private BigDecimal price;
    private Map<String, Object> attributes;  // Flexible per category
    private List<String> tags;
}
```

```java
// Cart Service — Redis (fast read/write, TTL support)
@RedisHash("cart")
public class Cart {
    @Id
    private String userId;
    private List<CartItem> items;

    @TimeToLive
    private Long ttl = 3600L;  // Cart expires after 1 hour
}
```

### Configuration

```yaml
# Order Service — PostgreSQL
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/orders_db
    username: orders_user
    password: ${DB_PASSWORD}
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

# Product Service — MongoDB
spring:
  data:
    mongodb:
      uri: mongodb://localhost:27017/products_db
      database: products_db

# Cart Service — Redis
spring:
  data:
    redis:
      host: localhost
      port: 6379
```

### Cross-service data access patterns

Since you can't do SQL joins across services, use these patterns instead:

```java
// Pattern 1: API Composition — call each service and merge in memory
@Service
public class OrderDetailsService {

    public OrderDetailsDto getOrderDetails(UUID orderId) {
        Order order = orderServiceClient.getOrder(orderId);              // → orders_db
        UserDto user = userServiceClient.getUser(order.getCustomerId()); // → users_db
        List<ProductDto> products = order.getItems().stream()
            .map(i -> productServiceClient.getProduct(i.getProductId())) // → products_db
            .collect(toList());

        return new OrderDetailsDto(order, user, products);
    }
}

// Pattern 2: Event-driven data sync — maintain a local read copy
@KafkaListener(topics = "user-updated")
public void syncUser(UserUpdatedEvent event) {
    // Order service keeps a lightweight copy of user data it needs
    userReadRepository.save(new UserSnapshot(
        event.getUserId(), event.getName(), event.getEmail()
    ));
}
```

---

## 6. CQRS (Command Query Responsibility Segregation)

### What it does
Separates the **write model** (commands) from the **read model** (queries). Commands update a normalized write store; queries serve from a denormalized, query-optimized read store.

### Benefits
- Write and read sides can scale independently
- Read models can be optimized per query (e.g. Elasticsearch for search)
- Commands can be validated and processed without impacting reads
- Supports eventual consistency with event-driven sync

### Architecture
```
Client
  ├── POST /orders  → CommandController → CommandService → Write DB (normalized)
  │                                                       ↓ publishes event
  │                                              EventHandler → Read DB (denormalized)
  └── GET /orders   → QueryController  → QueryService  → Read DB (fast)
```

### Dependencies

```xml
<!-- Axon Framework (CQRS + Event Sourcing support) -->
<dependency>
  <groupId>org.axonframework</groupId>
  <artifactId>axon-spring-boot-starter</artifactId>
  <version>4.9.1</version>
</dependency>
```

### Command side

```java
// Commands
public record CreateOrderCommand(
    @TargetAggregateIdentifier UUID orderId,
    String customerId,
    List<OrderItem> items
) {}

public record CancelOrderCommand(
    @TargetAggregateIdentifier UUID orderId,
    String reason
) {}
```

```java
// Aggregate (write model)
@Aggregate
public class OrderAggregate {

    @AggregateIdentifier
    private UUID orderId;
    private OrderStatus status;
    private String customerId;

    @CommandHandler
    public OrderAggregate(CreateOrderCommand cmd) {
        if (cmd.items().isEmpty()) throw new InvalidOrderException("No items");
        apply(new OrderCreatedEvent(cmd.orderId(), cmd.customerId(), cmd.items()));
    }

    @CommandHandler
    public void handle(CancelOrderCommand cmd) {
        if (status == OrderStatus.SHIPPED)
            throw new InvalidStateException("Cannot cancel shipped order");
        apply(new OrderCancelledEvent(orderId, cmd.reason()));
    }

    @EventSourcingHandler
    public void on(OrderCreatedEvent event) {
        this.orderId = event.orderId();
        this.status = OrderStatus.PENDING;
        this.customerId = event.customerId();
    }

    @EventSourcingHandler
    public void on(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
    }
}
```

```java
// Command controller
@RestController
@RequestMapping("/orders")
public class OrderCommandController {

    private final CommandGateway commandGateway;

    @PostMapping
    public ResponseEntity<UUID> createOrder(@RequestBody @Valid CreateOrderRequest req) {
        UUID orderId = UUID.randomUUID();
        commandGateway.sendAndWait(new CreateOrderCommand(orderId, req.customerId(), req.items()));
        return ResponseEntity.status(HttpStatus.CREATED).body(orderId);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> cancelOrder(@PathVariable UUID id,
                                            @RequestParam String reason) {
        commandGateway.sendAndWait(new CancelOrderCommand(id, reason));
        return ResponseEntity.noContent().build();
    }
}
```

### Query (read) side

```java
// Read model — denormalized for fast queries
@Entity
@Table(name = "order_view")
public class OrderView {
    @Id private UUID orderId;
    private String customerName;   // Denormalized from User service
    private String customerEmail;
    private BigDecimal totalAmount;
    private String status;
    private int itemCount;
    private LocalDateTime createdAt;
}
```

```java
// Event handler — keeps read model in sync
@Component
public class OrderEventHandler {

    private final OrderViewRepository viewRepo;
    private final UserServiceClient userClient;

    @EventHandler
    public void on(OrderCreatedEvent event) {
        UserDto user = userClient.getUserById(event.customerId());
        viewRepo.save(new OrderView(
            event.orderId(), user.getName(), user.getEmail(),
            event.totalAmount(), "PENDING", event.items().size(), LocalDateTime.now()
        ));
    }

    @EventHandler
    public void on(OrderCancelledEvent event) {
        viewRepo.findById(event.orderId()).ifPresent(view -> {
            view.setStatus("CANCELLED");
            viewRepo.save(view);
        });
    }
}
```

```java
// Query controller — read-only
@RestController
@RequestMapping("/orders")
public class OrderQueryController {

    private final OrderViewRepository viewRepo;

    @GetMapping("/{id}")
    public OrderView getOrder(@PathVariable UUID id) {
        return viewRepo.findById(id)
            .orElseThrow(() -> new NotFoundException("Order not found: " + id));
    }

    @GetMapping
    public Page<OrderView> getOrders(
            @RequestParam(required = false) String status,
            Pageable pageable) {
        if (status != null) return viewRepo.findByStatus(status, pageable);
        return viewRepo.findAll(pageable);
    }

    @GetMapping("/customer/{customerId}")
    public List<OrderView> getCustomerOrders(@PathVariable String customerId) {
        return viewRepo.findByCustomerIdOrderByCreatedAtDesc(customerId);
    }
}
```

---

## 7. Event Sourcing

### What it does
Instead of storing **current state**, store the **full history of events** that led to that state. The current state is always derived by replaying events from the beginning (or from a snapshot).

### Benefits
- Complete audit trail — every change is recorded with who, what, and when
- Time travel — reconstruct state at any point in time
- Event replay — rebuild read models or fix bugs by replaying history
- Natural fit with CQRS and Saga patterns

### Concept
```
Traditional (state-based):
  orders table: { id, status: "CANCELLED", total: 150.00, ... }
  → You know the state but not how it got there

Event Sourced:
  event_store:
    1. OrderCreated   { orderId, customerId, items, total: 150.00 }
    2. ItemAdded      { orderId, productId, quantity: 2 }
    3. PaymentMade    { orderId, amount: 150.00 }
    4. OrderCancelled { orderId, reason: "Customer request" }
  → Replay events 1→4 to get current state
  → Replay events 1→3 to get state before cancellation
```

### Aggregate with event sourcing

```java
@Aggregate
public class OrderAggregate {

    @AggregateIdentifier
    private UUID orderId;
    private OrderStatus status;
    private List<OrderItem> items = new ArrayList<>();
    private BigDecimal total;

    // Constructor handles CreateOrderCommand
    @CommandHandler
    public OrderAggregate(CreateOrderCommand cmd) {
        if (cmd.items().isEmpty()) throw new InvalidOrderException("No items");
        apply(new OrderCreatedEvent(cmd.orderId(), cmd.customerId(), cmd.items(), cmd.total()));
    }

    @CommandHandler
    public void handle(AddItemCommand cmd) {
        if (status != OrderStatus.PENDING)
            throw new InvalidStateException("Cannot add items to " + status + " order");
        apply(new ItemAddedEvent(orderId, cmd.item()));
    }

    @CommandHandler
    public void handle(CancelOrderCommand cmd) {
        if (status == OrderStatus.SHIPPED)
            throw new InvalidStateException("Cannot cancel shipped order");
        apply(new OrderCancelledEvent(orderId, cmd.reason()));
    }

    // EventSourcingHandlers rebuild state from events
    @EventSourcingHandler
    public void on(OrderCreatedEvent event) {
        this.orderId = event.orderId();
        this.status = OrderStatus.PENDING;
        this.items = new ArrayList<>(event.items());
        this.total = event.total();
    }

    @EventSourcingHandler
    public void on(ItemAddedEvent event) {
        this.items.add(event.item());
        this.total = this.total.add(event.item().getPrice());
    }

    @EventSourcingHandler
    public void on(OrderCancelledEvent event) {
        this.status = OrderStatus.CANCELLED;
    }
}
```

### Snapshots (performance optimization)

Replaying thousands of events on every load is slow. Snapshots periodically capture current state so only events after the snapshot need replaying.

```java
@Component
public class OrderSnapshotTrigger {

    // Take a snapshot every 50 events
    @Bean
    public SnapshotTriggerDefinition orderSnapshotTrigger(Snapshotter snapshotter) {
        return new EventCountSnapshotTriggerDefinition(snapshotter, 50);
    }
}
```

```yaml
# application.yml
axon:
  axonserver:
    servers: localhost:8124
  eventhandling:
    processors:
      order-processor:
        mode: tracking
        thread-count: 2
```

### Querying event history

```java
@Service
public class OrderHistoryService {

    private final EventStore eventStore;

    public List<Object> getOrderHistory(UUID orderId) {
        // Retrieve all events for this aggregate
        return eventStore.readEvents(orderId.toString())
            .asStream()
            .map(EventMessage::getPayload)
            .collect(toList());
    }

    public OrderAggregate reconstructStateAt(UUID orderId, Instant pointInTime) {
        // Replay only events up to a specific timestamp
        return eventStore.readEvents(orderId.toString())
            .asStream()
            .filter(e -> e.getTimestamp().isBefore(pointInTime))
            .map(EventMessage::getPayload)
            .collect(/* reconstruct aggregate */);
    }
}
```

---

## 8. Outbox Pattern

### What it does
Writes both the **database change** and the **outgoing event** atomically in the same local transaction to an "outbox" table. A separate process polls the outbox and publishes events to the message broker — guaranteeing at-least-once delivery with no message loss.

### The problem it solves
```
❌ Dual-write problem (without Outbox):
  1. Save order to DB  ✅
  2. Publish to Kafka  ❌  (app crashes here — event is lost forever)

✅ Outbox solution:
  1. Save order to DB  }  same transaction — atomic
  2. Save event to outbox_events }
  3. Outbox publisher polls table and publishes to Kafka
  4. Mark event as published
  → Even if step 3 fails, it retries — event is never lost
```

### Benefits
- Guaranteed at-least-once event delivery
- Eliminates dual-write problems
- No message loss on application crash
- Transactional consistency between DB state and events

### Outbox table schema

```sql
CREATE TABLE outbox_events (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    topic       VARCHAR(255) NOT NULL,
    payload     JSONB NOT NULL,
    created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
    published   BOOLEAN NOT NULL DEFAULT FALSE,
    published_at TIMESTAMP
);

CREATE INDEX idx_outbox_unpublished ON outbox_events (published, created_at)
WHERE published = FALSE;
```

### Outbox entity

```java
@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id @GeneratedValue
    private UUID id;

    private String topic;

    @Column(columnDefinition = "jsonb")
    private String payload;

    private LocalDateTime createdAt = LocalDateTime.now();
    private boolean published = false;
    private LocalDateTime publishedAt;
}
```

### Writing to outbox (same transaction as business logic)

```java
@Service
@Transactional
public class OrderService {

    private final OrderRepository orderRepository;
    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public Order createOrder(OrderRequest request) {
        // Step 1: Save business entity
        Order order = orderRepository.save(new Order(request));

        // Step 2: Save event — same transaction, atomic
        OutboxEvent outboxEvent = new OutboxEvent();
        outboxEvent.setTopic("order-created");
        outboxEvent.setPayload(objectMapper.writeValueAsString(
            new OrderCreatedPayload(order.getId(), order.getCustomerId(), order.getTotal())
        ));
        outboxRepository.save(outboxEvent);

        return order;
        // Both commits happen together — or both roll back
    }

    public void cancelOrder(UUID orderId, String reason) {
        Order order = orderRepository.findById(orderId).orElseThrow();
        order.setStatus(OrderStatus.CANCELLED);
        orderRepository.save(order);

        outboxRepository.save(new OutboxEvent(
            "order-cancelled",
            objectMapper.writeValueAsString(new OrderCancelledPayload(orderId, reason))
        ));
    }
}
```

### Outbox publisher — polling approach

```java
@Component
public class OutboxPublisher {

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;

    @Scheduled(fixedDelay = 1000)    // Poll every second
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> events = outboxRepository
            .findTop100ByPublishedFalseOrderByCreatedAtAsc();

        events.forEach(event -> {
            try {
                kafkaTemplate.send(event.getTopic(), event.getId().toString(), event.getPayload())
                    .get(5, TimeUnit.SECONDS);  // Wait for ack

                event.setPublished(true);
                event.setPublishedAt(LocalDateTime.now());
                outboxRepository.save(event);

            } catch (Exception e) {
                log.error("Failed to publish outbox event {}", event.getId(), e);
                // Leave as unpublished — will retry on next poll
            }
        });
    }
}
```

### Outbox publisher — CDC approach (Debezium, production recommended)

Debezium streams DB changes directly from the PostgreSQL write-ahead log — no polling overhead, sub-second latency.

```yaml
# Docker Compose — Debezium connector config
connector:
  name: outbox-connector
  config:
    connector.class: io.debezium.connector.postgresql.PostgresConnector
    database.hostname: postgres
    database.port: 5432
    database.user: debezium
    database.password: ${DB_PASSWORD}
    database.dbname: orders_db
    table.include.list: public.outbox_events
    transforms: outbox
    transforms.outbox.type: io.debezium.transforms.outbox.EventRouter
    transforms.outbox.table.field.event.key: id
    transforms.outbox.table.field.event.payload: payload
    transforms.outbox.route.by.field: topic
```

### Repository

```java
public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByPublishedFalseOrderByCreatedAtAsc();

    @Modifying
    @Query("DELETE FROM OutboxEvent e WHERE e.published = true AND e.publishedAt < :cutoff")
    void deletePublishedBefore(@Param("cutoff") LocalDateTime cutoff);
}
```

### Cleanup old published events

```java
@Scheduled(cron = "0 0 2 * * *")  // 2 AM daily
@Transactional
public void cleanupPublishedEvents() {
    LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
    outboxRepository.deletePublishedBefore(cutoff);
    log.info("Cleaned up outbox events older than {}", cutoff);
}
```

---

## Quick Reference

| Pattern | Library | Problem Solved | When to Use |
|---|---|---|---|
| **API Gateway** | Spring Cloud Gateway | Single entry point, cross-cutting concerns | Always — front door for all clients |
| **Service Discovery** | Eureka, Consul | Dynamic service location | When services need to find each other |
| **Circuit Breaker** | Resilience4j | Cascade failure prevention | All inter-service HTTP/gRPC calls |
| **Saga** | Axon, Kafka | Distributed transactions | Operations spanning multiple services |
| **DB per Service** | (design principle) | Data isolation | From day 1 — never share databases |
| **CQRS** | Axon Framework | Read/write scalability | High read/write asymmetry |
| **Event Sourcing** | Axon, EventStoreDB | Audit trail, state history | Complex domains, compliance requirements |
| **Outbox** | Debezium, Scheduler | Reliable event delivery | Any event-driven messaging |

---

## Pattern Relationships

```
API Gateway
  └── uses → Service Discovery (to find downstream services)
  └── uses → Circuit Breaker (to protect against failures)

Saga
  └── uses → Event Sourcing (to record saga steps)
  └── uses → Outbox (to reliably publish saga events)

CQRS
  └── pairs with → Event Sourcing (commands produce events, events build read model)
  └── pairs with → DB per Service (separate read/write stores)

Outbox
  └── enables → Saga (reliable event delivery between saga steps)
  └── enables → Event Sourcing (reliable event publishing from aggregates)
```

---

*Generated with Claude — Microservices Design Patterns Reference Guide*
