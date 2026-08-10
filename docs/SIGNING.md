# APK Signing Policy

Floating Voice GitHub APK releases use one long-term release-signing certificate. The private key and passwords are never committed to Git or uploaded as GitHub Release assets.

## Official certificate

- Subject: `CN=Floating Voice Release, O=Namseok Yoo, C=KR`
- Key: RSA 4096-bit
- Signature algorithm: SHA256withRSA
- Certificate SHA-256 fingerprint:

```text
FD:97:82:9D:19:F8:B0:57:5B:79:EC:1E:8B:7A:26:16:A0:69:7C:EE:86:5D:29:B0:B5:28:78:3C:39:88:AB:A6
```

Do not install an APK presented as an official Floating Voice release if this fingerprint differs.

## Verify a downloaded APK

Use Android SDK Build Tools:

```bash
apksigner verify --min-sdk-version 24 --verbose --print-certs FloatingVoice-arm64-v0.4.1.apk
shasum -a 256 FloatingVoice-arm64-v0.4.1.apk
```

Compare the signer fingerprint above and the APK SHA-256 published in the corresponding GitHub Release Notes.

## Release rules

- APKs are attached to GitHub Releases and are never committed to Git history.
- Every release APK must use the same long-term certificate.
- Every release records its APK filename, byte size, SHA-256, supported ABI, Android minimum version, and signature verification result.
- The release key must remain outside the repository with a separately verified backup.
- A signing-key change is a breaking distribution event: Android will not update an installed app signed by another key without uninstalling it and deleting that app's local data.
- Debug APKs are not official release artifacts.
