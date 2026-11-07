# HanReader: signing Android releases

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

## One-time setup

Keep the following two private files provided with this release:
- hanreader-release-keystore.p12
- hanreader-signing-setup.txt

GitHub repository Settings → Secrets and variables → Actions → New repository secret.
Add the four named secrets and copy their values from hanreader-signing-setup.txt:

1. HANREADER_KEYSTORE_BASE64
2. HANREADER_KEYSTORE_PASSWORD
3. HANREADER_KEY_ALIAS
4. HANREADER_KEY_PASSWORD

After that, run Android CI on main using "Run workflow".
It produces both hanreader-debug-apk and hanreader-signed-release-apk artifacts.
The release APK is signed using the stable release key. Debug APKs are signed with
the shared test key committed as app/debug.keystore, so every debug build (CI or local)
installs over the previous one. That key is public: it identifies test builds only.

## Beta link for testers

Every successful CI build on main or a working branch replaces the APK of the
GitHub release `beta`. The link stays the same:
https://github.com/boikot5225-afk/HanReader/releases/download/beta/HanReader-beta.apk

## Switching from the debug build to the signed release

Android refuses to update an installed app with an APK signed by a different
certificate, even if both APKs have the same package name. Moving from the
debug APK to the release APK therefore needs a reinstall:

1. In the installed debug HanReader (0.7.0 or newer): Settings → «Резервная копия» →
   «Сохранить копию в файл». Check that the file exists.
2. Uninstall the debug HanReader.
3. Install `hanreader-signed-release-apk` from the latest Android CI run.
4. Settings → «Резервная копия» → «Восстановить из файла».

All later release APKs install as normal updates over the release build.

## Keep the key safe

The signing keystore and its password must be stored safely for every future
release. If they are lost, updates signed by the same identity are no longer
possible (the app would have to be reinstalled again and restored from backup).

Without secrets, CI still builds debug APKs but deliberately skips release signing.
