# sol-mesh server (relay + offline mailbox)

One small program, one TCP port, two jobs:

1. **Relay** - lets two phones connect when neither can reach the other directly (for example both on
   mobile data). Each phone connects *out* with the same one-time code; the server pairs them and forwards
   bytes. Also carries files.
2. **Mailbox** - keeps text messages for people who are offline. The message is sealed on the sender's
   phone so only the recipient can open it. The server hands it over the moment the recipient is online and
   deletes it right after the phone confirms it stored the message. If nobody comes, it is deleted after
   14 days.

## What the server can and cannot see

- It **cannot** read messages or files: they are end-to-end encrypted on the phones.
- It **does** see phone IP addresses, public keys (as mailbox ids), message sizes and timing. You run it,
  so this stays with you, not with a third-party company.
- Traffic to the server is not wrapped in TLS, so someone watching the network (not the server) can also
  see those details but still not the contents.
- Offline messages are sealed to the recipient's long-term key, which is weaker than live chat in one way:
  if that key is stolen later, someone who had recorded old offline messages could read them. Live chat
  keeps full forward secrecy.

## Run it

Python 3.8+ is all you need (no packages to install):

```
python3 relay_server.py 9000
```

Optional settings (environment variables): `MAILBOX_DB` (database file, default `./mailbox.db`),
`MAILBOX_TTL_DAYS` (default 14), `MAX_PER_MAILBOX` (default 500).

Docker:

```
docker build -t sol-mesh-server .
docker run -d --restart unless-stopped -p 9000:9000 -v solmesh-data:/data sol-mesh-server
```

Open TCP port 9000 in the machine's firewall. **Step by step for an Oracle Cloud free VM, in Bangla:
see `ORACLE_GUIDE_BN.md`.**

## In the app

Home -> **Relay** tab -> enter `your.server.ip:9000`. The line "Offline messages" should say **Connected**.
That is all: from then on, messages to someone who is offline wait on the server for them. For a live
connection through the relay, tap **New code** on one phone, send the code, and tap **Connect via relay**
on both.

Both phones must have connected directly at least once before: that is how they learn each other's keys.

## Limits (constants at the top of `relay_server.py`)

Message text up to about 8000 bytes (no files in the mailbox), 500 waiting messages per mailbox, 60 messages
per minute per sender, 120 per minute per IP address, phone clocks within 5 minutes of the server's.

## Tests

```
python3 -m unittest -v test_server
```

## Protocol (for the curious)

The first line of each connection decides what it is; all values are hex, timestamps are Unix milliseconds.

```
JOIN <code>                                  -> PAIRED I | PAIRED R | TIMEOUT | ERR ...   then raw bytes
PUT <to_mailbox> <sender_pub> <ts> <sig> <blob>   -> OK <id> | ERR ...
SUB <pub> <ts> <sig>                         -> OK, then MSG <id> <created> <blob> ... ; client sends ACK <id>, PING
```

`PUT` is signed over `SolMeshPUT1|<to_mailbox>|<ts>|<sha256(blob)>`, `SUB` over `SolMeshSUB1|<pub>|<ts>`
with the phone's Ed25519 key. A mailbox id is `sha256(public key)`, so only the key holder can open it.
Requests older or newer than 5 minutes, and repeated signatures, are refused.
