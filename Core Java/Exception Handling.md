# Java Exception Handling — Complete Guide

## 1. What Is an Exception?

An exception is an event that disrupts the normal flow of a program's instructions during execution. When an error occurs, Java creates an exception object and hands it off to the runtime system — a process called **throwing an exception**. The runtime then searches for a suitable handler — called **catching an exception**.

---

## 2. Exception Class Hierarchy

All exception-related classes descend from `Throwable`.

```
                       java.lang.Object
                             |
                      java.lang.Throwable
                       /              \
                  Exception            Error
                 /      |    \           |
     IOException  RuntimeException   OutOfMemoryError
        |            /    |    \      StackOverflowError
   FileNotFound  NullPointer Arithmetic  VirtualMachineError
   Exception     Exception   Exception
                    |
              IndexOutOfBounds
                 Exception
                    |
        ArrayIndexOutOfBoundsException
        StringIndexOutOfBoundsException
```

**Key branches:**

| Class | Nature | Recovery Expected? |
|---|---|---|
| `Error` | Serious problems (JVM level) — `OutOfMemoryError`, `StackOverflowError` | No, generally unrecoverable |
| `Exception` (checked) | Anticipated conditions — `IOException`, `SQLException` | Yes, must be handled or declared |
| `RuntimeException` (unchecked) | Programming bugs — `NullPointerException`, `ArithmeticException` | Optional handling |

---

## 3. Checked vs Unchecked Exceptions

### Checked Exceptions
- Subclasses of `Exception` (excluding `RuntimeException`).
- Checked at **compile time** — the compiler forces you to either catch them or declare them with `throws`.
- Represent recoverable, external conditions (file not found, network down, DB unreachable).

```java
public void readFile(String path) throws IOException {
    FileReader reader = new FileReader(path); // may throw checked FileNotFoundException
}
```

### Unchecked Exceptions
- Subclasses of `RuntimeException`.
- Not checked at compile time — usually indicate a bug in the code.

```java
public int divide(int a, int b) {
    return a / b; // throws ArithmeticException at runtime if b == 0
}
```

### Errors
- Subclasses of `Error`. Represent serious problems an application should not try to catch (`OutOfMemoryError`, `StackOverflowError`).

---

## 4. Basic try-catch-finally

```java
public class Division {
    public static void main(String[] args) {
        int[] numbers = {10, 20, 30};
        try {
            int result = numbers[0] / 0;          // ArithmeticException
            System.out.println(numbers[5]);       // never reached
        } catch (ArithmeticException e) {
            System.out.println("Cannot divide by zero: " + e.getMessage());
        } catch (ArrayIndexOutOfBoundsException e) {
            System.out.println("Invalid array index: " + e.getMessage());
        } finally {
            System.out.println("Cleanup runs regardless of exception.");
        }
    }
}
```

**Rules to remember:**
- `finally` always executes — even if `try` or `catch` has a `return`, `break`, or `continue` (unless JVM exits via `System.exit()` or the thread is killed).
- Catch blocks must be ordered from **most specific to most general** subclass; otherwise you get a compile error ("unreachable catch block").

---

## 5. Multi-catch Block (Java 7+)

Use when multiple exception types need identical handling logic:

```java
try {
    process();
} catch (IOException | SQLException e) {
    System.out.println("Operation failed: " + e.getMessage());
}
```
Note: the caught variable (`e`) is implicitly `final`, and the exception classes in the list cannot be related by inheritance (no subclass–superclass pairs).

---

## 6. try-with-resources (Java 7+)

Automatically closes any resource implementing `AutoCloseable`/`Closeable`, eliminating manual `finally` cleanup.

```java
try (BufferedReader br = new BufferedReader(new FileReader("data.txt"));
     FileWriter fw = new FileWriter("output.txt")) {
    String line;
    while ((line = br.readLine()) != null) {
        fw.write(line);
    }
} catch (IOException e) {
    System.out.println("I/O error: " + e.getMessage());
}
// br and fw are closed automatically, in reverse order of declaration
```

If both the try block and the auto-close both throw, the close exception is **suppressed** and attached to the original — retrievable via `getSuppressed()`.

---

## 7. throw vs throws

| | `throw` | `throws` |
|---|---|---|
| Purpose | Actually raises an exception instance | Declares that a method might raise a checked exception |
| Location | Inside a method body | In the method signature |
| Example | `throw new IllegalArgumentException("bad input");` | `public void save() throws IOException { ... }` |

```java
public void setAge(int age) {
    if (age < 0) {
        throw new IllegalArgumentException("Age cannot be negative");
    }
    this.age = age;
}
```

---

## 8. Custom (User-Defined) Exceptions

Create domain-specific exceptions by extending `Exception` (checked) or `RuntimeException` (unchecked).

```java
// Checked custom exception
public class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String message) {
        super(message);
    }
}

public class Account {
    private double balance;

    public void withdraw(double amount) throws InsufficientFundsException {
        if (amount > balance) {
            throw new InsufficientFundsException(
                "Requested: " + amount + ", Available: " + balance);
        }
        balance -= amount;
    }
}
```

Best practice: always provide constructors that accept `(String message)` and `(String message, Throwable cause)` so the exception plays nicely with chaining.

---

## 9. Exception Chaining (Wrapping)

Preserve the original cause when translating a low-level exception into a higher-level, more meaningful one.

```java
public class ServiceException extends RuntimeException {
    public ServiceException(String message, Throwable cause) {
        super(message, cause);
    }
}

public void loadConfig() {
    try {
        Files.readAllLines(Path.of("config.properties"));
    } catch (IOException e) {
        throw new ServiceException("Failed to load configuration", e); // cause preserved
    }
}
```
`getCause()` returns the original `IOException`; the full chain prints via `printStackTrace()`.

---

## 10. Common Built-in Exceptions

| Exception | Typical Cause |
|---|---|
| `NullPointerException` | Calling a method / accessing a field on a `null` reference |
| `ArrayIndexOutOfBoundsException` | Accessing an array index outside its bounds |
| `ClassCastException` | Invalid downcast between incompatible types |
| `NumberFormatException` | `Integer.parseInt("abc")` — string not a valid number |
| `IllegalArgumentException` | A method receives an inappropriate argument |
| `IllegalStateException` | A method is invoked at an inappropriate time/state |
| `ConcurrentModificationException` | Modifying a collection while iterating without an `Iterator.remove()` |
| `IOException` | File/stream I/O failures (checked) |
| `SQLException` | Database access errors (checked) |

---

## 11. Best Practices

1. **Catch specific exceptions**, not blanket `catch (Exception e)`, so you don't accidentally swallow unrelated bugs.
2. **Never leave a catch block empty** — at minimum, log it.
3. **Don't use exceptions for normal control flow** (e.g., don't use an exception to exit a loop).
4. **Preserve the stack trace** when rethrowing — use `throw new XException(msg, e)` instead of discarding `e`.
5. **Clean up resources** with try-with-resources rather than manual `finally` blocks.
6. **Prefer unchecked exceptions for programming errors**, checked exceptions for recoverable, expected conditions the caller should be forced to handle.
7. **Document exceptions** a public method can throw using Javadoc `@throws`.
8. **Don't catch `Throwable` or `Error`** unless you have a very specific, well-understood reason.
9. Keep custom exceptions **meaningful and few** — don't create one exception class per method.

---

## 12. Interview Questions & Answers

**Q1. What is the difference between `throw` and `throws`?**
`throw` is a keyword used to explicitly raise an exception instance inside a method body (`throw new Exception()`). `throws` is used in a method's signature to declare that the method may propagate one or more checked exceptions to its caller, without handling them itself.

**Q2. What is the difference between checked and unchecked exceptions?**
Checked exceptions (subclasses of `Exception`, excluding `RuntimeException`) are verified by the compiler — the method must either catch them or declare `throws`. They represent recoverable, external conditions like file I/O failures. Unchecked exceptions (subclasses of `RuntimeException`) aren't checked at compile time and usually represent programming bugs like `NullPointerException` or `ArithmeticException`.

**Q3. Why is `finally` block used, and does it always execute?**
`finally` holds cleanup code (closing files, releasing locks, DB connections) that must run whether or not an exception occurred. It executes even if the `try` or `catch` block returns, breaks, or continues. It will NOT execute only if the JVM exits via `System.exit()`, the thread is killed, or the machine loses power.

**Q4. Can we have a try block without a catch block?**
Yes — `try` can be paired with just a `finally` block (`try { } finally { }`), typically used for guaranteed cleanup when the exception itself is meant to propagate up to the caller.

**Q5. What happens if both the try block and finally block have a return statement?**
The `finally` block's return statement overrides the `try` block's return — this is considered bad practice and should be avoided, since it silently discards the original return value (and even suppresses exceptions thrown in `try`).

**Q6. What is exception chaining and why is it used?**
Exception chaining means wrapping a lower-level exception inside a higher-level, more meaningful one while preserving the original as the "cause" (via `initCause()` or a constructor like `super(message, cause)`). It lets you translate exceptions across abstraction layers (e.g., DB `SQLException` → service-layer `ServiceException`) without losing the original root-cause stack trace.

**Q7. What is the difference between `Exception` and `Error`?**
`Exception` represents conditions that a well-written application should anticipate and possibly recover from (e.g., `IOException`). `Error` represents serious problems related to the JVM/environment that applications typically cannot and should not try to handle (e.g., `OutOfMemoryError`, `StackOverflowError`).

**Q8. Why should you avoid catching `Exception` or `Throwable` generically?**
Catching broad types can accidentally swallow unrelated bugs (including `Error`s like `OutOfMemoryError`), makes debugging harder since the real cause is masked, and often hides logic errors instead of surfacing them. Catch the most specific exception type your code can meaningfully handle.

**Q9. What is try-with-resources and what interface must a resource implement?**
Introduced in Java 7, try-with-resources automatically closes resources declared in the `try(...)` parentheses once the block finishes (normally or via exception), removing the need for a manual `finally` block. The resource class must implement `AutoCloseable` (or its subinterface `Closeable`), providing a `close()` method.

**Q10. Can a `catch` block catch multiple exception types? How?**
Yes, using multi-catch (`catch (IOException | SQLException e)`) introduced in Java 7 — used when different exception types require identical handling logic. The exception types in the list must not be subclass/superclass of each other, and the caught variable is implicitly final.

**Q11. What is the difference between `final`, `finally`, and `finalize()`?**
`final` is a modifier used on classes, methods, or variables to prevent inheritance, overriding, or reassignment respectively. `finally` is a block that always executes after `try`/`catch`, typically for cleanup. `finalize()` is a method called by the garbage collector before an object is destroyed (now deprecated since Java 9 in favor of `Cleaner`/try-with-resources).

**Q12. If a method overrides another and the parent declares a checked exception, can the child throw a broader checked exception?**
No. An overriding method can throw the same checked exception, a subclass of it, or no checked exception at all — but never a broader/new checked exception than the parent declares. This preserves the Liskov substitution principle for callers relying on the parent's method signature.

**Q13. What is a `StackOverflowError` and when does it occur?**
It's an `Error` (not `Exception`) thrown when a program's call stack exceeds its limit — most commonly caused by unbounded or incorrect recursion (missing/incorrect base case).

**Q14. How do you create a custom exception, and when should it be checked vs unchecked?**
Extend `Exception` for a checked custom exception (forces callers to handle a recoverable business condition, e.g., `InsufficientFundsException`) or extend `RuntimeException` for an unchecked one (used for programming errors or conditions the caller isn't expected to recover from). Always provide constructors accepting a message and a `(message, cause)` pair for proper chaining support.

---

## Quick Reference Summary

- **Hierarchy:** `Throwable` → `Exception` (checked) & `Error`; `Exception` → `RuntimeException` (unchecked).
- **Handle with:** `try-catch-finally`, multi-catch, try-with-resources.
- **Declare with:** `throws`; **raise with:** `throw`.
- **Preserve root cause** when wrapping exceptions across layers.
- **Prefer specific catches**, clean resource management, and meaningful custom exceptions.
