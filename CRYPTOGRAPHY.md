# Nexus Cryptography Specification

sol-mesh implements standard, vetted cryptographic primitives with zero custom/unreviewed algorithms.

## 1. Primitives Overview
| Purpose | Primitive | Specification | Key Size |
| :--- | :--- | :--- | :--- |
| Long-term Identity & Signatures | Ed25519 | RFC 8032 | 256 bits |
| Key Exchange / Key Agreement | X25519 | RFC 7748 | 256 bits |
| Key Derivation Function | HKDF-SHA256 | RFC 5869 | 256 bits |
| Authenticated Encryption | AES-256-GCM | NIST SP 800-38D | 256-bit key, 96-bit nonce, 128-bit tag |
| Cryptographic Hash Function | SHA-256 / SHA-512 | FIPS 180-4 | 256 / 512 bits |
| Secure Random Generation | SecureRandom | CSPRNG | - |
| Device Storage Sealing | AndroidKeyStore | Hardware HSM (Keymaster/StrongBox) | AES-256-GCM |

## 2. Handshake Key Schedule (Noise_XX / Triple-DH)
```
Initiator (A)                             Responder (B)
IK_A, EK_A                                IK_B, EK_B
    |                                          |
    +---- ee = X25519(EK_A_priv, EK_B_pub) ----+
    +---- se = X25519(IK_A_priv, EK_B_pub) ----+
    +---- es = X25519(EK_A_priv, IK_B_pub) ----+
    |                                          |
IKM = ee || se || es
PRK = HMAC-SHA256("Nexus-P2P-v1", IKM)
RootKey = HKDF-Expand(PRK, "NoiseHandshakeRoot", 32)
```

## 3. Double Ratchet State Machine
- **Root Key (RK)**: Updated during each DH ratchet turn.
- **Sending Chain Key (CKs)**: Advanced symmetrically for each sent message.
  - `MessageKey = HKDF(CKs, "MessageKey", 32)`
  - `NextCKs = HKDF(CKs, "NextChainKey", 32)`
- **Receiving Chain Key (CKr)**: Advanced symmetrically for each received message.
- **Skipped Message Keys**: Handled in a ring buffer to allow decrypting out-of-order messages while discarding replayed messages.

## 4. Mutual Safety Numbers (Fingerprint)
Derived deterministically from the lexicographically sorted Ed25519 public keys of both participants:
```kotlin
val (first, second) = sort(ourPubKey, theirPubKey)
val ikm = first + second
val fingerprintMaterial = HKDF(salt = "Nexus-Safety-Number-v1", ikm = ikm, info = "Fingerprint", 32)
// Formatted into 12 groups of 5 decimal digits
```
Users compare these numbers verbally or visually (e.g., via QR code or out-of-band channel) to verify there is no active interception.
