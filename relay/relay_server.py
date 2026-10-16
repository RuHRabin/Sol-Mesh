#!/usr/bin/env python3
"""
sol-mesh relay: pairs two phones that present the same one-time code and forwards bytes between them.

The relay never sees message contents: sol-mesh encrypts end to end (Double Ratchet + AES-GCM) on the
phones. It only learns the IP addresses of the two phones and how much data they exchange.

Protocol (one TCP connection per phone):
    phone -> relay : "JOIN <code>\\n"           code = 8..64 chars of [A-Za-z0-9_-]
    relay -> phone : "PAIRED I\\n"  (first phone to arrive, acts as handshake initiator)
                     "PAIRED R\\n"  (second phone, acts as responder)
                     "TIMEOUT\\n"   nobody joined with that code in time
                     "ERR <reason>\\n" bad request / server full
    after PAIRED the connection is a transparent pipe to the other phone.

Usage:  python3 relay_server.py [port]        (default 9000, needs Python 3.8+, no dependencies)
"""
import asyncio
import re
import sys

CODE_RE = re.compile(rb"^[A-Za-z0-9_-]{8,64}$")
HELLO_TIMEOUT = 10        # seconds to send the JOIN line
WAIT_TIMEOUT = 120        # seconds the first phone waits for the second
IDLE_TIMEOUT = 600        # a paired session with no traffic for this long is closed
MAX_WAITING = 2000        # simultaneous unpaired phones
MAX_SESSIONS = 5000       # simultaneous paired sessions

waiting = {}              # code -> (reader, writer, paired_event, done_event)
sessions = 0


def close(writer):
    try:
        writer.close()
    except Exception:
        pass


async def pipe(reader, writer):
    """Copy bytes until either side closes or goes idle."""
    try:
        while True:
            data = await asyncio.wait_for(reader.read(65536), IDLE_TIMEOUT)
            if not data:
                break
            writer.write(data)
            await writer.drain()
    except Exception:
        pass
    finally:
        close(writer)


async def reply(writer, text):
    try:
        writer.write(text)
        await writer.drain()
    except Exception:
        pass


async def handle(reader, writer):
    global sessions
    try:
        try:
            line = await asyncio.wait_for(reader.readline(), HELLO_TIMEOUT)
        except Exception:
            return
        parts = line.strip().split(b" ")
        if len(line) > 128 or len(parts) != 2 or parts[0] != b"JOIN" or not CODE_RE.match(parts[1]):
            await reply(writer, b"ERR bad request\n")
            return
        code = parts[1]

        if code in waiting:
            # second phone: pair with the one that is waiting
            other_reader, other_writer, paired, done = waiting.pop(code)
            if sessions >= MAX_SESSIONS:
                await reply(writer, b"ERR server busy\n")
                await reply(other_writer, b"ERR server busy\n")
                close(other_writer)
                paired.set()
                done.set()
                return
            await reply(other_writer, b"PAIRED I\n")
            await reply(writer, b"PAIRED R\n")
            paired.set()
            sessions += 1
            try:
                await asyncio.gather(pipe(other_reader, writer), pipe(reader, other_writer))
            finally:
                sessions -= 1
                close(writer)
                close(other_writer)
                done.set()
            return

        # first phone: wait for the other one
        if len(waiting) >= MAX_WAITING:
            await reply(writer, b"ERR server full\n")
            return
        paired = asyncio.Event()
        done = asyncio.Event()
        waiting[code] = (reader, writer, paired, done)
        waited = 0.0
        while not paired.is_set():
            await asyncio.sleep(0.5)
            waited += 0.5
            if reader.at_eof():            # phone gave up while waiting
                break
            if waited >= WAIT_TIMEOUT:
                break
        if not paired.is_set():
            if waiting.get(code, (None, None, None, None))[1] is writer:
                del waiting[code]
            if not reader.at_eof():
                await reply(writer, b"TIMEOUT\n")
            return
        await done.wait()                  # keep this handler alive while the second handler pipes
    finally:
        close(writer)


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
    server = await asyncio.start_server(handle, None, port, reuse_address=True)  # all interfaces
    addrs = ", ".join(str(s.getsockname()) for s in server.sockets)
    print(f"sol-mesh relay listening on {addrs}", flush=True)
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
