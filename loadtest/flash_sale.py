#!/usr/bin/env python3
"""
Flash-sale load test for HoldLatch. Standard library only (asyncio + raw sockets).

What it does
  1. Creates an organizer, one event with a seated section, and N buyers.
  2. "Same seats" round: every buyer requests the SAME two seats at the same instant.
     Exactly one may win; everyone else must get a clean 409.
  3. "Flash sale" round: every buyer races to hold a pair of seats picked at random
     from the whole hall until they win one or run out of attempts.
  4. Checks the invariants that matter (no seat won by two buyers, no server errors)
     and prints latency percentiles and throughput.

Run HoldLatch and AeroKV first. Registering hundreds of users from one address would
trip the login rate limiter (that is the limiter working), so start HoldLatch for the
test with the limits raised - see loadtest/README.md.
"""
import argparse
import asyncio
import json
import random
import statistics
import sys
import time
import uuid
from datetime import datetime, timedelta, timezone
from urllib.parse import urlparse


class Http:
    """One keep-alive HTTP/1.1 connection. Enough of a client for JSON APIs; nothing more."""

    def __init__(self, host, port):
        self.host, self.port = host, port
        self.reader = self.writer = None

    async def _connect(self):
        self.reader, self.writer = await asyncio.open_connection(self.host, self.port)

    async def close(self):
        if self.writer:
            self.writer.close()
            try:
                await self.writer.wait_closed()
            except Exception:
                pass
        self.reader = self.writer = None

    async def request(self, method, path, body=None, token=None):
        """Returns (status, parsed_json_or_None, latency_ms)."""
        if self.writer is None:
            await self._connect()
        payload = json.dumps(body).encode() if body is not None else b""
        head = [f"{method} {path} HTTP/1.1", f"Host: {self.host}:{self.port}", "Accept: application/json",
                "Connection: keep-alive", f"Content-Length: {len(payload)}"]
        if payload:
            head.append("Content-Type: application/json")
        if token:
            head.append(f"Authorization: Bearer {token}")
        started = time.perf_counter()
        self.writer.write(("\r\n".join(head) + "\r\n\r\n").encode() + payload)
        await self.writer.drain()

        status_line = await self.reader.readline()
        if not status_line:
            await self.close()
            raise ConnectionResetError("server closed the connection")
        status = int(status_line.split()[1])
        headers = {}
        while True:
            line = await self.reader.readline()
            if line in (b"\r\n", b""):
                break
            k, _, v = line.decode().partition(":")
            headers[k.strip().lower()] = v.strip()

        if headers.get("transfer-encoding", "").lower() == "chunked":
            chunks = []
            while True:
                size = int((await self.reader.readline()).strip() or b"0", 16)
                if size == 0:
                    await self.reader.readline()
                    break
                chunks.append(await self.reader.readexactly(size))
                await self.reader.readline()
            raw = b"".join(chunks)
        else:
            raw = await self.reader.readexactly(int(headers.get("content-length", "0")))
        latency = (time.perf_counter() - started) * 1000

        if headers.get("connection", "").lower() == "close":
            await self.close()
        return status, (json.loads(raw) if raw else None), latency


def percentile(values, p):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, int(round((p / 100.0) * (len(ordered) - 1))))]


class Buyer:
    def __init__(self, host, port, index):
        self.http = Http(host, port)
        self.index = index
        self.token = None


async def setup_buyers(host, port, count, concurrency):
    gate = asyncio.Semaphore(concurrency)
    buyers = [Buyer(host, port, i) for i in range(count)]

    async def prepare(buyer):
        async with gate:
            email = f"load-{uuid.uuid4().hex[:10]}@example.com"
            status, body, _ = await buyer.http.request("POST", "/api/auth/register",
                {"email": email, "password": "correct-horse", "displayName": f"Buyer {buyer.index}", "role": "CUSTOMER"})
            if status == 429:
                sys.exit("Rate limited during setup. Start HoldLatch with the load-test limits (see loadtest/README.md).")
            if status != 201:
                sys.exit(f"Registration failed: {status} {body}")
            status, body, _ = await buyer.http.request("POST", "/api/auth/login", {"email": email, "password": "correct-horse"})
            if status != 200:
                sys.exit(f"Login failed: {status} {body}")
            buyer.token = body["accessToken"]

    await asyncio.gather(*(prepare(b) for b in buyers))
    return buyers


async def create_event(http, host_token, rows, row_size):
    starts = (datetime.now(timezone.utc) + timedelta(days=30)).strftime("%Y-%m-%dT%H:%M:%SZ")
    status, event, _ = await http.request("POST", "/api/events", {
        "name": "Flash Sale Load Test", "venue": "Load Arena", "startsAt": starts, "seatingMode": "ASSIGNED",
        "sections": [{"name": "Floor", "kind": "ASSIGNED", "currency": "USD", "priceCents": 5000,
                      "rows": [{"label": chr(65 + r), "seats": row_size} for r in range(rows)]}]}, host_token)
    if status != 201:
        sys.exit(f"Could not create event: {status} {event}")
    status, _, _ = await http.request("POST", f"/api/events/{event['id']}/publish", None, host_token)
    if status != 200:
        sys.exit("Could not publish event")
    return event


async def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base", default="http://localhost:8081")
    parser.add_argument("--buyers", type=int, default=300, help="simultaneous buyers")
    parser.add_argument("--rows", type=int, default=10)
    parser.add_argument("--row-size", type=int, default=10, help="must be even so pairs never strand a seat")
    parser.add_argument("--attempts", type=int, default=25, help="max hold attempts per buyer in the flash-sale round")
    parser.add_argument("--json", help="also write the results to this file")
    args = parser.parse_args()
    if args.row_size % 2:
        sys.exit("--row-size must be even")

    url = urlparse(args.base)
    host, port = url.hostname, url.port or 80
    print(f"Target {args.base}: {args.buyers} buyers, hall of {args.rows * args.row_size} seats "
          f"({args.rows * args.row_size // 2} seat pairs)\n")

    organizer = Buyer(host, port, -1)
    email = f"org-{uuid.uuid4().hex[:10]}@example.com"
    await organizer.http.request("POST", "/api/auth/register", {"email": email, "password": "correct-horse", "displayName": "Organizer", "role": "ORGANIZER"})
    status, body, _ = await organizer.http.request("POST", "/api/auth/login", {"email": email, "password": "correct-horse"})
    if status != 200:
        sys.exit(f"Organizer login failed: {status} {body}")
    organizer.token = body["accessToken"]

    setup_started = time.perf_counter()
    buyers = await setup_buyers(host, port, args.buyers, concurrency=40)
    print(f"Created {args.buyers} buyers in {time.perf_counter() - setup_started:.1f}s")

    event = await create_event(organizer.http, organizer.token, args.rows, args.row_size)
    section_id = event["sections"][0]["id"]
    _, seat_map, _ = await buyers[0].http.request("GET", f"/api/events/{event['id']}/sections/{section_id}/seats", None, buyers[0].token)
    by_label = {f"{s['row']}{s['number']}": s["id"] for s in seat_map}
    pairs = [(by_label[f"{chr(65 + r)}{c}"], by_label[f"{chr(65 + r)}{c + 1}"])
             for r in range(args.rows) for c in range(1, args.row_size, 2)]
    print(f"Event ready with {len(seat_map)} seats\n")

    hold_url = f"/api/events/{event['id']}/holds"
    latencies, statuses, winners_by_seat = [], {}, {}
    problems = []

    def record(status, latency, ids=None):
        latencies.append(latency)
        statuses[status] = statuses.get(status, 0) + 1
        if status == 201 and ids:
            for seat in ids:
                winners_by_seat[seat] = winners_by_seat.get(seat, 0) + 1

    # ---- Round 1: everyone, same two seats, same instant -------------------------------------------
    contested = list(pairs[0])
    start_gun = asyncio.Event()
    round1 = {}

    async def contest(buyer):
        await start_gun.wait()
        status, body, latency = await buyer.http.request("POST", hold_url, {"seatIds": contested}, buyer.token)
        round1[status] = round1.get(status, 0) + 1
        record(status, latency, contested if status == 201 else None)

    tasks = [asyncio.create_task(contest(b)) for b in buyers]
    await asyncio.sleep(0.3)
    round1_started = time.perf_counter()
    start_gun.set()
    await asyncio.gather(*tasks)
    round1_seconds = time.perf_counter() - round1_started
    print(f"Round 1 - {args.buyers} buyers request the SAME 2 seats at once ({round1_seconds:.2f}s)")
    print(f"  winners: {round1.get(201, 0)}   clean conflicts (409): {round1.get(409, 0)}   other: "
          f"{ {k: v for k, v in round1.items() if k not in (201, 409)} }")
    if round1.get(201, 0) != 1:
        problems.append(f"Round 1 produced {round1.get(201, 0)} winners for one pair of seats (must be exactly 1)")

    # ---- Round 2: flash sale over the whole hall -----------------------------------------------------
    latencies_before = len(latencies)
    winners = 0

    async def shop(buyer):
        nonlocal winners
        rng = random.Random(buyer.index)
        for _ in range(args.attempts):
            pair = rng.choice(pairs)
            try:
                status, body, latency = await buyer.http.request("POST", hold_url, {"seatIds": list(pair)}, buyer.token)
            except (ConnectionResetError, asyncio.IncompleteReadError):
                problems.append("connection dropped mid-request")
                return
            record(status, latency, list(pair) if status == 201 else None)
            if status == 201:
                winners += 1
                return

    sale_started = time.perf_counter()
    await asyncio.gather(*(shop(b) for b in buyers))
    sale_seconds = time.perf_counter() - sale_started
    sale_latencies = latencies[latencies_before:]
    print(f"\nRound 2 - flash sale: buyers race for random seat pairs ({sale_seconds:.2f}s)")
    print(f"  requests: {len(sale_latencies)}   throughput: {len(sale_latencies) / sale_seconds:,.0f} req/s   pairs sold: {winners}/{len(pairs)}")

    # ---- Verdict --------------------------------------------------------------------------------------
    double_booked = [seat for seat, count in winners_by_seat.items() if count > 1]
    server_errors = {k: v for k, v in statuses.items() if k >= 500}
    if double_booked:
        problems.append(f"{len(double_booked)} seats were won by more than one buyer")
    if server_errors:
        problems.append(f"server errors: {server_errors}")

    print("\nHold-request latency (all rounds)")
    for label, value in (("p50", percentile(latencies, 50)), ("p90", percentile(latencies, 90)),
                         ("p99", percentile(latencies, 99)), ("max", max(latencies))):
        print(f"  {label:>4}: {value:8.1f} ms")
    print(f"  mean: {statistics.mean(latencies):8.1f} ms   total requests: {len(latencies)}")
    print(f"\nResponses: {dict(sorted(statuses.items()))}   (201 held, 409 seat taken, 422 orphan rule, 429 rate limited)")
    print(f"Seats won by more than one buyer: {len(double_booked)}")

    if args.json:
        with open(args.json, "w", encoding="utf-8") as f:
            json.dump({"buyers": args.buyers, "seats": len(seat_map), "round1": round1, "statuses": statuses,
                       "latency_ms": {"p50": percentile(latencies, 50), "p90": percentile(latencies, 90),
                                      "p99": percentile(latencies, 99), "max": max(latencies)},
                       "sale_throughput_rps": len(sale_latencies) / sale_seconds, "pairs_sold": winners,
                       "double_booked": len(double_booked), "problems": problems}, f, indent=2)

    for buyer in buyers + [organizer]:
        await buyer.http.close()
    if problems:
        print("\nFAILED:")
        for p in problems:
            print("  -", p)
        sys.exit(1)
    print("\nPASSED: no double-bookings, no server errors.")


if __name__ == "__main__":
    asyncio.run(main())
