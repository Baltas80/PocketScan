# PocketScan

PocketScan is an offline-first Android document scanner focused on fast capture, intelligent document organization and AI-assisted document understanding.

## Current capabilities
- Google ML Kit Document Scanner with automatic document capture and perspective correction
- Local post-processing to improve contrast and readability of captured pages
- Gallery import and multipage scanning
- PDF and JPEG output
- Local OCR with support for Latin, Chinese, Devanagari, Japanese and Korean text
- OCR text export to TXT
- Optional AI OCR correction with export of a corrected TXT copy
- Automatic document naming and categorization
- Local document search and folders
- AI metadata extraction for structured document information
- AI questions about individual PDFs with streaming responses
- AI questions across the document library with deterministic local filtering and aggregation
- Sharing, rename and delete
- Biometric app lock
- Firebase App Check with Play Integrity for production and Debug provider for development
- Offline-first local document storage

## Production work remaining
- Firebase production configuration and API restrictions
- Verify and tune Remote Config values for AI model/configuration management
- Production monitoring, quotas and budget controls
- Final security, privacy and Google Play compliance review
- Release signing and Play Store release testing

Package: `com.baltas80.pocketscan`

Target SDK: Android 16 / API 36
