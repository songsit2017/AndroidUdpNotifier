# Security setup

## Device pairing

On first launch, enter the same randomly generated pairing code on both devices. Use at least 16 random characters. Packets without a valid AES-GCM authentication tag, packets older than two minutes, and replayed packets are rejected.

Clearing app data removes the pairing code. The code is encrypted at rest with an Android Keystore key and is excluded from Android backups.

## Release signing

The release keystore must not be committed. Configure these GitHub Actions secrets:

- `RELEASE_KEYSTORE_BASE64`: base64 encoding of the rotated release keystore
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`
- `RELEASE_KEY_PASSWORD`

The previously committed keystore and password must be considered compromised. Preserve a secure offline backup before rotating it. Existing Android installations can only accept updates signed by the same key unless an Android-supported signing-key rotation is performed.

## Update verification

Downloaded updates are accepted only when the APK package name and signing certificate exactly match the currently installed application. Invalid APKs are deleted before the package installer is opened.
