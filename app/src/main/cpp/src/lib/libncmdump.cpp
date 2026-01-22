#include "libncmdump.h"
#include "logger.h"
#include <filesystem>
#include <jni.h>

namespace fs = std::filesystem;

extern "C" {
    API NeteaseCrypt* CreateNeteaseCrypt(const char* path) {
        fs::path fPath = fs::u8path(path);
        return new NeteaseCrypt(fPath.u8string());
    }

    API int Dump(NeteaseCrypt* neteaseCrypt, const char* outputPath) {
        try
        {
            neteaseCrypt->Dump(outputPath);
        }
        catch (const std::invalid_argument& e)
        {
            return 1;
        }
        return 0;
    }

    API void FixMetadata(NeteaseCrypt* neteaseCrypt) {
        neteaseCrypt->FixMetadata();
    }

    API void DestroyNeteaseCrypt(NeteaseCrypt* neteaseCrypt) {
        delete neteaseCrypt;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pks_dencm_NativeConverter_processFdToFd(
        JNIEnv* env,
        jobject /* this */,
        jint inputFd,
        jint outputFd) {

    try {
        NeteaseCrypt crypt(inputFd);
        crypt.Dump(outputFd);
        crypt.FixMetadata(outputFd);
        return 0;
    } catch (const std::exception& e) {
        LOGE("processFdToFd: exception: %s", e.what());
        return -1;
    } catch (...) {
        LOGE("processFdToFd: unknown exception");
        return -2;
    }
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pks_dencm_NativeConverter_getFormatFromFd(
        JNIEnv* env,
        jobject /* this */,
        jint inputFd) {

    try {
        NeteaseCrypt crypt(inputFd);
        std::string format = crypt.getFormat();
        LOGD("getFormatFromFd: detected format: %s", format.c_str());
        return env->NewStringUTF(format.c_str());
    } catch (const std::exception& e) {
        LOGE("getFormatFromFd: exception: %s", e.what());
        return nullptr;
    } catch (...) {
        LOGE("getFormatFromFd: unknown exception");
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pks_dencm_NativeConverter_DumpFileTo( // 替换为你的包名和类名
        JNIEnv* env,
        jobject /* this */,
        jstring filePathJ,
        jstring outputFolderJ) { // 这个 outputFolder 是 C++ 用来构造完整输出文件路径的目录

    const char* ncmFilePathNativeChars = env->GetStringUTFChars(filePathJ, nullptr);
    const char* outputDirNativeChars = env->GetStringUTFChars(outputFolderJ, nullptr);

    if (ncmFilePathNativeChars == nullptr || outputDirNativeChars == nullptr) {
        if (ncmFilePathNativeChars) env->ReleaseStringUTFChars(filePathJ, ncmFilePathNativeChars);
        if (outputDirNativeChars) env->ReleaseStringUTFChars(outputFolderJ, outputDirNativeChars);
        return -2; // JNI string conversion error
    }

    // 将 C 风格字符串转换为 std::filesystem::path
    fs::path inputPath = fs::u8path(ncmFilePathNativeChars); // 从 C-string 构建 path
    fs::path outputDirectoryForCpp = fs::u8path(outputDirNativeChars); // 从 C-string 构建 path

    try{
        NeteaseCrypt crypt(inputPath.u8string());
        crypt.Dump(outputDirectoryForCpp.u8string());
        crypt.FixMetadata();
        return 0;
    }catch (...){
        return -1;
    }
}