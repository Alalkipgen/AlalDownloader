# Third-party notices

The project source is MIT licensed. Dependencies retain their own licenses;
the project license does not replace their notices or redistribution terms.
Versions below match the current Gradle version catalog.

| Component | Version | License | Upstream |
| --- | --- | --- | --- |
| Kotlin standard library and Gradle plugins | 2.0.21 | Apache-2.0 | https://github.com/JetBrains/kotlin |
| kotlinx.coroutines | 1.9.0 | Apache-2.0 | https://github.com/Kotlin/kotlinx.coroutines |
| AndroidX Compose UI, Foundation, Material 3 | BOM 2024.12.01 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Activity | 1.9.3 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Lifecycle | 2.8.7 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| AndroidX Room (including compiler) | 2.6.1 | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Other transitive AndroidX components (Core, SQLite, etc.) | Gradle-resolved | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| OkHttp | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| Okio (transitive) | Gradle-resolved | Apache-2.0 | https://github.com/square/okio |
| Dagger / Hilt (including compiler) | 2.52 | Apache-2.0 | https://github.com/google/dagger |
| JUnit (tests only) | 4.13.2 | EPL-1.0 | https://github.com/junit-team/junit4 |
| Hamcrest (transitive, tests only) | Gradle-resolved | BSD-3-Clause | https://github.com/hamcrest/JavaHamcrest |
| Gradle wrapper / build tool | 8.10.2 | Apache-2.0 | https://github.com/gradle/gradle |
| Android Gradle Plugin | 8.7.3 | Apache-2.0 | https://android.googlesource.com/platform/tools/base/ |

## Poppins font — planned, not yet bundled

Poppins is licensed under the **SIL Open Font License 1.1 (OFL-1.1)**.
Upstream font files and the authoritative copyright/license notice are at
https://github.com/google/fonts/tree/main/ofl/poppins.
The requested Poppins Medium, SemiBold, and Bold assets are not present in
this checkout. When they are bundled, include upstream `OFL.txt` alongside
them and preserve its copyright and reserved-font-name provisions. This CI
change does not download fonts or implement the Alal rebrand.

## Dependency inventory

On a configured build host, use `./gradlew :app:dependencies` to inspect the
resolved graph, including build-time and transitive dependencies. This table
is an inventory, not a substitute for upstream license/NOTICE files. Audit
the resolved release dependencies and required notices before distribution.