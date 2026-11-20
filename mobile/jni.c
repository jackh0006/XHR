/*
 * JNI entry points for the XHR Android client.
 * The package is com.jackh0006.xhr and the Kotlin object is Native.
 */
#ifdef __ANDROID__

#include <jni.h>
#include <stdlib.h>

extern char *xhr_start_json(char *raw);
extern void xhr_stop(void);
extern char *xhr_stats_json(void);
extern char *xhr_logs_text(void);
extern void xhr_logs_clear(void);
extern char *xhr_test_sni_json(char *raw);
extern char *xhr_version(void);

static jstring take(JNIEnv *env, char *s)
{
    jstring out;
    if (s == NULL) return NULL;
    out = (*env)->NewStringUTF(env, s);
    free(s);
    return out;
}

static jstring call_json(JNIEnv *env, jstring input, char *(*fn)(char *))
{
    const char *raw;
    char *result;
    jstring out;

    if (input == NULL) return NULL;
    raw = (*env)->GetStringUTFChars(env, input, NULL);
    if (raw == NULL) return NULL;
    result = fn((char *) raw);
    (*env)->ReleaseStringUTFChars(env, input, raw);
    out = take(env, result);
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_start(JNIEnv *env, jobject self, jstring config)
{
    (void) self;
    return call_json(env, config, xhr_start_json);
}

JNIEXPORT void JNICALL
Java_com_jackh0006_xhr_Native_stop(JNIEnv *env, jobject self)
{
    (void) env;
    (void) self;
    xhr_stop();
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_stats(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_stats_json());
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_logs(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_logs_text());
}

JNIEXPORT void JNICALL
Java_com_jackh0006_xhr_Native_clearLogs(JNIEnv *env, jobject self)
{
    (void) env;
    (void) self;
    xhr_logs_clear();
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_testSni(JNIEnv *env, jobject self, jstring input)
{
    (void) self;
    return call_json(env, input, xhr_test_sni_json);
}

JNIEXPORT jstring JNICALL
Java_com_jackh0006_xhr_Native_version(JNIEnv *env, jobject self)
{
    (void) self;
    return take(env, xhr_version());
}

#endif
