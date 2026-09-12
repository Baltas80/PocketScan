# PocketScan 1.0.0 release checklist

## Code and build
- [x] Version 1.0.0 / versionCode 3
- [x] Unit tests pass in CI
- [x] Debug APK builds in CI
- [x] Release APK and AAB build in CI
- [x] Android lint passes in CI
- [x] Transactional PDF/image persistence and recovery covered by tests
- [ ] Final physical-device regression test
- [ ] Final release-signing/upload-key setup

## Firebase production
- [ ] Confirm production Firebase project and Android package registration
- [ ] Confirm required Firebase AI Logic APIs/configuration
- [ ] Enable App Check enforcement for production after Play Integrity verification
- [ ] Review Remote Config key `ai_model_name`
- [ ] Set production AI quotas and budget/usage alerts
- [ ] Verify Firebase console monitoring before publication

## Privacy and Play Store
- [ ] Publish privacy policy at a public HTTPS URL
- [ ] Complete Google Play Data Safety declaration
- [ ] Complete content rating questionnaire
- [ ] Prepare store listing, screenshots and app icon
- [ ] Upload signed AAB to Play Console internal testing
- [ ] Test install/update/restore from Play internal track
- [ ] Review production release notes

## Post-1.0 optional extensions
- OpenCV for stronger perspective, border and shadow correction
- ZXing for QR/barcode extraction
- PDFBox for advanced PDF validation/manipulation
