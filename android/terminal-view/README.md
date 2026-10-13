# terminal-view

The `terminal-view` library of [Termux](https://github.com/termux/termux-app) v0.118.3
(commit 5b657c6adf4304e5198951ce815fe0205dcac29c), which gives the Android app its terminal.
The Java and C sources (and the emulator's tests) are unchanged; only the build differs:

- `build.gradle` is this project's.
- The manifest no longer names the package (Android Gradle Plugin 8 reads the namespace
  from `build.gradle`).

License: Apache License 2.0. The code derives from
[Terminal Emulator for Android](https://github.com/jackpal/Android-Terminal-Emulator), and Termux
releases these two libraries under that license as an exception to its GPLv3 (see its
LICENSE.md); the Apache License 2.0 is compatible with this project's GPLv3.
