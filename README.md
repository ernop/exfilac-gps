# Exfilac GPS

Exfilac GPS uploads directories on an Android phone to S3-compatible storage.
It continues [Exfilac](https://codeberg.org/io7m-com/exfilac) by Mark
Raynsford, and is not affiliated with or endorsed by Exfilac's author.

## Why a new version

Exfilac's last release is 1.1.4 (December 2024). Its Codeberg repository
accepts no issues or pull requests and its GitHub repository is archived, so
fixes cannot go upstream. Exfilac GPS starts from 1.1.4 and fixes three
problems:

1. **Photos and videos lose their location.** Android removes the GPS
   location from photos (Exif) and videos when an app reads them the way
   Exfilac does, so every copy Exfilac uploads has lost the location your
   camera recorded. Exfilac GPS reads them so that the location stays.
2. **Large files may never finish uploading.** Exfilac starts a large upload
   again from the beginning on every run and cannot retry a part that fails
   partway through, so a video of a few gigabytes can take many runs or
   never finish. Exfilac GPS continues an interrupted upload from the parts
   already in the bucket.
3. **System folders clutter every run.** Android keeps hidden folders and
   files such as `.thumbnails`, `.trashed-*` and `.pending-*` among your
   photos. Exfilac goes through all of them on every run: it uploads
   thumbnails, deleted photos and unfinished files, and logs each one as
   uploaded, skipped or failed. Exfilac GPS skips anything whose name starts
   with `.` without looking inside.

The [latest release](https://github.com/ernop/exfilac-gps/releases/latest)
has a signed APK; see [Installing](#installing).

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
- **Large files continue where they stopped.** Exfilac starts a multi-part
  upload (files of 16 MiB or more) from the first part on every run. It
  cancels the upload when it fails, and its parts stay in the bucket when the
  app is stopped or the cancel fails too. Exfilac GPS leaves a failed upload
  in the bucket. The next run lists the file's unfinished uploads and
  compares the size and MD5 of each uploaded part with the file. It continues
  the upload holding the most data whose parts all match, and cancels the
  file's other unfinished uploads once the file is complete.
- **Failed parts are retried.** Exfilac sends each part from a stream that
  cannot be rewound, so a retry of a part that failed partway through has
  nothing left to send. Exfilac GPS opens the file at the part's offset for
  each attempt.
- **Hidden files and directories skipped.** Entries whose names start with
  `.`, such as `.thumbnails`, `.trashed-*`, `.pending-*` and `.nomedia`, are
  skipped while listing, without listing what is inside them. Exfilac lists
  and reads them. MediaStore has no entry for most of them, so with the
  location change every one would fail on every run, and `.thumbnails` alone
  can hold thousands of files.
- **One read to hash a file.** Exfilac reads each file twice for its SHA-256
  before uploading it. Exfilac GPS reads it once, for its SHA-256 and, for a
  multi-part upload, the MD5 of each part. A file whose size changed since it
  was listed fails before anything is sent.
- **Notifications.** The ongoing notification shows the file being uploaded
  and the percentage of it in the bucket. When a run ends with files that did
  not upload, a notification names them. Exfilac's notification only says
  that it is running.
- **Installs beside Exfilac.** Application ID `com.io7m.exfilac.main.gps` and
  app name "Exfilac GPS", so both apps can be installed, each with its own
  settings.
- **Version numbers.** `versionName` is upstream's plus `-gpsN`, and
  `versionCode` is upstream's × 100 + N, where N counts revisions on one
  upstream release. The current version is 1.1.4-gps3.
- **Android 10 or later.** `minSdk` is 29 (upstream: 26), because
  `MediaStore.getMediaUri` needs it.

`MediaStore.setRequireOriginal` is not used. `getMediaUri` grants access to the
plain URI, MediaProvider checks the grant against the URI including
`?requireOriginal=1`, and every open fails with "has no access". Revision 1
did this.

## Known limitations

- Exfilac GPS cancels unfinished multi-part uploads only of a file it has just
  uploaded. The parts of other unfinished uploads stay in the bucket, for
  example those of a file deleted from the phone before it finished
  uploading. A bucket lifecycle rule that cancels unfinished multi-part
  uploads after some days removes them.

## Why Exfilac

Exfilac does one job. Its only S3 calls are HeadObject, PutObject and the
multi-part upload calls, which in Exfilac GPS include listing and cancelling
the unfinished uploads of the file being uploaded. It has no code that
deletes files on the phone or objects in the bucket. After each upload it
reads back the object's size and SHA-256 metadata.

## Installing

Needs Android 10 or later. Download the APK from the
[latest release](https://github.com/ernop/exfilac-gps/releases/latest), open
it on the phone, and when Android asks, allow the app you opened it with to
install apps. When Exfilac GPS opens, allow "Photos and videos", which lets it
read the location, and allow notifications.

Release APKs are signed with the certificate whose SHA-256 is
`0b55514f75df2140329c875602325f66db1c5b8471f8931e1d611ee3bf0515b8`
(`apksigner verify --print-certs` shows it). Android installs an update only
over an app with the same signature, so uninstall first to switch between a
release and your own build.

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
the key.

The tests run with `./gradlew :com.io7m.exfilac.tests:test`. The multi-part
upload tests run against an S3 endpoint named by `EXFILAC_TEST_S3_ENDPOINT`
and are skipped without it. A local [moto](https://github.com/getmoto/moto)
server works:

```sh
uvx --from 'moto[server]' moto_server -p 5055 &
EXFILAC_TEST_S3_ENDPOINT=http://127.0.0.1:5055 ./gradlew :com.io7m.exfilac.tests:test --tests '*EFS3AMZ*'
```

## Using it

[Exfilac's user manual](https://www.io7m.com/software/exfilac/documentation/index-m.xhtml)
applies.

Don't upload the same directory to the same bucket from both Exfilac and
Exfilac GPS. Exfilac skips a file only when the bucket copy has the same size
and SHA-256, and a copy with its location removed has different bytes, so each
app would upload again every file the other uploaded, on every run.

## License

ISC, the same as Exfilac: [README-LICENSE.txt](README-LICENSE.txt).
