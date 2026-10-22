#include <jni.h>
#include <lzma.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
    lzma_stream strm;
} native_handle;

JNIEXPORT jlong JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_createEncoder(JNIEnv *env, jclass cls, jint preset, jint threads) {
    native_handle *h = (native_handle *)calloc(1, sizeof(native_handle));
    if (!h) return 0;

    lzma_ret ret;
    if (threads > 1 || threads == 0) {
        lzma_mt mt = {
            .flags = 0,
            .threads = (threads == 0) ? lzma_cputhreads() : (uint32_t)threads,
            .block_size = 0,
            .timeout = 0,
            .preset = (uint32_t)preset,
            .filters = NULL,
            .check = LZMA_CHECK_CRC64
        };
        ret = lzma_stream_encoder_mt(&h->strm, &mt);
    } else {
        ret = lzma_easy_encoder(&h->strm, (uint32_t)preset, LZMA_CHECK_CRC64);
    }

    if (ret != LZMA_OK) {
        free(h);
        return 0;
    }

    return (jlong)h;
}

JNIEXPORT jlong JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_createDecoder(JNIEnv *env, jclass cls) {
    native_handle *h = (native_handle *)calloc(1, sizeof(native_handle));
    if (!h) return 0;

    lzma_ret ret = lzma_stream_decoder(&h->strm, UINT64_MAX, LZMA_CONCATENATED);
    if (ret != LZMA_OK) {
        free(h);
        return 0;
    }

    return (jlong)h;
}

JNIEXPORT jint JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_resetEncoder(JNIEnv *env, jclass cls, jlong handle, jint preset, jint threads) {
    native_handle *h = (native_handle *)handle;
    if (!h) return -1;

    // Release the memory of the previous stream first, otherwise re-initializing
    // lzma_stream_encoder()/lzma_stream_encoder_mt() on an already initialized
    // stream leaks the old encoder state (dictionary + MT worker pool) every time
    // a pooled encoder is reused.
    lzma_end(&h->strm);
    memset(&h->strm, 0, sizeof(lzma_stream));

    lzma_ret ret;
    if (threads > 1 || threads == 0) {
        lzma_mt mt = {
            .flags = 0,
            .threads = (threads == 0) ? lzma_cputhreads() : (uint32_t)threads,
            .block_size = 0,
            .timeout = 0,
            .preset = (uint32_t)preset,
            .filters = NULL,
            .check = LZMA_CHECK_CRC64
        };
        ret = lzma_stream_encoder_mt(&h->strm, &mt);
    } else {
        ret = lzma_easy_encoder(&h->strm, (uint32_t)preset, LZMA_CHECK_CRC64);
    }

    return (jint)ret;
}

JNIEXPORT jint JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_resetDecoder(JNIEnv *env, jclass cls, jlong handle) {
    native_handle *h = (native_handle *)handle;
    if (!h) return -1;

    // Same leak as in resetEncoder: free the old decoder state before
    // re-initializing the stream.
    lzma_end(&h->strm);
    memset(&h->strm, 0, sizeof(lzma_stream));

    lzma_ret ret = lzma_stream_decoder(&h->strm, UINT64_MAX, LZMA_CONCATENATED);
    return (jint)ret;
}

JNIEXPORT jlong JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_process(
    JNIEnv *env, jclass cls, jlong handle,
    jbyteArray input, jint inputOffset, jint inputLen,
    jbyteArray output, jint outputOffset, jint outputLen,
    jint action) {

    native_handle *h = (native_handle *)handle;
    if (!h) return ((jlong)11 << 56); // LZMA_PROG_ERROR

    jbyte *in_ptr = NULL;
    if (inputLen > 0) {
        in_ptr = (*env)->GetPrimitiveArrayCritical(env, input, NULL);
        h->strm.next_in = (const uint8_t *)(in_ptr + inputOffset);
        h->strm.avail_in = (size_t)inputLen;
    } else {
        h->strm.next_in = NULL;
        h->strm.avail_in = 0;
    }

    jbyte *out_ptr = NULL;
    if (outputLen > 0) {
        out_ptr = (*env)->GetPrimitiveArrayCritical(env, output, NULL);
        h->strm.next_out = (uint8_t *)(out_ptr + outputOffset);
        h->strm.avail_out = (size_t)outputLen;
    } else {
        h->strm.next_out = NULL;
        h->strm.avail_out = 0;
    }

    lzma_action lz_action;
    switch (action) {
        case 1: lz_action = LZMA_SYNC_FLUSH; break;
        case 2: lz_action = LZMA_FULL_FLUSH; break;
        case 3: lz_action = LZMA_FINISH; break;
        default: lz_action = LZMA_RUN; break;
    }

    lzma_ret ret = lzma_code(&h->strm, lz_action);

    uint64_t consumed = (uint64_t)(inputLen - h->strm.avail_in);
    uint64_t produced = (uint64_t)(outputLen - h->strm.avail_out);

    if (in_ptr) (*env)->ReleasePrimitiveArrayCritical(env, input, in_ptr, JNI_ABORT);
    if (out_ptr) (*env)->ReleasePrimitiveArrayCritical(env, output, out_ptr, 0);

    // Pack: status (8 bits) | consumed (28 bits) | produced (28 bits)
    return ((jlong)(ret & 0xFF) << 56) | ((jlong)(consumed & 0x0FFFFFFF) << 28) | (jlong)(produced & 0x0FFFFFFF);
}

JNIEXPORT void JNICALL Java_de_bluecolored_bluemap_lzma2_nativexz_NativeLZMA2_free(JNIEnv *env, jclass cls, jlong handle) {
    native_handle *h = (native_handle *)handle;
    if (h) {
        lzma_end(&h->strm);
        free(h);
    }
}
