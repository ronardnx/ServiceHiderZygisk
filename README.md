# ServiceHiderZygisk

A standalone **Zygisk module** built with [ZygoteLoader](https://github.com/aerath-stuff/ZygoteLoader) that hides LineageOS framework system services (such as `profile`, `lineagehardware`, `lineagetrust`, etc.) from detection tools like DuckDetector without requiring ART method hooks or LSPosed.

## Features

- **Obfuscation-Proof Caller Filtering**: Uses stack trace inspection against system framework class prefixes (`SYSTEM_PREFIXES`) to reliably distinguish between framework system callers and obfuscated app detection code (`zz`, `nv`, etc.).
- **Android 16 (API 36) AIDL Support**: Intercepts `getService`, `getService2`, `checkService`, and `tryGetService` AIDL calls on `IServiceManager`.
- **Cache & Iteration Filtering**: Intercepts `sCache` map methods (`keySet`, `entrySet`, `values`, `get`, `containsKey`) to strip hidden keys and prevent service re-caching.
- **AssetManager Signature Hiding**: Clears `LINEAGE_APK_PATH` via Unsafe reflection to prevent Lineage asset signature leaks.

## Supported Services

- `profile`
- `lineageglobalactions`
- `lineagehardware`
- `lineagehealth`
- `lineagelivedisplay`
- `lineagetrust`
- `vendor.lineage.*` HIDL/AIDL services

## Building

Requires Java 17 and Android SDK:

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleRelease
```

The output zip module will be generated at:
`app/build/outputs/magisk/release/servicehider-zygisk.zip`

## License

MIT License
