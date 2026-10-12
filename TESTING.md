# sol-mesh Testing Guide

## 1. Automated Unit & Local Tests
The project contains comprehensive unit tests covering:
- **`CryptoEngineTest.kt`**:
  - RFC 8032 Ed25519 key generation, signing, and verification.
  - Tampered message signature rejection.
  - RFC 7748 X25519 Diffie-Hellman scalar multiplication agreement.
  - RFC 5869 HKDF extraction and expansion.
  - AES-256-GCM AEAD encryption, decryption, and authentication tag validation.
  - Signal-style Double Ratchet session bi-directional message exchange.
- **`KademliaDhtTest.kt`**:
  - PeerID derivation and Base58 encoding.
  - Kademlia XOR distance metric mathematical properties ($d(x, x)=0$, symmetry, positivity).
  - 256 K-bucket routing table nearest node sorting.
  - Cryptographically signed `PeerRecord` verification and TTL expiry check.
- **`FileTransferProtocolTest.kt`**:
  - 64 KB file chunking and per-chunk SHA-256 hashing.
  - Simulated network drop / transfer interruption.
  - Resumption from specific chunk index.
  - Disk reassembly and full file SHA-256 verification.

### Running Unit Tests
```bash
gradle :app:testDebugUnitTest
```

---

## 2. Two-Device Physical Testing Instructions

### Scenario A: Same Local Wi-Fi Network (Zero-Config Subnet Discovery)
1. **Device A & Device B**: Install and open sol-mesh on two Android phones connected to the same Wi-Fi.
2. **Automatic Discovery**:
   - Both devices start the foreground service and announce their presence via UDP broadcast on port 9090.
   - Within 5–10 seconds, each phone automatically detects the other in the **Peers** tab.
3. **Direct Connection & E2EE**:
   - On Device A, tap **Connect** on Device B's card.
   - The devices execute the 3-way authenticated Triple-DH handshake.
   - The status changes to **CONNECTED**.
4. **Chat**:
   - Open Chat. Send `"Hello from Device A"`.
   - Device B receives the message in real time and automatically sends back a `DELIVERY_ACK` (double checkmark appears on Device A).
5. **Direct Chunked File Transfer**:
   - In Chat, tap the attachment paperclip icon.
   - Select an image, PDF, or video.
   - Watch the live chunk progress bar increment in real time on both devices.
   - Upon completion, the file is saved to the private transfers directory and verified with SHA-256.

### Scenario B: Different Networks / Over the Internet
1. **Device A**: Navigate to the **Identity** tab and copy your `PeerID`.
2. **Device B**: Open the **Peers** tab, tap **Connect**, and paste Device A's `PeerID` or public multiaddress (e.g. `/ip4/<Public-IP>/tcp/9091`).
3. **Kademlia Lookup & Dial**:
   - Device B queries the decentralized DHT for Device A's signed presence record.
   - Once resolved, Device B establishes a direct TCP socket connection to Device A.
4. **Interruption & Resumption Test**:
   - Start transferring a larger file (> 5 MB).
   - Midway through the transfer, toggle Airplane Mode or Wi-Fi on Device A.
   - The transfer enters **PAUSED**.
   - Re-enable connectivity. Tap **Resume**.
   - The protocol sends `FILE_RESUME`, skipping all previously transferred chunks and completing the remaining chunks without re-downloading from scratch!
   - Verify that the final SHA-256 matches.
