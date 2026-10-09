# MLFRS Android app

Standalone Android app (own window, no Chrome needed) for https://fault-summary.netlify.app.
The app opens the live site, so site updates show automatically.

Every push builds the APK with GitHub Actions; download **MLFRS.apk** from the Releases page.
The signing key is stored as the repository secrets `KEYSTORE_B64` and `KS_PASS` (never in the files).
