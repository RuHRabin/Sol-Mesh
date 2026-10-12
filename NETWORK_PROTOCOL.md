# Nexus Network Protocol Specification

## 1. Multiaddress Format
Nexus follows libp2p multiaddress encoding:
- `/ip4/192.168.1.50/tcp/9091`
- `/ip4/203.0.113.19/tcp/9091`
- `/dns4/bootstrap1.nexus.network/tcp/9091`

## 2. Binary Framing Format
All messages transmitted across raw TCP/QUIC sockets use a standard 5-byte header:
```
+-------------------+---------------+-----------------------+
| 4 Bytes (Big-End) | 1 Byte        | Length Bytes          |
| Payload Length    | Stream ID     | Payload (JSON/Binary) |
+-------------------+---------------+-----------------------+
```

### Stream IDs
- `0x01`: Handshake / Mutual Authentication
- `0x02`: Real-time Chat (`/p2p-chat/1.0.0`)
- `0x03`: Chunked File Transfer (`/p2p-file/1.0.0`)
- `0x04`: Kademlia DHT RPC (`/p2p-dht/1.0.0`)
- `0x05`: Keep-Alive Ping / Pong

## 3. Kademlia DHT RPC (`/p2p-dht/1.0.0`)
Operates over UDP port 9092 (or multiplexed over TCP socket):
- `PING`: Liveness probe.
- `PONG`: Includes `observedAddress` (the caller's public IP:port seen by the responder; functions like STUN).
- `FIND_NODE`: Requests closest known contacts to a target PeerID.
- `NODES_RESPONSE`: Returns up to $K=20$ closest contacts.
- `PUT_VALUE`: Stores a signed `PeerRecord` on a node.
- `GET_VALUE`: Retrieves a `PeerRecord` for a target PeerID.
- `VALUE_RESPONSE`: Returns the requested `PeerRecord` or closest nodes if not present.

## 4. Real-Time Chat Protocol (`/p2p-chat/1.0.0`)
Wire Envelope:
```json
{
  "version": "1.0.0",
  "messageId": "UUID",
  "senderPeerId": "12D3Koo...",
  "recipientPeerId": "12D3Koo...",
  "timestamp": 1791217933848,
  "sequenceNumber": 4,
  "ratchetKeyHex": "1a2b...",
  "nonceHex": "f0e1...",
  "ciphertextHex": "3c4d...",
  "previousChainLength": 0
}
```
Payload Types (decrypted inside Double Ratchet AEAD envelope):
- `TEXT`: User message.
- `DELIVERY_ACK`: Acknowledgment from recipient that message was stored.
- `READ_RECEIPT`: Acknowledgment that message was viewed.
- `TYPING`: Ephemeral typing indicator.

## 5. Direct Chunked File Transfer Protocol (`/p2p-file/1.0.0`)
Each file frame payload is a JSON wrapper `{seq, rk, n, ct, pn}` holding the Double Ratchet
ciphertext of the `FileEnvelope` JSON. Sender/recipient identity is taken from the authenticated
connection, never from the envelope. Receivers validate `fileId` (`[A-Za-z0-9-]{1,64}`), sanitize
file names, require `totalChunks == ceil(size/64KB)`, a 2 GB size cap, and a per-chunk SHA-256.
- Chunk size: 64 KB (65,536 bytes).
- Commands:
  - `FILE_INIT`: Metadata (fileName, size, totalChunks, sha256Hash).
  - `FILE_ACCEPT`: Recipient accepts, optionally specifies `chunkIndex` for resumption.
  - `FILE_REJECT`: Recipient cancels/rejects.
  - `FILE_CHUNK`: 64 KB encrypted chunk with chunk SHA-256 and index.
  - `FILE_ACK`: Recipient acknowledges receipt of chunk.
  - `FILE_RESUME`: Sent on reconnection to resume from missing offset.
  - `FILE_COMPLETE`: Sender indicates completion; recipient verifies full file SHA-256.
  - `FILE_ERROR`: Error description.
