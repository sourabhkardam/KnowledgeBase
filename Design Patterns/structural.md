# Structural Design Patterns — Java Reference Guide

> **Series:** GoF Design Patterns | **Category:** Structural (7 Patterns)
> **Coverage:** Adapter · Bridge · Composite · Decorator · Facade · Flyweight · Proxy

---

## What are Structural Patterns?

Structural patterns deal with **how classes and objects are composed** to form larger structures. They help ensure that when individual parts change, the entire structure doesn't need to change. They answer the question: **"How do we assemble objects and classes into larger, flexible structures?"**

---

## Pattern 1 — Adapter

### What is it?

Acts as a **bridge between two incompatible interfaces** making them compatible and enabling them to work seamlessly. This pattern is useful when you need to integrate existing code or libraries or third party api (that have different interfaces) with your codebase without making significant modifications or without changing the client code.

> Think of it as a **power plug adapter** when you travel abroad — your laptop and the socket haven't changed, but the adapter makes them work together.

### Real-World Example: Payment Gateway Integration

```java
// Target interface — what your app already uses i.e in client code OrderService
public interface PaymentProcessor {
    void processPayment(String customerId, double amount);
    void refundPayment(String transactionId);
}

// Adaptee — Stripe SDK - A third party Api (Now you want to use this third party Api in client code i.e. OrderService without changing it's 
// code and neither you can modify Adaptee code because it's third party api)
public class StripePaymentGateway {
    public void charge(String stripeCustomerId, long amountInCents, String currency) {
        System.out.println("Stripe: Charging " + stripeCustomerId +
                " $" + amountInCents / 100.0 + " " + currency);
    }
    public void issueRefund(String paymentIntentId, long amountInCents) {
        System.out.println("Stripe: Refunding " + amountInCents / 100.0 +
                " for intent: " + paymentIntentId);
    }
}

// Adapter (Created the StripePaymentAdapter which implements PaymentProcessor so that it can be injected in client code 
// and it has StripePaymentGateway reference whose methods we can use in processPayment/refundPayment)
public class StripePaymentAdapter implements PaymentProcessor {
    private final StripePaymentGateway stripeGateway;

    public StripePaymentAdapter(StripePaymentGateway stripeGateway) {
        this.stripeGateway = stripeGateway;
    }

    @Override
    public void processPayment(String customerId, double amount) {
        long amountInCents = (long) (amount * 100);
        stripeGateway.charge(customerId, amountInCents, "USD");
    }

    @Override
    public void refundPayment(String transactionId) {
        stripeGateway.issueRefund(transactionId, 0L);
    }
}

// Client Code — never knows it's talking to Stripe (Didn't touched it at all and that's the requirement - Don't change client code)
public class OrderService {
    private final PaymentProcessor paymentProcessor;

    public OrderService(PaymentProcessor paymentProcessor) {
        this.paymentProcessor = paymentProcessor;
    }

    public void placeOrder(String customerId, double totalAmount) {
        paymentProcessor.processPayment(customerId, totalAmount);
    }
}

// Wiring it all together
public class Main {
    public static void main(String[] args) {

        // BEFORE Stripe — we might we using our own in-house payment class
        PaymentProcessor processor = new InHousePaymentProcessor(); // your own impl
        OrderService orderService = new OrderService(processor);

        // AFTER Stripe — adapter wraps Stripe 
        StripePaymentGateway stripeGateway = new StripePaymentGateway();
        PaymentProcessor adapter = new StripePaymentAdapter(stripeGateway);

        // And if Tomorrow you switch to PayPal? Just swap the adapter.
        OrderService orderService = new OrderService(adapter);

        orderService.placeOrder("cust_123", 49.99);
        System.out.println("---");
        orderService.cancelOrder("pi_abc456");
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Integrates incompatible interfaces without modification | Adds an extra layer of indirection |
| Follows Open/Closed Principle | Translation logic can get complex |
| Easy to swap implementations | Can mask poorly designed abstractions |

### When to Use / Avoid

- ✔ Integrating third-party libraries or legacy code you can't modify
- ✔ Multiple implementations behind one interface (Stripe, PayPal, Razorpay)
- ✖ Avoid if interfaces are already compatible — just use directly

---

## Pattern 2 — Bridge

### What is it?

**Separates abstraction from implementation** so both can vary independently. Instead of a class explosion via inheritance, you use composition — the abstraction *holds a reference* to the implementation.

> Without Bridge: `EmailDailyReport`, `EmailWeeklyReport`, `SMSDailyReport`, `SMSWeeklyReport` — 4 classes for 2×2.
> With Bridge: 2 abstractions + 2 implementations = 4 combinations from just 4 classes.

### Real-World Example: Report Sender

```java
// IMPLEMENTATION side — how to send
public interface MessageSender {
    void sendMessage(String recipient, String content);
}

public class EmailSender implements MessageSender {
    @Override
    public void sendMessage(String recipient, String content) {
        System.out.println("EMAIL → " + recipient + ": " + content);
    }
}

public class SMSSender implements MessageSender {
    @Override
    public void sendMessage(String recipient, String content) {
        System.out.println("SMS → " + recipient + ": " + content);
    }
}

// ABSTRACTION side — what kind of report
public abstract class Report {
    protected MessageSender sender; // The Bridge

    public Report(MessageSender sender) {
        this.sender = sender;
    }

    public abstract void send(String recipient);
}

public class DailyReport extends Report {
    public DailyReport(MessageSender sender) { super(sender); }

    @Override
    public void send(String recipient) {
        sender.sendMessage(recipient, "Daily Report: Sales up 3% today.");
    }
}

public class WeeklyReport extends Report {
    public WeeklyReport(MessageSender sender) { super(sender); }

    @Override
    public void send(String recipient) {
        sender.sendMessage(recipient, "Weekly Report: Revenue $1.2M this week.");
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        Report emailDaily  = new DailyReport(new EmailSender());
        Report smsWeekly   = new WeeklyReport(new SMSSender());
        Report emailWeekly = new WeeklyReport(new EmailSender());

        emailDaily.send("manager@company.com");
        smsWeekly.send("+91-9999999999");
        emailWeekly.send("ceo@company.com");
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Eliminates class explosion | Increases complexity with two hierarchies |
| Abstraction and implementation vary independently | Can be overkill if only one implementation exists |
| Open/Closed Principle on both sides | Harder to understand at first glance |

### When to Use / Avoid

- ✔ Two independent dimensions of variation (type × channel, shape × renderer)
- ✔ You want to switch implementations at runtime
- ✖ Avoid when there's only one implementation — just use inheritance

---

## Pattern 3 — Composite

### What is it?

Composes objects into **tree structures** to represent part-whole hierarchies. Lets clients treat **individual objects and compositions uniformly** — a single file and a folder of files are treated the same way.

### Real-World Example: File System

```java
// Component — common interface for both leaf and composite
public interface FileSystemItem {
    String getName();
    long getSize();
    void print(String indent);
}

// Leaf — no children
public class File implements FileSystemItem {
    private final String name;
    private final long size;

    public File(String name, long size) {
        this.name = name;
        this.size = size;
    }

    @Override public String getName() { return name; }
    @Override public long getSize()   { return size; }

    @Override
    public void print(String indent) {
        System.out.println(indent + "📄 " + name + " (" + size + " KB)");
    }
}

// Composite — has children (Files or other Folders)
public class Folder implements FileSystemItem {
    private final String name;
    private final List<FileSystemItem> children = new ArrayList<>();

    public Folder(String name) { this.name = name; }

    public void add(FileSystemItem item) { children.add(item); }

    @Override public String getName() { return name; }

    @Override
    public long getSize() {
        return children.stream().mapToLong(FileSystemItem::getSize).sum();
    }

    @Override
    public void print(String indent) {
        System.out.println(indent + "📁 " + name + " (" + getSize() + " KB)");
        children.forEach(child -> child.print(indent + "   "));
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        Folder root = new Folder("root");

        Folder src = new Folder("src");
        src.add(new File("Main.java", 12));
        src.add(new File("Service.java", 34));

        Folder resources = new Folder("resources");
        resources.add(new File("application.yml", 5));
        resources.add(new File("schema.sql", 18));

        root.add(src);
        root.add(resources);
        root.add(new File("pom.xml", 8));

        root.print("");
        System.out.println("\nTotal: " + root.getSize() + " KB");
    }
}
```

**Output:**
```
📁 root (77 KB)
   📁 src (46 KB)
      📄 Main.java (12 KB)
      📄 Service.java (34 KB)
   📁 resources (23 KB)
      📄 application.yml (5 KB)
      📄 schema.sql (18 KB)
   📄 pom.xml (8 KB)
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Treat single objects and composites uniformly | Can make design overly general |
| Easy to add new component types | Hard to restrict what can be added to composite |
| Recursive operations are clean | |

### When to Use / Avoid

- ✔ Tree structures: file systems, org charts, UI trees, menus, Bills of Materials
- ✖ Avoid when your data isn't naturally hierarchical

---

## Pattern 4 — Decorator

### What is it?

**Attaches additional responsibilities to an object dynamically** by wrapping it. An alternative to subclassing for extending functionality — you stack wrappers like layers.

> Think of it as a **Russian doll** — each layer adds behavior, the core object stays untouched.

### Real-World Example: HTTP Request Pipeline

```java
// Component interface
public interface RequestHandler {
    String handle(String request);
}

// Core handler
public class BasicRequestHandler implements RequestHandler {
    @Override
    public String handle(String request) {
        return "Response for: [" + request + "]";
    }
}

// Base decorator
public abstract class RequestHandlerDecorator implements RequestHandler {
    protected final RequestHandler wrapped;

    public RequestHandlerDecorator(RequestHandler wrapped) {
        this.wrapped = wrapped;
    }
}

// Logging decorator
public class LoggingDecorator extends RequestHandlerDecorator {
    public LoggingDecorator(RequestHandler wrapped) { super(wrapped); }

    @Override
    public String handle(String request) {
        System.out.println("[LOG] Incoming: " + request);
        String response = wrapped.handle(request);
        System.out.println("[LOG] Outgoing: " + response);
        return response;
    }
}

// Auth decorator
public class AuthDecorator extends RequestHandlerDecorator {
    private final String validToken;

    public AuthDecorator(RequestHandler wrapped, String validToken) {
        super(wrapped);
        this.validToken = validToken;
    }

    @Override
    public String handle(String request) {
        if (!request.contains(validToken)) {
            return "401 Unauthorized";
        }
        return wrapped.handle(request);
    }
}

// Caching decorator
public class CachingDecorator extends RequestHandlerDecorator {
    private final Map<String, String> cache = new HashMap<>();

    public CachingDecorator(RequestHandler wrapped) { super(wrapped); }

    @Override
    public String handle(String request) {
        if (cache.containsKey(request)) {
            System.out.println("[CACHE] Hit for: " + request);
            return cache.get(request);
        }
        String response = wrapped.handle(request);
        cache.put(request, response);
        return response;
    }
}

// Client — stack decorators in any order
public class Main {
    public static void main(String[] args) {
        RequestHandler handler = new LoggingDecorator(
                                    new CachingDecorator(
                                        new AuthDecorator(
                                            new BasicRequestHandler(), "token123"
                                        )
                                    )
                                 );

        System.out.println("--- Request 1 ---");
        handler.handle("GET /users token123");

        System.out.println("\n--- Request 2 (cached) ---");
        handler.handle("GET /users token123");

        System.out.println("\n--- Request 3 (unauthorized) ---");
        System.out.println(handler.handle("GET /users badtoken"));
    }
}
```

> **Spring Boot note:** Spring's filter chain (`OncePerRequestFilter`) is Decorator in action. Java I/O (`BufferedReader` wrapping `InputStreamReader` wrapping `FileReader`) is the classic textbook example.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Add/remove behavior at runtime | Many small objects — hard to debug |
| Each decorator does one thing (SRP) | Order of wrapping matters and can be confusing |
| More flexible than inheritance | Stack traces can be deep and noisy |

### When to Use / Avoid

- ✔ Cross-cutting concerns: logging, auth, caching, compression, retry
- ✔ When subclassing would create a combinatorial explosion
- ✖ Avoid when the core behavior rarely changes and only one variant is needed

---

## Pattern 5 — Facade

### What is it?

Provides a **simplified interface to a complex subsystem**. The client talks to the Facade; the Facade coordinates everything behind the scenes. Think of it as a "front desk" — one person you talk to, many people working behind them.

### Real-World Example: Order Processing System

```java
// Complex subsystem classes — each does one thing
public class InventoryService {
    public boolean checkStock(String productId, int qty) {
        System.out.println("Inventory: Checking stock for " + productId);
        return true;
    }
    public void reserveStock(String productId, int qty) {
        System.out.println("Inventory: Reserved " + qty + " units of " + productId);
    }
}

public class PaymentService {
    public boolean processPayment(String customerId, double amount) {
        System.out.println("Payment: Charging $" + amount + " to " + customerId);
        return true;
    }
}

public class ShippingService {
    public String scheduleDelivery(String customerId, String productId) {
        String trackingId = "TRK-" + System.currentTimeMillis();
        System.out.println("Shipping: Scheduled. Tracking: " + trackingId);
        return trackingId;
    }
}

public class NotificationService {
    public void notifyCustomer(String customerId, String message) {
        System.out.println("Notification: Sending to " + customerId + " → " + message);
    }
}

// FACADE — one simple method orchestrates everything
public class OrderFacade {
    private final InventoryService    inventory    = new InventoryService();
    private final PaymentService      payment      = new PaymentService();
    private final ShippingService     shipping     = new ShippingService();
    private final NotificationService notification = new NotificationService();

    public String placeOrder(String customerId, String productId,
                             int quantity, double amount) {
        if (!inventory.checkStock(productId, quantity))
            throw new RuntimeException("Out of stock!");

        if (!payment.processPayment(customerId, amount))
            throw new RuntimeException("Payment failed!");

        inventory.reserveStock(productId, quantity);
        String trackingId = shipping.scheduleDelivery(customerId, productId);
        notification.notifyCustomer(customerId, "Order confirmed! Tracking: " + trackingId);
        return trackingId;
    }
}

// Client — one call, done
public class Main {
    public static void main(String[] args) {
        OrderFacade orderFacade = new OrderFacade();
        String tracking = orderFacade.placeOrder("cust_42", "PROD_99", 2, 149.99);
        System.out.println("Tracking ID: " + tracking);
    }
}
```

> **Spring Boot note:** A `@Service` that orchestrates multiple other services/repositories is a Facade. `JdbcTemplate` is a facade over raw JDBC.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Simplifies complex subsystem usage | Can become a God class if unchecked |
| Decouples client from internals | Doesn't prevent direct subsystem access |
| Easy to understand and test | May hide useful subsystem flexibility |

### When to Use / Avoid

- ✔ Wrapping complex libraries or multi-step workflows with a clean API
- ✔ Defining entry points to each layer in a layered architecture
- ✖ Avoid if the client needs fine-grained control over the subsystem

---

## Pattern 6 — Flyweight

### What is it?

Uses **sharing to efficiently support a large number of fine-grained objects**. Separates **intrinsic state** (shared, immutable — stored in the flyweight) from **extrinsic state** (unique per object — passed in at runtime). You store one shared object instead of thousands of duplicates.

### Real-World Example: Text Editor Character Rendering

```java
// Flyweight — intrinsic (shared) state only
public class CharacterStyle {
    private final String font;
    private final int fontSize;
    private final String color;

    public CharacterStyle(String font, int fontSize, String color) {
        this.font     = font;
        this.fontSize = fontSize;
        this.color    = color;
    }

    // Extrinsic state (position) passed in — NOT stored in flyweight
    public void render(char character, int x, int y) {
        System.out.printf("Rendering '%c' at (%d,%d) | Font: %s %dpt %s%n",
                character, x, y, font, fontSize, color);
    }
}

// Flyweight Factory — cache and reuse styles
public class CharacterStyleFactory {
    private static final Map<String, CharacterStyle> cache = new HashMap<>();

    public static CharacterStyle getStyle(String font, int size, String color) {
        String key = font + "-" + size + "-" + color;
        return cache.computeIfAbsent(key, k -> {
            System.out.println("[Factory] Creating new style: " + key);
            return new CharacterStyle(font, size, color);
        });
    }

    public static int getCacheSize() { return cache.size(); }
}

// Client
public class Main {
    public static void main(String[] args) {
        String[] text = {"H","e","l","l","o"," ","W","o","r","l","d"};

        for (int i = 0; i < text.length; i++) {
            CharacterStyle style = CharacterStyleFactory.getStyle("Arial", 12, "black");
            style.render(text[i].charAt(0), i * 10, 0);
        }

        CharacterStyle bold = CharacterStyleFactory.getStyle("Arial", 12, "red");
        bold.render('!', 110, 0);
        bold.render('!', 120, 0);

        // Only 2 style objects created, no matter how many characters
        System.out.println("\nStyle objects in memory: " + CharacterStyleFactory.getCacheSize());
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Massive memory savings for large object counts | Complex to implement correctly |
| Improves performance in memory-heavy scenarios | Intrinsic vs extrinsic split can be confusing |
| | Shared state must be truly immutable |

### When to Use / Avoid

- ✔ Huge numbers of similar objects (characters, particles, map tiles, game sprites)
- ✔ Most object state can be made extrinsic (passed in)
- ✖ Avoid with small numbers of objects — not worth the complexity
- ✖ Avoid if objects genuinely differ in most of their state

---

## Pattern 7 — Proxy

### What is it?

Provides a **surrogate or placeholder** for another object to control access to it. The proxy implements the same interface as the real object, so the client can't tell the difference.

**Common types:** Virtual (lazy init) · Protection (access control) · Remote (network) · Caching · Logging

### Real-World Example: Lazy-Loading + Caching Repository Proxy

```java
// Subject interface
public interface UserRepository {
    User findById(Long id);
    List<User> findAll();
}

public record User(Long id, String name, String email) {}

// Real object — expensive DB calls
public class DatabaseUserRepository implements UserRepository {

    public DatabaseUserRepository() {
        System.out.println("[DB] Connection established.");
    }

    @Override
    public User findById(Long id) {
        System.out.println("[DB] Querying user " + id + "...");
        return new User(id, "Sourabh", "sourabh@example.com");
    }

    @Override
    public List<User> findAll() {
        System.out.println("[DB] Loading all users...");
        return List.of(
            new User(1L, "Sourabh", "sourabh@example.com"),
            new User(2L, "Amit", "amit@example.com")
        );
    }
}

// Proxy — lazy init + caching
public class CachingUserRepositoryProxy implements UserRepository {
    private DatabaseUserRepository realRepo; // not created until needed
    private final Map<Long, User> cache = new HashMap<>();

    private DatabaseUserRepository getRealRepo() {
        if (realRepo == null) {
            System.out.println("[Proxy] Initializing real repository...");
            realRepo = new DatabaseUserRepository();
        }
        return realRepo;
    }

    @Override
    public User findById(Long id) {
        if (cache.containsKey(id)) {
            System.out.println("[Proxy] Cache HIT for user " + id);
            return cache.get(id);
        }
        System.out.println("[Proxy] Cache MISS for user " + id);
        User user = getRealRepo().findById(id);
        cache.put(id, user);
        return user;
    }

    @Override
    public List<User> findAll() {
        return getRealRepo().findAll();
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        UserRepository repo = new CachingUserRepositoryProxy();

        System.out.println("--- First call ---");
        System.out.println(repo.findById(1L));

        System.out.println("\n--- Second call (cached) ---");
        System.out.println(repo.findById(1L));

        System.out.println("\n--- New user ---");
        System.out.println(repo.findById(2L));
    }
}
```

**Output:**
```
--- First call ---
[Proxy] Cache MISS for user 1
[Proxy] Initializing real repository...
[DB] Connection established.
[DB] Querying user 1...

--- Second call (cached) ---
[Proxy] Cache HIT for user 1

--- New user ---
[Proxy] Cache MISS for user 2
[DB] Querying user 2...
```

> **Spring Boot note:** `@Transactional`, `@Cacheable`, and `@PreAuthorize` all work via Spring AOP-generated proxies. When you call a `@Transactional` method, you're calling a Spring proxy, not your class directly.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Controls access without changing the real object | Adds a layer of indirection |
| Lazy initialization saves startup time | Response delay if proxy does heavy work |
| Caching, logging, auth added transparently | Can get complex with multiple proxy concerns |

### When to Use / Avoid

- ✔ Lazy initialization of heavy objects
- ✔ Access control / protection
- ✔ Caching, logging, or retry around a real service
- ✖ Avoid when direct access is perfectly fine

---

## Quick Comparison Summary

| Pattern | Core Idea | Analogy | Use When |
|---|---|---|---|
| **Adapter** | Convert interface to another | Power plug adapter | Integrating incompatible code |
| **Bridge** | Separate abstraction from implementation | Remote + TV (independent) | Two dimensions vary independently |
| **Composite** | Tree of uniform objects | File system (file/folder) | Part-whole hierarchies |
| **Decorator** | Wrap to add behavior dynamically | Russian doll | Adding cross-cutting behavior |
| **Facade** | Simplify complex subsystem | Hotel front desk | Simplifying complex workflows |
| **Flyweight** | Share common state across objects | Font glyphs in a document | Huge numbers of similar objects |
| **Proxy** | Control access to an object | Security guard / cache layer | Lazy init, caching, access control |

---

## Key Distinctions (Common Confusion)

| Often Confused | How to Tell Apart |
|---|---|
| **Adapter vs Facade** | Adapter wraps *one* class to match an interface. Facade wraps *many* classes to simplify a subsystem. |
| **Decorator vs Proxy** | Decorator *adds* behavior. Proxy *controls access* to the real object. |
| **Decorator vs Inheritance** | Decorator is composited at runtime. Inheritance is fixed at compile time. |
| **Bridge vs Adapter** | Bridge is designed upfront to keep two hierarchies separate. Adapter is a retrofit fix for incompatible code. |

---

## What's Next?

**Part 3 — Behavioral Patterns (11 patterns):**
Chain of Responsibility · Command · Iterator · Mediator · Memento · Observer · State · Strategy · Template Method · Visitor · Interpreter
