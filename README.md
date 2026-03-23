# Canonical Log Lines for Spring Boot

One dense, structured log line per request. Zero logging in business code.

A Spring Boot 4.0.4 + Java 25 implementation of [Stripe's canonical log lines](https://stripe.com/blog/canonical-log-lines) pattern — adapted for virtual threads, `ScopedValue`, and `StructuredTaskScope`.

## What It Does

Every HTTP request produces a single JSON (or text) log line containing the complete flight recording: HTTP metadata, every method call with args and results, every database operation, every outbound API call, request/response bodies, durations, errors — all with sensitive data masked automatically.

**Actual output from a successful repayment request:**

```json
{
  "log_type": "canonical-log-line",
  "http_method": "POST",
  "http_path": "/api/v1/repayments",
  "http_status": 200,
  "correlation_id": "test-success-001",
  "outcome": "SUCCESS",
  "duration_ms": 665,
  "request_body": {
    "loanId": "LOAN-001", "contractId": "CNT001_EXT123",
    "msisdn": "+2*********78", "amount": 5000.0, "currency": "KES"
  },
  "step_count": 8,
  "steps": [
    {"type": "outbound", "endpoint": "/mock/ledger-service/msisdn-query", "status_code": 200, "duration_ms": 171},
    {"type": "method", "class": "LedgerServiceApiClientImpl", "method": "ledgerQueryMsisdn", "duration_ms": 190},
    {"type": "entity", "entity": "RepaymentTransaction", "operation": "INSERT"},
    {"type": "outbound", "endpoint": "/mock/ledger-service/repayment", "status_code": 200, "duration_ms": 277},
    {"type": "method", "class": "LedgerServiceApiClientImpl", "method": "ledgerProcessRepayment", "duration_ms": 279},
    {"type": "entity", "entity": "RepaymentTransaction", "operation": "UPDATE"},
    {"type": "method", "class": "RepaymentServiceImpl", "method": "processRepayment", "duration_ms": 551},
    {"type": "method", "class": "RepaymentController", "method": "handleRepaymentRequest", "duration_ms": 551}
  ]
}
```

The business code that produced this contains **zero** `log.info()` calls.

## How It Works

Four interception layers capture everything automatically:

| Layer | Mechanism | What It Captures |
|---|---|---|
| **HTTP Filter** | `OncePerRequestFilter` + `ScopedValue` binding | Request/response headers, bodies, status, correlation ID. Emits the ONE canonical line in `finally`. |
| **Method Aspect** | Spring AOP `@Around` on `@Tracked` methods | Class, method, args (masked), result (reflection-based field extraction), duration, errors |
| **Repository Aspect** | Spring AOP on `CrudRepository.save()`/`delete()` | Entity type, ID, operation (INSERT/UPDATE/DELETE). Preserves chronological order (unlike JPA entity listeners). |
| **HTTP Client Interceptor** | `ClientHttpRequestInterceptor` on `RestClient` | Service, endpoint, method, status, duration, request/response headers |

### Why Not JPA Entity Listeners?

Hibernate defers flushes to transaction commit. Entity listener events fire out of chronological order. The repository aspect intercepts at `save()` call time, preserving the real sequence of operations.

### Why Java 25?

`ScopedValue` (JEP 506) and `StructuredTaskScope` (JEP 505) replace `ThreadLocal`/MDC for virtual thread environments. MDC breaks with virtual threads — values disappear on carrier thread remounting and are not inherited by `StructuredTaskScope` subtasks. `ScopedValue` provides automatic inheritance, bounded lifetime, and immutable bindings with zero cleanup.

## Project Structure

```
src/main/java/com/github/barney/canonicallog/
├── lib/                                    # Reusable logging library
│   ├── aspect/
│   │   ├── CanonicalLogAspect.java         # @Tracked method interception
│   │   ├── CanonicalRepositoryAspect.java  # Repository save/delete interception
│   │   └── Tracked.java                    # Annotation
│   ├── context/
│   │   ├── CanonicalLogContext.java         # Event accumulator + dual-format emission
│   │   └── ObservabilityContext.java        # ScopedValue holder
│   └── masking/
│       └── SensitiveMasker.java            # PII masking utility
│
├── app/                                    # Demo application
│   ├── config/                             # RestClient + interceptor wiring
│   ├── controller/
│   │   ├── RepaymentController.java        # API endpoint
│   │   ├── GlobalExceptionHandler.java     # Error categorization by phase
│   │   └── MockLedgerApiController.java    # Mock external service
│   ├── filter/
│   │   └── ApplicationLogFilter.java       # HTTP filter (outermost layer)
│   ├── interceptor/
│   │   └── ApplicationClientHttpInterceptor.java
│   ├── service/impl/
│   │   ├── RepaymentServiceImpl.java       # Business logic (zero logging)
│   │   └── LedgerServiceApiClientImpl.java # REST client
│   ├── models/
│   │   ├── entity/RepaymentTransaction.java
│   │   └── dto/RepaymentDto.java           # Records
│   └── repository/
│       └── RepaymentTransactionRepository.java
```

The `lib` package is the reusable canonical logging library. The `app` package is a working demo — a loan repayment service that calls an external ledger API.

## Prerequisites

- **Java 25** (for finalized `ScopedValue` and `StructuredTaskScope`)
- **Gradle** (wrapper included)

## Quick Start

```bash
# Clone
git clone https://github.com/3barney/canonical-log.git
cd canonical-log

# Run with JSON logging (default)
./gradlew bootRun

# Run with text logging (grep-friendly logfmt)
CANONICAL_LOG_FORMAT=text ./gradlew bootRun
```

## Test Scenarios

The mock ledger service runs on the same application. Contract ID prefixes control behavior:

```bash
# Success — full flow
curl -s -X POST http://localhost:8080/api/v1/repayments \
  -H "Content-Type: application/json" \
  -H "X-Correlation-Id: test-success-001" \
  -d '{"loanId":"LOAN-001","contractId":"CNT001_EXT123",
       "loanProviderId":"PROVIDER-A","msisdn":"+254712345678",
       "amount":5000.00,"currency":"KES"}'

# MSISDN lookup failure — FAIL_ prefix
curl -s -X POST http://localhost:8080/api/v1/repayments \
  -H "Content-Type: application/json" \
  -d '{"loanId":"LOAN-002","contractId":"FAIL_CNT002_EXT456",
       "loanProviderId":"PROVIDER-B","msisdn":"+254798765432",
       "amount":3000.00,"currency":"KES"}'

# Upstream 500 — ERROR_ prefix
curl -s -X POST http://localhost:8080/api/v1/repayments \
  -H "Content-Type: application/json" \
  -d '{"loanId":"LOAN-003","contractId":"ERROR_CNT003_EXT789",
       "loanProviderId":"PROVIDER-C","msisdn":"+254700000000",
       "amount":2500.00,"currency":"KES"}'

# Timeout (5s delay) — TIMEOUT_ prefix
curl -s -X POST http://localhost:8080/api/v1/repayments \
  -H "Content-Type: application/json" \
  -d '{"loanId":"LOAN-004","contractId":"TIMEOUT_CNT004_EXT012",
       "loanProviderId":"PROVIDER-D","msisdn":"+254711111111",
       "amount":1000.00,"currency":"KES"}'
```

## Configuration

```yaml
canonical:
  log:
    format: json          # "text" for grep-friendly logfmt, "json" for structured JSON
    include-bodies: true  # Toggle request/response body capture

spring:
  threads:
    virtual:
      enabled: true       # Virtual threads (required for ScopedValue)
```

## Sensitive Data Masking

Masking is automatic at every capture boundary:

- **Method arguments**: `@Tracked(maskArgs = {"msisdn"})` or auto-detected by field name
- **Method results**: Reflection-based field extraction with sensitive field masking (records, POJOs, collections)
- **Request/response bodies**: Recursive JSON tree masking
- **Headers**: Authorization and other sensitive headers masked

Phone numbers: `+254712345678` becomes `+2*********78`. Generic secrets: first 2 + last 2 characters visible.

## Event Model

Four sealed record types for type-safe event accumulation:

```java
sealed interface LogEvent permits MethodLogEvent, EntityLogEvent, OutboundLogEvent, ErrorLogEvent
```

| Event Type | Captured By | Fields |
|---|---|---|
| `MethodLogEvent` | `@Tracked` AOP aspect | class, method, args, result, duration, error |
| `EntityLogEvent` | Repository aspect | entity type, ID, operation (INSERT/UPDATE/DELETE) |
| `OutboundLogEvent` | HTTP client interceptor | service, endpoint, method, status, duration, headers |
| `ErrorLogEvent` | `GlobalExceptionHandler` | phase, error type, message, stack snippet |

## Tech Stack

- Spring Boot 4.0.4
- Java 25 (`ScopedValue`, `StructuredTaskScope`, sealed records)
- Spring AOP for method and repository interception
- Spring Data JPA + H2 (embedded)
- RestClient with `ClientHttpRequestInterceptor`
- Jackson + Logstash Logback Encoder for structured JSON
