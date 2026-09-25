# Alizz File Manager

Original Material 3 Android file manager — local files, archives, viewers, SAF storages, network providers, clouds, HTTP server and Shizuku support.

- Package: `com.alizz.filemanager`
- Stack: Kotlin, Jetpack Compose (Material 3), Coroutines/Flow, ViewModel, DataStore
- Min SDK 26, target/compile SDK 35, Java 17

## Features
- Local browser (list/grid, breadcrumbs, sort, search, hidden files), selection action mode
- Copy/cut/paste, rename, batch rename (`{n}`/`{name}`), checksums (MD5/SHA-256), properties
- Recycle bin with restore + 30-day auto-purge; bookmarks, history, recent
- Viewers: image (slideshow, EXIF), text editor (atomic save), APK info/install, video/audio (Media3), PDF
- Archives: ZIP/TAR browse/extract/create (traversal + bomb guards)
- SAF external storages, FTP/FTPS, SFTP, SMB, WebDAV, Google Drive, Dropbox, OneDrive, Box, pCloud, Yandex Disk
- HTTP file server, Shizuku privileged deletes (opt-in), light/dark themes, app shortcuts

## Build
```sh
gradle assembleDebug
```
CI builds every `main` push touching `app/**` and attaches `app-debug.apk` to the rolling `dev` GitHub Release.

## Cloud setup
Drive, Dropbox, OneDrive, Box, pCloud and Yandex each need their own OAuth client registered for
`com.alizz.filemanager` (see in-app hints + `docs` in the private research repo). Unregistered apps get a clear sign-in error, not a crash.

## License
MIT — see [LICENSE](LICENSE).
