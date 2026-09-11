# PocketScan — Data handling

Technical documentation of the current data flows; this is not a legal privacy policy.

## Local storage
- Scanned/imported documents are stored in app-private `files/scans`.
- OCR output may be stored as a local `.txt` sidecar.
- AI metadata is stored as a local `.ai.json` sidecar.
- The FileProvider is restricted to the `scans/` directory.
- Android app backup is disabled.

## Camera and biometrics
- Camera permission is used for document scanning.
- Optional biometric/device-credential app lock stores its state in app-private preferences.

## Cloud AI
When a PDF has no AI metadata, background analysis is scheduled. If cloud AI is available and the PDF is within the current 14 MB inline limit, the complete PDF can be sent to the configured Gemini model for metadata extraction. OCR text may also be supplied as auxiliary context. If cloud analysis fails, local deterministic extraction is used.

The library assistant first filters and aggregates locally, then may send structured library context to cloud AI for natural-language answers.

Production AI calls use Firebase App Check with Play Integrity and limited-use tokens. The AI model name is resolved through Firebase Remote Config with a local default.

## Not established by this repository
The repository does not establish provider-side retention periods, model-training use, or contractual data-processing terms. These must be verified for the production Firebase configuration before publishing a legal privacy policy or completing Google Play Data Safety declarations.

## Production checklist
1. Production Firebase configuration and API restrictions.
2. App Check enforcement and Play Integrity configuration.
3. Remote Config values and access controls.
4. AI quotas, monitoring and budget controls.
5. Public privacy policy and Google Play Data Safety declarations.
6. Release signing and Play Console testing.
7. Clear user-facing disclosure of cloud AI document processing.
