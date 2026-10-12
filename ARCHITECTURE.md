# sol-mesh Architecture

sol-mesh is a decentralized, serverless, peer-to-peer messaging and direct file transfer system designed for Android.

```
+-----------------------------------------------------------------------+
|                            Compose UI Layer                           |
|  HomeScreen | PeersScreen | ChatScreen | TransfersScreen | Identity   |
+-----------------------------------+-----------------------------------+
                                    |
+-----------------------------------v-----------------------------------+
|                        Domain & Service Layer                         |
|     P2PNode (Master Coordinator) <---> P2PNodeService (Foreground)    |
|        ChatManager              <--->     FileTransferManager         |
+-----------------------------------+-----------------------------------+
                                    |
+-----------------------------------v-----------------------------------+
|                     Protocol & Multiplexing Layer                     |
|  FramedSocketStream ([4B Length][1B StreamID][Encrypted Payload])     |
|   - 0x01: Handshake / Auth         - 0x02: /p2p-chat/1.0.0            |
|   - 0x03: /p2p-file/1.0.0          - 0x04: Kademlia DHT RPC           |
+-----------------------------------+-----------------------------------+
                                    |
+-----------------------------------v-----------------------------------+
|                     End-to-End Cryptography Layer                     |
|  DoubleRatchetSession (PFS) | Curve25519 (X25519 / Ed25519)           |
|  AeadCipher (AES-256-GCM)   | Hkdf (RFC 5869)                         |
|  KeyStoreManager (AndroidKeyStore Hardware Vault)                     |
+-----------------------------------+-----------------------------------+
                                    |
+-----------------------------------v-----------------------------------+
|                   Decentralized Networking & Discovery                |
|  TransportManager (Direct TCP/QUIC ServerSocket)                      |
|  DhtManager (Kademlia 256 K-Buckets, XOR distance, STUN PONG reflex)  |
|  PeerDiscovery (Zero-Config UDP Multicast/Broadcast on Subnet)        |
+-----------------------------------+-----------------------------------+
                                    |
+-----------------------------------v-----------------------------------+
|                     Local Persistence Only (Room)                     |
|  AppDatabase (SQLite on-device only; NEVER sent to any remote server) |
|  - PeerEntity  - MessageEntity  - TransferEntity                      |
+-----------------------------------------------------------------------+
```

## Architectural Tenets
1. **Decentralized by Design**: No central server, Firebase, Supabase, AWS, REST API, WebSocket server, or cloud file relay.
2. **Deterministic Cryptographic Identity**: Identity is derived directly from public keys.
3. **Multi-Tier Discovery**: Zero-config UDP multicast on LAN + Kademlia DHT over the Internet + community bootstrap nodes.
4. **Stream Multiplexing**: A single encrypted connection multiplexes chat messages, chunked file transfers, and DHT RPCs.
5. **Streaming Chunked File Transfer**: 64 KB chunks read and written via `RandomAccessFile` streaming I/O; no entire files buffered in RAM.
6. **Hardware-Backed Key Protection**: Identity keys sealed with Android Keystore.
