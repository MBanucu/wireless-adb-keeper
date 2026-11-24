#!/usr/bin/env python3
"""Print TCP ports in START..END that accept a connection.

The single connect-scan implementation shared by the keeper scripts. adbd's
wireless-debugging port is a listening socket, and nothing else on loopback
answers by design; callers still have to prove a port is adbd, and skip the
adb server's own 5037. HOST defaults to 127.0.0.1; the host-side
preunlock-connect.sh passes the phone's address instead.

Usage: ./scan_ports.py START END [HOST]
"""
import asyncio
import sys


async def check(host, port, sem):
    async with sem:
        try:
            _, writer = await asyncio.wait_for(
                asyncio.open_connection(host, port), timeout=0.25)
            writer.close()
            return port
        except Exception:
            return None


async def main():
    start, end = int(sys.argv[1]), int(sys.argv[2])
    host = sys.argv[3] if len(sys.argv) > 3 else "127.0.0.1"
    sem = asyncio.Semaphore(400)
    found = []
    # Chunked so one gather never holds the whole ephemeral range at once.
    for chunk in range(start, end + 1, 5000):
        results = await asyncio.gather(
            *(check(host, p, sem) for p in range(chunk, min(chunk + 5000, end + 1))))
        found += [p for p in results if p]
    print(" ".join(str(p) for p in sorted(found)))


asyncio.run(main())
