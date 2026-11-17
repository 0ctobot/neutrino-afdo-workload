// Anonymous memory for the mem-fixture case: what an app's native heap does,
// with explicit semantics (private anonymous mappings, written page by page).
// Every page starts with a stamp (pass, page index) so the case can verify
// the contents after the trace stops; the rest of the page is filled by kind:
// 0 nearly empty (highly compressible), 1 text-like, 2 random (incompressible).
#include <jni.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#define FN(name) Java_org_neutrino_afdo_workload_Mem_##name

static long page(void) { return sysconf(_SC_PAGESIZE); }

JNIEXPORT jlong JNICALL FN(nativeAlloc)(JNIEnv *env, jclass c, jlong bytes)
{
	void *p = mmap(NULL, (size_t)bytes, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
	return p == MAP_FAILED ? 0 : (jlong)(uintptr_t)p;
}

JNIEXPORT void JNICALL FN(nativeFree)(JNIEnv *env, jclass c, jlong addr, jlong bytes)
{
	munmap((void *)(uintptr_t)addr, (size_t)bytes);
}

// Write every page of [addr, addr+bytes); returns the pages written.
JNIEXPORT jlong JNICALL FN(nativeTouch)(JNIEnv *env, jclass c, jlong addr, jlong bytes, jint kind, jint pass, jlong seed)
{
	static const char text[] = "the page cache holds file data while reclaim moves anonymous pages to zram ";
	long ps = page(), n = bytes / ps;
	uint64_t x = (uint64_t)seed * 0x9e3779b97f4a7c15ull + (uint64_t)pass;
	for (long i = 0; i < n; i++) {
		uint64_t *w = (uint64_t *)((uintptr_t)addr + (uintptr_t)(i * ps));
		w[0] = ((uint64_t)(uint32_t)pass << 32) | (uint32_t)i;
		if (kind == 1) {
			for (long b = 8; b < ps; b += sizeof(text) - 1)
				memcpy((char *)w + b, text, (size_t)(ps - b < (long)sizeof(text) - 1 ? ps - b : (long)sizeof(text) - 1));
		} else if (kind == 2) {
			for (long k = 1; k < ps / 8; k++) {
				x ^= x << 13; x ^= x >> 7; x ^= x << 17;
				w[k] = x;
			}
		} else {
			w[ps / 16] = x + (uint64_t)i;
		}
	}
	return n;
}

// Read the stamp of every page (swapped-out pages fault back in); returns
// the pages whose stamp is not (pass, index).
JNIEXPORT jlong JNICALL FN(nativeVerify)(JNIEnv *env, jclass c, jlong addr, jlong bytes, jint pass)
{
	long ps = page(), n = bytes / ps, bad = 0;
	for (long i = 0; i < n; i++) {
		const volatile uint64_t *w = (const uint64_t *)((uintptr_t)addr + (uintptr_t)(i * ps));
		if (w[0] != (((uint64_t)(uint32_t)pass << 32) | (uint32_t)i))
			bad++;
	}
	return bad;
}

// Give [addr+off, addr+off+len) back to the kernel, as an allocator releasing
// free memory does (madvise MADV_DONTNEED: the next touch faults in zeroed pages).
JNIEXPORT jint JNICALL FN(nativeRelease)(JNIEnv *env, jclass c, jlong addr, jlong off, jlong len)
{
	return madvise((void *)(uintptr_t)(addr + off), (size_t)len, MADV_DONTNEED);
}

JNIEXPORT jlong JNICALL FN(nativePageSize)(JNIEnv *env, jclass c) { return page(); }
