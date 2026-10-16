# sol-mesh relay

A tiny server that lets two phones connect when neither can reach the other directly
(for example both on mobile data). Each phone connects **out** to the relay with the same
one-time code; the relay pairs them and forwards bytes.

**Privacy:** messages and files stay end-to-end encrypted by the phones (Double Ratchet + AES-GCM).
The relay cannot read them. It does see both phones' IP addresses and how much data is exchanged.
Anyone who knows (or guesses) a code before the second phone joins can take that slot, so use the
app's **New code** button (about 60 random bits) and share it privately.

## Run it

Needs Python 3.8+ and nothing else:

```
python3 relay_server.py 9000
```

or with Docker:

```
docker build -t sol-mesh-relay .
docker run -d --restart unless-stopped -p 9000:9000 sol-mesh-relay
```

Open TCP port 9000 in the host's firewall / security group. In the app, enter `your.server.ip:9000`
(or a hostname) under **Connect Via Relay Server**, tap **New code** on one phone, send the code to the
other person, and tap **Connect via relay** on both phones.

## Where to host it

It must be a machine with a public IP that accepts **raw TCP** (not only HTTP): any small VPS works
(a few dollars per month), as do cloud "always free" VMs where available. Platforms that only expose
HTTP/HTTPS (many free web hosts) will not work.

## Limits (see the constants at the top of `relay_server.py`)

120 s wait for the second phone, 10 min idle timeout, at most 2000 waiting and 5000 paired sessions.
The relay does not log codes.
