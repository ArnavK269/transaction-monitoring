# AML Transaction Monitoring System

A hybrid Anti-Money Laundering (AML) monitoring engine that combines rule-based detection with machine learning anomaly scoring to flag suspicious financial transactions and trades.

---

## Table of Contents

1. [Problem Statement](#problem-statement)
2. [How It Was Solved](#how-it-was-solved)
3. [Algorithm Design — Why Isolation Forest](#algorithm-design--why-isolation-forest)
4. [Time & Space Complexity](#time--space-complexity)
5. [Real-World Impact](#real-world-impact)
6. [Security](#security)
7. [Architecture](#architecture)
8. [Features](#features)
9. [Prerequisites](#prerequisites)
10. [Setup](#setup)
11. [API Reference](#api-reference)
12. [Severity Thresholds](#severity-thresholds)
13. [Project Structure](#project-structure)

---

## Problem Statement

Financial compliance teams at brokerages and banks are required to screen every client's transactions and trades for money-laundering patterns — a process governed by regulations such as PMLA (Prevention of Money Laundering Act) and SEBI guidelines in India.

Before systems, that process looked like this:

- Analysts manually exported transaction and trade data from the core system into Excel spreadsheets.
- They applied filters and conditional formatting by hand to spot suspicious patterns (large volumes, structuring, mismatches between trades and cash flow).
- Each client review took **15–30 minutes** of manual effort.
- With hundreds of clients per cycle, a full screening run took **2–4 days** and was only done periodically — not in real time.
- There was no consistent scoring model; different analysts applied different thresholds, leading to **inconsistent alert quality** and missed cases.
- Findings were undocumented and hard to audit.

**Core problems to solve:**
1. Manual, slow, and inconsistent client screening.
2. No cross-referencing between transaction data and trade data — a key signal for layering (a common money-laundering stage).
3. No ML layer to catch anomalies that don't match known rule patterns.
4. No searchable, real-time dashboard for compliance officers.
5. Credentials and environment config were hardcoded — a security risk for any shared or production environment.

---

## How It Was Solved

### 1. Automated Rule Engine (Spring Boot — Java)

Five deterministic rules are applied to every customer's transaction history on every API call:

| Rule | Logic | What it catches |
|------|-------|-----------------|
| `LARGE_TRANSACTION_VOLUME` | Cumulative amount > ₹1.5 Cr | Bulk cash movement over a period |
| `STRUCTURING_PATTERN` | ≥ 3 transactions between ₹4,500–₹5,000 | Deliberate breaking of amounts to stay below reporting thresholds |
| `RAPID_MOVEMENT` | ≥ 5 `BANK`-type vouchers | Frequent inter-bank transfers suggesting layering |
| `MULTIPLE_HIGH_VALUE_TRANSACTIONS` | ≥ 2 transactions > ₹2 L | Repeated large individual movements |
| `TRADE_TRANSACTION_MISMATCH` | \|total trade value − total transaction value\| > ₹70 L | Cash flow inconsistent with declared trading activity |

The last rule is the most significant — it cross-joins the `transaction_file` and `trade_file` tables per customer, which was previously done manually (if at all).

### 2. ML Anomaly Scoring (Python service)

A Python service scores each customer 0–100 using six features computed from their transaction set: total amount, transaction count, average amount, max/min amounts, bank voucher count, cash ratio, and rapid-movement ratio. This catches customers who don't match any known rule pattern but whose aggregate behaviour is statistically unusual — a second, independent signal layer.

### 3. Hybrid Severity Classification

Every alert combines both signals into a single severity verdict:

- Rule hits and ML score are weighted together — neither alone decides the outcome.
- This reduces both **false positives** (rule hits on genuinely normal accounts) and **false negatives** (anomalous accounts that narrowly miss every rule threshold).

### 4. Real-Time REST API

The `/monitor` endpoint processes all customers and returns structured JSON alerts on demand — no batch job scheduling, no exports, no waiting for an overnight run.

### 5. React Dashboard with Dual Search Modes

- **AI Search** — analysts type naturally ("show me high severity structuring cases") and the Python NLP service parses the intent into filters.
- **Manual Search** — a grid of per-column inputs with autocomplete dropdowns for analysts who prefer exact filtering.
- A 250ms debounce on the search input means results update as the analyst types without hammering the backend.

### 6. Secure Configuration Management

- All credentials and environment-specific paths were removed from source code.
- Moved to `application.properties` (gitignored) backed by environment variables via Spring's `${VAR}` injection.
- A `.env.example` and `application.properties.template` are committed so any developer can onboard without being given credentials directly.

---

## Algorithm Design — Why Isolation Forest

### The Core Problem with Standard Algorithms

AML anomaly detection is an **unsupervised** problem — there are no labelled "fraudulent" examples to train on. This rules out supervised classifiers immediately.

### Why Isolation Forest Fits This Problem

Isolation Forest works on a key insight: **anomalies are easier to isolate than normal points**. It builds an ensemble of random decision trees, each recursively splitting the feature space with random cuts. An anomalous customer (unusual combination of total amount, transaction count, cash ratio, etc.) gets isolated in very few splits — its average path length across all trees is short. Normal customers require many splits to isolate.

This matches AML data precisely:

1. **No labelled fraud data needed** — the model trains only on a set of known-normal behaviour profiles (`X` in `IsolationForestService.py`, covering very low risk through extreme risk patterns). It learns what "normal" looks like, not what "fraud" looks like.

2. **Multivariate by design** — a customer with ₹8L total, 30 transactions, 90% cash ratio, and a high rapid ratio is flagged not because any single feature is extreme, but because the *combination* is unusual. Distance-based methods struggle with this when features are on very different scales (total amount is in lakhs; ratios are 0–1).

3. **Scale-insensitive** —  This matters because `total` ranges from ₹1,000 to ₹1 Cr+ while `cashratio` ranges from 0.1 to 0.99 — mixing these in a distance metric would let the large-scale feature dominate.

4. **Produces a continuous score** — `decision_function()` returns a raw anomaly score, not just "outlier / not outlier". This is normalised to 0–100 using the formula `(0.4 - score) * 120`, then clamped, giving compliance officers a graded risk signal rather than a binary flag.

5. **Fast at inference** — once trained, scoring a new customer is a single path traversal across 300 trees, each of depth O(log n). This makes the `/predict` call sub-millisecond.

6. **Explainability** — rule-based boosts are added on top of the raw score (`+8` for total > ₹10L, `+12` for max transaction > ₹10L, etc.), making the final score partially interpretable. A compliance officer can understand "the ML base score was 60, plus 12 for a large max transaction, plus 10 for high rapid ratio = 82 → HIGH".

### Model Configuration

```python
model = IsolationForest(
    contamination=0.08,   # 8% of training data is treated as anomalous
    n_estimators=300,     # 300 trees — higher than the default (100) for more stable scores
    max_samples='auto',   # uses min(256, n_samples) — fits the 18-sample training set
    random_state=42       # reproducible results across restarts
)
```

`contamination=0.08` means the model expects roughly 8% of customers in production to be anomalous — a deliberate conservative estimate that keeps the score sensitive without flooding analysts with false positives.

---

## Time & Space Complexity

### Rule Engine (Java — per `/monitor` call)

Let **C** = number of unique customers, **T** = total transactions, **R** = total trades.

| Step | Operation | Time Complexity | Space Complexity |
|------|-----------|----------------|-----------------|
| Load all transactions from DB | Single SQL SELECT | O(T) | O(T) |
| Group transactions by customer | HashMap insert × T | O(T) | O(T) |
| Apply rules per customer | Single pass over each customer's transactions | O(T) | O(1) per customer |
| Load trades per customer | SQL SELECT with WHERE per customer | O(R) total across all C calls | O(R/C) per customer |
| Compute trade/transaction mismatch | Sum over trades list | O(R/C) per customer | O(1) |
| Severity classification | Constant comparisons | O(1) per customer | O(1) |
| **Total per API call** | | **O(T + R)** | **O(T + R)** |

The rule engine is **linear** in the size of the data — it makes one pass over each dataset. There is no nested looping over customers against each other, no sorting, and no recursive computation.

### Isolation Forest (Python — per `/predict` call)

Let **n** = training samples (18 here), **f** = features (8), **t** = `n_estimators` (300), **h** = average tree height.

| Phase | Time Complexity | Space Complexity | Actual cost |
|-------|----------------|-----------------|-------------|
| **Training** (once, at startup) | O(t · n · log n) | O(t · n) | 300 × 18 × ~4 ≈ 21,600 ops — negligible |
| **Inference** (per `/predict` call) | O(t · log n) | O(f) | 300 × ~4 = 1,200 ops — sub-millisecond |
| Feature extraction in Java (before HTTP call) | O(T_c) where T_c = transactions for that customer | O(1) | Single pass to compute 8 aggregates |

The theoretical average tree height for Isolation Forest is **O(log n)** — anomalies are isolated in fewer splits, normal points take more. With n=18 training samples, `log₂(18) ≈ 4.2`, so each tree traversal is extremely shallow.

**Training happens once at service startup** (`model.fit(X)` at module level in `IsolationForestService.py`), not per request. Every `/predict` call only runs inference — O(t · log n) = O(300 × 4) constant-time operations regardless of how many customers or transactions exist in the database.


The bottleneck is the **database round-trips**, not the ML model or the rule engine. The ML inference is the cheapest step.

### Full Pipeline Complexity Summary

| Component | Time | Space | Notes |
|-----------|------|-------|-------|
| Rule engine (all customers) | O(T + R) | O(T + R) | Linear in data size |
| ML training | O(t · n · log n) | O(t · n) | Once at startup; n=18 so effectively O(1) |
| ML inference (per customer) | O(t · log n) | O(f) | Constant — independent of DB size |
| Frontend filtering | O(A) | O(A) | A = number of alerts returned |
| AI search parse | O(\|query\|) | O(1) | Regex + keyword scan on the query string |

Where T = total transactions, R = total trades, t = 300 trees, n = 18 training samples, f = 8 features, A = alert count.

---

## Real-World Impact

> All estimates are based on the manual process described in the Problem Statement and the throughput characteristics of this system.

### Time Savings

| Task | Before (Manual) | After (This System) | Reduction |
|------|----------------|---------------------|-----------|
| Screening a single customer | ~20 min (avg) | < 1 second (API call) | **~99.9%** |
| Full screening cycle (100 customers) | ~33 hours | < 2 minutes | **~99.8%** |
| Finding a specific alert by case ID | 5–15 min (search Excel) | Instant (AI/manual search bar) | ~100% |
| Cross-referencing trades vs transactions | 10–20 min per customer | Automatic (built into every run) | **100% automated** |

A compliance team running weekly screenings on 100 clients **saves ~30+ hours of analyst work per week** — time that can be redirected to investigating flagged cases rather than generating them.

### Quality Improvements

- **Consistency** — every customer is evaluated against the same 5 rules and the same ML model on every run, eliminating analyst-to-analyst variation.
- **Coverage** — the `TRADE_TRANSACTION_MISMATCH` rule cross-references two data sources that were previously screened in isolation. This catches layering patterns that neither dataset reveals alone.
- **Second-opinion layer** — the ML anomaly score independently flags customers who don't match any rule but show unusual aggregate behaviour, reducing the chance of a false negative slipping through.
- **Auditability** — every alert carries a unique `caseId`, a list of specific rule hits, an ML score, and exact transaction counts and totals — a complete, reproducible paper trail for regulators.

### Regulatory Risk Reduction

Under PMLA and SEBI regulations, failure to detect and report suspicious transactions can result in fines and license revocations. By moving from periodic manual reviews to an on-demand automated system:

- **Screening frequency can increase** from weekly/monthly to daily or per-event without additional analyst headcount.
- **STR (Suspicious Transaction Report) generation** becomes faster and more defensible because every alert is backed by machine-readable evidence, not analyst notes.
- **Audit readiness** improves — every alert is timestamped and structured; regulators can be given direct access to the dashboard rather than a retrospectively assembled spreadsheet.

---

## Security

### How It Was Fixed

**1. Removed all credentials from source code**

The three static constants (`DB_URL`, `DB_USER`, `DB_PASSWORD`) and the hardcoded script path were deleted from the Java file and replaced with `@Value`-injected instance fields:

```java
@Value("${spring.datasource.url}")      private String dbUrl;
@Value("${spring.datasource.username}") private String dbUser;
@Value("${spring.datasource.password}") private String dbPassword;
@Value("${screening.script.path}")      private String screeningScriptPath;
```

Spring resolves these at startup from `application.properties`, which in turn reads from environment variables.

**2. Environment variable–backed configuration**

`application.properties` now uses `${ENV_VAR}` placeholders:

```properties
spring.datasource.password=${DB_PASSWORD}
screening.script.path=${SCREENING_SCRIPT_PATH}
```

Secrets with no default will cause a fast startup failure if missing — intentional, so misconfigured deployments are caught immediately rather than silently connecting with wrong credentials.

**3. Layered gitignore strategy**

Three categories of files are blocked from being committed:

```
src/main/resources/application.properties   ← real DB credentials
.env                                         ← real secrets for local dev
*.env                                        ← any other env files
```

And two safe, committed counterparts exist for onboarding:

```
application.properties.template   ← shows required keys, no values
.env.example                      ← shows required env vars, no values
```

## Architecture

```
┌─────────────────────┐        ┌──────────────────────┐        ┌─────────────────┐
│   React Frontend    │◄──────►│  Spring Boot Backend  │◄──────►│   PostgreSQL DB  │
│   (aml-ui)          │  HTTP  │  (port 8080)          │  JDBC  │   (aml_db)       │
└─────────────────────┘        └──────────┬───────────┘        └─────────────────┘
                                           │ HTTP
                                           ▼
                                ┌──────────────────────┐
                                │  Python ML Service   │
                                │  (port 5000)         │
                                └──────────────────────┘
```

| Layer | Technology | Purpose |
|-------|-----------|---------|
| Frontend | React 19 | Alert dashboard with AI & manual search |
| Backend | Spring Boot 3.2.5 / Java 17 | REST API, rule engine, ML orchestration |
| Database | PostgreSQL | Transaction & trade data storage |
| ML Service | Python (Flask) | Anomaly score prediction + AI search parsing |

---

## Features

- **Rule-Based Detection** — 5 rules covering volume, structuring, velocity, and cross-asset mismatch
- **ML Anomaly Scoring** — Python service returns a 0–100 risk score per customer based on 8 features
- **Hybrid Severity Classification** — HIGH / MEDIUM / LOW combining rule hits, ML score, and total volume
- **AI-Assisted Natural Language Search** — type queries like "HIGH severity structuring" and the ML service parses intent into filters
- **Manual Filter Grid** — per-column inputs with datalist autocomplete as an alternative to AI search
- **250ms Debounced Search** — real-time filtering without over-requesting the backend
- **Dark / Light Theme Toggle**
- **Graceful Fallback** — if the AI search service is unavailable, the frontend falls back to local substring matching automatically

---

## Prerequisites

| Requirement | Version |
|-------------|---------|
| Java | 17+ |
| Maven | 3.8+ |
| Node.js | 18+ |
| PostgreSQL | 14+ |
| Python | 3.9+ |

---

## Setup

### 1. Database

```sql
CREATE DATABASE aml_db;

CREATE TABLE transaction_file (
    customer_id                  BIGINT,
    client_name                  TEXT,
    amount                       NUMERIC,
    transaction_date             TEXT,
    segment                      TEXT,
    voucher_type                 TEXT,
    remarks                      TEXT,
    instrument_type              TEXT,
    transaction_reference_number TEXT
);

CREATE TABLE trade_file (
    customer_id  BIGINT,
    client_name  TEXT,
    trade_id     TEXT,
    buy_sell     TEXT,
    rate         NUMERIC,
    qty          NUMERIC,
    scrip_code   TEXT,
    trade_date   TEXT,
    trade_status TEXT
);
```

### 2. Environment & Backend Configuration

```bash
cp .env.example .env
# Edit .env with your real DB credentials and script path

cp src/main/resources/application.properties.template \
   src/main/resources/application.properties
# application.properties reads from .env vars automatically
```


### 3. Backend

```bash
mvn spring-boot:run
# http://localhost:8080
```

### 4. Frontend

```bash
cd aml-ui
npm install
npm start
# http://localhost:3000
```

---

## API Reference

### `GET /`
Health check.

```json
{ "status": "RUNNING", "system": "Hybrid AML Monitoring Engine" }
```

### `GET /monitor`
Returns all AML alerts. Each alert is a full customer summary including rule hits, ML score, severity, and transaction totals.

```json
[
  {
    "caseId": "AML-1234567890-C001",
    "customerId": "C001",
    "clientName": "John Doe",
    "severity": "HIGH",
    "ruleHits": ["LARGE_TRANSACTION_VOLUME", "RAPID_MOVEMENT"],
    "reason": "LARGE_TRANSACTION_VOLUME, RAPID_MOVEMENT",
    "anomalyScore": 85.3,
    "totalTransactionAmount": 18500000,
    "transactionCount": 42
  }
]
```

### `POST /run-screening`
Triggers the AML batch screening script. Returns `"Started"` on success.

---

## Severity Thresholds

| Severity | Condition |
|----------|-----------|
| **HIGH** | ML score ≥ 80 **OR** rule hits ≥ 4 **OR** total volume ≥ ₹3 Cr |
| **MEDIUM** | ML score ≥ 50 **OR** rule hits ≥ 2 **OR** total volume ≥ ₹1 Cr |
| **LOW** | All other cases |

---

## Project Structure

```
Transaction Monitoring/
├── src/
│   └── main/
│       ├── java/com/aml/monitoring/
│       │   └── TransactionMonitoringApplication.java   # Backend: API + rule engine + ML call
│       └── resources/
│           ├── application.properties                  # gitignored — real credentials
│           └── application.properties.template         # committed — safe onboarding template
├── aml-ui/
│   ├── src/
│   │   ├── App.js    # React dashboard: dual search, alert table, theme toggle
│   │   └── App.css
│   └── package.json
├── .env                        # gitignored — real secrets
├── .env.example                # committed — documents all required env vars
├── pom.xml
├── .gitignore
└── README.md
```

---

## License

Internal internship project. Not for public distribution.
#   t r a n s a c t i o n - m o n i t o r i n g  
 