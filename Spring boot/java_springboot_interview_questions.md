# Java & Spring Boot Interview Questions
### For Candidates with 4+ Years of Experience

---

## Table of Contents

1. [Core Java](#core-java)
2. [Spring Core](#spring-core)
3. [Spring Boot](#spring-boot)
4. [Spring Data JPA](#spring-data-jpa)
5. [Spring REST & Web](#spring-rest--web)
6. [Microservices & Advanced](#microservices--advanced)

---

## Core Java

---

### Q1. What is the difference between `HashMap` and `ConcurrentHashMap`?

`HashMap` is not thread-safe. If multiple threads access it simultaneously and at least one modifies it, it can cause data corruption or infinite loops. `ConcurrentHashMap` is thread-safe — it uses segment-level locking (Java 7) or CAS operations with synchronized blocks (Java 8+) internally, so multiple threads can read/write concurrently without explicit synchronization.

**Follow-up:** *Why not just use `Collections.synchronizedMap()`?*

`synchronizedMap` locks the entire map for every operation, making it a bottleneck. `ConcurrentHashMap` allows concurrent reads and fine-grained locking on writes, so it's significantly more performant under high concurrency.

---

### Q2. Explain the Java Memory Model — heap vs stack.

Stack stores method call frames, local variables, and references. It's thread-specific and automatically managed. Heap stores all objects and is shared across threads, managed by the GC. Instance variables live on the heap inside their object. A common mistake is thinking primitives always go on the stack — they do when they're local variables, but when they're fields of an object, they live on the heap.

---

### Q3. What are the different types of Garbage Collectors in Java?

| GC Type | Description | Use Case |
|---|---|---|
| Serial GC | Single-threaded | Small apps |
| Parallel GC | Multi-threaded, throughput-focused | Java 8 default |
| G1 GC | Region-based, balances throughput and pause | Java 9+ default |
| ZGC / Shenandoah | Low-latency, sub-millisecond pauses | Large heaps |

In production, G1GC can be tuned with flags like `-XX:MaxGCPauseMillis=200` to control pause times.

---

### Q4. What is the difference between `==` and `.equals()`?

`==` compares object references (memory addresses). `.equals()` compares object content, but only if the class overrides it — otherwise it falls back to `==`. A classic trap is comparing `String` objects with `==`, which works accidentally for string literals (due to the string pool) but fails for `new String("abc")`.

---

### Q5. What are functional interfaces and how have you used them?

A functional interface has exactly one abstract method. Java 8 introduced `@FunctionalInterface` and built-ins like `Predicate`, `Function`, `Consumer`, `Supplier`. They enable lambdas and are used heavily with streams.

```java
// Predicate to filter active employees
Predicate<Employee> isActive = emp -> emp.isActive();

// Function to transform data
Function<String, Integer> strToInt = Integer::parseInt;

// Used in stream pipeline
List<String> names = employees.stream()
    .filter(isActive)
    .map(Employee::getName)
    .collect(Collectors.toList());
```

---

### Q6. Explain `Optional` and when you use it.

`Optional` is a container that may or may not hold a non-null value. It forces the caller to handle the absent case explicitly, reducing NPEs. Use it as a return type from service or repository methods when a result might not exist.

```java
Optional<Employee> emp = employeeRepository.findById(id);
emp.ifPresentOrElse(
    e -> process(e),
    () -> { throw new EmployeeNotFoundException(id); }
);
```

**Anti-patterns to avoid:**
- Using `Optional` as a field type
- Using `Optional` as a method parameter
- Calling `.get()` without checking `.isPresent()`

---

### Q7. What is the difference between `Callable` and `Runnable`?

| Feature | `Runnable` | `Callable` |
|---|---|---|
| Return type | `void` | Generic `V` |
| Checked exceptions | Cannot throw | Can throw |
| Used with | `Thread`, `ExecutorService` | `ExecutorService.submit()` |
| Result access | N/A | Via `Future<V>` |

Use `Callable` with `ExecutorService.submit()` when you need the result of an async computation via `Future<T>`.

---

### Q8. What are `volatile` and `synchronized` keywords?

`volatile` ensures **visibility** — changes to a volatile variable are immediately visible to all threads. But it does not guarantee atomicity. `synchronized` ensures both **visibility and atomicity** by acquiring a monitor lock.

```java
// volatile for simple flags
private volatile boolean isRunning = true;

// synchronized for compound operations
public synchronized void increment() {
    count++; // check-then-act: not atomic without synchronized
}
```

---

### Q9. What is the difference between `String`, `StringBuilder`, and `StringBuffer`?

| | `String` | `StringBuilder` | `StringBuffer` |
|---|---|---|---|
| Mutability | Immutable | Mutable | Mutable |
| Thread-safe | Yes (immutable) | No | Yes (synchronized) |
| Performance | Slow for concatenation | Fast | Slower than StringBuilder |
| Use case | Fixed values | Single-threaded string building | Multi-threaded string building |

In practice, always use `StringBuilder` inside methods since string building is usually single-threaded.

---

### Q10. Explain Java Streams — intermediate vs terminal operations.

Intermediate operations (`filter`, `map`, `sorted`, `distinct`, `limit`) are **lazy** — they don't execute until a terminal operation is called. Terminal operations (`collect`, `forEach`, `count`, `reduce`, `findFirst`) trigger the pipeline.

```java
List<String> names = employees.stream()
    .filter(e -> e.isActive())           // intermediate - lazy
    .sorted(Comparator.comparing(Employee::getName)) // intermediate - lazy
    .map(Employee::getName)              // intermediate - lazy
    .collect(Collectors.toList());       // terminal - triggers execution
```

---

## Spring Core

---

### Q11. What is Dependency Injection and what types does Spring support?

DI is a design pattern where dependencies are provided to a class rather than the class creating them itself, promoting loose coupling.

**Constructor Injection (recommended):**
```java
@Service
@RequiredArgsConstructor  // Lombok
public class EmployeeService {
    private final EmployeeRepository repository; // injected via constructor
}
```

**Setter Injection (for optional dependencies):**
```java
@Autowired
public void setRepository(EmployeeRepository repository) {
    this.repository = repository;
}
```

**Field Injection (avoid in production — harder to test):**
```java
@Autowired
private EmployeeRepository repository;
```

Constructor injection is preferred as it makes dependencies explicit, supports immutability, and is easier to unit test.

---

### Q12. What is the Spring Bean lifecycle?

```
Bean Definition Loaded
        ↓
Dependencies Injected
        ↓
@PostConstruct called  ← initialization logic (e.g., load cache)
        ↓
Bean Ready & In Use
        ↓
@PreDestroy called     ← cleanup logic (e.g., close connections)
        ↓
Bean Destroyed
```

```java
@Component
public class CacheLoader {

    @PostConstruct
    public void init() {
        // load initial data into cache on startup
    }

    @PreDestroy
    public void cleanup() {
        // close connections, flush cache
    }
}
```

---

### Q13. What are the different bean scopes in Spring?

| Scope | Description | Use Case |
|---|---|---|
| `singleton` | One instance per Spring container (default) | Stateless services |
| `prototype` | New instance every time requested | Stateful beans |
| `request` | One instance per HTTP request | Web layer data |
| `session` | One instance per HTTP session | User session data |
| `application` | One instance per ServletContext | App-wide shared state |

**Common pitfall:** Injecting a prototype bean into a singleton — the prototype is created once at injection time. Use `ApplicationContext.getBean()` or method injection to get a new prototype each time.

---

### Q14. What is the difference between `@Component`, `@Service`, `@Repository`, and `@Controller`?

All are specializations of `@Component` and trigger component scanning.

| Annotation | Layer | Extra Behavior |
|---|---|---|
| `@Component` | Generic | None |
| `@Repository` | Data access | Auto exception translation (JPA → `DataAccessException`) |
| `@Service` | Business logic | None (semantic marker) |
| `@Controller` | Web MVC | Handles HTTP, works with view resolvers |
| `@RestController` | Web REST | `@Controller` + `@ResponseBody` |

---

### Q15. What is `@Qualifier` and when do you use it?

When Spring finds multiple beans of the same type, it throws `NoUniqueBeanDefinitionException`. `@Qualifier("beanName")` tells Spring exactly which bean to inject.

```java
// Two implementations of the same interface
@Service("sbi")
public class SbiBankService implements BankService { ... }

@Service("hdfc")
public class HdfcBankService implements BankService { ... }

// Inject specific implementation
@Autowired
@Qualifier("sbi")
private BankService sbiBankService;

@Autowired
@Qualifier("hdfc")
private BankService hdfcBankService;
```

---

### Q16. What is the difference between `@Bean` and `@Component`?

| | `@Component` | `@Bean` |
|---|---|---|
| Placement | Class level | Method level inside `@Configuration` |
| Discovery | Classpath scanning | Explicit declaration |
| Use case | Your own classes | Third-party classes you can't annotate |

```java
// @Bean — for third-party classes like RestTemplate
@Configuration
public class AppConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
}
```

---

### Q17. What is Spring AOP and where have you used it?

AOP lets you separate cross-cutting concerns from business logic.

| Concept | Description |
|---|---|
| Aspect | Class containing cross-cutting logic |
| Advice | What runs (`@Before`, `@After`, `@Around`, `@AfterThrowing`) |
| Pointcut | Where it runs (method pattern expression) |
| JoinPoint | The specific execution point being intercepted |

```java
@Aspect
@Component
public class LoggingAspect {

    @Around("execution(* com.project.service.*.*(..))")
    public Object logExecutionTime(ProceedingJoinPoint joinPoint) throws Throwable {
        long start = System.currentTimeMillis();
        Object result = joinPoint.proceed();
        long time = System.currentTimeMillis() - start;
        log.info("{} executed in {}ms", joinPoint.getSignature(), time);
        return result;
    }

    @AfterThrowing(pointcut = "execution(* com.project.service.*.*(..))", throwing = "ex")
    public void logException(JoinPoint joinPoint, Exception ex) {
        log.error("Exception in {}: {}", joinPoint.getSignature(), ex.getMessage());
    }
}
```

---

## Spring Boot

---

### Q18. What does `@SpringBootApplication` do internally?

It is a meta-annotation combining three annotations:

```java
@SpringBootApplication
// is equivalent to:
@Configuration          // marks as bean definition source
@EnableAutoConfiguration // triggers auto-configuration
@ComponentScan          // scans package and sub-packages
public class BankingAppApplication { ... }
```

Auto-configuration works by scanning `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` and conditionally applying configurations based on what's on the classpath.

---

### Q19. How does Spring Boot auto-configuration work?

Spring Boot ships with hundreds of `@Configuration` classes with conditional annotations:

| Annotation | Meaning |
|---|---|
| `@ConditionalOnClass` | Only if class is on classpath |
| `@ConditionalOnMissingBean` | Only if no bean of that type exists |
| `@ConditionalOnProperty` | Only if property is set |
| `@ConditionalOnWebApplication` | Only in web context |

**Example:** If `DataSource` class is on the classpath and no `DataSource` bean is defined, Spring Boot auto-configures one from `application.properties`.

Debug with `--debug` flag or `/actuator/conditions` endpoint.

---

### Q20. How do you externalize configuration in Spring Boot?

**Property source priority (highest to lowest):**
1. Command-line arguments
2. `application-{profile}.properties`
3. `application.properties`
4. `@PropertySource` annotations
5. Default properties

```java
// Simple values
@Value("${banking.default-ifsc}")
private String defaultIfsc;

// Grouped config with validation
@ConfigurationProperties(prefix = "banking")
@Validated
public class BankingProperties {

    @NotNull
    private String defaultIfsc;

    private int timeout = 30; // default value
}
```

```properties
# application-dev.properties
banking.default-ifsc=SBI0001
banking.timeout=30

# application-prod.properties
banking.default-ifsc=HDFC0001
banking.timeout=10
```

---

### Q21. What is Spring Boot Actuator and which endpoints have you used?

Actuator exposes production-ready endpoints for monitoring and managing the app.

| Endpoint | Purpose |
|---|---|
| `/actuator/health` | Liveness/readiness probes (used in Kubernetes) |
| `/actuator/metrics` | CPU, memory, request counts |
| `/actuator/env` | Active properties |
| `/actuator/loggers` | Change log level at runtime without restart |
| `/actuator/conditions` | Debug auto-configuration |
| `/actuator/httptrace` | Recent HTTP request/response pairs |

```properties
# Expose specific endpoints
management.endpoints.web.exposure.include=health,metrics,loggers
management.endpoint.health.show-details=always
```

Actuator can be integrated with Prometheus and Grafana for real-time production dashboards.

---

## Spring Data JPA

---

### Q22. What is the difference between `CrudRepository`, `JpaRepository`, and `PagingAndSortingRepository`?

```
CrudRepository
    └── PagingAndSortingRepository
            └── JpaRepository
```

| Repository | Key Methods |
|---|---|
| `CrudRepository` | `save()`, `findById()`, `findAll()`, `delete()` |
| `PagingAndSortingRepository` | Adds `findAll(Pageable)`, `findAll(Sort)` |
| `JpaRepository` | Adds `flush()`, `saveAndFlush()`, `deleteInBatch()`, `getById()` |

`JpaRepository` is the go-to choice as it provides the most functionality.

---

### Q23. What is the N+1 problem and how do you fix it?

N+1 occurs when fetching N entities triggers N additional queries for their associations.

```java
// Problem: 1 query for orders + 100 queries for each order's customer
List<Order> orders = orderRepository.findAll(); // 1 query
orders.forEach(o -> o.getCustomer().getName()); // 100 queries!
```

**Fix 1: JOIN FETCH in JPQL**
```java
@Query("SELECT o FROM Order o JOIN FETCH o.customer")
List<Order> findAllWithCustomer();
```

**Fix 2: @EntityGraph**
```java
@EntityGraph(attributePaths = {"customer"})
List<Order> findAll();
```

**Fix 3: @BatchSize (fetches in batches)**
```java
@OneToMany
@BatchSize(size = 20)
private List<OrderItem> items;
```

---

### Q24. What is the difference between `FetchType.LAZY` and `FetchType.EAGER`?

| | `LAZY` | `EAGER` |
|---|---|---|
| When loaded | On first access | Immediately with parent |
| SQL | Separate query when accessed | JOIN with parent query |
| Default for `@OneToMany` | Yes | No |
| Default for `@ManyToOne` | No | Yes |
| Recommended | Yes | Avoid as default |

Always default to `LAZY` and fetch eagerly only when needed via `JOIN FETCH` or `@EntityGraph`. `EAGER` by default causes unnecessary data loading and can trigger N+1 in unexpected places.

---

### Q25. Explain `@Transactional` — propagation and isolation.

**Propagation:**

| Type | Behavior |
|---|---|
| `REQUIRED` (default) | Join existing transaction or create new |
| `REQUIRES_NEW` | Always create new, suspend existing |
| `NESTED` | Run in nested transaction with savepoint |
| `SUPPORTS` | Join if exists, else run non-transactionally |
| `NEVER` | Throw exception if transaction exists |

**Isolation:**

| Level | Prevents |
|---|---|
| `READ_UNCOMMITTED` | Nothing (dirty reads possible) |
| `READ_COMMITTED` | Dirty reads |
| `REPEATABLE_READ` | Dirty + non-repeatable reads |
| `SERIALIZABLE` | All anomalies (worst performance) |

```java
// Main transaction
@Transactional
public void processOrder(Order order) {
    orderRepository.save(order);
    auditService.log(order); // runs in NEW transaction
}

// Audit always saved even if main tx rolls back
@Transactional(propagation = Propagation.REQUIRES_NEW)
public void log(Order order) {
    auditRepository.save(new AuditLog(order));
}
```

---

## Spring REST & Web

---

### Q26. What is the difference between `@RequestParam`, `@PathVariable`, and `@RequestBody`?

```java
// @PathVariable — from URL path: /employees/123
@GetMapping("/employees/{id}")
public Employee getById(@PathVariable Long id) { ... }

// @RequestParam — from query string: /employees?dept=IT&active=true
@GetMapping("/employees")
public List<Employee> getByDept(
        @RequestParam String dept,
        @RequestParam(defaultValue = "true") boolean active) { ... }

// @RequestBody — from HTTP request body (JSON)
@PostMapping("/employees")
public Employee create(@RequestBody EmployeeRequest request) { ... }
```

---

### Q27. How do you handle exceptions globally in Spring Boot?

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(EmployeeNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(EmployeeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ErrorResponse(ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ErrorResponse(message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ErrorResponse("Something went wrong"));
    }
}
```

---

### Q28. How do you validate request bodies in Spring Boot?

```java
// DTO with validation annotations
public class EmployeeRequest {

    @NotBlank(message = "Name is required")
    private String name;

    @Email(message = "Invalid email format")
    private String email;

    @Min(value = 18, message = "Age must be at least 18")
    private int age;

    @NotNull(message = "Department is required")
    private String department;
}

// Controller triggers validation with @Valid
@PostMapping("/employees")
public ResponseEntity<Employee> create(@Valid @RequestBody EmployeeRequest request) {
    return ResponseEntity.ok(employeeService.create(request));
}
```

Spring throws `MethodArgumentNotValidException` on failure, handled in `@ControllerAdvice`.

---

## Microservices & Advanced

---

### Q29. How do services communicate in a microservices architecture?

**Synchronous (REST):**
```java
// WebClient — non-blocking, preferred
@Service
public class PaymentClient {

    private final WebClient webClient;

    public PaymentClient(WebClient.Builder builder) {
        this.webClient = builder.baseUrl("http://payment-service").build();
    }

    public Mono<PaymentResponse> processPayment(PaymentRequest request) {
        return webClient.post()
                .uri("/payments")
                .bodyValue(request)
                .retrieve()
                .bodyToMono(PaymentResponse.class);
    }
}
```

**Asynchronous (Kafka):**
```java
// Producer
kafkaTemplate.send("order-placed", orderEvent);

// Consumer
@KafkaListener(topics = "order-placed", groupId = "inventory-service")
public void handleOrderPlaced(OrderEvent event) {
    inventoryService.reserveStock(event);
}
```

---

### Q30. What is a Circuit Breaker and have you used Resilience4j?

A circuit breaker prevents cascading failures. When a downstream service fails repeatedly, the circuit opens and subsequent calls fail fast without hitting the service.

```
CLOSED → failures exceed threshold → OPEN → wait duration → HALF-OPEN → success → CLOSED
                                                                        ↓ failure
                                                                       OPEN
```

```java
@CircuitBreaker(name = "paymentService", fallbackMethod = "paymentFallback")
@Retry(name = "paymentService")
@TimeLimiter(name = "paymentService")
public PaymentResponse processPayment(PaymentRequest request) {
    return paymentClient.process(request);
}

public PaymentResponse paymentFallback(PaymentRequest request, Exception ex) {
    log.warn("Payment service unavailable, returning default response");
    return PaymentResponse.defaultResponse();
}
```

```properties
# application.properties
resilience4j.circuitbreaker.instances.paymentService.failure-rate-threshold=50
resilience4j.circuitbreaker.instances.paymentService.wait-duration-in-open-state=10s
resilience4j.circuitbreaker.instances.paymentService.slow-call-rate-threshold=80
```

---

### Q31. How do you secure REST APIs in Spring Boot?

**JWT-based security flow:**

```
Client → POST /login (credentials)
       ← JWT token

Client → GET /employees (Authorization: Bearer <token>)
       → JwtFilter validates token
       → Sets SecurityContext
       → Request proceeds
```

```java
// JWT Filter
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = extractToken(request);
        if (token != null && jwtService.isValid(token)) {
            UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(
                    jwtService.getUsername(token), null,
                    jwtService.getAuthorities(token));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }
        chain.doFilter(request, response);
    }
}

// Method-level authorization
@PreAuthorize("hasRole('ADMIN')")
public void deleteEmployee(Long id) { ... }
```

---

### Q32. How do you implement pagination in Spring Boot?

```java
// Repository
public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Page<Employee> findByDepartment(String dept, Pageable pageable);
}

// Service
public Page<Employee> getByDept(String dept, int page, int size) {
    Pageable pageable = PageRequest.of(page, size, Sort.by("name").ascending());
    return employeeRepository.findByDepartment(dept, pageable);
}

// Controller
@GetMapping("/employees")
public Page<Employee> getEmployees(
        @RequestParam String dept,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {
    return employeeService.getByDept(dept, page, size);
}
```

Response includes `content`, `totalElements`, `totalPages`, `number` — everything a frontend needs for pagination controls.

---

### Q33. What is caching and how have you implemented it in Spring Boot?

```java
// Enable caching
@SpringBootApplication
@EnableCaching
public class BankingAppApplication { ... }

// Cache on read
@Cacheable(value = "employees", key = "#id")
public Employee getById(Long id) {
    return employeeRepository.findById(id).orElseThrow();
}

// Update cache on write
@CachePut(value = "employees", key = "#employee.id")
public Employee update(Employee employee) {
    return employeeRepository.save(employee);
}

// Evict cache on delete
@CacheEvict(value = "employees", key = "#id")
public void delete(Long id) {
    employeeRepository.deleteById(id);
}
```

```java
// Redis cache config with TTL
@Bean
public RedisCacheConfiguration cacheConfiguration() {
    return RedisCacheConfiguration.defaultCacheConfig()
            .entryTtl(Duration.ofMinutes(30))
            .disableCachingNullValues();
}
```

---

### Q34. How do you write unit tests vs integration tests in Spring Boot?

**Unit Test — fast, no Spring context:**
```java
@ExtendWith(MockitoExtension.class)
class EmployeeServiceTest {

    @Mock
    private EmployeeRepository repository;

    @InjectMocks
    private EmployeeService service;

    @Test
    void shouldReturnEmployee_whenValidId() {
        Employee emp = new Employee(1L, "Sourabh");
        when(repository.findById(1L)).thenReturn(Optional.of(emp));

        Employee result = service.getById(1L);

        assertThat(result.getName()).isEqualTo("Sourabh");
        verify(repository, times(1)).findById(1L);
    }
}
```

**Integration Test — full stack with real HTTP:**
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class EmployeeControllerIntegrationTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @LocalServerPort
    private int port;

    @Test
    void shouldCreateEmployee() {
        EmployeeRequest request = new EmployeeRequest("Sourabh", "sourabh@test.com");

        ResponseEntity<Employee> response = restTemplate.postForEntity(
                "http://localhost:" + port + "/employees", request, Employee.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getName()).isEqualTo("Sourabh");
    }
}
```

| Test Type | Annotation | Speed | Scope |
|---|---|---|---|
| Unit | `@ExtendWith(MockitoExtension.class)` | Fast | Single class |
| Web layer only | `@WebMvcTest` | Medium | Controller + filters |
| JPA layer only | `@DataJpaTest` | Medium | Repository + DB |
| Full integration | `@SpringBootTest` | Slow | Entire application |

---

### Q35. How do you handle database migrations in Spring Boot?

Using **Flyway** — SQL scripts named with version prefixes run automatically on startup in order.

```
src/main/resources/db/migration/
    V1__init_schema.sql
    V2__add_employee_table.sql
    V3__add_department_column.sql
```

```sql
-- V1__init_schema.sql
CREATE TABLE employee (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL
);
```

```properties
# application.properties
spring.flyway.enabled=true
spring.flyway.locations=classpath:db/migration
spring.jpa.hibernate.ddl-auto=validate  # never use 'update' in production
```

Flyway tracks applied migrations in a `flyway_schema_history` table. This replaces `ddl-auto=update` in production, which is risky as it cannot safely drop columns or handle complex schema changes.

---

## Quick Reference — Topic Coverage

| Topic | Questions |
|---|---|
| Core Java | Q1 – Q10 |
| Spring Core (DI, AOP, Beans) | Q11 – Q17 |
| Spring Boot (Auto-config, Actuator) | Q18 – Q21 |
| Spring Data JPA | Q22 – Q25 |
| Spring REST & Web | Q26 – Q28 |
| Microservices & Advanced | Q29 – Q35 |

---

> These questions cover what's typically asked at the **4–6 year experience level**. Interviewers will usually start broad and drill deeper based on your answers — be ready to follow up each answer with a real example from your own projects.
