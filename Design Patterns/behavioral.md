# Behavioral Design Patterns — Java Reference Guide

> **Series:** GoF Design Patterns | **Category:** Behavioral (11 Patterns)
> **Coverage:** Strategy · Observer · Command · Chain of Responsibility · Template Method · State · Iterator · Mediator · Memento · Visitor · Interpreter

---

## What are Behavioral Patterns?

Behavioral patterns deal with **how objects communicate and distribute responsibility** among themselves. They focus on algorithms, assignment of responsibilities, and the patterns of communication between objects. They answer: **"Who does what, and how do objects talk to each other?"**

---

## Pattern 1 — Strategy

### What is it?

Defines a **family of algorithms**, encapsulates each one, and makes them **interchangeable at runtime**. The client selects which algorithm to use without changing the code that uses it.

> Think of it as **swappable behaviour** — same context, different strategy.

### Real-World Example: Sorting Service

```java
// Strategy interface
public interface SortStrategy {
    void sort(List<Integer> data);
}

// Concrete strategies
public class BubbleSortStrategy implements SortStrategy {
    @Override
    public void sort(List<Integer> data) {
        System.out.println("Sorting with Bubble Sort...");
        // bubble sort logic
    }
}

public class QuickSortStrategy implements SortStrategy {
    @Override
    public void sort(List<Integer> data) {
        System.out.println("Sorting with Quick Sort...");
        Collections.sort(data);
    }
}

public class MergeSortStrategy implements SortStrategy {
    @Override
    public void sort(List<Integer> data) {
        System.out.println("Sorting with Merge Sort...");
        // merge sort logic
    }
}

// Context — holds a strategy, delegates to it
public class DataProcessor {
    private SortStrategy strategy;

    public DataProcessor(SortStrategy strategy) {
        this.strategy = strategy;
    }

    public void setStrategy(SortStrategy strategy) {
        this.strategy = strategy;
    }

    public void process(List<Integer> data) {
        strategy.sort(data);
        System.out.println("Processed: " + data);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        DataProcessor processor = new DataProcessor(new QuickSortStrategy());
        processor.process(new ArrayList<>(List.of(5, 2, 8, 1, 9)));

        // Swap strategy at runtime — no change to DataProcessor
        processor.setStrategy(new BubbleSortStrategy());
        processor.process(new ArrayList<>(List.of(3, 7, 1)));
    }
}
```

> **Spring Boot note:** `AuthenticationManager` in Spring Security uses Strategy. Payment methods (UPI, card, wallet) in an e-commerce app are a classic real-world Strategy.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Swap algorithms at runtime | Client must know which strategies exist |
| Eliminates large if/switch blocks | More classes for simple cases |
| Open/Closed — add strategies without touching context | Overkill if only 1–2 strategies ever exist |

### When to Use / Avoid

- ✔ Multiple algorithms for the same task (sort, compress, pay, validate, discount)
- ✔ You want to eliminate large if-else or switch blocks
- ✖ Avoid when behaviour rarely changes — just hardcode it

---

## Pattern 2 — Observer

### What is it?

Defines a **one-to-many dependency** so that when one object (subject) changes state, all its dependents (observers) are **notified and updated automatically**.

> The classic **publish-subscribe** mechanism.

### Real-World Example: Stock Price Alert System

```java
// Observer interface
public interface StockObserver {
    void onPriceChange(String ticker, double newPrice);
}

// Subject
public class StockMarket {
    private final Map<String, Double> prices = new HashMap<>();
    private final List<StockObserver> observers = new ArrayList<>();
    private String lastChangedTicker;

    public void subscribe(StockObserver observer)   { observers.add(observer); }
    public void unsubscribe(StockObserver observer) { observers.remove(observer); }

    private void notifyObservers() {
        double price = prices.get(lastChangedTicker);
        observers.forEach(o -> o.onPriceChange(lastChangedTicker, price));
    }

    public void updatePrice(String ticker, double price) {
        System.out.println("\n[Market] " + ticker + " changed to $" + price);
        prices.put(ticker, price);
        lastChangedTicker = ticker;
        notifyObservers();
    }
}

// Concrete Observers
public class EmailAlertObserver implements StockObserver {
    private final String email;
    private final double threshold;

    public EmailAlertObserver(String email, double threshold) {
        this.email = email;
        this.threshold = threshold;
    }

    @Override
    public void onPriceChange(String ticker, double newPrice) {
        if (newPrice > threshold)
            System.out.println("[EMAIL] Alert to " + email + ": " + ticker + " → $" + newPrice);
    }
}

public class TradingBotObserver implements StockObserver {
    @Override
    public void onPriceChange(String ticker, double newPrice) {
        if (newPrice < 100)
            System.out.println("[BOT] Auto-buying " + ticker + " at $" + newPrice);
    }
}

public class DashboardObserver implements StockObserver {
    @Override
    public void onPriceChange(String ticker, double newPrice) {
        System.out.println("[DASHBOARD] Updating chart: " + ticker + " = $" + newPrice);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        StockMarket market = new StockMarket();

        market.subscribe(new EmailAlertObserver("sourabh@example.com", 150.0));
        market.subscribe(new TradingBotObserver());
        market.subscribe(new DashboardObserver());

        market.updatePrice("AAPL", 145.0);
        market.updatePrice("AAPL", 162.0);
        market.updatePrice("TSLA", 95.0);
    }
}
```

> **Spring Boot note:** `ApplicationEventPublisher` / `@EventListener` is Observer built into Spring. Kafka/RabbitMQ are Observer at distributed scale.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Loose coupling between subject and observers | Unexpected cascades if observer chain is long |
| Add/remove observers at runtime | Memory leaks if observers aren't unsubscribed |
| Open/Closed — add observers without touching subject | Notification order is unpredictable |

### When to Use / Avoid

- ✔ Event systems, notifications, pub-sub, real-time UI data binding
- ✔ One change must trigger updates across many unrelated components
- ✖ Avoid when the dependency chain is simple and direct

---

## Pattern 3 — Command

### What is it?

Encapsulates a **request as an object**, letting you parameterize clients with different requests, queue or log requests, and support **undoable operations**.

> Turns a method call into a first-class object you can store, pass around, or reverse.

### Real-World Example: Text Editor with Undo/Redo

```java
// Command interface
public interface Command {
    void execute();
    void undo();
}

// Receiver — actual work happens here
public class TextEditor {
    private final StringBuilder text = new StringBuilder();

    public void insertText(String str, int position) {
        text.insert(position, str);
        System.out.println("Editor: " + text);
    }

    public void deleteText(int position, int length) {
        text.delete(position, position + length);
        System.out.println("Editor: " + text);
    }

    public String getText() { return text.toString(); }
}

// Concrete Commands
public class InsertCommand implements Command {
    private final TextEditor editor;
    private final String text;
    private final int position;

    public InsertCommand(TextEditor editor, String text, int position) {
        this.editor = editor; this.text = text; this.position = position;
    }

    @Override public void execute() { editor.insertText(text, position); }
    @Override public void undo()    { editor.deleteText(position, text.length()); }
}

public class DeleteCommand implements Command {
    private final TextEditor editor;
    private final int position, length;
    private String deletedText;

    public DeleteCommand(TextEditor editor, int position, int length) {
        this.editor = editor; this.position = position; this.length = length;
    }

    @Override
    public void execute() {
        deletedText = editor.getText().substring(position, position + length);
        editor.deleteText(position, length);
    }

    @Override public void undo() { editor.insertText(deletedText, position); }
}

// Invoker — manages command history
public class CommandHistory {
    private final Deque<Command> history = new ArrayDeque<>();

    public void execute(Command command) {
        command.execute();
        history.push(command);
    }

    public void undo() {
        if (!history.isEmpty()) history.pop().undo();
        else System.out.println("Nothing to undo.");
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        TextEditor editor = new TextEditor();
        CommandHistory history = new CommandHistory();

        history.execute(new InsertCommand(editor, "Hello", 0));
        history.execute(new InsertCommand(editor, " World", 5));
        history.execute(new DeleteCommand(editor, 0, 5));

        System.out.println("\n--- Undo ---");
        history.undo();
        history.undo();
        history.undo();
    }
}
```

> **Spring Boot note:** `@Transactional` rollback is Command-like. Job queues (Spring Batch, Quartz) encapsulate work as command objects.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Undo/redo support is natural | Many small Command classes |
| Decouple sender from receiver | Overkill for simple one-time actions |
| Commands can be queued, logged, or scheduled | |

### When to Use / Avoid

- ✔ Undo/redo, macro recording, job queues, audit logs
- ✖ Avoid for simple one-time actions with no undo requirement

---

## Pattern 4 — Chain of Responsibility

### What is it?

Passes a request along a **chain of handlers**. Each handler decides to process the request or **pass it to the next** handler. Decouples sender from receiver — the sender doesn't know which handler will process it.

### Real-World Example: HTTP Request Middleware Pipeline

```java
// Handler base
public abstract class RequestFilter {
    protected RequestFilter next;

    public RequestFilter setNext(RequestFilter next) {
        this.next = next;
        return next;
    }

    public abstract void handle(HttpRequest request);

    protected void passToNext(HttpRequest request) {
        if (next != null) next.handle(request);
        else System.out.println("[Pipeline] Request fully processed.");
    }
}

public record HttpRequest(String token, String ip, String body) {}

// Concrete Handlers
public class AuthFilter extends RequestFilter {
    @Override
    public void handle(HttpRequest request) {
        if (request.token() == null || request.token().isBlank()) {
            System.out.println("[AuthFilter] BLOCKED — missing token");
            return;
        }
        System.out.println("[AuthFilter] Passed");
        passToNext(request);
    }
}

public class RateLimitFilter extends RequestFilter {
    private final Map<String, Integer> requestCounts = new HashMap<>();
    private final int maxRequests = 3;

    @Override
    public void handle(HttpRequest request) {
        int count = requestCounts.merge(request.ip(), 1, Integer::sum);
        if (count > maxRequests) {
            System.out.println("[RateLimitFilter] BLOCKED — too many requests from " + request.ip());
            return;
        }
        System.out.println("[RateLimitFilter] Passed (" + count + "/" + maxRequests + ")");
        passToNext(request);
    }
}

public class LoggingFilter extends RequestFilter {
    @Override
    public void handle(HttpRequest request) {
        System.out.println("[LoggingFilter] IP: " + request.ip());
        passToNext(request);
    }
}

public class BusinessLogicHandler extends RequestFilter {
    @Override
    public void handle(HttpRequest request) {
        System.out.println("[BusinessLogic] Processing: " + request.body());
        passToNext(request);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        AuthFilter auth = new AuthFilter();
        auth.setNext(new RateLimitFilter())
            .setNext(new LoggingFilter())
            .setNext(new BusinessLogicHandler());

        auth.handle(new HttpRequest("token123", "192.168.1.1", "GET /users"));
        auth.handle(new HttpRequest(null, "192.168.1.2", "GET /admin"));
    }
}
```

> **Spring Boot note:** Spring Security's `SecurityFilterChain` and Servlet `FilterChain` are textbook Chain of Responsibility.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Add/remove/reorder handlers independently | Request may go unhandled if chain is incomplete |
| Single Responsibility per handler | Hard to debug — must trace the full chain |
| Dynamic chain construction at runtime | |

### When to Use / Avoid

- ✔ Middleware pipelines, filter chains, validation workflows, approval processes
- ✖ Avoid if every request always goes to exactly one handler

---

## Pattern 5 — Template Method

### What is it?

Defines the **skeleton of an algorithm in a base class**, deferring some steps to subclasses. Subclasses can override specific steps without changing the algorithm's overall structure.

### Real-World Example: Data Export Pipeline

```java
// Abstract class with the template method
public abstract class DataExporter {

    // TEMPLATE METHOD — fixed skeleton, final so subclasses can't reorder steps
    public final void export(String destination) {
        List<String> data      = fetchData();
        List<String> processed = processData(data);
        String formatted       = formatData(processed);
        writeOutput(formatted, destination);
        notifyCompletion(destination);
    }

    protected abstract List<String> fetchData();
    protected abstract String formatData(List<String> data);

    // Default implementation — subclasses can override
    protected List<String> processData(List<String> data) {
        return data.stream().filter(s -> !s.isBlank()).toList();
    }

    protected void writeOutput(String content, String destination) {
        System.out.println("[Write] Saving to: " + destination + "\n" + content);
    }

    // Hook — optional override
    protected void notifyCompletion(String destination) {
        System.out.println("[Done] Export complete → " + destination);
    }
}

// CSV Exporter
public class CsvExporter extends DataExporter {
    @Override
    protected List<String> fetchData() {
        System.out.println("[CSV] Fetching from DB...");
        return List.of("Alice,30,Engineer", "Bob,25,Designer", "", "Carol,28,PM");
    }

    @Override
    protected String formatData(List<String> data) {
        return "name,age,role\n" + String.join("\n", data);
    }
}

// JSON Exporter
public class JsonExporter extends DataExporter {
    @Override
    protected List<String> fetchData() {
        System.out.println("[JSON] Fetching from API...");
        return List.of("Alice", "Bob", "Carol");
    }

    @Override
    protected String formatData(List<String> data) {
        String items = data.stream()
                           .map(name -> "  {\"name\": \"" + name + "\"}")
                           .collect(Collectors.joining(",\n"));
        return "[\n" + items + "\n]";
    }

    @Override
    protected void notifyCompletion(String destination) {
        System.out.println("[JSON] Webhook fired for: " + destination);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        new CsvExporter().export("/reports/users.csv");
        System.out.println();
        new JsonExporter().export("/api/export/users.json");
    }
}
```

> **Spring Boot note:** `JdbcTemplate` uses Template Method — you provide query and row mapper, Spring handles connection, statement prep, exception mapping, and cleanup.

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Reuse common algorithm skeleton | Subclasses can't change the algorithm order |
| Enforces a consistent process | Inheritance makes it less flexible than Strategy |
| Hook methods give controlled flexibility | Can violate LSP if overrides break the contract |

### When to Use / Avoid

- ✔ Multiple classes share the same algorithm structure with different steps
- ✔ Frameworks defining extension points (Spring, JUnit lifecycle)
- ✖ Avoid when classes differ too much — use Strategy (composition) instead

---

## Pattern 6 — State

### What is it?

Allows an object to **alter its behaviour when its internal state changes**. Instead of massive if-else chains on a state field, each state is its own class that handles transitions.

### Real-World Example: Order State Machine

```java
// State interface
public interface OrderState {
    void confirm(Order order);
    void ship(Order order);
    void deliver(Order order);
    void cancel(Order order);
}

// Context
public class Order {
    private OrderState state;
    private final String orderId;

    public Order(String orderId) {
        this.orderId = orderId;
        this.state   = new PendingState();
    }

    public void setState(OrderState state) { this.state = state; }
    public void confirm()  { state.confirm(this); }
    public void ship()     { state.ship(this); }
    public void deliver()  { state.deliver(this); }
    public void cancel()   { state.cancel(this); }
    public String getOrderId() { return orderId; }
}

// Concrete States
public class PendingState implements OrderState {
    @Override
    public void confirm(Order order) {
        System.out.println("[" + order.getOrderId() + "] Confirmed.");
        order.setState(new ConfirmedState());
    }
    @Override public void ship(Order o)    { System.out.println("Cannot ship — not confirmed."); }
    @Override public void deliver(Order o) { System.out.println("Cannot deliver — not shipped."); }
    @Override public void cancel(Order order) {
        System.out.println("[" + order.getOrderId() + "] Cancelled.");
        order.setState(new CancelledState());
    }
}

public class ConfirmedState implements OrderState {
    @Override public void confirm(Order o) { System.out.println("Already confirmed."); }
    @Override public void ship(Order order) {
        System.out.println("[" + order.getOrderId() + "] Shipped.");
        order.setState(new ShippedState());
    }
    @Override public void deliver(Order o) { System.out.println("Cannot deliver — not shipped."); }
    @Override public void cancel(Order order) {
        System.out.println("[" + order.getOrderId() + "] Cancelled.");
        order.setState(new CancelledState());
    }
}

public class ShippedState implements OrderState {
    @Override public void confirm(Order o) { System.out.println("Already confirmed."); }
    @Override public void ship(Order o)    { System.out.println("Already shipped."); }
    @Override public void deliver(Order order) {
        System.out.println("[" + order.getOrderId() + "] Delivered!");
        order.setState(new DeliveredState());
    }
    @Override public void cancel(Order o) { System.out.println("Cannot cancel — already shipped."); }
}

public class DeliveredState implements OrderState {
    @Override public void confirm(Order o) { System.out.println("Order complete."); }
    @Override public void ship(Order o)    { System.out.println("Order complete."); }
    @Override public void deliver(Order o) { System.out.println("Already delivered."); }
    @Override public void cancel(Order o)  { System.out.println("Cannot cancel delivered order."); }
}

public class CancelledState implements OrderState {
    @Override public void confirm(Order o) { System.out.println("Order was cancelled."); }
    @Override public void ship(Order o)    { System.out.println("Order was cancelled."); }
    @Override public void deliver(Order o) { System.out.println("Order was cancelled."); }
    @Override public void cancel(Order o)  { System.out.println("Already cancelled."); }
}

// Client
public class Main {
    public static void main(String[] args) {
        Order order = new Order("ORD-001");

        order.ship();     // blocked — not confirmed
        order.confirm();  // Pending → Confirmed
        order.ship();     // Confirmed → Shipped
        order.cancel();   // blocked — already shipped
        order.deliver();  // Shipped → Delivered
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Eliminates large if-else/switch on state | Many state classes for complex machines |
| Each state is independently testable | Transitions can be hard to visualise |
| New states don't break existing ones | Overkill for 2–3 simple states |

### When to Use / Avoid

- ✔ Order lifecycle, user session, document workflow, vending machine, traffic lights
- ✖ Avoid for simple boolean flags — just use a field

---

## Pattern 7 — Iterator

### What is it?

Provides a way to **sequentially access elements of a collection** without exposing its underlying representation. Java's `Iterator<T>` and enhanced `for-each` loop are built on this pattern.

### Real-World Example: Paginated API Result Iterator

```java
// Custom Iterator interface
public interface PageIterator<T> {
    boolean hasNext();
    List<T> next();
}

public record User(Long id, String name) {}

// Concrete Iterator — simulates paginated DB/API calls
public class UserPageIterator implements PageIterator<User> {
    private final int pageSize;
    private int currentPage = 0;
    private final int totalPages;

    private final List<User> allUsers = List.of(
        new User(1L, "Alice"), new User(2L, "Bob"),
        new User(3L, "Carol"), new User(4L, "Dave"),
        new User(5L, "Eve"),   new User(6L, "Frank"),
        new User(7L, "Grace"), new User(8L, "Hank")
    );

    public UserPageIterator(int pageSize) {
        this.pageSize   = pageSize;
        this.totalPages = (int) Math.ceil((double) allUsers.size() / pageSize);
    }

    @Override public boolean hasNext() { return currentPage < totalPages; }

    @Override
    public List<User> next() {
        int from = currentPage * pageSize;
        int to   = Math.min(from + pageSize, allUsers.size());
        System.out.println("[DB] Fetching page " + (currentPage + 1) + " of " + totalPages);
        currentPage++;
        return allUsers.subList(from, to);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        PageIterator<User> iterator = new UserPageIterator(3);
        while (iterator.hasNext()) {
            List<User> page = iterator.next();
            page.forEach(u -> System.out.println("  → " + u));
        }
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Hides internal collection structure | Extra class for each custom traversal |
| Multiple iterators can traverse simultaneously | Less relevant now that Java streams exist |
| Uniform interface across different collections | |

### When to Use / Avoid

- ✔ Custom traversal logic: pagination, tree traversal, filtered iteration
- ✖ Avoid for standard Java collections — `for-each` and streams handle this

---

## Pattern 8 — Mediator

### What is it?

Defines an object (the **mediator**) that **encapsulates how a set of objects interact**. Objects don't communicate directly — they go through the mediator. Reduces many-to-many dependencies to one-to-many.

### Real-World Example: Air Traffic Control

```java
// Mediator interface
public interface AirTrafficControl {
    void requestLanding(Aircraft aircraft);
    void notifyDeparture(Aircraft aircraft);
}

// Colleague
public class Aircraft {
    private final String callSign;
    private final AirTrafficControl atc;

    public Aircraft(String callSign, AirTrafficControl atc) {
        this.callSign = callSign; this.atc = atc;
    }

    public String getCallSign() { return callSign; }

    public void land()   { System.out.println(callSign + ": Requesting landing."); atc.requestLanding(this); }
    public void depart() { System.out.println(callSign + ": Departing."); atc.notifyDeparture(this); }
    public void receiveClearance(String msg) { System.out.println(callSign + " received: " + msg); }
}

// Concrete Mediator
public class ControlTower implements AirTrafficControl {
    private boolean runwayOccupied = false;
    private final Queue<Aircraft> waitingQueue = new LinkedList<>();

    @Override
    public void requestLanding(Aircraft aircraft) {
        if (!runwayOccupied) {
            runwayOccupied = true;
            aircraft.receiveClearance("Cleared to land.");
        } else {
            waitingQueue.offer(aircraft);
            aircraft.receiveClearance("Runway busy. Hold — position " + waitingQueue.size());
        }
    }

    @Override
    public void notifyDeparture(Aircraft aircraft) {
        if (!waitingQueue.isEmpty()) {
            Aircraft next = waitingQueue.poll();
            System.out.println("[ATC] Clearing " + next.getCallSign());
            next.receiveClearance("Cleared to land.");
        } else {
            runwayOccupied = false;
            System.out.println("[ATC] Runway now free.");
        }
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        ControlTower atc = new ControlTower();

        Aircraft ai101 = new Aircraft("AI-101", atc);
        Aircraft ba202 = new Aircraft("BA-202", atc);
        Aircraft ek303 = new Aircraft("EK-303", atc);

        ai101.land();
        ba202.land();
        ek303.land();

        System.out.println();
        ai101.depart(); // triggers queue
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Reduces coupling between colleagues | Mediator itself can become a God class |
| Centralises interaction logic | Adds complexity for simple cases |
| Easy to add new colleagues | |

### When to Use / Avoid

- ✔ Chat rooms, ATC, UI form coordination, event bus, workflow engines
- ✖ Avoid when object interactions are already simple and direct

---

## Pattern 9 — Memento

### What is it?

Captures and externalises an object's **internal state** so it can be **restored later** — without violating encapsulation. The object controls what gets saved and restored; the caretaker only stores snapshots.

### Real-World Example: Blog Post Draft Auto-Save

```java
// Memento — snapshot of state
public class FormMemento {
    private final String title, body, category;

    public FormMemento(String title, String body, String category) {
        this.title = title; this.body = body; this.category = category;
    }

    public String getTitle()    { return title; }
    public String getBody()     { return body; }
    public String getCategory() { return category; }
}

// Originator — whose state we snapshot
public class BlogPostForm {
    private String title, body, category;

    public void update(String title, String body, String category) {
        this.title = title; this.body = body; this.category = category;
    }

    public FormMemento save() {
        System.out.println("[Form] Saving draft...");
        return new FormMemento(title, body, category);
    }

    public void restore(FormMemento memento) {
        this.title    = memento.getTitle();
        this.body     = memento.getBody();
        this.category = memento.getCategory();
        System.out.println("[Form] Restored draft.");
    }

    @Override
    public String toString() {
        return "Title='" + title + "' | Category='" + category + "'\nBody: " + body;
    }
}

// Caretaker — stores snapshots, doesn't inspect them
public class DraftHistory {
    private final Deque<FormMemento> history = new ArrayDeque<>();

    public void saveState(FormMemento memento) { history.push(memento); }

    public FormMemento undo() {
        if (history.size() > 1) { history.pop(); return history.peek(); }
        System.out.println("[History] Nothing to undo.");
        return history.peek();
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        BlogPostForm form = new BlogPostForm();
        DraftHistory history = new DraftHistory();

        form.update("Java Tips", "Java is great...", "Tech");
        history.saveState(form.save());

        form.update("Java Tips v2", "Java 21 is better...", "Tech");
        history.saveState(form.save());

        form.update("Wrong content", "garbage...", "Random");
        history.saveState(form.save());

        form.restore(history.undo()); // back to v2
        System.out.println("After undo:\n" + form);
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Clean undo/redo without exposing internals | Memory-heavy for large or frequent state |
| Preserves encapsulation | Caretaker must manage when to save |
| Simple to implement for small state | |

### When to Use / Avoid

- ✔ Undo/redo, auto-save drafts, snapshots, transaction rollback
- ✖ Avoid when state is very large or changes extremely frequently

---

## Pattern 10 — Visitor

### What is it?

Lets you **add new operations to existing object structures without modifying them**. You separate the algorithm from the object. Each element "accepts" a visitor that performs the operation on it.

### Real-World Example: GST Tax Calculator for Order Items

```java
// Visitor interface
public interface TaxVisitor {
    double visit(FoodItem item);
    double visit(ElectronicsItem item);
    double visit(ClothingItem item);
}

// Element interface
public interface OrderItem {
    double getPrice();
    String getName();
    double accept(TaxVisitor visitor);
}

// Concrete Elements
public class FoodItem implements OrderItem {
    private final String name;
    private final double price;
    public FoodItem(String name, double price) { this.name = name; this.price = price; }
    @Override public double getPrice()           { return price; }
    @Override public String getName()            { return name; }
    @Override public double accept(TaxVisitor v) { return v.visit(this); }
}

public class ElectronicsItem implements OrderItem {
    private final String name;
    private final double price;
    public ElectronicsItem(String name, double price) { this.name = name; this.price = price; }
    @Override public double getPrice()           { return price; }
    @Override public String getName()            { return name; }
    @Override public double accept(TaxVisitor v) { return v.visit(this); }
}

public class ClothingItem implements OrderItem {
    private final String name;
    private final double price;
    public ClothingItem(String name, double price) { this.name = name; this.price = price; }
    @Override public double getPrice()           { return price; }
    @Override public String getName()            { return name; }
    @Override public double accept(TaxVisitor v) { return v.visit(this); }
}

// Concrete Visitor — India GST rules
public class GSTTaxVisitor implements TaxVisitor {
    @Override public double visit(FoodItem item) {
        double tax = item.getPrice() * 0.05;
        System.out.printf("  %s: ₹%.2f + 5%% GST = ₹%.2f%n",
                item.getName(), item.getPrice(), item.getPrice() + tax);
        return tax;
    }
    @Override public double visit(ElectronicsItem item) {
        double tax = item.getPrice() * 0.18;
        System.out.printf("  %s: ₹%.2f + 18%% GST = ₹%.2f%n",
                item.getName(), item.getPrice(), item.getPrice() + tax);
        return tax;
    }
    @Override public double visit(ClothingItem item) {
        double tax = item.getPrice() * 0.12;
        System.out.printf("  %s: ₹%.2f + 12%% GST = ₹%.2f%n",
                item.getName(), item.getPrice(), item.getPrice() + tax);
        return tax;
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        List<OrderItem> cart = List.of(
            new FoodItem("Basmati Rice", 200),
            new ElectronicsItem("Bluetooth Speaker", 1500),
            new ClothingItem("Cotton T-Shirt", 499)
        );

        TaxVisitor gst = new GSTTaxVisitor();
        System.out.println("=== GST Breakdown ===");
        double totalTax = cart.stream().mapToDouble(item -> item.accept(gst)).sum();
        System.out.printf("Total Tax: ₹%.2f%n", totalTax);
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Add operations without changing element classes | Adding a new element type requires updating all visitors |
| Related operations grouped in one visitor | Can break encapsulation if elements expose internals |
| Open/Closed for operations | Double dispatch is initially hard to understand |

### When to Use / Avoid

- ✔ Operations on stable object structures with many types (compilers, ASTs, pricing/tax engines)
- ✖ Avoid when the element hierarchy changes frequently

---

## Pattern 11 — Interpreter

### What is it?

Defines a **grammar for a language** and provides an interpreter to deal with that grammar. Each grammar rule is represented as a class. Build expression trees, evaluate them with context.

### Real-World Example: Boolean Expression Evaluator (Access Control Rules)

```java
// Abstract Expression
public interface Expression {
    boolean interpret(Map<String, Boolean> context);
}

// Terminal Expressions
public class VariableExpression implements Expression {
    private final String name;
    public VariableExpression(String name) { this.name = name; }
    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return context.getOrDefault(name, false);
    }
}

// Non-terminal Expressions
public class AndExpression implements Expression {
    private final Expression left, right;
    public AndExpression(Expression left, Expression right) {
        this.left = left; this.right = right;
    }
    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return left.interpret(context) && right.interpret(context);
    }
}

public class OrExpression implements Expression {
    private final Expression left, right;
    public OrExpression(Expression left, Expression right) {
        this.left = left; this.right = right;
    }
    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return left.interpret(context) || right.interpret(context);
    }
}

public class NotExpression implements Expression {
    private final Expression expr;
    public NotExpression(Expression expr) { this.expr = expr; }
    @Override
    public boolean interpret(Map<String, Boolean> context) {
        return !expr.interpret(context);
    }
}

// Client
public class Main {
    public static void main(String[] args) {
        // Rule: (isLoggedIn AND hasPermission) OR isAdmin
        Expression rule = new OrExpression(
            new AndExpression(
                new VariableExpression("isLoggedIn"),
                new VariableExpression("hasPermission")
            ),
            new VariableExpression("isAdmin")
        );

        Map<String, Boolean> userA = Map.of("isLoggedIn", true,  "hasPermission", true,  "isAdmin", false);
        Map<String, Boolean> userB = Map.of("isLoggedIn", true,  "hasPermission", false, "isAdmin", false);
        Map<String, Boolean> userC = Map.of("isLoggedIn", false, "hasPermission", false, "isAdmin", true);

        System.out.println("User A access: " + rule.interpret(userA)); // true
        System.out.println("User B access: " + rule.interpret(userB)); // false
        System.out.println("User C access: " + rule.interpret(userC)); // true (admin)
    }
}
```

### Pros & Cons

| ✅ Pros | ❌ Cons |
|---|---|
| Easy to extend grammar with new rules | Grammar class count grows fast |
| Each rule is independently testable | Poor performance for complex grammars |
| | Rarely used directly — use ANTLR for real parsers |

### When to Use / Avoid

- ✔ Simple rule engines, expression evaluators, access control DSLs
- ✖ Avoid for complex grammars — use ANTLR or parser libraries instead

---

## Quick Comparison Summary

| Pattern | Core Idea | Classic Use Case |
|---|---|---|
| **Strategy** | Swap algorithms at runtime | Payment method, sorting, validation |
| **Observer** | Notify many on one change | Event bus, stock alerts, Kafka |
| **Command** | Encapsulate request as object | Undo/redo, job queues, audit log |
| **Chain of Responsibility** | Pass request along handler chain | Middleware, filter chains, approval flow |
| **Template Method** | Skeleton in base, steps in subclass | Export pipeline, JdbcTemplate |
| **State** | Behaviour changes with state | Order lifecycle, FSM, vending machine |
| **Iterator** | Traverse without exposing internals | Pagination, tree traversal |
| **Mediator** | Centralise object communication | ATC, chat room, UI form coordination |
| **Memento** | Snapshot and restore state | Undo/redo, auto-save drafts |
| **Visitor** | Add operations without modifying objects | Tax engine, compiler, AST |
| **Interpreter** | Grammar as object tree | Rule engine, access control DSL |

---

## Key Distinctions (Common Interview Confusion)

| Often Confused | How to Tell Apart |
|---|---|
| **Strategy vs State** | Strategy swaps algorithms; State changes behaviour based on internal condition |
| **Command vs Strategy** | Command encapsulates a request (often for undo); Strategy encapsulates an algorithm |
| **Observer vs Mediator** | Observer is direct subject → observer; Mediator routes through a central coordinator |
| **Template Method vs Strategy** | Template Method uses inheritance (override steps); Strategy uses composition (inject algorithm) |
| **Decorator vs Chain of Responsibility** | Decorator always passes to its inner object; CoR can stop the chain at any point |
| **Visitor vs Strategy** | Visitor operates on an object structure (many types); Strategy is one pluggable algorithm |

---

## Complete GoF Pattern Roadmap

| Category | Patterns |
|---|---|
| **Creational (5)** | Singleton · Factory Method · Abstract Factory · Builder · Prototype |
| **Structural (7)** | Adapter · Bridge · Composite · Decorator · Facade · Flyweight · Proxy |
| **Behavioral (11)** | Strategy · Observer · Command · Chain of Responsibility · Template Method · State · Iterator · Mediator · Memento · Visitor · Interpreter |
