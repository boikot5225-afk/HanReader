# Amauta Reader: signing Android releases

The private signing key is NEVER committed to this public repository.

## Creating the release key (once, on your own computer)

Needs a JDK (Java 17+; `keytool` comes with it). Run, choosing your own passwords:

    keytool -genkeypair -v -storetype PKCS12 -keystore hanreader-release-keystore.p12 \
      -alias hanreader -keyalg RSA -keysize 4096 -validity 10000 \
      -dname "CN=HanReader"

Then make the base64 text GitHub needs:

- Linux / macOS: `base64 -w0 hanreader-release-keystore.p12 > keystore.base64.txt`
  (macOS: `base64 -i hanreader-release-keystore.p12 -o keystore.base64.txt`)
- Windows PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("hanreader-release-keystore.p12")) > keystore.base64.txt`

Keep the .p12 file and both passwords in two safe places (e.g. a password manager and an
offline copy). Losing them means no more updates for the published app. Never send them in
a chat, an e-mail or a commit. Delete keystore.base64.txt after adding the secret.

## Existing production identity

Amauta Reader keeps package `com.hanreader.app.es`. The established private release
key is stored in GitHub Actions secrets; do not create a replacement for this release.
Certificate SHA-256: `59345a3b2a7007d83b95832fe30e120fb71686c413680ed86fb078f813d97859`.
The committed `app/debug.keystore` is public and must never sign a store release.
A separate older app uses `com.hanreader.app`: its RuStore listing cannot be updated
with the Amauta package. Use the Amauta listing or create one for this package.

Required Actions secrets (already used by Android CI):

- `HANREADER_KEYSTORE_BASE64`
- `HANREADER_KEYSTORE_PASSWORD`
- `HANREADER_KEY_ALIAS`
- `HANREADER_KEY_PASSWORD`

A push to existing `amauta` runs tests and creates `hanreader-debug-apk` and,
when these secrets are configured, `hanreader-signed-release-apk` artifacts.
Release tests and Android Lint run before the signed APK is uploaded.
`amauta` does not replace the public Chinese beta. An artifact is not a RuStore
submission or an approval by its moderators. Keep signing passwords out of logs.

For a local signed build, set `HANREADER_KEYSTORE_FILE` and the three password /
alias environment variables above, then run `:app:assembleRelease`. Without them,
the local release output is unsigned and must not be distributed as ready for a store.
Verify the final APK with `apksigner verify --verbose --print-certs`, inspect its
manifest and compare the certificate with the previous signed release.

## Switching from a test build to the production signature

Android cannot install a production APK over a debug APK of the same package.
Before uninstalling anything:

1. Install the **debug 0.26.4** (versionCode 72) over the previous debug app.
2. Save the manual backup to a file outside the app and check it exists. From
   0.26.4 / backup format 4 it includes books, reading progress, cards, manual
   meanings, AI corrections, rejected suggestions and cached paid checks.
3. Only then uninstall the debug app and install the signed release 0.26.4.
4. Restore the archive. Enter the DeepSeek key again and enable AI if wanted.

Archives from 0.26.3 and earlier do not contain AI results. Exporting from an old
version before uninstalling would lose those results. Never promise to recover
results which were not included in the archive. AI mode and API key are never
exported; the encrypted API key is also excluded from Android's system backups.
Book ids and context/cache hashes remain unchanged during restore, allowing the
same checks to be reused without API calls. All later production updates must
keep the package and certificate and increase versionCode.

Store listing text and release notes: `store/amauta/`.

## Keep the key safe

The signing keystore and its password must be stored safely for every future
release. If they are lost, updates signed by the same identity are no longer
possible (the app would have to be reinstalled again and restored from backup).

Without secrets, CI still builds debug APKs but deliberately skips release signing.
