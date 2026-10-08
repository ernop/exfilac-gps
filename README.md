# Exfilac GPS

Exfilac GPS uploads directories on an Android phone to S3-compatible storage
and keeps the GPS location in the photos and videos it uploads. It continues
[Exfilac](https://codeberg.org/io7m-com/exfilac) by Mark Raynsford. Android
zeroes the location in everything stock Exfilac uploads.

Exfilac's last release is 1.1.4 (December 2024). Its Codeberg repository
accepts no issues or pull requests and its GitHub repository is archived, so
changes cannot go upstream. Exfilac GPS starts from 1.1.4 and is developed
here. It is not affiliated with or endorsed by Exfilac's author.

## Changes from Exfilac 1.1.4

Full diff:
[`com.io7m.exfilac-1.1.4...main`](https://github.com/ernop/exfilac-gps/compare/com.io7m.exfilac-1.1.4...main)

- **Location kept in photos and videos.** Android removes the location from
  photos (Exif) and MP4-family videos when an app reads them through the
  document URI that the folder picker hands out. It returns the file as
  written only when the app opens the file's MediaStore URI while holding
  `ACCESS_MEDIA_LOCATION`. Exfilac GPS declares that permission, asks for it
  each time it opens until it is granted (Android calls it "Photos and
  videos"), and reads image, video and audio files through
  `MediaStore.getMediaUri`. Without the permission those reads fail instead of
  uploading a copy without its location. Other files are read as before.
- **Installs beside Exfilac.** Application ID `com.io7m.exfilac.main.gps` and
  app name "Exfilac GPS", so both apps can be installed, each with its own
  settings.
- **Version numbers.** `versionName` is upstream's plus `-gpsN`, and
  `versionCode` is upstream's × 100 + N, where N counts revisions on one
  upstream release. The current version is 1.1.4-gps2.
- **Android 10 or later.** `minSdk` is 29 (upstream: 26), because
  `MediaStore.getMediaUri` needs it.

`MediaStore.setRequireOriginal` is not used. `getMediaUri` grants access to the
plain URI, MediaProvider checks the grant against the URI including
`?requireOriginal=1`, and every open fails with "has no access". Revision 1
did this.

## Known limitations

- MediaStore has no entry for trashed (`.trashed-*`), pending (`.pending-*`)
  and `.thumbnails` files, so reading them fails. An upload containing such
  files is marked failed on every run; its other files still upload.

## Why Exfilac

Exfilac does one job. Its only S3 calls are HeadObject, PutObject and
multipart upload, so it has no code that deletes files on the phone or objects
in the bucket. After each upload it reads back the object's size and SHA-256
metadata.

## Building

Needs JDK 21 and the Android SDK with platform 34 and build-tools 34.0.0.

```sh
echo "sdk.dir=$HOME/Android/Sdk" > local.properties
./gradlew :com.io7m.exfilac.main:assembleRelease
```

That builds an unsigned APK,
`com.io7m.exfilac.main/build/outputs/apk/release/com.io7m.exfilac.main-release-unsigned.apk`.
Align it and sign it with your own key:

```sh
zipalign -f -p 4 com.io7m.exfilac.main-release-unsigned.apk aligned.apk
apksigner sign --ks your-key.p12 --out exfilac-gps.apk aligned.apk
```

Android installs an update only over an app signed with the same key, so keep
the key. No prebuilt APKs are published.

## Using it

[Exfilac's user manual](https://www.io7m.com/software/exfilac/documentation/index-m.xhtml)
applies.

Don't upload the same directory to the same bucket from both Exfilac and
Exfilac GPS. Exfilac skips a file only when the bucket copy has the same size
and SHA-256, and a copy with its location removed has different bytes, so each
app would upload again every file the other uploaded, on every run.

## License

ISC, the same as Exfilac: [README-LICENSE.txt](README-LICENSE.txt).
