# Creational Design Patterns — Java Reference Guide

> **Series:** GoF Design Patterns | **Category:** Creational (5 Patterns)
> **Coverage:** Singleton · Factory Method · Abstract Factory · Builder · Prototype

---

## What are Creational Patterns?

Creational patterns deal with **object creation mechanisms**. They abstract the instantiation process so the system is independent of how its objects are created, composed, and represented. They answer the question: **"Who creates objects, how, and when?"**

---

## Pattern 1 — Singleton

### What is it?

Ensures a class has **only one instance** and provides a **global access point** to it. Useful for shared resources like config, logging, thread pools, or DB connections.

### Real-World Example: Application Configuration Manager

```java
public class ConfigurationManager {

    // volatile ensures visibility across threads
    private static volatile ConfigurationManager instance;

    private final Map<String, String> configs = new HashMap<>();

    // Private constructor — no one can do `new ConfigurationManager()`
    private ConfigurationManager() {
        // Simulate loading from a file or environment
        configs.put("db.url", "jdbc:postgresql://localhost:5432/mydb");
        configs.put("db.pool.size", "10");
        configs.put("app.env", "production");
        System.out.println("ConfigurationManager initialized.");
    }

    // Double-checked locking — thread-safe & performant
    public static ConfigurationManager getInstance() {
        if (instance == null) {
            synchronized (ConfigurationManager.class) {
                if (instance == null) {
                    instance = new ConfigurationManager();
                }
            }
        }
        return instance;
    }

    public String get(String key) {
        return configs.getOrDefault(key, "NOT_FOUND");
    }
}
```

```java
public class Main {
    public static void main(String[] args) {
        ConfigurationManager config1 = ConfigurationManager.getInstance();
        ConfigurationManager config2 = ConfigurationManager.getInstance();

        System.out.println(config1.get("db.url"));
        System.out.println(config1 == config2); // true — same instance
    }
}
```

> **Spring Boot note:** Every `@Service`, `@Repository`, `@Component` bean is a Singleton by default. Spring manages it for you — you rarely need to hand-roll this.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Controlled single instance | Hard to unit test (global state) |
| Lazy initialization possible | Violates Single Responsibility |
| Saves resources for heavy objects | Can become an anti-pattern if overused |

### When to Use / Avoid

- ✔ Logging, config, connection pools, caches
- ✖ Avoid when you need multiple instances later — it's very hard to undo

---

## Pattern 2 — Factory Method

### What is it?

Defines an **interface for creating an object**, but lets **subclasses decide** which class to instantiate. The client works with the interface — it never knows the concrete type.

### Real-World Example: Notification Service

```java
// Product interface
public interface Notification {
    void send(String recipient, String message);
}

// Concrete Products
public class EmailNotification implements Notification {
    @Override
    public void send(String recipient, String message) {
        System.out.println("EMAIL to " + recipient + ": " + message);
    }
}

public class SMSNotification implements Notification {
    @Override
    public void send(String recipient, String message) {
        System.out.println("SMS to " + recipient + ": " + message);
    }
}

public class PushNotification implements Notification {
    @Override
    public void send(String recipient, String message) {
        System.out.println("PUSH to " + recipient + ": " + message);
    }
}
```

```java
// Creator — the factory
public class NotificationFactory {

    public static Notification create(String type) {
        return switch (type.toUpperCase()) {
            case "EMAIL" -> new EmailNotification();
            case "SMS"   -> new SMSNotification();
            case "PUSH"  -> new PushNotification();
            default -> throw new IllegalArgumentException("Unknown type: " + type);
        };
    }
}
```

```java
// Client — doesn't know or care which class it gets
public class AlertService {
    public void sendAlert(String channel, String user, String msg) {
        Notification notification = NotificationFactory.create(channel);
        notification.send(user, msg);
    }
}
```

```java
public class Main {
    public static void main(String[] args) {
        AlertService alertService = new AlertService();
        alertService.sendAlert("EMAIL", "sourabh@example.com", "Your order shipped!");
        alertService.sendAlert("SMS", "+91-9999999999", "OTP: 482910");
        alertService.sendAlert("PUSH", "device_token_xyz", "Flash sale starts now!");
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Decouples creation from usage | Can grow into a large switch/if chain |
| Easy to add new types | Requires a new class per product type |
| Follows Open/Closed Principle | Slight indirection can confuse beginners |

### When to Use / Avoid

- ✔ When object type is determined at runtime (from config, DB, user input)
- ✖ Avoid when you only ever create one type — just use `new`

---

## Pattern 3 — Abstract Factory

### What is it?

A **factory of factories**. Provides an interface for creating **families of related objects** without specifying their concrete classes. Think of it as Factory Method taken one level higher.

### Real-World Example: Multi-Database Support

Your app needs to support both PostgreSQL and MySQL. Each DB has its own `Connection` and `QueryBuilder` implementation.

```java
// Abstract products
public interface DbConnection {
    void connect();
}

public interface QueryBuilder {
    String build(String table, String condition);
}
```

```java
// PostgreSQL family
public class PostgresConnection implements DbConnection {
    @Override
    public void connect() {
        System.out.println("Connected to PostgreSQL");
    }
}

public class PostgresQueryBuilder implements QueryBuilder {
    @Override
    public String build(String table, String condition) {
        return "SELECT * FROM " + table + " WHERE " + condition + " -- [PostgreSQL]";
    }
}
```

```java
// MySQL family
public class MySQLConnection implements DbConnection {
    @Override
    public void connect() {
        System.out.println("Connected to MySQL");
    }
}

public class MySQLQueryBuilder implements QueryBuilder {
    @Override
    public String build(String table, String condition) {
        return "SELECT * FROM `" + table + "` WHERE " + condition + " -- [MySQL]";
    }
}
```

```java
// Abstract Factory
public interface DatabaseFactory {
    DbConnection createConnection();
    QueryBuilder createQueryBuilder();
}

// Concrete Factories
public class PostgresFactory implements DatabaseFactory {
    @Override public DbConnection createConnection() { return new PostgresConnection(); }
    @Override public QueryBuilder createQueryBuilder() { return new PostgresQueryBuilder(); }
}

public class MySQLFactory implements DatabaseFactory {
    @Override public DbConnection createConnection() { return new MySQLConnection(); }
    @Override public QueryBuilder createQueryBuilder() { return new MySQLQueryBuilder(); }
}
```

```java
// Client — works purely with abstractions
public class DataAccessLayer {
    private final DbConnection connection;
    private final QueryBuilder queryBuilder;

    public DataAccessLayer(DatabaseFactory factory) {
        this.connection = factory.createConnection();
        this.queryBuilder = factory.createQueryBuilder();
        this.connection.connect();
    }

    public void fetchUsers() {
        System.out.println(queryBuilder.build("users", "active = true"));
    }
}
```

```java
public class Main {
    public static void main(String[] args) {
        String dbType = "POSTGRES"; // could come from application.properties

        DatabaseFactory factory = dbType.equals("POSTGRES")
                ? new PostgresFactory()
                : new MySQLFactory();

        DataAccessLayer dal = new DataAccessLayer(factory);
        dal.fetchUsers();
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Guarantees product family compatibility | Adding a new product type requires changing all factories |
| Isolates concrete classes from client | More classes = more complexity |
| Easy to swap entire families | Can be over-engineered for simple cases |

### When to Use / Avoid

- ✔ Multiple related object families that must be used together (DB drivers, UI themes, cloud providers)
- ✖ Avoid when you only have one family — use Factory Method instead

---

## Pattern 4 — Builder

### What is it?

Separates the **construction of a complex object** from its representation. Lets you build objects **step by step**, and produce different representations using the same construction process. Great when a constructor would have many parameters.

### Real-World Example: HTTP Request Builder

```java
public class HttpRequest {
    private final String url;
    private final String method;
    private final Map<String, String> headers;
    private final String body;
    private final int timeoutSeconds;

    // Private — only Builder can create this
    private HttpRequest(Builder builder) {
        this.url            = builder.url;
        this.method         = builder.method;
        this.headers        = Collections.unmodifiableMap(builder.headers);
        this.body           = builder.body;
        this.timeoutSeconds = builder.timeoutSeconds;
    }

    @Override
    public String toString() {
        return method + " " + url +
               "\nHeaders: " + headers +
               "\nBody: " + body +
               "\nTimeout: " + timeoutSeconds + "s";
    }

    // Static nested Builder
    public static class Builder {
        // Required
        private final String url;
        private final String method;

        // Optional with defaults
        private Map<String, String> headers = new HashMap<>();
        private String body = "";
        private int timeoutSeconds = 30;

        public Builder(String url, String method) {
            this.url = url;
            this.method = method;
        }

        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this; // enables chaining
        }

        public Builder body(String body) {
            this.body = body;
            return this;
        }

        public Builder timeout(int seconds) {
            this.timeoutSeconds = seconds;
            return this;
        }

        public HttpRequest build() {
            if (url == null || url.isBlank())
                throw new IllegalStateException("URL is required");
            return new HttpRequest(this);
        }
    }
}
```

```java
public class Main {
    public static void main(String[] args) {

        // Clean, readable, no 6-argument constructor confusion
        HttpRequest request = new HttpRequest.Builder("https://api.example.com/users", "POST")
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer token123")
                .body("{\"name\": \"Sourabh\"}")
                .timeout(10)
                .build();

        System.out.println(request);
    }
}
```

> **Spring Boot note:** `ResponseEntity.ok().header(...).body(...)`, `MockMvcRequestBuilders`, and Lombok's `@Builder` all use this pattern.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Eliminates telescoping constructors | More boilerplate (Lombok's @Builder helps) |
| Readable, fluent API | Overkill for simple objects |
| Can enforce required vs optional fields | Builder and product can get out of sync |
| Immutable objects are easy to create | |

### When to Use / Avoid

- ✔ Objects with many optional parameters (configs, requests, test data)
- ✔ When you want immutable objects with a clean API
- ✖ Avoid for simple 2–3 field objects — just use a constructor

---

## Pattern 5 — Prototype

### What is it?

Creates new objects by **cloning an existing object** (the prototype) rather than building from scratch. Useful when object creation is expensive (DB fetch, network call, complex initialization).

### Real-World Example: Report Template Cloning

```java
public class ReportTemplate implements Cloneable {
    private String title;
    private String header;
    private List<String> sections;
    private String footer;

    public ReportTemplate(String title, String header,
                          List<String> sections, String footer) {
        this.title    = title;
        this.header   = header;
        this.sections = new ArrayList<>(sections); // deep copy
        this.footer   = footer;
    }

    // Deep clone — critical so lists aren't shared between copies
    @Override
    public ReportTemplate clone() {
        try {
            ReportTemplate copy = (ReportTemplate) super.clone();
            copy.sections = new ArrayList<>(this.sections); // deep copy list
            return copy;
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException("Clone failed", e);
        }
    }

    public void setTitle(String title) { this.title = title; }
    public void addSection(String section) { this.sections.add(section); }

    @Override
    public String toString() {
        return "Title: " + title + "\nHeader: " + header +
               "\nSections: " + sections + "\nFooter: " + footer;
    }
}
```

```java
public class Main {
    public static void main(String[] args) {

        // Expensive base template — imagine this loaded from DB
        ReportTemplate baseTemplate = new ReportTemplate(
                "Monthly Report",
                "Company Confidential",
                List.of("Executive Summary", "Financials", "Appendix"),
                "© 2026 MyCompany"
        );

        // Clone and customize — no DB call needed
        ReportTemplate salesReport = baseTemplate.clone();
        salesReport.setTitle("Sales Monthly Report");
        salesReport.addSection("Sales Funnel Analysis");

        ReportTemplate hrReport = baseTemplate.clone();
        hrReport.setTitle("HR Monthly Report");
        hrReport.addSection("Headcount & Attrition");

        System.out.println("=== Sales ===\n" + salesReport);
        System.out.println("\n=== HR ===\n" + hrReport);
        System.out.println("\n=== Base (unchanged) ===\n" + baseTemplate);
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Avoids expensive re-initialization | Deep vs shallow clone is tricky to get right |
| Produces variants from a base object cleanly | Cloning complex object graphs can be messy |
| Alternative to subclassing for variations | `Cloneable` in Java is considered poorly designed |

### When to Use / Avoid

- ✔ Object creation is expensive and a copy would do
- ✔ You need many similar objects with small differences (game enemies, report templates)
- ✖ Avoid when objects are cheap to construct — just use `new`
- ✖ Watch out for circular references during deep cloning

---

## Quick Comparison Summary

| Pattern | Core Idea | Use When |
|---|---|---|
| **Singleton** | One instance, global access | Shared resources (config, logger, pool) |
| **Factory Method** | Delegate creation to a method | Type decided at runtime |
| **Abstract Factory** | Create families of related objects | Multiple compatible product families |
| **Builder** | Step-by-step object construction | Complex objects with many optional fields |
| **Prototype** | Clone existing objects | Expensive creation, many similar objects |

---

## What's Next?

**Part 2 — Structural Patterns (7 patterns):**
Adapter · Bridge · Composite · Decorator · Facade · Flyweight · Proxy

**Part 3 — Behavioral Patterns (11 patterns):**
Chain of Responsibility · Command · Iterator · Mediator · Memento · Observer · State · Strategy · Template Method · Visitor · Interpreter
