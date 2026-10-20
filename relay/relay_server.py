#!/usr/bin/env python3
"""
sol-mesh server: a RELAY for live connections and a MAILBOX for offline text messages.

The server never sees message contents: sol-mesh encrypts end to end on the phones. It only learns
phone IP addresses, public keys (as mailbox ids), message sizes and timing.

One TCP port, one request type per connection. The first line decides:

  RELAY (pairs two phones that present the same one-time code, then forwards bytes)
    phone -> "JOIN <code>\\n"                     code = 8..64 chars of [A-Za-z0-9_-]
    server -> "PAIRED I\\n" | "PAIRED R\\n" | "TIMEOUT\\n" | "ERR <reason>\\n"

  MAILBOX (offline text messages; every request is signed with the phone's Ed25519 key)
    sender -> "PUT <to_mailbox> <sender_pub> <ts> <sig> <blob>\\n"   (hex; ts = Unix time in MILLISECONDS)
              signature over  "SolMeshPUT1|<to_mailbox>|<ts>|<sha256(blob) hex>"
    server -> "OK <id>\\n" | "ERR <reason>\\n"

    owner  -> "SUB <pub> <ts> <sig>\\n"            signature over  "SolMeshSUB1|<pub>|<ts>"
    server -> "OK\\n", then "MSG <id> <created> <blob>\\n" for everything waiting, and for every new
              message as it arrives. The connection stays open.
    owner  -> "ACK <id>\\n"  deletes that message (after the phone stored it);  "PING\\n" -> "PONG\\n"

    A mailbox id is sha256(owner public key) in hex, so only the key holder can open it.
    Messages are deleted when acknowledged, and after MAILBOX_TTL_DAYS (default 14) if nobody came.

Usage:  python3 relay_server.py [port]       (default 9000, Python 3.8+, no dependencies)
Environment: MAILBOX_DB (default ./mailbox.db), MAILBOX_TTL_DAYS, MAX_PER_MAILBOX
"""
import asyncio
import hashlib
import os
import re
import sqlite3
import sys
import time
from collections import deque

# --------------------------------------------------------------------------------------------
# Ed25519 (RFC 8032), pure Python. Uses the "cryptography" package for speed when it is installed.
# --------------------------------------------------------------------------------------------
_P = 2 ** 255 - 19
_L = 2 ** 252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)


def _sha512(data):
    return hashlib.sha512(data).digest()


def _point_add(p, q):
    a = (p[1] - p[0]) * (q[1] - q[0]) % _P
    b = (p[1] + p[0]) * (q[1] + q[0]) % _P
    c = 2 * p[3] * q[3] * _D % _P
    d = 2 * p[2] * q[2] % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _point_mul(s, p):
    q = (0, 1, 1, 0)
    while s > 0:
        if s & 1:
            q = _point_add(q, p)
        p = _point_add(p, p)
        s >>= 1
    return q


def _point_equal(p, q):
    if (p[0] * q[2] - q[0] * p[2]) % _P != 0:
        return False
    return (p[1] * q[2] - q[1] * p[2]) % _P == 0


def _recover_x(y, sign):
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P) % _P
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P != 0:
        x = x * _SQRT_M1 % _P
    if (x * x - x2) % _P != 0:
        return None
    if (x & 1) != sign:
        x = _P - x
    return x


_GY = 4 * pow(5, _P - 2, _P) % _P
_GX = _recover_x(_GY, 0)
_G = (_GX, _GY, 1, _GX * _GY % _P)


def _decompress(data):
    if len(data) != 32:
        return None
    y = int.from_bytes(data, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _P)


def _compress(p):
    zinv = pow(p[2], _P - 2, _P)
    x = p[0] * zinv % _P
    y = p[1] * zinv % _P
    return int.to_bytes(y | ((x & 1) << 255), 32, "little")


def _secret_expand(secret):
    h = _sha512(secret)
    a = int.from_bytes(h[:32], "little")
    a &= (1 << 254) - 8
    a |= 1 << 254
    return a, h[32:]


def ed25519_public_key(secret):
    a, _ = _secret_expand(secret)
    return _compress(_point_mul(a, _G))


def ed25519_sign(secret, msg):
    a, prefix = _secret_expand(secret)
    pub = _compress(_point_mul(a, _G))
    r = int.from_bytes(_sha512(prefix + msg), "little") % _L
    big_r = _compress(_point_mul(r, _G))
    k = int.from_bytes(_sha512(big_r + pub + msg), "little") % _L
    s = (r + k * a) % _L
    return big_r + int.to_bytes(s, 32, "little")


def verify_pure(pub, msg, sig):
    if len(pub) != 32 or len(sig) != 64:
        return False
    a = _decompress(pub)
    if a is None:
        return False
    r_bytes = sig[:32]
    r = _decompress(r_bytes)
    if r is None:
        return False
    s = int.from_bytes(sig[32:], "little")
    if s >= _L:
        return False
    k = int.from_bytes(_sha512(r_bytes + pub + msg), "little") % _L
    return _point_equal(_point_mul(s, _G), _point_add(r, _point_mul(k, a)))


try:  # faster when available, same result
    from cryptography.exceptions import InvalidSignature as _InvalidSignature
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey as _Ed25519PublicKey

    def ed25519_verify(pub, msg, sig):
        if len(pub) != 32 or len(sig) != 64:
            return False
        try:
            _Ed25519PublicKey.from_public_bytes(pub).verify(sig, msg)
            return True
        except (_InvalidSignature, ValueError):
            return False
except Exception:  # pragma: no cover - depends on the machine
    ed25519_verify = verify_pure

# --------------------------------------------------------------------------------------------
# Settings
# --------------------------------------------------------------------------------------------
CODE_RE = re.compile(rb"^[A-Za-z0-9_-]{8,64}$")
HEX64_RE = re.compile(rb"^[0-9a-f]{64}$")
HEX_RE = re.compile(rb"^[0-9a-f]*$")

HELLO_TIMEOUT = 10        # seconds to send the first line
WAIT_TIMEOUT = 120        # relay: seconds the first phone waits for the second
IDLE_TIMEOUT = 600        # relay: a paired session with no traffic for this long is closed
SUB_IDLE_TIMEOUT = 90     # mailbox: a subscriber must send PING at least this often
MAX_WAITING = 2000
MAX_SESSIONS = 5000
MAX_LINE = 40000          # longest accepted first line

MAX_BLOB = int(os.environ.get("MAX_BLOB", "12288"))                    # bytes of sealed message
MAX_PER_MAILBOX = int(os.environ.get("MAX_PER_MAILBOX", "500"))        # undelivered messages per mailbox
TTL_SECONDS = int(float(os.environ.get("MAILBOX_TTL_DAYS", "14")) * 86400)
CLOCK_SKEW = 300          # accepted difference between phone clock and server clock, seconds
PUT_PER_MIN_SENDER = 60
PUT_PER_MIN_IP = 120
DB_PATH = os.environ.get("MAILBOX_DB", "mailbox.db")

waiting = {}              # relay: code -> (reader, writer, paired_event, done_event)
sessions = 0
subscribers = {}          # mailbox id -> writer
_db = None
_recent_sigs = {}         # signature digest -> expiry time (replay protection)
_rate = {}                # key -> deque of timestamps


def db():
    global _db
    if _db is None:
        _db = sqlite3.connect(DB_PATH, isolation_level=None, check_same_thread=False)
        if DB_PATH != ":memory:":
            _db.execute("PRAGMA journal_mode=WAL")
        _db.execute(
            "CREATE TABLE IF NOT EXISTS messages ("
            "id INTEGER PRIMARY KEY AUTOINCREMENT, mailbox TEXT NOT NULL, "
            "blob BLOB NOT NULL, created INTEGER NOT NULL)"
        )
        _db.execute("CREATE INDEX IF NOT EXISTS idx_mailbox ON messages(mailbox, id)")
    return _db


def reset_state_for_tests(db_path=":memory:"):
    """Used by the tests: fresh database and empty runtime state."""
    global _db, DB_PATH
    if _db is not None:
        _db.close()
    _db = None
    DB_PATH = db_path
    waiting.clear()
    subscribers.clear()
    _recent_sigs.clear()
    _rate.clear()


def purge_expired(now=None):
    now = int(now if now is not None else time.time())
    cur = db().execute("DELETE FROM messages WHERE created < ?", (now - TTL_SECONDS,))
    return cur.rowcount


def rate_ok(key, limit, now):
    q = _rate.setdefault(key, deque())
    while q and q[0] < now - 60:
        q.popleft()
    if len(q) >= limit:
        return False
    q.append(now)
    return True


def close(writer):
    try:
        writer.close()
    except Exception:
        pass


async def reply(writer, text):
    try:
        writer.write(text)
        await writer.drain()
    except Exception:
        pass


# --------------------------------------------------------------------------------------------
# Relay
# --------------------------------------------------------------------------------------------
async def pipe(reader, writer):
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


async def handle_join(code, reader, writer):
    global sessions
    if not CODE_RE.match(code):
        await reply(writer, b"ERR bad request\n")
        return

    if code in waiting:
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
        if reader.at_eof():
            break
        if waited >= WAIT_TIMEOUT:
            break
    if not paired.is_set():
        if waiting.get(code, (None, None, None, None))[1] is writer:
            del waiting[code]
        if not reader.at_eof():
            await reply(writer, b"TIMEOUT\n")
        return
    await done.wait()


# --------------------------------------------------------------------------------------------
# Mailbox
# --------------------------------------------------------------------------------------------
def mailbox_of(pub_bytes):
    return hashlib.sha256(pub_bytes).hexdigest()


def fresh_signature(sig_bytes, now):
    """False if this exact signature was already used (replay). Remembered for the clock-skew window."""
    for key in [k for k, exp in _recent_sigs.items() if exp < now]:
        del _recent_sigs[key]
    digest = hashlib.sha256(sig_bytes).digest()
    if digest in _recent_sigs:
        return False
    _recent_sigs[digest] = now + 2 * CLOCK_SKEW
    return True


def parse_ts(token, now):
    """Timestamps are Unix milliseconds. Milliseconds (not seconds) so that a phone reconnecting twice in
    one second still signs different bytes; Ed25519 is deterministic and the replay check would refuse it."""
    try:
        ts = int(token)
    except ValueError:
        return None
    return ts if abs(now * 1000 - ts) <= CLOCK_SKEW * 1000 else None


async def handle_put(parts, writer, peer_ip):
    if len(parts) != 6:
        await reply(writer, b"ERR bad request\n")
        return
    _, to_box, sender_hex, ts_tok, sig_hex, blob_hex = parts
    now = int(time.time())
    if not (HEX64_RE.match(to_box) and HEX64_RE.match(sender_hex) and HEX_RE.match(sig_hex)
            and len(sig_hex) == 128 and HEX_RE.match(blob_hex) and len(blob_hex) % 2 == 0):
        await reply(writer, b"ERR bad request\n")
        return
    blob = bytes.fromhex(blob_hex.decode())
    if not (1 <= len(blob) <= MAX_BLOB):
        await reply(writer, b"ERR message too large\n")
        return
    ts = parse_ts(ts_tok, now)
    if ts is None:
        await reply(writer, b"ERR clock\n")
        return

    signed = b"SolMeshPUT1|" + to_box + b"|" + ts_tok + b"|" + hashlib.sha256(blob).hexdigest().encode()
    sig = bytes.fromhex(sig_hex.decode())
    if not ed25519_verify(bytes.fromhex(sender_hex.decode()), signed, sig):
        await reply(writer, b"ERR bad signature\n")
        return
    if not fresh_signature(sig, now):
        await reply(writer, b"ERR replay\n")
        return
    if not rate_ok(("s", sender_hex), PUT_PER_MIN_SENDER, now) or not rate_ok(("ip", peer_ip), PUT_PER_MIN_IP, now):
        await reply(writer, b"ERR slow down\n")
        return

    mailbox = to_box.decode()
    count = db().execute("SELECT COUNT(*) FROM messages WHERE mailbox=?", (mailbox,)).fetchone()[0]
    if count >= MAX_PER_MAILBOX:
        await reply(writer, b"ERR mailbox full\n")
        return
    cur = db().execute("INSERT INTO messages(mailbox, blob, created) VALUES (?,?,?)", (mailbox, blob, now))
    msg_id = cur.lastrowid
    await reply(writer, b"OK " + str(msg_id).encode() + b"\n")

    sub = subscribers.get(mailbox)
    if sub is not None:
        await reply(sub, b"MSG %d %d %s\n" % (msg_id, now, blob_hex))


async def handle_sub(parts, reader, writer):
    if len(parts) != 4:
        await reply(writer, b"ERR bad request\n")
        return
    _, pub_hex, ts_tok, sig_hex = parts
    now = int(time.time())
    if not (HEX64_RE.match(pub_hex) and HEX_RE.match(sig_hex) and len(sig_hex) == 128):
        await reply(writer, b"ERR bad request\n")
        return
    if parse_ts(ts_tok, now) is None:
        await reply(writer, b"ERR clock\n")
        return
    pub = bytes.fromhex(pub_hex.decode())
    sig = bytes.fromhex(sig_hex.decode())
    if not ed25519_verify(pub, b"SolMeshSUB1|" + pub_hex + b"|" + ts_tok, sig):
        await reply(writer, b"ERR bad signature\n")
        return
    if not fresh_signature(sig, now):
        await reply(writer, b"ERR replay\n")
        return

    mailbox = mailbox_of(pub)
    old = subscribers.get(mailbox)
    if old is not None and old is not writer:
        close(old)                      # a newer connection of the same phone replaces the old one

    await reply(writer, b"OK\n")
    # Send what is waiting, then register for live messages. Nothing awaits between the final query
    # and the registration, so no message can be missed or sent twice.
    query = "SELECT id, created, blob FROM messages WHERE mailbox=? AND id>? ORDER BY id"
    last_id = 0
    for msg_id, created, blob in db().execute(query, (mailbox, last_id)).fetchall():
        writer.write(b"MSG %d %d %s\n" % (msg_id, created, blob.hex().encode()))
        last_id = msg_id
    await writer.drain()
    for msg_id, created, blob in db().execute(query, (mailbox, last_id)).fetchall():
        writer.write(b"MSG %d %d %s\n" % (msg_id, created, blob.hex().encode()))
    subscribers[mailbox] = writer
    try:
        await writer.drain()
        while True:
            try:
                line = await asyncio.wait_for(reader.readline(), SUB_IDLE_TIMEOUT)
            except asyncio.TimeoutError:
                break
            if not line:
                break
            tokens = line.strip().split(b" ")
            if tokens == [b"PING"]:
                await reply(writer, b"PONG\n")
            elif len(tokens) == 2 and tokens[0] == b"ACK" and tokens[1].isdigit():
                db().execute("DELETE FROM messages WHERE id=? AND mailbox=?", (int(tokens[1]), mailbox))
            else:
                await reply(writer, b"ERR bad request\n")
                break
    except Exception:
        pass
    finally:
        if subscribers.get(mailbox) is writer:
            del subscribers[mailbox]


# --------------------------------------------------------------------------------------------
# Connection entry point
# --------------------------------------------------------------------------------------------
async def handle(reader, writer):
    try:
        try:
            line = await asyncio.wait_for(reader.readline(), HELLO_TIMEOUT)
        except Exception:
            await reply(writer, b"ERR bad request\n")
            return
        if len(line) > MAX_LINE or not line.endswith(b"\n"):
            await reply(writer, b"ERR bad request\n")
            return
        parts = line.strip().split(b" ")
        command = parts[0]
        peername = writer.get_extra_info("peername")
        peer_ip = peername[0] if peername else "?"

        if command == b"JOIN" and len(parts) == 2:
            await handle_join(parts[1], reader, writer)
        elif command == b"PUT":
            await handle_put(parts, writer, peer_ip)
        elif command == b"SUB":
            await handle_sub(parts, reader, writer)
        else:
            await reply(writer, b"ERR bad request\n")
    finally:
        close(writer)


async def cleanup_loop():
    while True:
        try:
            removed = purge_expired()
            if removed:
                print(f"removed {removed} expired messages", flush=True)
        except Exception as e:  # pragma: no cover
            print("cleanup error:", e, flush=True)
        await asyncio.sleep(3600)


async def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 9000
    db()
    server = await asyncio.start_server(handle, None, port, reuse_address=True, limit=MAX_LINE + 1024)
    addrs = ", ".join(str(s.getsockname()) for s in server.sockets)
    print(f"sol-mesh server (relay + mailbox) listening on {addrs}", flush=True)
    asyncio.create_task(cleanup_loop())
    async with server:
        await server.serve_forever()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
