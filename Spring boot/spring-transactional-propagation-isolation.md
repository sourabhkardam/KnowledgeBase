# Spring `@Transactional`: Propagation & Isolation — Complete Reference

## Table of Contents
1. [Overview](#1-overview)
2. [Propagation](#2-propagation)
   - 2.1 [What is Propagation](#21-what-is-propagation)
   - 2.2 [Propagation Types](#22-propagation-types-with-examples)
   - 2.3 [How Propagation Works Internally](#23-how-propagation-works-internally)
3. [Isolation](#3-isolation)
   - 3.1 [What is Isolation](#31-what-is-isolation)
   - 3.2 [The Three Classic Anomalies](#32-the-three-classic-anomalies)
   - 3.3 [Isolation Levels](#33-isolation-levels-with-examples)
   - 3.4 [How Isolation Works Internally](#34-how-isolation-works-internally)
4. [Quick Reference Tables](#4-quick-reference-tables)
5. [Follow-up Q&A](#5-follow-up-qa)

---

## 1. Overview

`@Transactional` has two attributes that solve **completely different problems**:

| Attribute | Solves | Question it answers |
|---|---|---|
| **Propagation** | Transaction *boundary* behavior | What happens when a transactional method calls another transactional method? Join, suspend, create new, or fail? |
| **Isolation** | Transaction *visibility* behavior | How much of another concurrently-running transaction's changes can I see? |

---

## 2. Propagation

### 2.1 What is Propagation

Propagation defines what should happen to the transaction when a method annotated with `@Transactional` is invoked from within another transactional method — i.e., how nested/chained transactional calls behave with respect to the "current transaction" already running on the thread.

### 2.2 Propagation Types (with examples)

#### `REQUIRED` (default)
Joins the existing transaction if one exists; otherwise starts a new one.

```java
@Service
public class OrderService {
    @Autowired private PaymentService paymentService;

    @Transactional(propagation = Propagation.REQUIRED)
    public void placeOrder(Order order) {
        orderRepository.save(order);
        paymentService.processPayment(order); // joins this same transaction
    }
}

@Service
public class PaymentService {
    @Transactional(propagation = Propagation.REQUIRED)
    public void processPayment(Order order) {
        paymentRepository.save(new Payment(order));
    }
}
```
If `processPayment` throws, the **entire** transaction (order save + payment) rolls back together.
**Use case:** default choice for operations that must be atomic as a unit.

---

#### `REQUIRES_NEW`
Always suspends any existing transaction and starts a brand-new, independent transaction.

```java
@Transactional(propagation = Propagation.REQUIRED)
public void placeOrder(Order order) {
    orderRepository.save(order);
    auditService.logOrderAttempt(order); // runs in its own transaction
}

@Service
public class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderAttempt(Order order) {
        auditRepository.save(new AuditLog(order));
    }
}
```
**Use case:** audit logs, notifications — must be committed regardless of whether the outer transaction later fails.

---

#### `NESTED`
Runs within a **savepoint** of the existing transaction (if one exists). A failure here rolls back only to the savepoint, not the whole outer transaction. Behaves like `REQUIRED` if no transaction exists.

```java
@Transactional(propagation = Propagation.REQUIRED)
public void placeOrder(Order order) {
    orderRepository.save(order);
    try {
        inventoryService.reserveStock(order); // nested, has a savepoint
    } catch (InsufficientStockException e) {
        notifyBackorder(order); // outer transaction continues
    }
}

@Service
public class InventoryService {
    @Transactional(propagation = Propagation.NESTED)
    public void reserveStock(Order order) {
        inventoryRepository.decrementStock(order.getProductId(), order.getQty());
    }
}
```
**Use case:** partial rollback — a sub-step can fail without discarding the whole business transaction. Requires a driver/`DataSourceTransactionManager` that supports savepoints.

---

#### `MANDATORY`
Must run inside an existing transaction; throws `IllegalTransactionStateException` if called outside one.

```java
@Transactional(propagation = Propagation.MANDATORY)
public void updateLedgerEntry(Ledger ledger) {
    ledgerRepository.save(ledger);
}
```
**Use case:** internal helper methods that should never be called standalone — enforces that the caller owns the transaction boundary.

---

#### `SUPPORTS`
Joins an existing transaction if present; otherwise runs non-transactionally.

```java
@Transactional(propagation = Propagation.SUPPORTS)
public List<Product> getProducts() {
    return productRepository.findAll();
}
```
**Use case:** read operations that work fine either way — flexible for reuse in transactional or non-transactional contexts.

---

#### `NOT_SUPPORTED`
Suspends any existing transaction and runs non-transactionally.

```java
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public void generateReport() {
    reportRepository.runHeavyReport(); // don't hold a DB txn/connection open for this
}
```
**Use case:** long-running or heavy read operations you don't want holding a connection/lock for the duration of an outer transaction.

---

#### `NEVER`
Must run without any transaction; throws an exception if one exists.

```java
@Transactional(propagation = Propagation.NEVER)
public void sendEmailNotification() {
    emailClient.send(...);
}
```
**Use case:** rare — guarantees a method is never accidentally called within a DB transaction (e.g. avoid holding a connection during a slow external call).

### 2.3 How Propagation Works Internally

1. `@Transactional` is **AOP-based**. When Spring processes a bean and detects `@Transactional`, a `BeanPostProcessor` (`AnnotationAwareAspectJAutoProxyCreator`) wraps the bean in a **proxy** — JDK dynamic proxy (if it implements an interface) or CGLIB subclass (otherwise).
2. Every call to a transactional method goes through this proxy, hitting `TransactionInterceptor.invoke()`.
3. `TransactionInterceptor` delegates to `TransactionAspectSupport.invokeWithinTransaction()`, which:
   - Reads the `TransactionAttribute` (propagation, isolation, timeout, rollback rules) via `TransactionAttributeSource`.
   - Calls `PlatformTransactionManager.getTransaction(txAttr)`.
4. Inside `getTransaction()`, the manager checks a **`ThreadLocal`** (`TransactionSynchronizationManager`) to see if a transaction is already bound to the current thread. This is exactly how "does a transaction already exist" is determined:
   - **`REQUIRED`** → if ThreadLocal has an active transaction, proceed using it; else bind a new `Connection`, `setAutoCommit(false)`.
   - **`REQUIRES_NEW`** → suspends the current transaction (detaches the bound `ConnectionHolder` from the ThreadLocal into a `SuspendedResourcesHolder`), acquires a **new** connection, commits/rolls back independently, then restores the suspended resources afterward.
   - **`NESTED`** → if a transaction exists and the driver supports savepoints (`DatabaseMetaData.supportsSavepoints()`), calls `Connection.setSavepoint()` before the nested method runs. On exception, calls `Connection.rollback(savepoint)` instead of a full rollback.
   - **`MANDATORY` / `NEVER`** → simple existence checks against the ThreadLocal; throw `IllegalTransactionStateException` if the condition isn't met.
   - **`SUPPORTS` / `NOT_SUPPORTED`** → conditionally attach/detach the transactional resource without forcing a new transaction.
5. After the target method returns, the interceptor calls `commitTransactionAfterReturning()`, or on a matching exception (default: unchecked `RuntimeException`/`Error`), `completeTransactionAfterThrowing()` → rollback.

> **Note:** This is exactly why **self-invocation bypasses propagation** — calling `this.someMethod()` from within the same bean skips the proxy entirely, so none of the above logic runs.

---

## 3. Isolation

### 3.1 What is Isolation

Isolation defines how much a transaction can "see" of other concurrently running transactions — controlling the classic read anomalies below.

### 3.2 The Three Classic Anomalies

| Anomaly | What changes between two reads |
|---|---|
| **Dirty Read** | Reading **uncommitted** changes from another transaction (which might still roll back) |
| **Non-Repeatable Read** | Same row's **value** changes because another transaction committed an update in between two reads |
| **Phantom Read** | Same query's **row set** changes because another transaction inserted/deleted rows in between two reads |

#### Dirty Read — detailed example

Table setup:
```sql
CREATE TABLE accounts (id INT PRIMARY KEY, balance DECIMAL(10,2));
INSERT INTO accounts VALUES (1, 1000.00);
```

| Time | T1 (writer) | T2 (reader, `READ_UNCOMMITTED`) |
|---|---|---|
| t1 | `BEGIN` | |
| t2 | `UPDATE accounts SET balance = 5000 WHERE id = 1;` (not committed) | |
| t3 | | `BEGIN` |
| t4 | | `SELECT balance ...` → reads **5000** |
| t5 | `ROLLBACK` | |
| t6 | | T2 already used 5000 in a decision — but it never really existed! |

```java
@Service
public class TransferService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void debitAccount(Long accountId, BigDecimal amount) {
        Account acc = accountRepository.findById(accountId);
        acc.setBalance(acc.getBalance().subtract(amount));
        accountRepository.save(acc); // UPDATE executed, not yet committed
        if (someValidationFails()) {
            throw new IllegalStateException("insufficient funds after fee check");
            // rolls back — the debit never really happened
        }
    }
}

@Service
public class FraudCheckService {
    @Transactional(isolation = Isolation.READ_UNCOMMITTED) // dangerous!
    public boolean isSuspiciousBalance(Long accountId) {
        Account acc = accountRepository.findById(accountId);
        return acc.getBalance().compareTo(BigDecimal.ZERO) < 0;
        // may read the uncommitted, soon-to-be-rolled-back balance
    }
}
```

#### Non-Repeatable Read — detailed example

| Time | T1 (`READ_COMMITTED`) | T2 |
|---|---|---|
| t1 | `BEGIN` | |
| t2 | `SELECT balance ...` → **1000** | |
| t3 | | `BEGIN` |
| t4 | | `UPDATE accounts SET balance = 800 WHERE id = 1;` |
| t5 | | `COMMIT` |
| t6 | `SELECT balance ...` → **800** (same query, different result) | |
| t7 | `COMMIT` | |

```java
@Service
public class StatementService {
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void generateStatement(Long accountId) {
        Account acc1 = accountRepository.findById(accountId);
        BigDecimal openingBalance = acc1.getBalance(); // 1000

        doSomeSlowProcessing(); // T2 commits an update meanwhile

        Account acc2 = accountRepository.findById(accountId);
        BigDecimal closingBalance = acc2.getBalance(); // 800 — inconsistent within one txn!
    }
}
```

#### Phantom Read — detailed example

| Time | T1 (`REPEATABLE_READ`) | T2 |
|---|---|---|
| t1 | `BEGIN` | |
| t2 | `SELECT * FROM accounts WHERE balance > 500;` → 3 rows | |
| t3 | | `BEGIN` |
| t4 | | `INSERT INTO accounts VALUES (99, 700.00);` |
| t5 | | `COMMIT` |
| t6 | `SELECT * FROM accounts WHERE balance > 500;` → **4 rows** (phantom row appeared) | |
| t7 | `COMMIT` | |

```java
@Service
public class ReportService {
    @Transactional(isolation = Isolation.REPEATABLE_READ)
    public void auditHighValueAccounts() {
        List<Account> firstPass = accountRepository.findByBalanceGreaterThan(new BigDecimal("500"));
        int firstCount = firstPass.size(); // 3

        doSomeSlowValidation(); // T2 inserts a new qualifying row and commits meanwhile

        List<Account> secondPass = accountRepository.findByBalanceGreaterThan(new BigDecimal("500"));
        int secondCount = secondPass.size(); // 4 — a phantom row showed up
    }
}
```

> **Distinction:** `REPEATABLE_READ` guarantees a row you've already read keeps the same value — it does not inherently stop *new* rows from matching your filter later. That's the phantom read gap, closed only by `SERIALIZABLE`.

### 3.3 Isolation Levels (with examples)

#### `READ_UNCOMMITTED`
Allows dirty reads, non-repeatable reads, and phantom reads. Rarely used in practice.
```java
@Transactional(isolation = Isolation.READ_UNCOMMITTED)
public BigDecimal getAccountBalance(Long accountId) {
    return accountRepository.findById(accountId).getBalance();
}
```

#### `READ_COMMITTED`
Prevents dirty reads. Default in **PostgreSQL, Oracle, SQL Server**. Non-repeatable and phantom reads still possible.
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public void transferFunds(Long fromId, Long toId, BigDecimal amount) {
    Account from = accountRepository.findById(fromId);
    Account to = accountRepository.findById(toId);
    from.debit(amount);
    to.credit(amount);
    accountRepository.save(from);
    accountRepository.save(to);
}
```

#### `REPEATABLE_READ`
Prevents dirty and non-repeatable reads. Default in **MySQL/InnoDB**. Phantom reads possible per the ANSI standard (though InnoDB's gap-locking mitigates many practical cases).
```java
@Transactional(isolation = Isolation.REPEATABLE_READ)
public void generateMonthlyStatement(Long accountId) {
    Account acc = accountRepository.findById(accountId);
    List<Transaction> txns = transactionRepository.findByAccountId(accountId);
    statementRepository.save(new Statement(acc, txns));
}
```

#### `SERIALIZABLE`
Highest isolation — transactions behave as if executed one after another. Prevents all three anomalies; heavy locking/contention, possible serialization failures requiring retry.
```java
@Transactional(isolation = Isolation.SERIALIZABLE)
public void reserveLastSeat(Long showId) {
    int available = showRepository.getAvailableSeats(showId);
    if (available > 0) {
        showRepository.decrementSeat(showId);
    } else {
        throw new SoldOutException();
    }
}
```

#### `DEFAULT`
Uses the underlying database's default isolation level (e.g. `READ_COMMITTED` for Postgres/Oracle, `REPEATABLE_READ` for MySQL). Applied when nothing is explicitly specified.

### 3.4 How Isolation Works Internally

Isolation is passed straight through to the JDBC `Connection` — Spring does **not** implement any locking or MVCC logic itself:

```java
connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ); // etc.
```

This call happens right after the connection is obtained and before the first statement runs. The actual guarantee (row locks, MVCC snapshots, gap/next-key locks, undo logs) is **100% enforced by the database engine**, not by the application framework.

**Physical mechanics (why dirty reads/overlap happen):**
- There is **one shared, in-memory buffer pool page** per row/table that all connections read through — not separate "committed" and "uncommitted" copies.
- When a transaction runs `UPDATE`, the engine:
  1. Copies the *old* value into the **undo log** (rollback segment) — enabling rollback later.
  2. Overwrites the live buffer pool page **in place, immediately** — before commit.
  3. Writes a **redo log** entry (for crash durability).
  4. Acquires an exclusive row lock (blocks writers/locked readers, not unlocked readers).
- **Commit** doesn't "apply" the change — it was already applied. Commit just finalizes it as permanent and updates visibility rules; **rollback** replays the undo log to restore the old value.
- Normal isolation levels close the "dirty read window" via:
  - **Locking (2PL):** readers must acquire a shared lock, blocked until the writer commits/rolls back.
  - **MVCC:** readers are redirected to the undo log's older, safe snapshot instead of the live page, until the writer's change is confirmed committed.
- `READ_UNCOMMITTED` disables both protections — a reader just sees whatever is physically in the shared page at that instant, committed or not.

**Overlap ≠ requires `@Transactional`:** Concurrency is a fact of multiple DB connections running at once, independent of whether transactions are explicitly used. Even a single, auto-committed statement runs inside an implicit micro-transaction internally. `@Transactional` doesn't create the possibility of anomalies — it **widens the window** during which uncommitted changes can be observed, by deliberately deferring commit until business logic finishes.

**Division of responsibility:**

| Concept | Where it lives |
|---|---|
| `@Transactional`, propagation, proxy/AOP, `ThreadLocal` tracking | Spring Framework (JVM-side) |
| Deciding *when* to call `BEGIN` / `COMMIT` / `ROLLBACK` | Spring |
| Actually executing `BEGIN` / `COMMIT` / `ROLLBACK` | JDBC driver → database |
| Isolation enforcement (locks, MVCC, snapshots, undo logs) | 100% database engine |
| Anomaly possibility (dirty/non-repeatable/phantom reads) | 100% determined by DB engine + isolation level |

---

## 4. Quick Reference Tables

### Propagation types

| Type | Existing txn found | No existing txn | Typical use |
|---|---|---|---|
| `REQUIRED` (default) | Joins it | Creates new | Standard atomic business operation |
| `REQUIRES_NEW` | Suspends it, starts independent new one | Creates new | Audit logs, notifications that must survive outer failure |
| `NESTED` | Runs in a savepoint within it | Creates new (behaves like `REQUIRED`) | Partial rollback of a sub-step |
| `MANDATORY` | Joins it | Throws `IllegalTransactionStateException` | Enforce caller owns the transaction |
| `SUPPORTS` | Joins it | Runs non-transactionally | Flexible read operations |
| `NOT_SUPPORTED` | Suspends it, runs non-transactionally | Runs non-transactionally | Long/heavy reads, avoid holding connection |
| `NEVER` | Throws `IllegalTransactionStateException` | Runs non-transactionally | Guarantee no active transaction |

### Isolation levels

| Level | Dirty Read | Non-Repeatable Read | Phantom Read | Default in |
|---|---|---|---|---|
| `READ_UNCOMMITTED` | Possible | Possible | Possible | rarely used |
| `READ_COMMITTED` | Prevented | Possible | Possible | PostgreSQL, Oracle, SQL Server |
| `REPEATABLE_READ` | Prevented | Prevented | Possible (standard) | MySQL/InnoDB |
| `SERIALIZABLE` | Prevented | Prevented | Prevented | — (strictest) |

---

## 5. Follow-up Q&A

### Q1. In `REQUIRES_NEW`, it suspends the existing transaction and runs a new transaction. What happens if an exception occurs in the outer transaction, since the old transaction was "closed"?

**Answer:** "Suspend" does **not** mean close or commit. The outer transaction is parked — its connection/resource is detached from the thread but stays open and uncommitted. The inner (`REQUIRES_NEW`) transaction is completely independent, runs on its own new connection, and commits or rolls back **on its own**, before control even returns to the outer method.

```java
@Service
public class OrderService {
    @Autowired private AuditService auditService;

    @Transactional(propagation = Propagation.REQUIRED)
    public void placeOrder(Order order) {
        orderRepository.save(order);              // (1) part of outer txn T1

        auditService.logOrderAttempt(order);       // (2) suspends T1, runs+commits T2 independently

        // (3) back in T1 (resumed)
        if (order.getAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new InvalidOrderException("negative amount"); // only T1 rolls back
        }
    }
}

@Service
public class AuditService {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logOrderAttempt(Order order) {
        auditRepository.save(new AuditLog(order)); // commits here, independently, immediately
    }
}
```

**Sequence:**
1. Outer txn T1 starts, saves order.
2. `logOrderAttempt` is called → T1 suspended (detached, not closed) → new txn T2 starts on a new connection → T2 commits the audit log immediately and permanently.
3. T2 finishes → T1's resources re-attached → execution resumes in `placeOrder`.
4. If step 3 throws, **only T1 rolls back** (order save undone). The audit log from T2 is already committed and stays — it survives regardless of what T1 does afterward.

If the exception instead happens **inside** `logOrderAttempt`, T2 rolls back on its own, the exception propagates, and T1 (seeing an uncaught runtime exception) also rolls back — but these are two independent rollback decisions, not one cascading rollback.

---

### Q2. In `NESTED`, what exactly is a savepoint, and how does "rollback only to the savepoint" behave?

**Answer:** A savepoint is a JDBC-level marker **inside a single, still-open transaction** — not a new transaction, not a new connection, not a commit boundary. It's a bookmark you can rewind to without discarding everything before it.

```java
Connection conn = dataSource.getConnection();
conn.setAutoCommit(false);

conn.createStatement().execute("INSERT INTO orders ..."); // step A

Savepoint sp = conn.setSavepoint();                        // bookmark placed here

conn.createStatement().execute("UPDATE inventory ...");    // step B

conn.rollback(sp);   // undoes ONLY step B; step A is still pending, uncommitted, intact

conn.commit();       // now step A actually gets committed
```

Mapped to Spring's `NESTED` propagation:

```java
@Service
public class OrderService {
    @Autowired private InventoryService inventoryService;

    @Transactional(propagation = Propagation.REQUIRED)
    public void placeOrder(Order order) {
        orderRepository.save(order);   // step A — same connection, same physical transaction

        try {
            inventoryService.reserveStock(order); // step B, wrapped in a savepoint
        } catch (InsufficientStockException e) {
            // Spring already did connection.rollback(savepoint) here
            notifyBackorder(order); // outer txn continues, order save still intact
        }

        orderRepository.markConfirmed(order); // step C — same outer transaction
    } // whole T1 commits here (order save + markConfirmed); inventory decrement is gone
}

@Service
public class InventoryService {
    @Transactional(propagation = Propagation.NESTED)
    public void reserveStock(Order order) {
        inventoryRepository.decrementStock(order.getProductId(), order.getQty());
        if (!hasEnoughStock(order)) throw new InsufficientStockException();
    }
}
```

**Internally:** the transaction manager sees `NESTED` and, because it's the same physical connection/transaction, calls `connection.setSavepoint()` right before `reserveStock` runs. On exception, it calls `connection.rollback(savepoint)`, discarding only the inventory decrement. Execution returns to the `catch` block, still inside the same open outer transaction — nothing has committed yet. Only when `placeOrder` finishes does the whole outer transaction commit.

**Contrast with `REQUIRES_NEW`:** there, the inner unit is a genuinely separate transaction with its own commit — once committed, nothing the outer transaction does later can undo it. With `NESTED`, everything belongs to one transaction and one eventual commit/rollback decision, except the segment between the savepoint and the rollback call can be selectively discarded.

---

### Q3. In `NOT_SUPPORTED`, if it suspends the existing transaction, what happens if an exception occurs in the outer method — will the suspended part not roll back?

**Answer:** Correct — it will **not** roll back. `NOT_SUPPORTED` runs the method **without any transaction at all**, typically with `autoCommit(true)`, so each SQL statement commits itself the instant it executes. There is no rollback mechanism wrapping it — it can never be undone, not by its own failure, and not by the outer transaction failing afterward.

```java
@Service
public class OrderService {
    @Autowired private ReportService reportService;

    @Transactional(propagation = Propagation.REQUIRED)
    public void placeOrder(Order order) {
        orderRepository.save(order);              // part of outer txn T1

        reportService.logHeavyAuditRow(order);     // runs non-transactionally, auto-commits immediately

        paymentGateway.charge(order);              // suppose this throws
        // T1 rolls back here — order save is undone
        // but logHeavyAuditRow's row is ALREADY committed and stays, forever
    }
}

@Service
public class ReportService {
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void logHeavyAuditRow(Order order) {
        reportRepository.insertRawRow(order); // executes with autoCommit=true, no txn wrapper
    }
}
```

This is different from `REQUIRES_NEW`, which is still a real ACID transaction (commits as one atomic unit) — `NOT_SUPPORTED` isn't transactional at all; its work is committed statement-by-statement as it happens, completely detached from whatever the outer transaction decides later.

---

### Q4. When we say "suspends existing transaction," does it mean it releases the DB connection?

**Answer:** Generally, **no** — it does not return the connection to the pool. Internal flow:

1. `AbstractPlatformTransactionManager.suspend()` calls `TransactionSynchronizationManager.unbindResource(dataSource)`.
2. This removes the `ConnectionHolder` (wrapping the actual JDBC `Connection`) from the **ThreadLocal map** Spring uses to track "is there an active transaction/connection for this thread."
3. The `ConnectionHolder` isn't discarded — it's captured into a `SuspendedResourcesHolder` and kept as a local variable on the call stack, for the duration of the inner transaction.
4. A **brand-new connection** is acquired from the pool for the inner (`REQUIRES_NEW`) transaction.
5. Once the inner transaction completes, `resume()` re-binds the original `ConnectionHolder` back into the ThreadLocal — the outer method continues using the **exact same physical connection** it had before. It was just "invisible" to thread-local lookups meanwhile, not closed.

**Practical implication:** during suspension, you effectively have **two physical connections checked out from the pool simultaneously** (the parked outer one + the new inner one). Heavy use of `REQUIRES_NEW` inside already-transactional call chains can exhaust a small connection pool under load, since suspended connections still count as "in use," not returned.

For `NOT_SUPPORTED`, suspension is similar (detach from ThreadLocal), but since the inner method doesn't need a new transaction, Spring may or may not fetch a new connection depending on whether the method touches the DB — if it does, it typically gets a plain autoCommit connection from the pool for that scope.

---

### Q5. In `NEVER`, do we get any error at compile time if a transactional method calls this method?

**Answer:** No — there is **no compile-time enforcement at all**. `@Transactional` and its propagation semantics are purely a **runtime, reflection/proxy-based (AOP) mechanism**, not something `javac` or an annotation processor understands or validates.

```java
@Service
public class ReportingService {
    @Transactional(propagation = Propagation.NEVER)
    public void generateAdHocReport() {
        reportRepository.runReport();
    }
}

@Service
public class OrderService {
    @Autowired private ReportingService reportingService;

    @Transactional(propagation = Propagation.REQUIRED)
    public void placeOrder(Order order) {
        orderRepository.save(order);
        reportingService.generateAdHocReport(); // compiles perfectly fine!
        // but throws IllegalTransactionStateException at RUNTIME
    }
}
```

This compiles without a single warning. The violation is only detected when `TransactionInterceptor.invoke()` actually runs for `generateAdHocReport()`, checks `TransactionSynchronizationManager.isActualTransactionActive()`, finds it `true` (because `placeOrder` already started one), and throws:
```
IllegalTransactionStateException: Existing transaction found for transaction marked with propagation 'never'
```

The only way to catch this earlier than production is via integration tests that actually exercise the call path, or IDE/static-analysis plugins that understand Spring annotations (not standard `javac` or part of the Java language / Spring Framework compile step).

---

### Q6. In dirty read, how is T2 able to read uncommitted changes? Isn't the DB value still 1000? Explain the detailed concurrent flow.

**Answer:** The key misconception to fix: there isn't a "committed value" and a separate "uncommitted value" stored in two different places. There is **one shared, in-memory data structure** (the buffer pool / page cache) that all connections read from. When T1 runs `UPDATE`, it modifies those bytes **in place, immediately** — before commit. The old value (`1000`) is preserved only in a separate **undo log / rollback segment**, whose sole purpose is to let T1 undo the change if it rolls back. Commit doesn't "apply" the change — it was already applied. Commit just finalizes it as permanent.

**Detailed internal flow:**

**Step 1 — T1 begins and updates:**
```sql
BEGIN; -- T1
UPDATE accounts SET balance = 5000 WHERE id = 1;
```
1. Engine locates the data page for `id=1` in the shared **buffer pool** (not private to T1).
2. Copies the old value (`1000`) into the **undo log** first — enabling rollback.
3. Overwrites the actual bytes in that buffer pool page to `5000` — this page is what every connection's reads go through.
4. Writes a **redo log** entry (crash durability, separate concern).
5. Acquires an **exclusive row lock** — blocks writers/locked readers, but not a reader explicitly told to skip lock/snapshot checks.

At this moment, the shared buffer pool page **already says 5000**, even though T1 hasn't committed.

**Step 2 — T2 reads concurrently, before T1 commits:**
```sql
BEGIN; -- T2, isolation = READ_UNCOMMITTED
SELECT balance FROM accounts WHERE id = 1;
```
Under `READ_UNCOMMITTED`, the engine skips the normal MVCC/lock-check machinery and reads the **current buffer pool page contents directly** — no undo-log reconstruction, no waiting for T1's lock. It reads `5000` because that is, right now, literally what's in shared memory.

**Step 3 — T1 rolls back:**
```sql
ROLLBACK; -- T1
```
Engine replays the undo log entry, writing `1000` back into the buffer pool page — as if `5000` never happened.

**Step 4 — T2 already acted on `5000`, a value that never became real.**

**Why "concurrent" doesn't mean "simultaneous":** it means execution windows overlap in time. The DB interleaves operations from both connections against the same shared memory structures. T2's `SELECT` just needs to be scheduled after T1's `UPDATE` modifies the page but before T1's `COMMIT`/`ROLLBACK` — realistically milliseconds to seconds wide, easily long enough for another query to land inside it.

At normal isolation levels, this window is closed via:
- **Locking (2PL):** T2's read needs a shared lock, blocked by T1's exclusive lock until T1 finishes.
- **MVCC:** T2 is redirected to the undo log's older, safe snapshot (`1000`) instead of the live page, until T1's change is confirmed committed.

`READ_UNCOMMITTED` disables both — the reader sees whatever is physically in the shared page at that instant, committed or not.

**Concrete JDBC demo (two threads):**
```java
public class DirtyReadDemo {
    public static void main(String[] args) throws Exception {
        Thread t1 = new Thread(DirtyReadDemo::runT1);
        Thread t2 = new Thread(DirtyReadDemo::runT2);
        t1.start();
        Thread.sleep(500); // ensure T1's UPDATE runs first
        t2.start();
    }

    static void runT1() {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            conn.setAutoCommit(false);
            Statement stmt = conn.createStatement();
            stmt.executeUpdate("UPDATE accounts SET balance = 5000 WHERE id = 1");
            System.out.println("T1: updated balance to 5000 (NOT yet committed)");
            Thread.sleep(3000); // hold the uncommitted change open
            conn.rollback();
            System.out.println("T1: rolled back — balance restored to 1000");
        } catch (Exception e) { e.printStackTrace(); }
    }

    static void runT2() {
        try (Connection conn = DriverManager.getConnection(URL, USER, PASS)) {
            conn.setTransactionIsolation(Connection.TRANSACTION_READ_UNCOMMITTED);
            conn.setAutoCommit(false);
            Statement stmt = conn.createStatement();
            ResultSet rs = stmt.executeQuery("SELECT balance FROM accounts WHERE id = 1");
            rs.next();
            System.out.println("T2 (READ_UNCOMMITTED): read balance = " + rs.getBigDecimal("balance"));
            conn.commit();
        } catch (Exception e) { e.printStackTrace(); }
    }
}
```

Expected output:
```
T1: updated balance to 5000 (NOT yet committed)
T2 (READ_UNCOMMITTED): read balance = 5000
T1: rolled back — balance restored to 1000
```

With `READ_COMMITTED` instead, T2's `SELECT` would either block until T1's rollback completes (lock-based engines), or immediately return `1000` from the pre-update MVCC snapshot, never seeing `5000` at all.

---

### Q7. Does this overlapping happen only in the case of transactions / `@Transactional`? Are these checks at the DB level or the Spring Boot level?

**Answer, part A — does overlap require transactions?**

No — overlap (concurrency) happens purely from having multiple DB connections active at the same time; it has nothing to do with transaction boundaries. `@Transactional` doesn't create the overlap — it only decides **what rules apply while overlap is happening**.

- **Overlap in time** = two DB connections executing statements whose execution windows intersect — a fact of concurrent traffic, independent of transactions.
- **Transaction** = a boundary drawn around one or more statements so they succeed/fail as a unit, and so isolation rules can be requested.

Even a single `UPDATE` with no explicit transaction (auto-commit mode) still runs inside a tiny implicit transaction internally (engines like InnoDB wrap every statement this way). So the real question becomes: **how long does that implicit or explicit transaction stay open before commit?**

```java
// No explicit @Transactional, autoCommit = true (JDBC/JPA default)
accountRepository.updateBalance(1L, new BigDecimal("5000"));
// Internally: BEGIN (implicit) -> UPDATE -> COMMIT happens almost instantly
// The "open" window is a few microseconds — technically dirty-readable,
// but very unlikely another query lands exactly inside it
```

```java
// With @Transactional, the window can be milliseconds to seconds — much bigger target
@Transactional(propagation = Propagation.REQUIRED)
public void debitAccount(Long id, BigDecimal amount) {
    Account acc = accountRepository.findById(id);
    acc.setBalance(acc.getBalance().subtract(amount));
    accountRepository.save(acc);      // UPDATE happens here, uncommitted
    someSlowExternalCall();           // transaction stays open through this — BIG window
}                                      // COMMIT only happens here
```

So `@Transactional` doesn't cause dirty reads to become *possible* — it **widens the time window** during which uncommitted changes sit in shared memory, by deliberately deferring commit until business logic finishes. Without transaction management, every statement still technically has this window, just razor-thin in practice.

**Answer, part B — DB level or Spring Boot level?**

Entirely at the **DB level**. Spring does not implement isolation or locking itself. Spring's role is purely to:
1. Read the `isolation` attribute off `@Transactional`.
2. Call `connection.setTransactionIsolation(...)` on the JDBC `Connection` — a one-line pass-through to the driver.
3. Manage *when* `BEGIN`/`COMMIT`/`ROLLBACK` happen (propagation, transaction boundaries).

```java
// Roughly what Spring's DataSourceTransactionManager does internally, simplified
Connection conn = dataSource.getConnection();
conn.setAutoCommit(false);
conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED); // <-- just forwards to JDBC driver
// ... your repository/business code runs, using this same connection ...
conn.commit(); // or conn.rollback()
```

Everything after that — undo logs, MVCC snapshots, row/gap/range locks, buffer pool page visibility rules, lock waiting/blocking, deadlock detection — is implemented **inside the database engine itself**, completely independent of Java or Spring. The same logic written in Python, Go, or raw JDBC with zero Spring involved would show identical anomaly behavior at the same isolation level.

**Division of responsibility:**

| Concept | Where it actually lives |
|---|---|
| `@Transactional`, propagation, proxy/AOP, `ThreadLocal` tracking | Spring Framework (JVM-side) |
| `BEGIN` / `COMMIT` / `ROLLBACK` timing decision | Spring decides *when* to call these |
| Actually executing `BEGIN`/`COMMIT`/`ROLLBACK` | JDBC driver sends it to DB; DB executes it |
| Isolation level *enforcement* (locks, MVCC, snapshots, undo logs) | 100% database engine |
| Dirty read / non-repeatable read / phantom read possibility | 100% determined by DB engine + isolation level, not by Spring |

Spring is essentially a **conductor** telling the database when to open/close a transaction and what isolation flag to request — the actual concurrency-control machinery sits entirely inside the database engine, below the application framework layer.
