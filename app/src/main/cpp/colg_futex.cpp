#include <climits>
#include <errno.h>
#include <jni.h>
#include <linux/futex.h>
#include <sys/syscall.h>
#include <time.h>
#include <unistd.h>

/* Ashmem SharedMemory futex helpers only — no AHardwareBuffer / DMA-BUF. */

static inline int futex_wake(volatile int* addr, int count) {
    return (int) syscall(SYS_futex, addr, FUTEX_WAKE, count, nullptr, nullptr, 0);
}

static inline int futex_wait(volatile int* addr, int expected, const struct timespec* timeout) {
    return (int) syscall(SYS_futex, addr, FUTEX_WAIT, expected, timeout, nullptr, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_net_z841973620_colorosliquidglass_ipc_NativeFutex_wakeNative(
        JNIEnv*, jclass, jlong baseAddress, jint offsetBytes) {
    if (baseAddress == 0) return;
    auto* word = reinterpret_cast<volatile int*>(
            static_cast<uintptr_t>(baseAddress) + static_cast<uintptr_t>(offsetBytes));
    futex_wake(word, INT_MAX);
}

extern "C" JNIEXPORT jint JNICALL
Java_net_z841973620_colorosliquidglass_ipc_NativeFutex_waitNative(
        JNIEnv*, jclass, jlong baseAddress, jint offsetBytes, jint expected,
        jlong timeoutNs) {
    if (baseAddress == 0) return EINVAL;
    auto* word = reinterpret_cast<volatile int*>(
            static_cast<uintptr_t>(baseAddress) + static_cast<uintptr_t>(offsetBytes));
    struct timespec ts{};
    const struct timespec* timeout = nullptr;
    if (timeoutNs > 0) {
        ts.tv_sec = (time_t) (timeoutNs / 1000000000L);
        ts.tv_nsec = (long) (timeoutNs % 1000000000L);
        timeout = &ts;
    }
    int rc = futex_wait(word, expected, timeout);
    if (rc == 0) return 0;
    return errno;
}
