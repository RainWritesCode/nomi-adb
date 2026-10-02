# nomi-adb

An Android library that lets an app connect to adb on its own phone. It pairs over Wireless debugging and runs shell commands, with no PC needed.

Needs Android 12 or newer and Wi-Fi.

## Build

```
./gradlew :adb:assembleRelease :sample:assembleDebug
```

The library ends up in `adb/build/outputs/aar/`. To use it in your app, copy the `adb` folder into your project and add `implementation project(":adb")`.

## Use

```java
AdbKey key = AdbKey.loadOrCreate(context.getFilesDir(), "myapp");

// once: Developer options > Wireless debugging > Pair device with pairing code
List<InetSocketAddress> targets = AdbDiscovery.pairingTargets(context, 5000);
AdbPairing.pairFirst(targets, "123456", key);

// any time after that, while Wireless debugging is on
AdbConnection adb = new AdbConnector(context, key).connect(); // null if adb is not reachable
String out = adb.shell("id", 20000);
adb.close();
```

The `sample` app shows the whole flow, including entering the pairing code from a notification.

## License

Apache 2.0. See `LICENSE` and `NOTICE`.
