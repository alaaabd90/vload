//go:build android

package localtether

/*
#include <dlfcn.h>
#include <stdint.h>

typedef int (*android_setsocknetwork_func)(uint64_t network, int fd);

// android_setsocknetwork (and its <android/multinetwork.h> declaration) only
// exist from API 23 on. This whole feature is gated to API 33+ at the Kotlin
// layer, but the NDK headers for libcore's own androidapi target (21, to
// match vload's minSdk) don't declare the symbol at all, so linking against
// it directly fails to even compile as part of the combined libcore build.
// Resolved via dlopen/dlsym instead, deferring the requirement to runtime,
// where it's already guaranteed to be met.
static int call_android_setsocknetwork(uint64_t network, int fd) {
    static android_setsocknetwork_func fn = NULL;
    static int resolved = 0;
    if (!resolved) {
        void *handle = dlopen("libandroid.so", RTLD_NOW);
        if (handle) {
            fn = (android_setsocknetwork_func)dlsym(handle, "android_setsocknetwork");
        }
        resolved = 1;
    }
    if (!fn) return -1;
    return fn(network, fd);
}
*/
import "C"

import "fmt"

// bindToNetwork pins fd to the network identified by handle.
func bindToNetwork(handle uint64, fd uintptr) error {
	rc, errno := C.call_android_setsocknetwork(C.uint64_t(handle), C.int(fd))
	if rc != 0 {
		return fmt.Errorf("bindToNetwork: android_setsocknetwork(handle=%d, fd=%d): %w",
			handle, fd, errno)
	}
	return nil
}
