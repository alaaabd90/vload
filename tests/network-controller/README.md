# Network controller regression harness

Compiles the real `VloadNetworkController.kt` against small Android callback
fakes, then drives callback ordering deterministically. It does not emulate
Android networking or replace phone testing.

With a Kotlin compiler installed, run from the repository root:

```sh
kotlinc app/src/main/java/io/nekohasekai/sagernet/utils/VloadNetworkController.kt tests/network-controller/*.kt -include-runtime -d network-controller-tests.jar
java -jar network-controller-tests.jar
```

The fakes belong only to this standalone harness. Do not include them in the
Android app's source sets.
