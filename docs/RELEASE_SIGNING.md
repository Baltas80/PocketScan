# Release signing

PocketScan must use a production signing key only for the final release pipeline.

## Rules

- Never commit a `.jks`, `.keystore`, private key, or signing password.
- Keep the production keystore backed up outside the repository.
- Prefer Google Play App Signing for Play distribution.
- Store CI signing material only in protected GitHub Actions secrets.
- Do not enable production signing in local/debug builds.

## Final CI setup

When the production keystore exists, configure the release build with secret-backed values for:

- keystore file
- keystore password
- key alias
- key password

The release workflow should decode the protected keystore into the runner temporarily, build the signed AAB, and remove the temporary file after the build.

## Current state

The repository intentionally builds release APK/AAB artifacts without production credentials. This keeps CI reproducible while Firebase configuration, Play Console setup, privacy/Data Safety declarations, and final signing are completed.
