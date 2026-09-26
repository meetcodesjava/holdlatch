# Flash-sale load test

`flash_sale.py` simulates a ticket drop: hundreds of buyers hit the hold endpoint at once for the same seats.
It uses only the Python standard library (`asyncio` + raw sockets), so there is nothing to install.

## What it checks

1. **Same seats, same instant.** Every buyer requests the *same* two seats simultaneously. Exactly one may win;
   everyone else must get a clean `409`.
2. **Flash sale.** Every buyer races for random seat pairs across the hall until they win one or run out of attempts.
3. **Invariants.** No seat is ever won by two buyers, and there are no `5xx` responses. The script exits non-zero if either breaks.
4. **Numbers.** Latency percentiles and throughput for hold requests.

## Running it

Start AeroKV and HoldLatch. Registering hundreds of users from one address would (correctly) trip the login
rate limiter, so start HoldLatch for the test with the limits raised and cheap password hashing:

```bash
# AeroKV (from the aerokv-holdlatch repo)
AEROKV_PORT=8080 AEROKV_CAPACITY=20000 java -cp target/classes day06.AeroKVServerApp

# HoldLatch
RATE_API_CAPACITY=1000000 RATE_API_REFILL_PER_SECOND=1000000 \
RATE_AUTH_CAPACITY=100000 RATE_AUTH_REFILL_PER_SECOND=100000 \
BCRYPT_STRENGTH=4 java -jar target/holdlatch-0.1.0-SNAPSHOT.jar --spring.profiles.active=local

# the test
python loadtest/flash_sale.py --buyers 300
```

Useful options: `--buyers N`, `--rows N`, `--row-size N` (must be even), `--attempts N`, `--json results.json`.

## Results

Measured on one Windows laptop where the load generator, HoldLatch, AeroKV and PostgreSQL all share the same CPU,
so real hardware with separate machines will do better. A hall of 100 seats; buyers are closed-loop
(each waits for its reply before sending the next request).

| Buyers | Same-seat round | Throughput | p50 | p99 | Server errors | Double-bookings |
|-------:|-----------------|-----------:|----:|----:|--------------:|----------------:|
| 100 | 1 winner, 99 conflicts | ~1,500 req/s | 32 ms | 122 ms | 0 | 0 |
| 300 | 1 winner, 299 conflicts | ~600 req/s | 379 ms | 1,083 ms | 0 | 0 |

At 300 buyers the latency is queueing on a shared machine, not slow requests (throughput x latency = concurrency).

### What the first run found

The first run at 300 buyers did **not** pass: 8 requests returned `500` and p99 was 3.1 s. The cause was
database connection starvation - every hold attempt, even one AeroKV was about to reject, first ran several
database queries, and 300 buyers exhausted the 20-connection pool. That is exactly what the AeroKV layer is meant to prevent.

The fix was to validate holds against a short-lived in-process cache (event, sections, seat layout) so a hold that loses the
race costs **zero** database queries; a hold that wins costs exactly one. `DatabaseShieldIntegrationTest` proves both numbers
by counting real JDBC statements. After the fix: 0 errors, ~1.8x throughput, p99 down about 65%.

The same testing also exposed two bugs in AeroKV itself (a data race on the shared LRU list, and eviction being too
aggressive), both fixed with regression tests.
