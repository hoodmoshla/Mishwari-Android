# Permanent Mishwari signing

The installable APK produced by Android CI uses one dedicated, permanent Mishwari key. `certificate-sha256.txt` is the public certificate SHA-256 fingerprint, not a private key. Never replace this fingerprint or regenerate the key to fix a build failure.

## GitHub Actions

Repository secrets used exclusively by the trusted signed-APK job:

- `MISHWARI_KEYSTORE_BASE64`: base64-encoded PKCS12 keystore.
- `MISHWARI_STORE_PASSWORD`: keystore password.
- `MISHWARI_KEY_ALIAS`: signing alias.
- `MISHWARI_KEY_PASSWORD`: key password.

The job restores the key into the runner's temporary directory, builds Release, verifies the APK's certificate against the committed fingerprint, and uploads an Actions artifact with checksum and provenance. Missing credentials or a different certificate stop the build/upload. The temporary key is removed even on failure. No GitHub Release is created.

Signing is allowed only on `push` or manual dispatch for `codex/initial-implementation` in `hoodmoshla/Mishwari-Android`, after the existing tests and lint pass. Pull requests never receive signing credentials. Debug APKs remain test-only and are no longer uploaded for distribution. Do not distribute locally generated Debug APKs: their default debug keys are not this permanent key.

## Local signed builds

Use this same keystore and supply `MISHWARI_KEYSTORE_PATH`, `MISHWARI_STORE_PASSWORD`, `MISHWARI_KEY_ALIAS`, and `MISHWARI_KEY_PASSWORD` securely through the environment. Run `./gradlew :app:assembleRelease`, then verify its signer against `certificate-sha256.txt`. Never commit the keystore, passwords, or an environment file.

Keep a protected backup of the keystore and credentials. GitHub Secrets cannot be downloaded in plaintext. Do not use keys belonging to another app.

## Updates and existing installations

A new permanent key does not make older differently signed APKs compatible. Do not uninstall the existing application or erase its data to bypass a signature conflict. Compatible future updates must keep `applicationId = ye.mishwari`, use this same signing key, and increment `versionCode` for each distributed update. This signing change does not change the current `versionCode = 1` or `versionName = 0.1.0`.
