# Security Design & Threat Model

## 1. Decentralized Identity Model
sol-mesh implements a serverless, cryptographic identity model:
- **Long-term Key**: Every installation generates an RFC 8032 Ed25519 identity keypair `(IK_priv, IK_pub)`.
- **PeerID**: Deterministic identifier derived from `Base58(0x12 || 0x20 || SHA-256(IK_pub))`, following libp2p multihash standards.
- **Key Storage**: Private identity keys and root master secrets are sealed in the `AndroidKeyStore` hardware security module using hardware-backed AES-256-GCM. Private keys never leave the Android device.

## 2. Peer Authentication & Handshake (Noise_XX / Triple-DH)
When two peers establish a direct P2P connection, they execute an authenticated 3-way handshake:
1. **Initiator -> Responder**: Ephemeral X25519 public key `E_A`, Ed25519 identity `IK_A`, timestamp, signed by `IK_A`.
2. **Responder -> Initiator**: Ephemeral X25519 public key `E_B`, Ed25519 identity `IK_B`, timestamp, signed by `IK_B`.
3. **Key Agreement**:
   - `ee = X25519(E_A_priv, E_B_pub)`
   - `se = X25519(IK_A_priv, E_B_pub)`
   - `es = X25519(E_A_priv, IK_B_pub)`
   - `RootKey = HKDF-SHA256(salt = "Nexus-P2P-v1", ikm = ee || se || es, info = "NoiseHandshakeRoot", 32)`
4. **Mutual Authentication**: Both parties verify the other party's Ed25519 signature before initializing session keys.

## 3. Application-Layer End-to-End Encryption (Signal-style Double Ratchet)
Message encryption is decoupled from transport encryption:
- **Diffie-Hellman Ratchet**: Ephemeral ratchet keypairs evolve with each conversation turn.
- **Symmetric KDF Chain Ratchet**: Sending and receiving chain keys advance per message via HKDF-SHA256.
- **Perfect Forward Secrecy (PFS)**: Compromise of current keys does not compromise past messages.
- **Break-in Recovery**: Post-compromise security is established as soon as a new DH ratchet step occurs.
- **Authenticated Encryption (AEAD)**: Every message is encrypted using AES-256-GCM with a 12-byte unique nonce and 128-bit authentication tag, binding sequence numbers and ephemeral ratchet public keys.

## 4. Replay Protection & Ordering
- Each message envelope carries a monotonically increasing `sequenceNumber` and timestamp.
- Out-of-order packets are decrypted using a bounded cache of skipped message keys.
- Duplicate message IDs are discarded by Room's unique constraint (`OnConflictStrategy.IGNORE`).

## 5. Direct Chunked File Transfer Security
- Files are transferred in 64 KB chunks.
- Each chunk is encrypted using AES-256-GCM with a distinct nonce.
- Each chunk includes a SHA-256 integrity checksum.
- Upon completion, the entire reassembled file is checked against the sender's original SHA-256 hash. If there is a mismatch, the file is rejected.

## 6. Threat Model & Limitations
- **What Kademlia DHT Nodes Can See**:
  - DHT nodes only see signed presence records: `(PeerId, Multiaddresses, Ed25519 Public Key, TTL)`.
  - DHT nodes **CANNOT** see message content, file names, file contents, contacts lists, or user activity.
- **Passive Wiretapping**:
  - Encrypted with AES-256-GCM at both transport and application layers. Zero plaintext is exposed.
- **Active Man-in-the-Middle (MitM)**:
  - Mitigated by mutual Ed25519 signature verification and user-verifiable 60-digit mutual Safety Numbers (out-of-band comparison).
- **NAT / CGNAT Limitations**:
  - If both peers are behind restrictive Symmetric NATs or carrier CGNATs where direct UDP/TCP hole punching fails, the connection will fail cleanly with an informative error rather than routing through an insecure or centralized third party.
