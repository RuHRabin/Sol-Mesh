"""
Tests for relay_server.py. Run from this folder:   python3 -m unittest -v test_server
Needs only Python 3.8+ (uses the server's own Ed25519 code to sign requests).
"""
import asyncio
import hashlib
import os
import time
import unittest

import relay_server as rs

RFC8032_SECRET = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC8032_PUBLIC = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
RFC8032_SIG = bytes.fromhex(
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b")


def now_ms(offset_seconds=0):
    return int((time.time() + offset_seconds) * 1000)


class Phone:
    """A fake phone: an Ed25519 identity plus helpers that speak the mailbox protocol."""

    def __init__(self):
        self.secret = os.urandom(32)
        self.public = rs.ed25519_public_key(self.secret)
        self.mailbox = rs.mailbox_of(self.public)

    def put_line(self, to_mailbox, blob, ts=None, tamper_blob=False):
        ts = str(now_ms() if ts is None else ts).encode()
        signed = b"SolMeshPUT1|" + to_mailbox.encode() + b"|" + ts + b"|" + hashlib.sha256(blob).hexdigest().encode()
        sig = rs.ed25519_sign(self.secret, signed)
        sent_blob = (blob + b"x") if tamper_blob else blob
        return b"PUT %s %s %s %s %s\n" % (to_mailbox.encode(), self.public.hex().encode(), ts, sig.hex().encode(), sent_blob.hex().encode())

    def sub_line(self, ts=None):
        ts = str(now_ms() if ts is None else ts).encode()
        sig = rs.ed25519_sign(self.secret, b"SolMeshSUB1|" + self.public.hex().encode() + b"|" + ts)
        return b"SUB %s %s %s\n" % (self.public.hex().encode(), ts, sig.hex().encode())


class ServerCase(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        rs.reset_state_for_tests(":memory:")
        self.server = await asyncio.start_server(rs.handle, "127.0.0.1", 0, limit=rs.MAX_LINE + 1024)
        self.port = self.server.sockets[0].getsockname()[1]

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()

    async def connect(self):
        return await asyncio.open_connection("127.0.0.1", self.port)

    async def request(self, line):
        reader, writer = await self.connect()
        writer.write(line)
        await writer.drain()
        answer = await asyncio.wait_for(reader.readline(), 5)
        writer.close()
        return answer.strip().decode()

    async def subscribe(self, phone):
        reader, writer = await self.connect()
        writer.write(phone.sub_line())
        await writer.drain()
        first = (await asyncio.wait_for(reader.readline(), 5)).strip().decode()
        return reader, writer, first

    async def next_msg(self, reader):
        line = (await asyncio.wait_for(reader.readline(), 5)).strip().split(b" ")
        self.assertEqual(line[0], b"MSG", line)
        return int(line[1]), bytes.fromhex(line[3].decode())


class Ed25519Tests(unittest.TestCase):
    def test_rfc8032_vector(self):
        self.assertEqual(rs.ed25519_public_key(RFC8032_SECRET), RFC8032_PUBLIC)
        self.assertEqual(rs.ed25519_sign(RFC8032_SECRET, b""), RFC8032_SIG)
        self.assertTrue(rs.verify_pure(RFC8032_PUBLIC, b"", RFC8032_SIG))
        self.assertTrue(rs.ed25519_verify(RFC8032_PUBLIC, b"", RFC8032_SIG))

    def test_tampering_is_detected(self):
        self.assertFalse(rs.verify_pure(RFC8032_PUBLIC, b"x", RFC8032_SIG))
        bad = bytearray(RFC8032_SIG)
        bad[10] ^= 1
        self.assertFalse(rs.verify_pure(RFC8032_PUBLIC, b"", bytes(bad)))
        self.assertFalse(rs.verify_pure(RFC8032_PUBLIC, b"", RFC8032_SIG[:63]))
        self.assertFalse(rs.verify_pure(b"\x00" * 31, b"", RFC8032_SIG))


class MailboxTests(ServerCase):
    async def test_message_waits_then_is_delivered_and_deleted_after_ack(self):
        alice, bob = Phone(), Phone()
        self.assertTrue((await self.request(alice.put_line(bob.mailbox, b"sealed-1"))).startswith("OK "))
        self.assertTrue((await self.request(alice.put_line(bob.mailbox, b"sealed-2", ts=now_ms() + 1))).startswith("OK "))

        reader, writer, first = await self.subscribe(bob)
        self.assertEqual(first, "OK")
        id1, blob1 = await self.next_msg(reader)
        id2, blob2 = await self.next_msg(reader)
        self.assertEqual((blob1, blob2), (b"sealed-1", b"sealed-2"))
        self.assertEqual(rs.db().execute("SELECT COUNT(*) FROM messages").fetchone()[0], 2)  # kept until acknowledged

        writer.write(b"ACK %d\nACK %d\nPING\n" % (id1, id2))
        await writer.drain()
        self.assertEqual((await asyncio.wait_for(reader.readline(), 5)).strip(), b"PONG")
        self.assertEqual(rs.db().execute("SELECT COUNT(*) FROM messages").fetchone()[0], 0)   # gone from the server
        writer.close()

    async def test_online_recipient_gets_it_immediately(self):
        alice, bob = Phone(), Phone()
        reader, writer, first = await self.subscribe(bob)
        self.assertEqual(first, "OK")
        await self.request(alice.put_line(bob.mailbox, b"live message"))
        _, blob = await self.next_msg(reader)
        self.assertEqual(blob, b"live message")
        writer.close()

    async def test_nothing_is_delivered_twice_after_reconnect_without_ack_it_is_resent(self):
        alice, bob = Phone(), Phone()
        await self.request(alice.put_line(bob.mailbox, b"keep me"))
        for _ in range(2):  # never acknowledged, so it is offered again
            reader, writer, _ = await self.subscribe(bob)
            _, blob = await self.next_msg(reader)
            self.assertEqual(blob, b"keep me")
            writer.close()
            await asyncio.sleep(0.05)

    async def test_other_people_cannot_read_or_delete_my_mailbox(self):
        alice, bob, eve = Phone(), Phone(), Phone()
        await self.request(alice.put_line(bob.mailbox, b"for bob only"))
        reader, writer, first = await self.subscribe(eve)           # eve opens HER mailbox: nothing there
        self.assertEqual(first, "OK")
        writer.write(b"PING\n")
        await writer.drain()
        self.assertEqual((await asyncio.wait_for(reader.readline(), 5)).strip(), b"PONG")
        writer.write(b"ACK 1\nPING\n")                                # eve tries to delete bob's message id
        await writer.drain()
        await asyncio.wait_for(reader.readline(), 5)
        self.assertEqual(rs.db().execute("SELECT COUNT(*) FROM messages").fetchone()[0], 1)
        writer.close()

    async def test_forged_and_tampered_requests_are_refused(self):
        alice, bob, eve = Phone(), Phone(), Phone()
        self.assertEqual(await self.request(alice.put_line(bob.mailbox, b"x", tamper_blob=True)), "ERR bad signature")
        line = alice.put_line(bob.mailbox, b"x").replace(alice.public.hex().encode(), eve.public.hex().encode())
        self.assertEqual(await self.request(line), "ERR bad signature")                  # claiming to be someone else
        # SUB signed by the wrong key
        bad = bob.sub_line().replace(bob.public.hex().encode(), eve.public.hex().encode())
        reader, writer, first = await self.subscribe_raw(bad)
        self.assertEqual(first, "ERR bad signature")
        self.assertEqual(rs.db().execute("SELECT COUNT(*) FROM messages").fetchone()[0], 0)

    async def subscribe_raw(self, line):
        reader, writer = await self.connect()
        writer.write(line)
        await writer.drain()
        return reader, writer, (await asyncio.wait_for(reader.readline(), 5)).strip().decode()

    async def test_old_or_future_timestamps_are_refused(self):
        alice, bob = Phone(), Phone()
        self.assertEqual(await self.request(alice.put_line(bob.mailbox, b"x", ts=now_ms(-3600))), "ERR clock")
        self.assertEqual(await self.request(alice.put_line(bob.mailbox, b"x", ts=now_ms(3600))), "ERR clock")
        _, _, first = await self.subscribe_raw(bob.sub_line(ts=now_ms(-3600)))
        self.assertEqual(first, "ERR clock")

    async def test_replayed_request_is_refused(self):
        alice, bob = Phone(), Phone()
        line = alice.put_line(bob.mailbox, b"once")
        self.assertTrue((await self.request(line)).startswith("OK "))
        self.assertEqual(await self.request(line), "ERR replay")
        self.assertEqual(rs.db().execute("SELECT COUNT(*) FROM messages").fetchone()[0], 1)

    async def test_size_and_count_limits(self):
        alice, bob = Phone(), Phone()
        self.assertEqual(await self.request(alice.put_line(bob.mailbox, b"a" * (rs.MAX_BLOB + 1))), "ERR message too large")
        self.assertTrue((await self.request(alice.put_line(bob.mailbox, b"a" * rs.MAX_BLOB))).startswith("OK "))
        old_cap, rs.MAX_PER_MAILBOX = rs.MAX_PER_MAILBOX, 3
        try:
            results = []
            for i in range(4):
                results.append(await self.request(alice.put_line(bob.mailbox, b"m%d" % i, ts=now_ms() + i)))
            self.assertEqual(results[-1], "ERR mailbox full")
        finally:
            rs.MAX_PER_MAILBOX = old_cap

    async def test_a_flood_from_one_sender_is_slowed_down(self):
        alice, bob = Phone(), Phone()
        old, rs.PUT_PER_MIN_SENDER = rs.PUT_PER_MIN_SENDER, 5
        try:
            answers = [await self.request(alice.put_line(bob.mailbox, b"s%d" % i, ts=now_ms() + i)) for i in range(7)]
            self.assertEqual(answers[:5].count("ERR slow down"), 0)
            self.assertEqual(answers[5:], ["ERR slow down", "ERR slow down"])
        finally:
            rs.PUT_PER_MIN_SENDER = old

    async def test_garbage_is_refused_without_crashing(self):
        for junk in (b"HELLO\n", b"PUT\n", b"PUT a b c d e\n", b"SUB zz 1 2\n", b"\n", b"JOIN short\n"):
            self.assertTrue((await self.request(junk)).startswith("ERR"))
        alice, bob = Phone(), Phone()
        self.assertTrue((await self.request(alice.put_line(bob.mailbox, b"still works"))).startswith("OK "))

    async def test_expired_messages_are_removed(self):
        alice, bob = Phone(), Phone()
        await self.request(alice.put_line(bob.mailbox, b"old"))
        rs.db().execute("UPDATE messages SET created = ?", (int(time.time()) - rs.TTL_SECONDS - 10,))
        await self.request(alice.put_line(bob.mailbox, b"fresh", ts=now_ms() + 1))
        self.assertEqual(rs.purge_expired(), 1)
        rows = rs.db().execute("SELECT blob FROM messages").fetchall()
        self.assertEqual([r[0] for r in rows], [b"fresh"])

    async def test_new_connection_replaces_the_old_subscription(self):
        alice, bob = Phone(), Phone()
        r1, w1, _ = await self.subscribe(bob)
        r2, w2, _ = await self.subscribe(bob)
        await asyncio.sleep(0.1)
        await self.request(alice.put_line(bob.mailbox, b"to the new one"))
        _, blob = await self.next_msg(r2)
        self.assertEqual(blob, b"to the new one")
        self.assertEqual(await asyncio.wait_for(r1.read(10), 5), b"")     # old connection was closed
        w1.close(); w2.close()


class RelayStillWorks(ServerCase):
    async def test_two_phones_with_the_same_code_are_paired(self):
        r1, w1 = await self.connect()
        w1.write(b"JOIN ABCDEFGH12345\n")
        await w1.drain()
        await asyncio.sleep(0.3)
        r2, w2 = await self.connect()
        w2.write(b"JOIN ABCDEFGH12345\n")
        await w2.drain()
        self.assertEqual((await asyncio.wait_for(r1.readline(), 5)).strip(), b"PAIRED I")
        self.assertEqual((await asyncio.wait_for(r2.readline(), 5)).strip(), b"PAIRED R")
        w2.write(b"hello across the relay")
        await w2.drain()
        self.assertEqual(await asyncio.wait_for(r1.readexactly(22), 5), b"hello across the relay")
        w1.close(); w2.close()


if __name__ == "__main__":
    unittest.main()
