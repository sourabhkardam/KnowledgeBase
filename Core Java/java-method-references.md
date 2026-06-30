# Method References in Java

## Table of Contents

1. [What is a Method Reference?](#1-what-is-a-method-reference)
2. [Why Does It Exist?](#2-why-does-it-exist)
3. [Syntax](#3-syntax)
4. [The 4 Types of Method References](#4-the-4-types-of-method-references)
   - [Type 1 — Static Method Reference](#type-1--static-method-reference)
   - [Type 2 — Instance Method on a Specific Object](#type-2--instance-method-on-a-specific-object)
   - [Type 3 — Instance Method on an Arbitrary Instance](#type-3--instance-method-on-an-arbitrary-instance)
   - [Type 4 — Constructor Reference](#type-4--constructor-reference)
5. [Real-World Stream Pipeline — With vs Without](#5-real-world-stream-pipeline--with-vs-without)
6. [When to Use vs When to Stick with a Lambda](#6-when-to-use-vs-when-to-stick-with-a-lambda)
7. [Key Rules to Remember](#7-key-rules-to-remember)

---

## 1. What is a Method Reference?

A **method reference** is a shorthand syntax introduced in **Java 8** that lets you refer to an existing method by name instead of writing a full lambda expression. It uses the `::` operator.

> **Core idea:** If a lambda expression is just calling an already-existing method, you can replace it with a direct reference to that method.

Method references work because Java's functional interfaces (`Function`, `Consumer`, `Predicate`, `Supplier`, etc.) all have a **single abstract method (SAM)**. A method reference is simply a way to say *"this existing method satisfies that contract"* — Java infers the wiring automatically.

---

## 2. Why Does It Exist?

Lambdas were introduced to reduce boilerplate for functional interfaces. But if your lambda body is just a single method call, the lambda itself becomes boilerplate. Method references eliminate that extra layer.

**Benefits:**

- **Readability** — reads like plain English (`list.forEach(System.out::println)`)
- **Reusability** — points to existing, tested logic instead of duplicating it inline
- **Conciseness** — less visual noise, especially in stream pipelines
- **Intent** — business-readable names like `Employee::getSalary`, `Order::isPending` are self-documenting

---

## 3. Syntax

```
ClassName::methodName        // static method or arbitrary instance method
objectInstance::methodName   // specific instance method
ClassName::new               // constructor reference
```

The `::` operator separates the class/object from the method name. No parentheses — you are **referencing** the method, not calling it.

---

## 4. The 4 Types of Method References

### Type 1 — Static Method Reference

**Syntax:** `ClassName::staticMethod`

A reference to a `static` method on a class. The lambda just calls the static method and passes the argument through.

**Without method reference (lambda):**
```java
List<String> numbers = List.of("3", "1", "4", "1", "5");

List<Integer> parsed = numbers.stream()
    .map(s -> Integer.parseInt(s))   // s is passed directly to parseInt
    .collect(Collectors.toList());
```

**With method reference:**
```java
List<Integer> parsed = numbers.stream()
    .map(Integer::parseInt)          // same behavior, cleaner
    .collect(Collectors.toList());
```

**More examples:**
```java
// Math.abs
List<Integer> values = List.of(-3, -1, 4, -1, 5);

// Without
List<Integer> absolutes = values.stream()
    .map(n -> Math.abs(n))
    .collect(Collectors.toList());

// With
List<Integer> absolutes = values.stream()
    .map(Math::abs)
    .collect(Collectors.toList());
```

**Rule:** Use when your lambda does nothing but call a static method with the same argument(s) it receives.

---

### Type 2 — Instance Method on a Specific Object

**Syntax:** `objectInstance::instanceMethod`

A reference to an instance method on a *particular, already-created object*. The object is **captured at the time the reference is written** — it doesn't change per element.

**Without method reference (lambda):**
```java
List<String> names = List.of("Alice", "Bob", "Charlie");

names.forEach(name -> System.out.println(name)); // calling println on a specific 'out'
```

**With method reference:**
```java
names.forEach(System.out::println); // System.out is the specific captured instance
```

**Example with a custom object:**
```java
public class Logger {
    public void log(String message) {
        System.out.println("[LOG] " + message);
    }
}

Logger myLogger = new Logger();
List<String> events = List.of("start", "process", "end");

// Without
events.forEach(e -> myLogger.log(e));

// With
events.forEach(myLogger::log);  // myLogger is the captured instance
```

**Rule:** The object (`myLogger`, `System.out`) is fixed. Every element in the stream/iteration calls the method on that **same** object.

---

### Type 3 — Instance Method on an Arbitrary Instance

**Syntax:** `ClassName::instanceMethod`

This is the **trickiest and most powerful type**. You reference an instance method via the *class name*, but the **instance itself is the parameter** — it gets supplied by the stream/functional interface at runtime.

In other words: each element in the stream *is* the object that calls the method on itself.

**Without method reference (lambda):**
```java
List<String> words = List.of("  hello  ", "  world  ");

List<String> trimmed = words.stream()
    .map(s -> s.trim())   // s is both the receiver AND the lambda parameter
    .collect(Collectors.toList());
```

**With method reference:**
```java
List<String> trimmed = words.stream()
    .map(String::trim)    // each String in the stream IS the instance calling trim()
    .collect(Collectors.toList());
```

**Example with a custom class:**
```java
public class Employee {
    private String name;
    private double salary;

    public String getName()   { return name; }
    public double getSalary() { return salary; }
}

List<Employee> employees = getEmployees();

// Without
List<String> names = employees.stream()
    .map(emp -> emp.getName())
    .collect(Collectors.toList());

// With
List<String> names = employees.stream()
    .map(Employee::getName)    // each emp in the stream calls getName() on itself
    .collect(Collectors.toList());

// Sorting — classic and clean use case
employees.sort(Comparator.comparing(Employee::getSalary));
```

**Key distinction from Type 2:**

| | Type 2 | Type 3 |
|---|---|---|
| Object source | Fixed (captured once) | Each stream element |
| Syntax | `myObject::method` | `ClassName::method` |
| Example | `myLogger::log` | `Employee::getName` |

---

### Type 4 — Constructor Reference

**Syntax:** `ClassName::new`

A reference to a constructor. Used wherever a **factory or supplier** is expected — the functional interface's method maps to the constructor's parameter signature.

**Without method reference (lambda):**
```java
Supplier<ArrayList<String>> supplier = () -> new ArrayList<>();
List<String> list = supplier.get();
```

**With method reference:**
```java
Supplier<ArrayList<String>> supplier = ArrayList::new;
List<String> list = supplier.get();
```

**Common use with streams:**
```java
List<String> names = List.of("Alice", "Bob", "Charlie");

// Without
List<Person> people = names.stream()
    .map(name -> new Person(name))
    .collect(Collectors.toList());

// With
List<Person> people = names.stream()
    .map(Person::new)    // calls Person(String name) constructor
    .collect(Collectors.toList());
```

**Multi-argument constructors** — the functional interface must match the parameter count:
```java
// BiFunction<String, Integer, Person> maps to Person(String name, int age)
BiFunction<String, Integer, Person> factory = Person::new;
Person p = factory.apply("Alice", 30);
```

**Rule:** Java picks the correct constructor overload based on the functional interface's method signature. If no matching constructor exists, it's a compile-time error.

---

## 5. Real-World Stream Pipeline — With vs Without

Here is a complete comparison in a realistic Spring Boot / service-layer scenario:

**Model and DTO:**
```java
public class Order {
    private Long id;
    private String status;
    private BigDecimal amount;

    public Long getId()           { return id; }
    public String getStatus()     { return status; }
    public BigDecimal getAmount() { return amount; }
    public boolean isPending()    { return "PENDING".equals(status); }
}

public class OrderSummary {
    private Long id;
    private BigDecimal amount;

    public OrderSummary(Long id, BigDecimal amount) {
        this.id = id;
        this.amount = amount;
    }
}
```

**Without method references:**
```java
List<Order> orders = orderRepository.findAll();

List<OrderSummary> summaries = orders.stream()
    .filter(order -> order.isPending())
    .sorted((a, b) -> a.getAmount().compareTo(b.getAmount()))
    .map(order -> new OrderSummary(order.getId(), order.getAmount()))
    .collect(Collectors.toList());
```

**With method references:**
```java
List<Order> orders = orderRepository.findAll();

List<OrderSummary> summaries = orders.stream()
    .filter(Order::isPending)                            // Type 3 — arbitrary instance
    .sorted(Comparator.comparing(Order::getAmount))      // Type 3 — arbitrary instance
    .map(order -> new OrderSummary(order.getId(), order.getAmount())) // lambda — 2 args from same object
    .collect(Collectors.toList());
```

> **Note on the last `.map()`:** The constructor takes two arguments pulled from the same `Order` object. There is no clean constructor reference shortcut here because the stream element isn't directly passed to the constructor — two getters are called first. A short lambda is still the right choice. Never force a method reference where a lambda is clearer.

---

## 6. When to Use vs When to Stick with a Lambda

| Situation | Prefer |
|---|---|
| Lambda just passes argument(s) directly to an existing method | Method reference |
| Lambda calls a no-arg or single-arg instance method on the stream element | Method reference |
| Lambda creates a new object from the stream element directly | Constructor reference |
| Lambda chains multiple calls (`s -> s.trim().toLowerCase()`) | Lambda |
| Lambda has conditional logic (`s -> s != null ? s.trim() : ""`) | Lambda |
| Lambda captures multiple local variables | Lambda |
| Lambda calls a constructor with multiple values taken from getters | Lambda |
| Forcing a method reference would make the code harder to read | Lambda |

---

## 7. Key Rules to Remember

1. **Method references don't add new capability** — they are syntactic sugar over lambdas. If a lambda works, a method reference to the same method also works.

2. **Type inference is done by the compiler** — Java matches the method reference to the functional interface's abstract method signature automatically.

3. **All four types resolve to a functional interface** — `Function`, `Consumer`, `Predicate`, `Supplier`, `BiFunction`, or any custom `@FunctionalInterface`.

4. **Type 3 is the most commonly misread** — `ClassName::method` does NOT mean the class is the receiver. The stream element itself becomes the receiver at runtime.

5. **Constructor references pick the right overload** — based on the functional interface parameter count and types. Ambiguous overloads cause compile-time errors.

6. **Don't force them** — a readable lambda is always better than a contorted method reference. The goal is clarity, not fewer characters.

---

*Java 8+ | Covers: static method refs, instance method refs, constructor refs, stream usage, Comparator.comparing, functional interfaces*
