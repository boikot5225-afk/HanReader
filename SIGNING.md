# HanReader: signing Android releases

The private signing key is NEVER committed to this public repository.

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
The release APK is signed using the stable release key. Debug APKs are signed by
ephemeral CI debug keys and do NOT have a stable identity across builds.

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
