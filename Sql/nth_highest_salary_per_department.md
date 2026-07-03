# Finding Highest / 2nd Highest Salary Per Department — SQL Deep Dive

## Table of Contents
1. [Problem Statement](#problem-statement)
2. [Sample Data Used Throughout](#sample-data-used-throughout)
3. [Solution 1: Highest Salary Per Department (Window Function)](#solution-1-highest-salary-per-department-window-function)
4. [Solution 2: Highest Salary Per Department (Correlated Subquery)](#solution-2-highest-salary-per-department-correlated-subquery)
5. [Solution 3: 2nd Highest Salary Per Department (Window Function)](#solution-3-2nd-highest-salary-per-department-window-function)
6. [Solution 4: 2nd Highest Salary Per Department (Correlated Subquery)](#solution-4-2nd-highest-salary-per-department-correlated-subquery)
7. [SQL Logical Order of Execution](#sql-logical-order-of-execution)
8. [RANK vs DENSE_RANK — Why It Matters](#rank-vs-dense_rank--why-it-matters)
9. [Correlated Subquery: Why "Below Me" Never Matters](#correlated-subquery-why-below-me-never-matters)
10. [Performance Notes](#performance-notes)
11. [Generalizing to Nth Highest](#generalizing-to-nth-highest)
12. [Interview Talking Points](#interview-talking-points)

---

## Problem Statement

Given an `employee` table with columns `dept_id, emp_id, emp_name, salary`, write queries to:

1. Find the employee(s) with the **highest salary** in each department.
2. Find the employee(s) with the **second highest (distinct) salary** in each department.

**Constraints to handle:**
- Multiple employees in the same department can have the **same salary** (ties).
- A department may have very few employees (edge case: 1 employee → no "2nd highest" exists).
- Should generalize cleanly to "Nth highest" if asked as a follow-up.

**Approaches considered:**
- Window functions (`RANK()` / `DENSE_RANK()`) — single-pass, clean, preferred in modern SQL.
- Correlated subquery — no window function support needed, works on older engines, but different tie-handling logic.
- `GROUP BY` + `JOIN` — conceptually simple, but limited to the "1st highest" case and gets clunky for "2nd highest."

---

## Sample Data Used Throughout

```
dept_id | emp_id | emp_name | salary
--------|--------|----------|-------
10      | 1      | A        | 90000
10      | 2      | B        | 90000
10      | 3      | C        | 80000
20      | 4      | D        | 70000
20      | 5      | E        | 60000
```

Department 10 has a **tie** at the top (A and B both earn 90000) — this is intentionally included because ties are where naive solutions break.

---

## Solution 1: Highest Salary Per Department (Window Function)

### Query
```sql
SELECT dept_id, emp_id, emp_name, salary
FROM (
    SELECT
        dept_id,
        emp_id,
        emp_name,
        salary,
        RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
    FROM employee
) ranked
WHERE rnk = 1;
```

### Approach
Rank every employee within their own department by salary (descending), then keep only rank 1. Wrapping in a subquery is **mandatory** because window function results cannot be filtered in the same `SELECT` level's `WHERE` clause.

### Internal Step-by-Step Working

**Step 1 — Read base table (`FROM employee`)**
The engine loads/scans the raw rows, unordered, unfiltered:
```
dept_id | emp_id | emp_name | salary
10      | 1      | A        | 90000
10      | 2      | B        | 90000
10      | 3      | C        | 80000
20      | 4      | D        | 70000
20      | 5      | E        | 60000
```

**Step 2 — PARTITION BY dept_id**
Rows are grouped into independent partitions — one per distinct `dept_id`. Each partition is processed in isolation from the others.
```
Partition dept_id=10 → rows: A, B, C
Partition dept_id=20 → rows: D, E
```

**Step 3 — ORDER BY salary DESC (within each partition)**
Each partition is sorted internally by salary, descending:
```
Partition 10 sorted: A(90000), B(90000), C(80000)
Partition 20 sorted: D(70000), E(60000)
```

**Step 4 — RANK() assignment**
Walk each sorted partition top to bottom. Equal salary → equal rank. Next distinct salary skips ranks by the number of tied rows before it.
```
Partition 10: A=90000 → rnk=1
              B=90000 → rnk=1   (tie with A)
              C=80000 → rnk=3   (skips rnk=2 due to 2-way tie above)

Partition 20: D=70000 → rnk=1
              E=60000 → rnk=2
```

**Step 5 — Materialize intermediate result (the `ranked` derived table)**
```
dept_id | emp_id | emp_name | salary | rnk
10      | 1      | A        | 90000  | 1
10      | 2      | B        | 90000  | 1
10      | 3      | C        | 80000  | 3
20      | 4      | D        | 70000  | 1
20      | 5      | E        | 60000  | 2
```

**Step 6 — Outer WHERE rnk = 1**
```
dept_id | emp_id | emp_name | salary | rnk
10      | 1      | A        | 90000  | 1
10      | 2      | B        | 90000  | 1
20      | 4      | D        | 70000  | 1
```

**Step 7 — Outer SELECT (drop `rnk` column)**
```
dept_id | emp_id | emp_name | salary
10      | 1      | A        | 90000
10      | 2      | B        | 90000
20      | 4      | D        | 70000
```

### Final Output
```
dept_id | emp_id | emp_name | salary
10      | 1      | A        | 90000
10      | 2      | B        | 90000
20      | 4      | D        | 70000
```
Both A and B correctly appear — `RANK()` handles the tie naturally.

---

## Solution 2: Highest Salary Per Department (Correlated Subquery)

### Query
```sql
SELECT e.dept_id, e.emp_id, e.emp_name, e.salary
FROM employee e
WHERE e.salary = (
    SELECT MAX(e2.salary)
    FROM employee e2
    WHERE e2.dept_id = e.dept_id
);
```

### Approach
For every row in the outer table, ask: "what is the max salary in *my* department?" If my salary equals that max, keep me. The inner query is **correlated** — it references `e.dept_id` from the outer row, so conceptually it re-runs once per outer row using that row's department as input.

### Internal Step-by-Step Working (row-by-row)

**Outer row: A (dept_id=10, salary=90000)**
- Inner query runs: `SELECT MAX(salary) FROM employee WHERE dept_id = 10`
- Scans partition 10: {90000, 90000, 80000} → returns `90000`
- Compare: `90000 = 90000` → ✅ TRUE → **A kept**

**Outer row: B (dept_id=10, salary=90000)**
- Inner query re-runs (same dept, same data) → returns `90000`
- Compare: `90000 = 90000` → ✅ TRUE → **B kept**

**Outer row: C (dept_id=10, salary=80000)**
- Inner query re-runs → returns `90000`
- Compare: `80000 = 90000` → ❌ FALSE → **C dropped**

**Outer row: D (dept_id=20, salary=70000)**
- Inner query runs: `SELECT MAX(salary) FROM employee WHERE dept_id = 20`
- Scans partition 20: {70000, 60000} → returns `70000`
- Compare: `70000 = 70000` → ✅ TRUE → **D kept**

**Outer row: E (dept_id=20, salary=60000)**
- Inner query re-runs → returns `70000`
- Compare: `60000 = 70000` → ❌ FALSE → **E dropped**

### Final Output
```
dept_id | emp_id | emp_name | salary
10      | 1      | A        | 90000
10      | 2      | B        | 90000
20      | 4      | D        | 70000
```
Same result as the window function — ties handled naturally because each row is compared independently against its own department's max, not against a single "winner" pick.

---

## Solution 3: 2nd Highest Salary Per Department (Window Function)

### Query
```sql
SELECT dept_id, emp_id, emp_name, salary
FROM (
    SELECT
        dept_id,
        emp_id,
        emp_name,
        salary,
        DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
    FROM employee
) ranked
WHERE rnk = 2;
```

### Approach
Same partition + sort mechanics as Solution 1, but uses `DENSE_RANK()` instead of `RANK()`, and filters for `rnk = 2`. This is critical — with `RANK()`, ties can consume the value "2" entirely, causing the query to silently return nothing.

### Internal Step-by-Step Working

**Steps 1–3** — identical to Solution 1 (scan table → partition by `dept_id` → sort each partition by `salary DESC`).
```
Partition 10 sorted: A(90000), B(90000), C(80000)
Partition 20 sorted: D(70000), E(60000)
```

**Step 4 — DENSE_RANK() assignment**
Unlike `RANK()`, ties do **not** cause the next rank to skip — the next *distinct* value simply gets the next consecutive rank number.
```
Partition 10: A=90000 → rnk=1
              B=90000 → rnk=1   (tie with A)
              C=80000 → rnk=2   (next distinct value, no skip)

Partition 20: D=70000 → rnk=1
              E=60000 → rnk=2
```

**Step 5 — Materialized derived table**
```
dept_id | emp_id | emp_name | salary | rnk
10      | 1      | A        | 90000  | 1
10      | 2      | B        | 90000  | 1
10      | 3      | C        | 80000  | 2
20      | 4      | D        | 70000  | 1
20      | 5      | E        | 60000  | 2
```

**Step 6 — Outer WHERE rnk = 2**
```
dept_id | emp_id | emp_name | salary | rnk
10      | 3      | C        | 80000  | 2
20      | 5      | E        | 60000  | 2
```

**Step 7 — Outer SELECT (drop `rnk`)**
```
dept_id | emp_id | emp_name | salary
10      | 3      | C        | 80000
20      | 5      | E        | 60000
```

### Final Output
```
dept_id | emp_id | emp_name | salary
10      | 3      | C        | 80000
20      | 5      | E        | 60000
```

### Why not RANK() here?
If `RANK()` were used instead:
```
Partition 10: A=90000→rnk=1, B=90000→rnk=1, C=80000→rnk=3   (rank 2 never assigned!)
```
`WHERE rnk = 2` would find **nothing** in department 10 — a silent, wrong result. `DENSE_RANK()` avoids this by never skipping ranks after ties.

---

## Solution 4: 2nd Highest Salary Per Department (Correlated Subquery)

### Query
```sql
SELECT e.dept_id, e.emp_id, e.emp_name, e.salary
FROM employee e
WHERE 1 = (
    SELECT COUNT(DISTINCT e2.salary)
    FROM employee e2
    WHERE e2.dept_id = e.dept_id
      AND e2.salary > e.salary
);
```

### Approach
For every outer row, count how many **distinct** salary values in the same department are **strictly greater** than this row's salary. If exactly `1` distinct tier is above me, I am the 2nd highest tier. Note: this only ever looks *upward*; rows with lower salaries than mine are never scanned by the inner query at all, because they fail the `salary > e.salary` filter before `COUNT` sees them.

### Internal Step-by-Step Working (row-by-row)

**Outer row: A (dept_id=10, salary=90000)**
- Inner query: `SELECT COUNT(DISTINCT salary) FROM employee WHERE dept_id=10 AND salary > 90000`
- Scans partition 10 for salary > 90000 → none exist → `COUNT = 0`
- Compare: `1 = 0` → ❌ FALSE → **A dropped**

**Outer row: B (dept_id=10, salary=90000)**
- Same inner query as A (identical dept + salary) → `COUNT = 0`
- Compare: `1 = 0` → ❌ FALSE → **B dropped**

**Outer row: C (dept_id=10, salary=80000)**
- Inner query: `SELECT COUNT(DISTINCT salary) FROM employee WHERE dept_id=10 AND salary > 80000`
- Scans partition 10 for salary > 80000 → finds A(90000), B(90000) → `DISTINCT` collapses both to one value → `COUNT = 1`
- Compare: `1 = 1` → ✅ TRUE → **C kept**

**Outer row: D (dept_id=20, salary=70000)**
- Inner query: `SELECT COUNT(DISTINCT salary) FROM employee WHERE dept_id=20 AND salary > 70000`
- Scans partition 20 for salary > 70000 → none exist → `COUNT = 0`
- Compare: `1 = 0` → ❌ FALSE → **D dropped**

**Outer row: E (dept_id=20, salary=60000)**
- Inner query: `SELECT COUNT(DISTINCT salary) FROM employee WHERE dept_id=20 AND salary > 60000`
- Scans partition 20 for salary > 60000 → finds D(70000) → `COUNT = 1`
- Compare: `1 = 1` → ✅ TRUE → **E kept**

### Final Output
```
dept_id | emp_id | emp_name | salary
10      | 3      | C        | 80000
20      | 5      | E        | 60000
```
Matches Solution 3 exactly, computed through a completely different mechanism.

---

## SQL Logical Order of Execution

SQL is written in one order but **processed by the engine in a different logical order**. This is the single most important mental model for understanding both window functions and correlated subqueries.

```
1. FROM        → identify source table(s), perform joins
2. WHERE       → filter individual rows (before any grouping)
3. GROUP BY    → collapse rows into groups
4. HAVING      → filter groups
5. WINDOW FNS  → compute RANK()/DENSE_RANK()/etc. (after WHERE/GROUP BY/HAVING,
                 operating on the rows that survived those stages)
6. SELECT      → project final columns/expressions
7. DISTINCT    → remove duplicate result rows (if specified)
8. ORDER BY    → sort final result
9. LIMIT/OFFSET→ restrict row count
```

### Why this matters for our queries

- **Window functions execute after `WHERE` but their result isn't available to that same `WHERE` clause.** This is exactly why `WHERE RANK() OVER (...) = 1` is illegal SQL — at the point `WHERE` is logically evaluated, the window function hasn't been computed yet for that query level. The fix is to compute the rank in an inner query/CTE, then filter on it one level up in an *outer* `WHERE`, which is a completely ordinary post-computation filter.

- **Correlated subqueries** don't follow this staged pipeline in the same way — instead, the inner query is (conceptually) executed once per row processed by the outer query's `WHERE` clause, using values from that specific outer row as input. This is a fundamentally different execution model: nested, row-driven evaluation rather than a staged, set-based pipeline.

### Physical execution (optimizer perspective)

In practice, engines like PostgreSQL/SQL Server rarely execute things exactly as the logical model suggests:
- For the window function query, the plan typically has a **Sort** node (on `dept_id, salary DESC`) feeding into a **WindowAgg**/**Window Spool** node that computes `rnk` while streaming, followed by a **Filter** node for `rnk = 1` (or `2`) — a single pass, no full materialization to a temp table in most cases.
- For the correlated subquery, the optimizer often rewrites it into a **semi-join** or converts the `MAX`/`COUNT` pattern into a **grouped aggregate joined back** to the outer table, especially if an index exists on `(dept_id, salary)` — avoiding a literal "re-run per row" execution.

---

## RANK vs DENSE_RANK — Why It Matters

| Salary tier | RANK() | DENSE_RANK() |
|---|---|---|
| 90000 (A) | 1 | 1 |
| 90000 (B) | 1 | 1 |
| 80000 (C) | 3 (skips 2) | 2 (no skip) |

- **`RANK()`**: leaves gaps after ties — the number of ranks skipped equals the number of tied rows. Use when you want "the Nth *row* from the top," where ties genuinely consuming multiple rank slots is the desired semantic.
- **`DENSE_RANK()`**: no gaps — ranks represent distinct salary *tiers*. Use when "2nd highest salary" should mean "2nd highest distinct value," which is what almost every interview question intends.
- For the **1st highest** case, both behave identically (nothing precedes rank 1, so there's nothing to skip) — this is why Solution 1 could safely use either.
- For the **2nd highest** case and beyond, the choice materially changes correctness — `RANK()` can cause a department to have **no rows at all** at a given rank if enough ties occurred above it.

---

## Correlated Subquery: Why "Below Me" Never Matters

A common point of confusion: does `COUNT(DISTINCT salary) WHERE salary > e.salary` get affected by how many rows have *lower* salaries?

**No.** The filter `salary > e.salary` excludes any row with a lower salary *before* `COUNT` ever examines it. Extending the earlier example with more low-salary rows in department 10:

```
dept_id | emp_id | salary
10      | A      | 90000
10      | B      | 90000
10      | C      | 80000   ← still expected as 2nd highest
10      | F      | 50000
10      | G      | 40000
10      | H      | 30000
```

For row **C** (salary = 80000):
```sql
SELECT COUNT(DISTINCT salary) FROM employee WHERE dept_id = 10 AND salary > 80000
```
This only scans rows satisfying `salary > 80000` — F, G, H (50000/40000/30000) fail that filter immediately and are never touched. Only A and B qualify, collapsed by `DISTINCT` into one value → `COUNT = 1` → C still correctly identified as 2nd highest, regardless of how many rows sit below it.

**Mental model:** *"Count how many distinct tiers are strictly above me — everyone below me is irrelevant to my own rank from the top."* This is also why the pattern generalizes cleanly to Nth highest: `WHERE (N-1) = COUNT(DISTINCT salary WHERE salary > e.salary)`.

---

## Performance Notes

| Approach | Typical complexity (no index) | Notes |
|---|---|---|
| Window function (`RANK`/`DENSE_RANK`) | O(N log N) — one sort + one linear pass | Single pass over data per partition; predictable regardless of optimizer cleverness |
| Correlated subquery | Naive mental model: O(N²) (inner query re-scanned per outer row) | Modern optimizers often rewrite this into a semi-join or grouped-aggregate-join, especially with an index on `(dept_id, salary)`, narrowing the real-world gap significantly |
| `GROUP BY` + `JOIN` on MAX | O(N) aggregate + O(N) join | Works cleanly for "1st highest"; awkward to extend to "Nth highest" |

**Interview-safe answer if asked to compare:** window functions give a single, predictable execution plan and are the modern, preferred approach; correlated subqueries are more portable to older engines lacking window function support, and their real-world performance depends heavily on whether the optimizer can rewrite the correlation into a join and whether a covering index exists on `(dept_id, salary)`.

---

## Generalizing to Nth Highest

**Window function version:**
```sql
SELECT dept_id, emp_id, emp_name, salary
FROM (
    SELECT dept_id, emp_id, emp_name, salary,
           DENSE_RANK() OVER (PARTITION BY dept_id ORDER BY salary DESC) AS rnk
    FROM employee
) ranked
WHERE rnk = N;
```

**Correlated subquery version:**
```sql
SELECT e.dept_id, e.emp_id, e.emp_name, e.salary
FROM employee e
WHERE (N - 1) = (
    SELECT COUNT(DISTINCT e2.salary)
    FROM employee e2
    WHERE e2.dept_id = e.dept_id
      AND e2.salary > e.salary
);
```

Just swap the literal `2` for a parameter `N` (or `N-1` in the subquery form). Both approaches naturally handle ties and departments with fewer than `N` distinct salary tiers (they simply return no rows for that department).

---

## Interview Talking Points

1. **Why `GROUP BY` alone fails** for retrieving `emp_name`/`emp_id` alongside `MAX(salary)`: aggregate functions collapse rows per group, so any non-aggregated, non-grouped column (like `emp_name`) can't be selected directly — that's the classic beginner mistake this problem is designed to expose.
2. **Why window functions can't be filtered in the same `SELECT`'s `WHERE`**: ties back directly to SQL's logical execution order — `WHERE` is evaluated before window functions are computed at that query level, so the wrapping subquery/CTE is structurally necessary, not just a style choice.
3. **RANK() vs DENSE_RANK()**: know this cold — it's the single most common "gotcha" follow-up, and picking the wrong one silently returns empty results for departments with ties at the top.
4. **Edge cases to mention proactively**: departments with only 1 employee (no 2nd highest exists — all four solutions correctly return zero rows for that department), and full ties across an entire department.
5. **Extending to Nth highest**: both approaches generalize with a single parameter swap — good to mention unprompted, it signals you're not just pattern-matching a memorized query.
6. **Correlated subquery mental model**: "for every outer row, count/compare against a recomputed inner value using that row's own attributes" — contrast this explicitly with window functions' single-pass, partition-and-sort model when asked to compare the two.
