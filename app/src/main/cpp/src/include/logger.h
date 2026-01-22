#pragma once

#if defined(__ANDROID__)
#include <android/log.h>
#define LOG_TAG "NcmCrypt"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#elif defined(_WIN32) || defined(_WIN64)
#define LOGD(...)
#define LOGE(...)
#else
#include <cstdio>
#define LOGD(...) printf(__VA_ARGS__); printf("\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif
