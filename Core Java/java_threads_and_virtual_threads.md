# Java Threads — From OS Internals to Virtual Threads
> Complete notes covering traditional threads, OS-level mechanics, locking internals, and virtual threads with AQS deep dive.

---

## Table of Contents

1. [How Traditional (Platform) Threads Work](#1-how-traditional-platform-threads-work)
2. [Thread Lifecycle and OS Scheduling](#2-thread-lifecycle-and-os-scheduling)
3. [10,000 Threads — What Actually Happens?](#3-10000-threads--what-actually-happens)
4. [Locking Internals — OS Level](#4-locking-internals--os-level)
5. [The Problem Virtual Threads Solve](#5-the-problem-virtual-threads-solve)
6. [Virtual Threads — How They Work](#6-virtual-threads--how-they-work)
7. [Continuations — The Mechanism Behind Virtual Threads](#7-continuations--the-mechanism-behind-virtual-threads)
8. [Virtual Threads Step-by-Step Trace](#8-virtual-threads-step-by-step-trace)
9. [Platform Threads vs Virtual Threads — Side by Side](#9-platform-threads-vs-virtual-threads--side-by-side)
10. [Why synchronized Pins Virtual Threads](#10-why-synchronized-pins-virtual-threads)
11. [ReentrantLock and AQS Internals](#11-reentrantlock-and-aqs-internals)
12. [LockSupport — The Bridge Between AQS and Virtual Threads](#12-locksupport--the-bridge-between-aqs-and-virtual-threads)
13. [Interview Questions](#13-interview-questions)

---

## 1. How Traditional (Platform) Threads Work

### The 1:1 Mapping Rule

Every Java platform thread maps **strictly 1:1** to an OS kernel thread. No exceptions. When you call `.start()` on a Java `Thread`, the JVM immediately makes a system call to the OS kernel:

- On **Linux**: `clone()` syscall
- Conceptually similar to `pthread_create()`

The OS kernel then:

1. Allocates a **fixed-size stack** for the thread (Java default: ~512KB–1MB, configurable via `-Xss`)
2. Creates a **Thread Control Block (TCB)** in kernel memory — stores:
   - Register values (program counter, stack pointer, general-purpose registers)
   - Thread state (RUNNING / READY / BLOCKED)
   - Priority
   - Which CPU core it's currently/last assigned to
3. Adds the thread to the OS scheduler's **run queue** (list of threads ready to execute)

```
Java Thread created
       │
       ▼
  JVM makes syscall (clone() on Linux)
       │
       ▼
  OS Kernel:
  ┌──────────────────────────────────────┐
  │  Allocates 512KB stack               │
  │  Creates TCB (registers, state, etc) │
  │  Adds to run queue                   │
  └──────────────────────────────────────┘
       │
       ▼
  Thread is now a real OS-managed entity
```

**The JVM does zero scheduling for platform threads — the OS handles everything.**

---

## 2. Thread Lifecycle and OS Scheduling

### How Many Threads Run Simultaneously?

A CPU core can only execute **one thread's instructions at any nanosecond**. With 4 cores, only 4 threads are *literally executing* at any instant. But your machine can have hundreds of threads **existing** — the OS **time-slices** them.

### Preemptive Context Switching

The OS scheduler gives each ready thread a small CPU time slice (a few milliseconds, called a **quantum** or **timeslice**), then forcibly interrupts it via a **hardware timer interrupt** and switches to the next ready thread.

A **context switch** involves:
```
1. Save current thread's register values → into its TCB
2. Load next thread's saved registers ← from its TCB
3. (if switching process) flush CPU cache / TLB
= costs ~1–10 microseconds per switch
```

With 4 cores and 100 ready threads — all 100 get to run, just not simultaneously. They take turns fast enough that it feels concurrent to us.

### Thread States and the Run Queue

```
           ┌─────────────────────────────────────────────────┐
           │              OS Kernel                           │
           │                                                  │
           │   RUN QUEUE (READY threads)                      │
           │   ┌──────────────────────────────────┐           │
           │   │ Thread A │ Thread B │ Thread C   │           │
           │   └──────────────────────────────────┘           │
           │                  │                               │
           │                  ▼ scheduled                     │
           │   CPU Cores: [Core1][Core2][Core3][Core4]        │
           │                                                  │
           │   WAIT QUEUE (BLOCKED threads)                   │
           │   ┌──────────────────────────────────┐           │
           │   │ Thread D (sleeping)              │           │
           │   │ Thread E (waiting on I/O)        │           │
           │   │ Thread F (waiting on lock)       │           │
           │   └──────────────────────────────────┘           │
           │            ↑ moved here when blocked             │
           │            ↓ moved back when unblocked           │
           └─────────────────────────────────────────────────┘
```

### What Happens When a Thread Sleeps?

When a thread calls `Thread.sleep(60000)`:

1. Triggers a kernel syscall (`nanosleep`)
2. Kernel changes thread state: `RUNNING` → `BLOCKED` (SLEEPING)
3. Removes it from the **run queue**
4. Sets a hardware/software timer to wake it after 60 seconds
5. **CPU core is immediately freed** — scheduler picks the next ready thread
6. After 60 seconds: timer fires → kernel moves thread back to `READY` → re-inserted into run queue

> **Key insight:** A sleeping thread consumes **zero CPU**. It only costs **memory** (its fixed stack sitting allocated) and a small bookkeeping overhead.

---

## 3. 10,000 Threads — What Actually Happens?

### Can You Create 10,000 Threads in Java?

**Technically yes.** Nothing in Java syntax stops you:

```java
for (int i = 0; i < 10_000; i++) {
    new Thread(() -> {
        Thread.sleep(60_000); // simulate blocking
    }).start();
}
```

But whether your machine can **sustain** this is a completely different question.

### 10,000 Java Threads = 10,000 OS Threads (strictly 1:1)

```
Java Thread #1      →    OS Kernel Thread #1    (Stack: 512KB, TCB in kernel)
Java Thread #2      →    OS Kernel Thread #2    (Stack: 512KB, TCB in kernel)
Java Thread #3      →    OS Kernel Thread #3    (Stack: 512KB, TCB in kernel)
...
Java Thread #10000  →    OS Kernel Thread #10000 (Stack: 512KB, TCB in kernel)
```

Each one is a real kernel thread with its own TCB, its own stack, its own entry in the OS scheduler's tracking tables.

### Do They All Run Simultaneously?

**No.** Running and existing are two completely different things.

```
CPU Cores = 8   (hypothetical machine)
Threads   = 10,000

Threads actually executing at this instant  = 8  (one per core, maximum)
Threads existing / alive in memory          = 10,000
```

Among those 10,000 threads, **most are NOT in the run queue at all** — they're blocked (waiting on I/O, lock, sleep). A blocked thread is in a separate **wait queue**, not the run queue. It consumes **zero CPU time** while blocked.

```
t=0ms : Thread #1 starts → makes HTTP call → immediately blocks → goes to WAIT queue
t=0ms : Thread #2 starts → makes HTTP call → immediately blocks → goes to WAIT queue
...
t=0ms : Thread #10000 starts → makes HTTP call → immediately blocks → goes to WAIT queue

Run queue during this period : nearly EMPTY
CPU cores                    : nearly IDLE
Wait queue                   : 10,000 threads just sitting there, reserving memory
```

### Then What IS the Problem?

**The bottleneck isn't CPU — it's memory + kernel overhead.**

#### Problem 1: Stack Memory Cost

```
10,000 threads × 512KB per stack = ~5GB of RAM
```
Just for stacks — before your heap, before the JVM itself, before anything else. Stacks aren't dynamically shrunk when a thread blocks. That memory is committed and stays reserved for the thread's full lifetime.

#### Problem 2: Context Switch Thrashing

Even blocked threads get periodically touched by the kernel (timer checks, signal delivery, wakeup handling). Every time a thread wakes (I/O response arrives), there's a context switch costing microseconds. With thousands of threads waking/sleeping constantly, a significant fraction of kernel time is spent context switching instead of running your application code.

#### Problem 3: OS Kernel Limits

The OS has hard limits on threads:
```bash
cat /proc/sys/kernel/threads-max    # system-wide limit, often ~100,000
ulimit -u                            # per-process limit
```
Beyond these limits:
```
java.lang.OutOfMemoryError: unable to create native thread
```
Even before hitting hard limits, the Linux CFS (Completely Fair Scheduler) degrades with tens of thousands of threads in and out of wait queues constantly.

---

## 4. Locking Internals — OS Level

### Step 1 — Try Cheaply First (User-Space CAS)

Most modern lock implementations first attempt an atomic **Compare-And-Swap (CAS)** — a single CPU instruction (`cmpxchg` on x86), no kernel involvement at all:

```
If memory_location == expected_value:
    set memory_location = new_value  (atomically)
    return SUCCESS
else:
    return FAIL
```

If the lock is free, this succeeds instantly. No context switch. Extremely fast.

### Step 2 — Spin Briefly (Optional)

If the lock is held but expected to be released very soon, the thread might **spin** — loop checking the CAS repeatedly for a short time. A context switch (~microseconds) can cost more than waiting a tiny bit if the lock holder is about to release it.

### Step 3 — Block via the Kernel (futex on Linux)

If the lock is held for longer, spinning wastes CPU. The thread asks the kernel to put it to sleep — via the `futex` (fast userspace mutex) syscall:

```
Thread calls futex(WAIT)
    → kernel moves thread to BLOCKED state
    → thread added to kernel's wait queue for this lock
    → CPU core freed for other ready threads
```

### Step 4 — Wake-up on Unlock

When the lock holder releases:
1. CAS flips lock state back to free
2. Checks if anyone is waiting
3. Calls `futex(WAKE)` → kernel moves waiting thread(s) back to `READY`
4. They re-insert into run queue, compete for CPU, retry the CAS, one wins the lock

### Java `synchronized` Specifically

`synchronized` in Java maps to the object's **monitor**, implemented via the object header's **Mark Word**:

```
Uncontended:  Biased Locking / Thin Lock → pure CAS in user space (no OS call, very fast)
Contended:    Lock Inflation → Heavy Monitor → uses OS futex mechanism (expensive)
```

This inflation is why uncontended `synchronized` is cheap and heavily contended `synchronized` is expensive (lots of context switches).

---

## 5. The Problem Virtual Threads Solve

### The Root Problem in One Line

> A blocked platform thread wastes **nothing in CPU** but wastes **real memory (512KB stack)** and **real kernel bookkeeping** — and you can't have more than a few thousand before the OS buckles.

Virtual threads solve exactly this. Not the CPU problem. The **memory + kernel overhead** problem.

### The Core Idea: Decouple "Thread the Concept" from "OS Thread the Resource"

```
Before Java 21 (platform threads):
Java Thread  =  OS Thread   (always, strict 1:1, no exceptions)

After Java 21 (virtual threads):
Virtual Thread  =  A task that FEELS like a thread to your code
Carrier Thread  =  The actual OS thread doing real executing
```

Your code still writes `Thread.sleep()`, catches `InterruptedException`, reads `Thread.currentThread()` — everything feels identical. But virtual threads are not 1:1 to OS threads. They're **tasks that borrow an OS thread only when they have real CPU work to do**, and return it the instant they'd otherwise block.

---

## 6. Virtual Threads — How They Work

### The Restaurant Analogy

Think of a restaurant with **4 waiters** (carrier threads = CPU cores) and **1,000 customers** (virtual threads = tasks).

A waiter takes an order from customer A, walks it to the kitchen, and instead of standing there waiting for food to cook, **walks away** and takes customer B's order. When A's food is ready, any free waiter delivers it. No waiter ever sits idle "waiting."

```
Waiter       = Carrier Thread (OS thread)
Customer     = Virtual Thread (your task)
Taking order = CPU work (executing code)
Waiting for food = I/O blocking (network, DB, sleep)
```

The waiter only stays attached to a customer while **real work is happening**. The moment the customer's request becomes a *waiting* situation, the waiter detaches and serves someone else.

### Carrier Threads

The JVM maintains a small pool of real OS threads called **carrier threads**:

- Backed by `ForkJoinPool`
- Default size = **number of CPU cores**
- Created once at JVM startup
- Your application never directly interacts with them

---

## 7. Continuations — The Mechanism Behind Virtual Threads

This is what makes unmounting/remounting possible.

### What Is a Continuation?

When a virtual thread is "paused," its entire execution state must be saved so it can resume exactly where it left off. That saved state is called a **continuation**. It contains:

- Current **program counter** (which line of code was it on?)
- All **local variables** at that point
- The entire **call stack** (all methods called to get here)

### Platform Thread Stack vs Virtual Thread Continuation

```
Platform Thread stack:
┌─────────────────────┐
│  Fixed 512KB block  │  ← OS-allocated, kernel-tracked, CANNOT be moved
│  in kernel memory   │     lives for the entire thread lifetime
└─────────────────────┘

Virtual Thread continuation:
┌──────────────────────────────────┐
│  Small heap object (~KB range)   │  ← Just a Java object on the heap
│  grows dynamically as needed     │     GC-managed, created/destroyed cheaply
└──────────────────────────────────┘
```

The continuation for a platform thread is locked to the kernel-allocated stack — you can't move it, shrink it, or detach it from the OS thread. The continuation for a virtual thread is just a heap object — portable, lightweight, GC-managed.

---

## 8. Virtual Threads Step-by-Step Trace

### The Code

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10_000; i++) {
        executor.submit(() -> {
            System.out.println("Before I/O");   // Line A — CPU work
            String result = httpClient.send();  // Line B — blocking I/O
            System.out.println(result);         // Line C — CPU work
        });
    }
}
```

Assume 8 CPU cores → 8 carrier threads.

---

### Phase 1: Virtual Threads Created — Very Cheap

```
VThread #1     created → heap object allocated (~few hundred bytes)
VThread #2     created → heap object allocated
...
VThread #10000 created → heap object allocated

Total memory: ~few hundred MB    (vs ~5GB for platform threads)
OS kernel threads created: still just 8 (the carrier threads, created at startup)
```

**Creating 10,000 virtual threads does NOT create any new OS threads.**

---

### Phase 2: Mount — Virtual Thread Starts Running

The JVM scheduler picks VThread #1, finds a free carrier thread (say Carrier #1), and **mounts** it:

```
Carrier Thread #1  ←── mounts ──  VThread #1
```

"Mounting" = the carrier thread loads VThread #1's continuation and starts executing it. The carrier thread has no idea it's serving a virtual thread — it's just running code.

VThread #1 executes **Line A** (`System.out.println`) — real CPU work. Carrier #1 is genuinely busy.

---

### Phase 3: Unmount — Virtual Thread Hits Blocking I/O

VThread #1 reaches **Line B** (`httpClient.send()`). The JDK's HTTP client has been rewritten to be virtual-thread-aware. Instead of blocking the OS thread:

```
Step 1: Register the network operation with the OS's async I/O 
        mechanism (epoll on Linux):
        "kernel, tell me when data arrives on this socket — don't block me"

Step 2: Snapshot VThread #1's entire call stack 
        → saved into its heap-allocated continuation object
        (saves: which line it was on, all local variables, full call stack)

Step 3: UNMOUNT
        → VThread #1 detaches from Carrier Thread #1
        → VThread #1 is now just a parked heap object (no OS thread attached)
        → Carrier Thread #1 is COMPLETELY FREE

Step 4: Carrier Thread #1 immediately picks up VThread #2
        → mounts it → starts executing VThread #2's code
```

At this point:
```
Carrier Thread #1  ←── now running ──  VThread #2
Carrier Thread #2  ←── now running ──  VThread #3
...
Carrier Thread #8  ←── now running ──  VThread #9

VThread #1    → parked on heap, waiting for network response  (NO OS thread!)
VThread #10   → parked on heap, waiting for network response  (NO OS thread!)
VThread #100  → parked on heap, waiting for network response  (NO OS thread!)
...
VThread #10000 → parked on heap                               (NO OS thread!)
```

All 10,000 virtual threads are "in flight" simultaneously. Only 8 OS threads exist. The waiting state costs only heap memory, not OS thread stacks.

---

### Phase 4: Remount — I/O Completes, Virtual Thread Resumes

The network response for VThread #1 arrives. OS notifies JVM via `epoll`. JVM scheduler marks VThread #1 as "ready to run."

```
Step 1: Pick any free carrier thread 
        (say Carrier #3 — doesn't have to be original Carrier #1)

Step 2: Load VThread #1's continuation from the heap
        into Carrier #3's execution context

Step 3: REMOUNT
        → Carrier #3 resumes executing VThread #1
        → from exactly where it left off (Line C)
```

VThread #1 wakes up, executes **Line C**, finishes. Its heap object is garbage collected.

> Your code (the lambda) never knew any of this happened. It looks like a normal sequential thread that ran straight through A → B → C.

---

### The Full Platform vs Virtual Thread Picture

```
                        TRADITIONAL THREAD MODEL
                        ─────────────────────────

  10,000 requests
       │
       ▼
  ┌─────────────────────────────────────────────────────────┐
  │              OS Kernel Thread Pool                       │
  │                                                          │
  │  Thread #1   [BLOCKED - waiting on HTTP response]  512KB│
  │  Thread #2   [BLOCKED - waiting on HTTP response]  512KB│
  │  Thread #3   [BLOCKED - waiting on HTTP response]  512KB│
  │  ...                                                     │
  │  Thread #9999 [BLOCKED]                            512KB │
  │  Thread #10000 [BLOCKED]                          512KB  │
  │                                                          │
  │  Total memory just for stacks: ~5GB                      │
  │  CPU actually being used: near 0%                        │
  │  OS scheduler bookkeeping: HIGH                          │
  └─────────────────────────────────────────────────────────┘
                              │
                   8 CPU cores below
                   sitting mostly idle
                   (nothing in run queue)


                        VIRTUAL THREAD MODEL
                        ─────────────────────

  10,000 requests
       │
       ▼
  ┌─────────────────────────────────────────────────────────┐
  │         JVM Heap (Virtual Thread Continuations)          │
  │                                                          │
  │  VThread #1   [parked on heap] ~few hundred bytes        │
  │  VThread #2   [parked on heap] ~few hundred bytes        │
  │  ...                                                      │
  │  VThread #10000 [parked on heap] ~few hundred bytes      │
  │                                                          │
  │  Total memory: ~few hundred MB (vs 5GB above)            │
  └─────────────────────────────────────────────────────────┘
                              │
  ┌─────────────────────────────────────────────────────────┐
  │         8 Carrier OS Threads (= CPU cores)               │
  │  Constantly picking up whichever VThread has work ready  │
  └─────────────────────────────────────────────────────────┘
                              │
                   8 CPU cores
                   fully utilized
                   for actual work
```

---

## 9. Platform Threads vs Virtual Threads — Side by Side

| Aspect | Platform Thread | Virtual Thread |
|---|---|---|
| Managed by | OS scheduler | JVM scheduler (on top of carrier threads) |
| Maps to | 1 OS kernel thread (strict 1:1) | Borrowed carrier thread (M:N mapping) |
| Memory footprint | ~512KB–1MB fixed stack | Few hundred bytes, grows on heap |
| Creation cost | Expensive (OS syscall, kernel allocations) | Very cheap (plain heap object) |
| Max practical count | Thousands | Millions |
| Blocking behavior | Blocks the OS thread (stays in wait queue) | Unmounts from carrier, frees it |
| Use case | CPU-bound work | I/O-bound, high-concurrency work |
| Thread pooling | Required (reuse is essential) | Not needed — create one per task |
| `ThreadLocal` | Fine, commonly used | Works but risky — millions × ThreadLocal = memory bloat |
| Daemon status | Configurable | Always daemon threads |
| Introduced as stable | Java 1.0 | Java 21 (JEP 444) |

### Code Comparison

**Platform thread approach — limited concurrency due to pool cap:**
```java
// Only 200 requests run concurrently; 9,800 queue up and wait
ExecutorService executor = Executors.newFixedThreadPool(200);
for (int i = 0; i < 10_000; i++) {
    executor.submit(() -> {
        Thread.sleep(Duration.ofSeconds(1)); // OS thread blocked for 1 second
        return fetchFromDownstreamService();
    });
}
```

**Virtual thread approach — all 10,000 in flight simultaneously:**
```java
// All 10,000 virtual threads in flight; carrier threads freed during sleep/I/O
try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
    for (int i = 0; i < 10_000; i++) {
        executor.submit(() -> {
            Thread.sleep(Duration.ofSeconds(1)); // unmounts, doesn't block carrier
            return fetchFromDownstreamService();
        });
    }
}
```

**Creating a virtual thread directly:**
```java
Thread vt = Thread.ofVirtual().name("worker-1").start(() -> {
    System.out.println("Running on: " + Thread.currentThread());
});
vt.join();

// Check if a thread is virtual
System.out.println(Thread.currentThread().isVirtual()); // true
```

### The Critical Constraint

Virtual threads only unmount during operations the JDK has made **virtual-thread-aware**:

| Operation | Unmounts? |
|---|---|
| `java.net` / `java.io` / `java.nio` blocking calls | ✅ Yes |
| `Thread.sleep()` | ✅ Yes |
| `ReentrantLock` / `java.util.concurrent` locks | ✅ Yes |
| JDBC (if driver supports it) | ✅ Yes |
| CPU-heavy work (tight loop, computation) | ❌ No — stays mounted, needs CPU |
| `synchronized` block with blocking inside (pre-JDK 24) | ❌ No — pins carrier thread |

---

## 10. Why `synchronized` Pins Virtual Threads

### The Monitor and Mark Word

Every Java object has a header in memory. Inside that header is a field called the **Mark Word** — a few bytes that stores lock state. When you write:

```java
synchronized (someObject) {
    // critical section
}
```

The JVM emits:
- `monitorenter` — acquire the lock on `someObject`
- `monitorexit` — release it when the block exits

When there's contention and the lock inflates to a **heavyweight monitor**, the JVM creates an `ObjectMonitor` structure:

```
ObjectMonitor {
    _owner       → pointer to the thread that holds the lock
    _count       → reentrance count
    _wait_set    → threads waiting (called wait())
    _entry_list  → threads blocked trying to acquire this lock
}
```

**The problem:** `_owner` points to the **OS carrier thread**, not the virtual thread riding on top of it.

### Why `_owner` Points to the OS Thread

`synchronized` was designed decades before virtual threads, when Java thread = OS thread was an absolute truth. Monitor ownership was always recorded at the OS thread level because that's all that existed. The JVM records lock ownership via `JavaThread*` — a native pointer to the carrier OS thread.

### What Happens When a Virtual Thread Enters `synchronized`

```java
synchronized (someObject) {       // ObjectMonitor._owner = Carrier Thread #3
    httpClient.send();            // virtual thread tries to unmount here
}
```

VThread #1 is mounted on Carrier Thread #3. It acquires the monitor — `ObjectMonitor._owner = Carrier Thread #3`.

Now it hits the blocking I/O call and tries to unmount. But:

```
ObjectMonitor._owner = Carrier Thread #3  ← OS thread is the recorded owner
```

If the JVM forcibly unmounts VThread #1 and lets Carrier Thread #3 pick up VThread #2:

**Scenario A:** VThread #2 tries to acquire the same lock:
```
ObjectMonitor._owner = Carrier Thread #3  (still set from VThread #1's acquisition)
VThread #2 is also running on Carrier Thread #3
→ JVM sees: "Carrier Thread #3 already owns this lock"
→ treats it as REENTRANT acquisition — WRONGLY succeeds
→ Two virtual threads inside the critical section simultaneously → data corruption
```

**Scenario B:** Even if VThread #2 doesn't touch that lock:
```
Carrier Thread #3 is now running VThread #2's code
But records still say: "Carrier Thread #3 owns monitor on someObject"
This is a LIE — VThread #1's logic owns it, not VThread #2's logic
```

**The JVM takes the only safe path: PIN VThread #1 to Carrier Thread #3.**

### What Pinning Looks Like

```
VThread #1  pinned to  Carrier Thread #3
                │
                │  hits blocking I/O inside synchronized block
                │
                ▼
        Carrier Thread #3 is now BLOCKED
        (exactly like a platform thread would be)
        
        This carrier thread CANNOT serve anyone else
        until VThread #1 exits the synchronized block
        AND the blocking I/O completes
```

If all 8 carrier threads get pinned simultaneously → your application effectively freezes. All 9,992 other virtual threads waiting for a carrier thread get starved.

**Detect pinning:**
```bash
-Djdk.tracePinnedThreads=full
```

### Why `ReentrantLock` Doesn't Pin

`ReentrantLock` is implemented entirely in Java using `AbstractQueuedSynchronizer (AQS)` and `LockSupport.park()`. Its ownership tracking is a plain Java `volatile` field pointing to a `Thread` object — which can point to the **virtual thread** directly, not the carrier OS thread.

```java
// This PINS the carrier thread (pre JDK 24)
synchronized (someObject) {
    httpClient.send();
}

// This does NOT pin — virtual thread unmounts cleanly
ReentrantLock lock = new ReentrantLock();
lock.lock();
try {
    httpClient.send();
} finally {
    lock.unlock();
}
```

### JDK 24 Fix (JEP 491)

JDK 24 reimplemented `synchronized` so that `ObjectMonitor._owner` now stores a reference to the **virtual thread object** rather than the OS carrier thread pointer. Ownership now travels with the virtual thread through mount/unmount cycles.

```
JDK 21–23:   synchronized  →  pins carrier thread     (bad for virtual threads)
             ReentrantLock →  unmounts cleanly         (good)

JDK 24+:     synchronized  →  unmounts cleanly         (fixed)
             ReentrantLock →  unmounts cleanly         (good)
```

### Summary: synchronized vs ReentrantLock for Virtual Threads

```
synchronized (pre JDK 24)             ReentrantLock
──────────────────────────────────    ──────────────────────────────────
Owner tracked in ObjectMonitor         Owner tracked in AQS field
  → stored as OS carrier thread ptr      → stored as Java Thread object ref

Blocking via OS futex/wait             Blocking via LockSupport.park()
  → OS kernel parks the carrier           → JVM unmounts the virtual thread
  → carrier thread BLOCKED                → carrier thread FREED

Cannot unmount while locked            Can unmount freely while locked
  → carrier thread PINNED                 → ownership stays on heap object

Lock = kernel-level concept            Lock = pure Java heap objects
```

---

## 11. ReentrantLock and AQS Internals

### The Three Core Components of AQS

`ReentrantLock` is built on top of `AbstractQueuedSynchronizer (AQS)`, the backbone of almost everything in `java.util.concurrent`.

```java
public abstract class AbstractQueuedSynchronizer {
    
    private volatile int state;               // Component 1: lock state
    private transient Node head;              // Component 2: CLH queue head
    private transient Node tail;              // Component 2: CLH queue tail
    
    // Component 3: from AbstractOwnableSynchronizer (parent of AQS)
    private transient Thread exclusiveOwnerThread; // owner — Java Thread object ref
}
```

---

### Component 1: `state` — The Lock's Heartbeat

```java
private volatile int state;
```

For `ReentrantLock`:

```
state = 0  → lock is FREE, nobody holds it
state = 1  → lock is HELD, acquired once
state = 2  → lock is HELD, acquired twice (same thread, reentrant)
state = N  → lock is HELD, acquired N times (must be released N times)
```

`volatile` guarantees that when any thread writes to `state`, the write is immediately visible to all other threads. No CPU cache hiding.

Changes to `state` under contention use **CAS**:

```java
// Atomic operation — single CPU instruction (cmpxchg on x86)
// "Only set state to 1 if it is currently 0"
compareAndSetState(0, 1)
```

If two threads try this simultaneously, the CPU hardware guarantees exactly one wins and one fails. No race condition. No kernel syscall. Pure user-space atomics.

---

### Component 2: The CLH Queue — Where Waiting Threads Live

When a thread fails to acquire the lock, it doesn't spin burning CPU — it enters the **CLH Queue** (Craig, Landin, Hagersten), a doubly linked list of `Node` objects:

```java
static final class Node {
    volatile Thread thread;     // the thread waiting in this node
    volatile Node prev;         // previous node in queue
    volatile Node next;         // next node in queue
    volatile int waitStatus;    // CANCELLED / SIGNAL / CONDITION / 0
}
```

`waitStatus = SIGNAL` means: "when you (the previous node's thread) release the lock, please wake me up."

The queue at any point:

```
HEAD (dummy)  →  Node(Thread A)  →  Node(Thread B)  →  Node(Thread C)  →  null
                  waitStatus=SIGNAL   waitStatus=SIGNAL   waitStatus=0

HEAD is always a dummy node.
The actual lock holder is NOT in this queue — queue only holds WAITING threads.
```

---

### Component 3: `exclusiveOwnerThread` — The Key Differentiator

```java
private transient Thread exclusiveOwnerThread; // plain Java field
```

This stores a reference to the `java.lang.Thread` object that currently owns the lock. For virtual threads, this IS the virtual thread object — not the carrier OS thread. This is the fundamental reason `ReentrantLock` works correctly with virtual threads.

Ownership is stored on the **heap**, travels with the virtual thread through mount/unmount cycles.

---

### Full Lifecycle Trace — 3 Virtual Threads, 1 Lock

```java
ReentrantLock lock = new ReentrantLock();

// VThread #1, #2, #3 all try to lock simultaneously
lock.lock();
try {
    httpClient.send();  // blocking I/O inside the lock
} finally {
    lock.unlock();
}
```

---

#### Step 1: VThread #1 Acquires — Uncontested Fast Path

VThread #1 calls `lock.lock()` → `sync.acquire(1)` → `tryAcquire(1)`:

```java
protected final boolean tryAcquire(int acquires) {
    final Thread current = Thread.currentThread(); // returns VThread #1 object
    int c = getState();
    
    if (c == 0) {
        // Lock is free — try to grab it atomically
        if (compareAndSetState(0, 1)) {        // CAS: 0 → 1
            setExclusiveOwnerThread(current);  // owner = VThread #1
            return true;                       // acquired!
        }
    }
    return false;
}
```

State after this:
```
state                 = 1
exclusiveOwnerThread  = VThread #1   ← Java object reference, NOT OS thread
CLH queue             = empty (no contention yet)
```

VThread #1 enters the lock, calls `httpClient.send()`, and **unmounts from its carrier thread** — because `httpClient.send()` is virtual-thread-aware.

```
VThread #1  → parked on heap (waiting for HTTP response)
              BUT STILL OWNS THE LOCK
              exclusiveOwnerThread = VThread #1  (heap reference, still valid)
              
Carrier Thread #1 → FREE, picks up VThread #2
```

> This is the magic. Ownership is a reference to a heap object. VThread #1 is unmounted but the reference still correctly says "VThread #1 owns this lock." No dangling pointer, no confusion.

---

#### Step 2: VThread #2 Tries to Acquire — Contested, Goes Into Queue

VThread #2 calls `tryAcquire(1)`:

```java
int c = getState();  // c = 1, lock is held

if (c == 0) { ... } // no, lock isn't free

else if (current == getExclusiveOwnerThread()) { ... } // no, owner is VThread #1, not VThread #2

return false; // cannot acquire
```

`tryAcquire` returns false. AQS calls `acquireQueued()`:

**Sub-step A — Create a node and enqueue VThread #2:**
```java
Node node = addWaiter(Node.EXCLUSIVE);
// Creates Node { thread = VThread #2 } and appends to CLH queue tail
```

Queue now:
```
HEAD (dummy)  →  Node(VThread #2, waitStatus=0)
```

**Sub-step B — Park VThread #2:**
```java
LockSupport.park(this);
```

`LockSupport.park()` is virtual-thread-aware. When called from a virtual thread, it **unmounts** the virtual thread from its carrier, saves the continuation to the heap, and frees the carrier thread.

```
VThread #2  → parked on heap (waiting to acquire lock)
              Node in CLH queue: Node(VThread #2, waitStatus=SIGNAL)
              
Carrier Thread → FREE again, picks up VThread #3
```

VThread #3 goes through the same path — `tryAcquire` fails, gets enqueued, parks:

```
HEAD (dummy)  →  Node(VThread #2, SIGNAL)  →  Node(VThread #3, 0)
```

**State of the world at this point:**
```
state                 = 1
exclusiveOwnerThread  = VThread #1  (parked on heap, waiting on HTTP)
CLH queue             = [VThread #2 parked] → [VThread #3 parked]
Carrier threads       = all free, running other virtual threads
OS threads consumed   = 0 for any of this waiting
```

---

#### Step 3: VThread #1's I/O Completes — Remounts and Unlocks

HTTP response arrives. JVM scheduler remounts VThread #1 onto any available carrier thread. VThread #1 resumes, calls `lock.unlock()`:

```java
public void unlock() {
    sync.release(1);
}

protected final boolean tryRelease(int releases) {
    int c = getState() - releases;  // 1 - 1 = 0
    
    if (Thread.currentThread() != getExclusiveOwnerThread())
        throw new IllegalMonitorStateException(); // safety check
    
    if (c == 0) {
        setExclusiveOwnerThread(null);  // clear owner
    }
    setState(c);  // state = 0  (volatile write — visible to all threads instantly)
    return c == 0;
}
```

State after release:
```
state                 = 0   ← lock is FREE
exclusiveOwnerThread  = null
```

---

#### Step 4: Wake Up the Next Waiter

```java
// In release():
if (tryRelease(arg)) {
    Node h = head;
    unparkSuccessor(h);  // wake up first real waiter
}

private void unparkSuccessor(Node node) {
    Node next = node.next;          // Node(VThread #2)
    LockSupport.unpark(next.thread); // unpark VThread #2
}
```

`LockSupport.unpark(VThread #2)` tells the JVM scheduler: "VThread #2 is ready to run again." JVM marks it as schedulable. When a carrier thread is free, it mounts VThread #2 and resumes from exactly where it parked — inside `acquireQueued()`, right after `LockSupport.park()`.

VThread #2 wakes, retries `tryAcquire`:
```java
compareAndSetState(0, 1)              // succeeds — state was 0
setExclusiveOwnerThread(VThread #2)   // VThread #2 is new owner
```

VThread #2 now holds the lock. VThread #3 stays parked in the queue.

---

### Reentrancy — Same Thread Acquires Multiple Times

```java
lock.lock();   // state = 1
lock.lock();   // state = 2  (reentrant)
lock.lock();   // state = 3
lock.unlock(); // state = 2
lock.unlock(); // state = 1
lock.unlock(); // state = 0 → lock released → wake next waiter
```

In `tryAcquire`:
```java
else if (current == getExclusiveOwnerThread()) {
    // Same thread trying to acquire again — it's reentrant
    int nextc = c + acquires;  // just increment state, no CAS needed
    setState(nextc);           // only owner can reach this branch
    return true;               // no blocking
}
```

Simple integer increment. No blocking. No CAS needed because only the owner can reach this branch.

---

### The Complete Internal Picture

```
ReentrantLock internals during VThread #2 and #3 waiting
────────────────────────────────────────────────────────────────

     ┌─────────────────────────────────────┐
     │           AQS State                 │
     │                                     │
     │  state = 1                          │  ← volatile int
     │  exclusiveOwnerThread = VThread #1  │  ← Java object ref (heap)
     │                                     │
     └─────────────────────────────────────┘

     ┌─────────────────────────────────────────────────────┐
     │                  CLH Queue                           │
     │                                                      │
     │  HEAD(dummy) → Node(VThread #2) → Node(VThread #3)  │
     │                 [parked]           [parked]          │
     │                                                      │
     └─────────────────────────────────────────────────────┘

     ┌─────────────────────────────────────┐
     │         JVM Heap                    │
     │                                     │
     │  VThread #1 continuation  [parked]  │  ← waiting on HTTP
     │  VThread #2 continuation  [parked]  │  ← waiting on lock
     │  VThread #3 continuation  [parked]  │  ← waiting on lock
     │                                     │
     └─────────────────────────────────────┘

     ┌─────────────────────────────────────┐
     │         OS Level                    │
     │                                     │
     │  Carrier Thread #1  → running other │
     │  Carrier Thread #2  → running other │
     │  ...                                │
     │                                     │
     │  OS kernel threads involved in      │
     │  any of the above waiting: ZERO     │
     │                                     │
     └─────────────────────────────────────┘
```

---

## 12. LockSupport — The Bridge Between AQS and Virtual Threads

`LockSupport.park()` and `LockSupport.unpark()` are the foundation that makes all of `java.util.concurrent` virtual-thread-compatible.

| Operation | Platform Thread | Virtual Thread |
|---|---|---|
| `LockSupport.park()` | Blocks the OS thread via `futex(WAIT)` | Unmounts the virtual thread from carrier, saves continuation to heap |
| `LockSupport.unpark(t)` | OS wakes up the blocked OS thread via `futex(WAKE)` | JVM marks the virtual thread as schedulable, mounts on next free carrier |

This single abstraction is why `ReentrantLock`, `Semaphore`, `CountDownLatch`, `CompletableFuture`, and every other `java.util.concurrent` class works correctly with virtual threads without modification. They all ultimately suspend via `LockSupport.park()`, which the JVM reimplemented to be virtual-thread-aware.

```
AQS waiting chain:
lock.lock() → tryAcquire() fails → addWaiter() → LockSupport.park()
                                                        │
                               ┌────────────────────────┤
                               │                        │
                    Platform Thread:           Virtual Thread:
                    futex(WAIT) syscall        unmount continuation
                    OS parks the OS thread     to heap, free carrier
```

---

## 13. Interview Questions

### OS Thread and Platform Thread Internals

1. What happens at the OS level when you call `new Thread(...).start()` in Java?
2. What is a Thread Control Block (TCB) and what does it contain?
3. With 4 CPU cores and 100 threads, how many threads run simultaneously? What happens to the others?
4. If 4 threads are sleeping for 60 seconds, are your 4 CPU cores blocked for that duration? Explain what actually happens.
5. What is the difference between a thread in the run queue vs the wait queue?
6. What is a context switch and what does it cost?
7. What is a futex, and why was it invented over pure kernel-based mutexes?
8. Explain the lock inflation path in Java's `synchronized`: biased locking → thin lock → heavy monitor.

### Virtual Threads

9. What problem do virtual threads solve, and why couldn't platform threads solve it just by tuning pool size?
10. What is a carrier thread? How is the carrier thread pool sized by default, and can you configure it?
11. Explain "mounting" and "unmounting" of a virtual thread onto a carrier thread.
12. What is a continuation, and how does it differ from a platform thread's call stack?
13. Why are virtual threads not suitable for CPU-bound tasks?
14. What is "thread pinning" in the context of virtual threads, and what causes it?
15. Why is it an anti-pattern to pool virtual threads? What's the recommended approach?
16. How do virtual threads interact with `ThreadLocal`? What is the proposed alternative?
17. Does `Thread.sleep()` block the carrier thread inside a virtual thread? Walk through what happens internally.
18. How would you migrate a Spring Boot app (Tomcat thread pool) to virtual threads? What configuration changes?
19. Virtual threads were stabilized in which JDK version and JEP?

### ReentrantLock and AQS

20. What are the three core components of AQS and what does each do?
21. Why does `synchronized` (pre-JDK 24) pin virtual threads but `ReentrantLock` does not?
22. What does `volatile` on AQS's `state` field guarantee, and why is it needed?
23. What is the CLH queue in AQS? What does each `Node` contain?
24. Walk through what happens when three threads simultaneously try to acquire a `ReentrantLock` and only one wins.
25. How does AQS implement reentrancy? What changes in `state` and what doesn't?
26. What role does `LockSupport.park/unpark` play in AQS, and why is it the bridge that makes `java.util.concurrent` virtual-thread-compatible?
27. Scenario: A service makes blocking JDBC calls with a connection pool of size 50. Will virtual threads automatically give more throughput? *(The bottleneck shifts to the connection pool, not the thread model — tests real understanding.)*

---

*Notes compiled from deep-dive discussion covering OS thread internals, Java platform thread mechanics, virtual thread architecture (JEP 444 / JDK 21+), synchronized pinning, and AQS/ReentrantLock implementation.*
