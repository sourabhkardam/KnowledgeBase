# Spring Framework vs Spring Boot — Comprehensive Interview Guide

## 1. The Core Relationship (Read This First)

**Spring Boot is NOT a replacement for Spring Framework — it is built on top of it.**

Think of it like this:
- **Spring Framework** = the engine (IoC container, DI, AOP, MVC, Data Access, Security, Transactions)
- **Spring Boot** = the car built around that engine (auto-configuration, embedded server, starter dependencies, production tooling) that lets you drive off immediately without assembling the engine yourself

Spring Boot doesn't reinvent anything — every `@Autowired`, every `ApplicationContext`, every `@Transactional` you use in a Spring Boot app is still 100% Spring Framework underneath. Spring Boot just removes the **setup and configuration burden**.

---

## 2. Why Spring Boot Was Created

Before Spring Boot (pre-2014), a typical Spring web app required:
- Manual `web.xml` or `DispatcherServlet` configuration
- Manual `pom.xml` dependency version management (and version conflicts — "dependency hell")
- Manual `DataSource`, `EntityManagerFactory`, `TransactionManager` bean definitions
- External Tomcat/Jetty server installation and WAR deployment
- Dozens of lines of XML or `@Configuration` boilerplate before writing a single line of business logic

Spring Boot's philosophy: **"Convention over Configuration."** Sensible defaults out of the box, override only what you need.

---

## 3. Key Differences — With Examples

### Difference 1: Configuration Approach

**Spring Framework** requires explicit configuration — either XML or Java `@Configuration` classes — for almost everything.

```xml
<!-- applicationContext.xml (Spring Framework - old style) -->
<beans xmlns="http://www.springframework.org/schema/beans">
    <bean id="dataSource" class="org.apache.commons.dbcp2.BasicDataSource">
        <property name="driverClassName" value="org.postgresql.Driver"/>
        <property name="url" value="jdbc:postgresql://localhost:5432/mydb"/>
        <property name="username" value="postgres"/>
        <property name="password" value="secret"/>
    </bean>

    <bean id="entityManagerFactory"
          class="org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean">
        <property name="dataSource" ref="dataSource"/>
        <property name="packagesToScan" value="com.example.entity"/>
    </bean>

    <bean id="transactionManager"
          class="org.springframework.orm.jpa.JpaTransactionManager">
        <property name="entityManagerFactory" ref="entityManagerFactory"/>
    </bean>
</beans>
```

Or the Java-config equivalent (still Spring Framework, no Boot):

```java
@Configuration
@EnableTransactionManagement
public class AppConfig {
    @Bean
    public DataSource dataSource() {
        BasicDataSource ds = new BasicDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl("jdbc:postgresql://localhost:5432/mydb");
        ds.setUsername("postgres");
        ds.setPassword("secret");
        return ds;
    }

    @Bean
    public LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource ds) {
        LocalContainerEntityManagerFactoryBean emf = new LocalContainerEntityManagerFactoryBean();
        emf.setDataSource(ds);
        emf.setPackagesToScan("com.example.entity");
        return emf;
    }

    @Bean
    public PlatformTransactionManager transactionManager(EntityManagerFactory emf) {
        return new JpaTransactionManager(emf);
    }
}
```

**Spring Boot** replaces all of this with `application.properties`/`application.yml` — the beans above are auto-configured behind the scenes when Boot sees PostgreSQL driver + Spring Data JPA on the classpath.

```yaml
# application.yml (Spring Boot)
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: postgres
    password: secret
  jpa:
    hibernate:
      ddl-auto: update
```

That's it. `DataSource`, `EntityManagerFactory`, and `PlatformTransactionManager` beans are created for you by `DataSourceAutoConfiguration` and `HibernateJpaAutoConfiguration`.

---

### Difference 2: Dependency Management

**Spring Framework**: you manually pick and pin every dependency version yourself, and you're responsible for making sure they're all compatible with each other.

```xml
<!-- pom.xml (Spring Framework) -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-webmvc</artifactId>
    <version>5.3.30</version>
</dependency>
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-orm</artifactId>
    <version>5.3.30</version>
</dependency>
<dependency>
    <groupId>org.hibernate</groupId>
    <artifactId>hibernate-core</artifactId>
    <version>5.6.15.Final</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.15.2</version>
</dependency>
<!-- ...and you must ensure all of these versions are mutually compatible -->
```

**Spring Boot** uses **starter dependencies** — curated, version-tested bundles managed by a parent BOM (`spring-boot-dependencies`).

```xml
<!-- pom.xml (Spring Boot) -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.2.5</version>
</parent>

<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-web</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
</dependencies>
<!-- no versions needed - the parent BOM picks compatible versions for everything -->
```

`spring-boot-starter-web` alone transitively pulls in Spring MVC, embedded Tomcat, Jackson, and validation — all pre-tested to work together.

---

### Difference 3: Embedded Server vs External Deployment

**Spring Framework**: you build a WAR file and deploy it to an externally installed Tomcat/JBoss/WebSphere server.

```java
// web.xml or WebApplicationInitializer needed to bootstrap DispatcherServlet
public class MyWebAppInitializer implements WebApplicationInitializer {
    @Override
    public void onStartup(ServletContext container) {
        AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext();
        context.register(AppConfig.class);
        container.addListener(new ContextLoaderListener(context));

        ServletRegistration.Dynamic dispatcher =
            container.addServlet("dispatcher", new DispatcherServlet(context));
        dispatcher.setLoadOnStartup(1);
        dispatcher.addMapping("/");
    }
}
// Then: build WAR -> copy to Tomcat/webapps -> start Tomcat separately
```

**Spring Boot**: an embedded Tomcat (or Jetty/Undertow) is bundled directly inside the executable JAR. No external server installation needed.

```java
@SpringBootApplication
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
```

```bash
mvn clean package
java -jar myapp.jar   # server starts on port 8080 - nothing else to install
```

---

### Difference 3a: Deep Dive — Do You Need to Configure DispatcherServlet Manually in Spring?

**Short answer: Yes, in plain Spring Framework you must explicitly register and configure `DispatcherServlet` yourself. In Spring Boot, it's done for you automatically.**

#### What DispatcherServlet actually is

`DispatcherServlet` is the **front controller** of Spring MVC — every incoming HTTP request goes through it first. It then delegates to `HandlerMapping` to find the right controller method, invokes it, and passes the result to a `ViewResolver` (or, for `@RestController`, straight to an `HttpMessageConverter` for JSON serialization). Without a registered `DispatcherServlet`, none of your `@Controller`/`@RestController` classes will ever receive a request — Spring MVC simply won't be wired into the servlet container.

#### Option 1: XML-based registration (`web.xml`) — Servlet 2.5 / legacy style

You declare the servlet and map it to a URL pattern inside `web.xml`, and point it at a Spring context config file:

```xml
<!-- src/main/webapp/WEB-INF/web.xml -->
<web-app xmlns="http://xmlns.jcp.org/xml/ns/javaee" version="3.1">

    <!-- Root application context (service/repository beans) -->
    <context-param>
        <param-name>contextConfigLocation</param-name>
        <param-value>/WEB-INF/applicationContext.xml</param-value>
    </context-param>
    <listener>
        <listener-class>org.springframework.web.context.ContextLoaderListener</listener-class>
    </listener>

    <!-- DispatcherServlet - the front controller -->
    <servlet>
        <servlet-name>dispatcher</servlet-name>
        <servlet-class>org.springframework.web.servlet.DispatcherServlet</servlet-class>
        <init-param>
            <param-name>contextConfigLocation</param-name>
            <param-value>/WEB-INF/dispatcher-servlet.xml</param-value>
        </init-param>
        <load-on-startup>1</load-on-startup>
    </servlet>

    <servlet-mapping>
        <servlet-name>dispatcher</servlet-name>
        <url-pattern>/</url-pattern>
    </servlet-mapping>

</web-app>
```

```xml
<!-- WEB-INF/dispatcher-servlet.xml -->
<beans xmlns="http://www.springframework.org/schema/beans"
       xmlns:mvc="http://www.springframework.org/schema/mvc"
       xmlns:context="http://www.springframework.org/schema/context">

    <mvc:annotation-driven/>
    <context:component-scan base-package="com.example.controller"/>

    <bean class="org.springframework.web.servlet.view.InternalResourceViewResolver">
        <property name="prefix" value="/WEB-INF/views/"/>
        <property name="suffix" value=".jsp"/>
    </bean>
</beans>
```

Two contexts are created here: a **root context** (`ContextLoaderListener` — service/repository/DAO beans, shared app-wide) and a **child servlet context** (`dispatcher-servlet.xml` — controllers, `ViewResolver`s, web-specific beans). This parent-child split is classic Spring MVC and trips people up in interviews.

#### Option 2: Java-based registration (`WebApplicationInitializer`) — Servlet 3.0+, no `web.xml` needed

Since Servlet 3.0, containers auto-detect any class implementing `WebApplicationInitializer` on startup — no `web.xml` at all:

```java
public class MyWebAppInitializer extends AbstractAnnotationConfigDispatcherServletInitializer {

    @Override
    protected Class<?>[] getRootConfigClasses() {
        // service/repository beans - the "root" context
        return new Class[]{ RootConfig.class };
    }

    @Override
    protected Class<?>[] getServletConfigClasses() {
        // controllers, view resolvers - the "servlet" context
        return new Class[]{ WebConfig.class };
    }

    @Override
    protected String[] getServletMappings() {
        return new String[]{ "/" };
    }
}
```

```java
@Configuration
@EnableWebMvc
@ComponentScan(basePackages = "com.example.controller")
public class WebConfig implements WebMvcConfigurer {
    @Bean
    public InternalResourceViewResolver viewResolver() {
        InternalResourceViewResolver resolver = new InternalResourceViewResolver();
        resolver.setPrefix("/WEB-INF/views/");
        resolver.setSuffix(".jsp");
        return resolver;
    }
}
```

`AbstractAnnotationConfigDispatcherServletInitializer` internally creates the `DispatcherServlet`, registers it against the servlet container, and wires the root + servlet contexts for you — but you still had to write this class yourself. That's the key point: **the wiring code exists, you just don't hand-write raw servlet registration calls.**

You could also skip the abstract base class entirely and register everything manually with the raw Servlet API:

```java
public class RawInitializer implements WebApplicationInitializer {
    @Override
    public void onStartup(ServletContext container) {
        AnnotationConfigWebApplicationContext ctx = new AnnotationConfigWebApplicationContext();
        ctx.register(WebConfig.class);

        ServletRegistration.Dynamic servlet =
            container.addServlet("dispatcher", new DispatcherServlet(ctx));
        servlet.setLoadOnStartup(1);
        servlet.addMapping("/");
    }
}
```

#### A Common Point of Confusion: "Servlet Mapping" vs "Controller Mapping"

These are **two completely different levels of URL mapping** — mixing them up is a very common interview stumble.

**Level 1 — Servlet-level mapping (which requests does DispatcherServlet even see?)**

This is the `<url-pattern>` in `web.xml` or the `getServletMappings()` / `addMapping()` call shown above. It answers: *"Out of every request hitting this web app, which ones should be handed to DispatcherServlet at all?"* It's usually just `/` (catch-all) — meaning **every** request goes to DispatcherServlet, and it decides what to do with each one next.

```java
protected String[] getServletMappings() {
    return new String[]{ "/" };  // ALL requests go to DispatcherServlet
}
```

**Level 2 — Handler-level mapping (which controller *method* handles this specific URL?)**

Once a request reaches `DispatcherServlet`, it does **not** decide the controller itself — it delegates that decision to a `HandlerMapping` bean. In modern Spring MVC this is `RequestMappingHandlerMapping`, which scans all `@Controller`/`@RestController` beans at startup and builds a lookup table from `@RequestMapping`/`@GetMapping`/`@PostMapping` etc. paths to controller methods.

```java
@RestController
@RequestMapping("/api/users")
public class UserController {

    @GetMapping("/{id}")          // GET /api/users/{id}  -> this method
    public User getUser(@PathVariable Long id) { ... }

    @PostMapping                  // POST /api/users       -> this method
    public User createUser(@RequestBody User user) { ... }

    @GetMapping                   // GET /api/users        -> this method
    public List<User> listUsers() { ... }
}
```

So the actual request flow is:

```
Request: GET /api/users/42
    │
    ▼
Servlet container checks servlet-level url-pattern ("/") → matches → sent to DispatcherServlet
    │
    ▼
DispatcherServlet asks HandlerMapping: "who handles /api/users/42?"
    │
    ▼
RequestMappingHandlerMapping looks up its table → finds UserController.getUser()
    │
    ▼
HandlerAdapter invokes UserController.getUser(42)
    │
    ▼
Return value -> HttpMessageConverter (Jackson) -> JSON response
```

**In one sentence for interviews:** *the servlet mapping decides whether DispatcherServlet gets involved at all (almost always "yes, for everything"); the `@RequestMapping` annotations on your controllers are what actually decide which specific method handles which specific URL — DispatcherServlet itself holds no URL-to-controller table, it just delegates that lookup to `HandlerMapping`.*

This mapping table (path → controller method) is built identically whether you're on plain Spring Framework or Spring Boot — `RequestMappingHandlerMapping` is core `spring-webmvc`, completely unaffected by which one you use. Boot only automates step 1 (getting a DispatcherServlet registered); step 2 (`@RequestMapping` resolution) is unchanged Spring MVC either way.



In Spring Boot, you never touch `DispatcherServlet` registration. When `spring-boot-starter-web` is on the classpath, `DispatcherServletAutoConfiguration` automatically:
1. Creates a `DispatcherServlet` bean with sensible defaults
2. Registers it against the **embedded** servlet container (Tomcat/Jetty/Undertow) via a `ServletRegistrationBean`, mapped to `/` by default
3. Wires it into the single `ApplicationContext` Boot creates (Boot doesn't use the root/child context split — it's one unified context, which simplifies bean visibility and is a common interview gotcha: *"How many ApplicationContexts does a Spring Boot web app have, vs classic Spring MVC?"* → Boot: one; classic Spring MVC with `web.xml`/initializer: two, parent-child)

```java
@SpringBootApplication
public class MyApp {
    public static void main(String[] args) {
        SpringApplication.run(MyApp.class, args);
    }
}
// DispatcherServlet is created, configured, and mapped to "/" automatically - nothing to write
```

If you ever do need to customize it in Boot (rare), you can still influence it declaratively without hand-registering anything:

```yaml
spring:
  mvc:
    servlet:
      path: /api   # changes DispatcherServlet's mapping from "/" to "/api/*"
```

or programmatically override the auto-configured bean:

```java
@Bean
public DispatcherServlet dispatcherServlet() {
    DispatcherServlet ds = new DispatcherServlet();
    ds.setThrowExceptionIfNoHandlerFound(true);
    return ds;
}
```

---

### Difference 4: Auto-Configuration

**Spring Framework**: nothing is configured automatically. You explicitly declare every bean you need — `@ComponentScan`, `@EnableWebMvc`, `ViewResolver` beans, `ObjectMapper` beans, etc.

**Spring Boot**: `@EnableAutoConfiguration` (bundled inside `@SpringBootApplication`) inspects the classpath and auto-registers beans that make sense.

```java
@SpringBootApplication
// This single annotation = @Configuration + @ComponentScan + @EnableAutoConfiguration
public class MyApp { ... }
```

Example of what happens under the hood: if Boot sees `spring-webmvc` + an embedded servlet container on the classpath, `WebMvcAutoConfiguration` fires and configures `DispatcherServlet`, a default `ViewResolver`, `HttpMessageConverters` (including a Jackson `ObjectMapper` for JSON), and static resource handling — all without you writing a single `@Bean` method. You only override what you need:

```java
@Configuration
public class WebConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**").allowedOrigins("https://example.com");
    }
}
// Boot's defaults still apply everywhere else - you only customized CORS
```

This works through `spring.factories` (older Boot) or `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` (Boot 3.x), combined with `@ConditionalOnClass`, `@ConditionalOnMissingBean`, `@ConditionalOnProperty` — conditional annotations that decide whether an auto-configuration class should activate.

---

### Difference 5: Boilerplate for a Simple REST Endpoint

**Spring Framework** (Spring MVC only, no Boot) — minimum viable setup:

```java
// 1. Web initializer
public class WebInit extends AbstractAnnotationConfigDispatcherServletInitializer {
    protected Class<?>[] getRootConfigClasses() { return new Class[]{ AppConfig.class }; }
    protected Class<?>[] getServletConfigClasses() { return new Class[]{ WebConfig.class }; }
    protected String[] getServletMappings() { return new String[]{ "/" }; }
}

// 2. Web config
@Configuration
@EnableWebMvc
@ComponentScan("com.example")
public class WebConfig implements WebMvcConfigurer { }

// 3. Controller
@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() { return "Hello"; }
}
// Plus: WAR packaging, Tomcat installation, deployment
```

**Spring Boot** — same endpoint:

```java
@SpringBootApplication
public class MyApp {
    public static void main(String[] args) { SpringApplication.run(MyApp.class, args); }
}

@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() { return "Hello"; }
}
// java -jar myapp.jar -> done
```

---

### Difference 6: Production-Readiness (Actuator, Metrics, Health Checks)

**Spring Framework** provides no built-in operational tooling — you'd have to hand-roll health checks and metrics endpoints yourself.

**Spring Boot** ships with **Spring Boot Actuator**, giving production-grade monitoring endpoints for free:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health, metrics, info, prometheus
```

This instantly exposes `/actuator/health`, `/actuator/metrics`, `/actuator/prometheus`, etc. — no Spring Framework equivalent exists out of the box (this ties directly into the Actuator/Micrometer/Prometheus/Grafana work you've done before).

---

### Difference 7: Opinionated Defaults

| Aspect | Spring Framework | Spring Boot |
|---|---|---|
| Default embedded server | None | Tomcat (switchable to Jetty/Undertow) |
| Default JSON library | You choose and wire | Jackson (pre-wired) |
| Default logging | You choose and wire | Logback (pre-wired) |
| Default error handling | You build it | `BasicErrorController` provides a default `/error` JSON response |
| Default profile-based config | Manual `@Profile` + externalized property wiring | `application-{profile}.yml` auto-picked up via `spring.profiles.active` |

---

## 4. Master Comparison Table

| Feature | Spring Framework | Spring Boot |
|---|---|---|
| **Nature** | Core framework (IoC, DI, AOP, MVC, etc.) | Tool built on top of Spring Framework |
| **Configuration** | Manual (XML or Java `@Configuration`) | Auto-configuration based on classpath |
| **Boilerplate** | High | Minimal |
| **Server** | External (WAR deployment) | Embedded (Tomcat/Jetty/Undertow), runnable JAR |
| **Dependency management** | Manual version pinning | Starter POMs + BOM version management |
| **Production monitoring** | Not built in | Actuator provides health/metrics endpoints out of the box |
| **Learning curve** | Steeper (must understand wiring) | Gentler to start, but hides complexity you eventually need to understand for debugging |
| **Microservices support** | Requires manual integration (Eureka, Config Server, etc.) | First-class support via Spring Cloud starters |
| **CLI tooling** | None | Spring Boot CLI, Spring Initializr |
| **Use case today** | Legacy systems, or when you need extremely fine-grained manual control | Default choice for virtually all new Spring projects |

---

## 5. What Spring Boot Inherits Directly From Spring Framework

This is the part most candidates get fuzzy on in interviews: **Spring Boot adds nothing new to the core programming model.** Every one of these is pure Spring Framework, just auto-wired for you by Boot:

### 5.1 IoC Container & Dependency Injection
`ApplicationContext`, `BeanFactory`, `@Component`, `@Autowired`, `@Qualifier`, constructor/setter/field injection, bean scopes (`singleton`, `prototype`, `request`, `session`) — all Spring Framework core (`spring-core`, `spring-beans`, `spring-context`). This is exactly the prototype-scope/`ObjectProvider` internals you were studying — none of that is Boot-specific; it's core container behavior Boot simply activates by default.

### 5.2 Aspect-Oriented Programming (AOP)
`@Aspect`, `@Before`, `@After`, `@Around`, proxy-based interception (JDK dynamic proxies / CGLIB) — from `spring-aop`. Boot auto-configures `AopAutoConfiguration` but the AOP engine itself is 100% Framework.

### 5.3 Spring MVC
`DispatcherServlet`, `@Controller`, `@RestController`, `@RequestMapping` family, `HandlerMapping`, `HandlerAdapter`, `ViewResolver`, `HttpMessageConverter` — all from `spring-webmvc`. Boot's `WebMvcAutoConfiguration` just wires these beans for you; the request-handling pipeline itself is unchanged Spring MVC.

### 5.4 Spring Data Access (JDBC / ORM / Transactions)
`JdbcTemplate`, `PlatformTransactionManager`, `@Transactional` and its propagation/isolation semantics, `JpaTransactionManager` — from `spring-tx` and `spring-orm`. This is exactly the `@Transactional` propagation and N+1/fetch-type work you've studied; Boot doesn't change any transaction semantics, it just auto-detects a `DataSource` and wires the transaction manager.

### 5.5 Spring Data JPA
Repository abstractions (`CrudRepository`, `JpaRepository`), derived query methods, `@Query` — from the separate `spring-data-jpa` project (part of the broader Spring ecosystem, predates Boot). Boot's `spring-boot-starter-data-jpa` just bundles it with Hibernate and auto-configures the `EntityManagerFactory`.

### 5.6 Spring Security
Filter chain architecture, `SecurityContextHolder`, `AuthenticationManager`, `UserDetailsService`, method-level security (`@PreAuthorize`) — from `spring-security-core`/`spring-security-web`, a separate umbrella project. This is exactly the JWT/Basic Auth security work you did; Boot's `spring-boot-starter-security` only auto-configures a default filter chain — the security model itself is unchanged Spring Security.

### 5.7 Spring Testing Support
`@ContextConfiguration`, `TestContextManager`, `MockMvc` — from `spring-test`. Boot's `@SpringBootTest` is a thin wrapper that adds auto-configuration-aware context loading on top of this.

### 5.8 Resource Abstraction, Type Conversion, Validation, Expression Language (SpEL)
`Resource`/`ResourceLoader`, `ConversionService`, JSR-380 Bean Validation integration, SpEL (`#{...}`) — all core Spring Framework modules Boot uses as-is.

---

## 6. The One-Line Answer (Interview-Ready Summary)

> **Spring Framework** is the foundational framework providing IoC/DI, AOP, MVC, data access, and security — but requires you to manually configure beans, dependencies, and the server. **Spring Boot** is built directly on top of Spring Framework and adds auto-configuration, embedded servers, starter dependency bundles, and production-ready tooling (Actuator) to eliminate that setup boilerplate — but every core capability (DI, transactions, MVC, security) it exposes at runtime is unmodified Spring Framework underneath.

**Good follow-up line if asked "so is Spring Boot a framework?"**: Technically it's more accurately described as a *convention-over-configuration layer / tool* on top of Spring Framework, not a separate framework with its own programming model — this distinction itself is a common interview trick question.

---

## 7. Quick Self-Test Questions (For Revision)

1. Can you use Spring Framework without Spring Boot? *(Yes — this was the only option pre-2014, and is still valid for legacy or highly customized setups.)*
2. Can you disable a specific auto-configuration in Spring Boot? *(Yes — `@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)`)*
3. Does Spring Boot replace Spring MVC? *(No — it auto-configures Spring MVC; the DispatcherServlet/HandlerMapping pipeline is identical.)*
4. Where does Spring Boot's auto-configuration logic actually live? *(`spring-boot-autoconfigure` module, driven by `@Conditional*` annotations and the `AutoConfiguration.imports` file in Boot 3.x.)*
5. If `@Transactional` misbehaves in a Boot app, is that a Boot bug or a Framework bug? *(Almost always a Framework-level proxy/self-invocation issue — because `@Transactional` semantics are pure Spring Framework, unchanged by Boot.)*
