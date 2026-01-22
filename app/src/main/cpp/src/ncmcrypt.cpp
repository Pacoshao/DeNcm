#include "ncmcrypt.h"
#include "aes.h"
#include "base64.h"
#include "cJSON.h"
#include "color.h"

#define TAGLIB_STATIC
#include "taglib/toolkit/tfile.h"
#include "taglib/toolkit/tfilestream.h"
#include "taglib/mpeg/mpegfile.h"
#include "taglib/flac/flacfile.h"
#include "taglib/mpeg/id3v2/id3v2framefactory.h"
#include "taglib/mpeg/id3v2/frames/attachedpictureframe.h"
#include "taglib/mpeg/id3v2/id3v2tag.h"
#include "taglib/tag.h"

#include <stdexcept>
#include <string>
#include <filesystem>
#include <streambuf>
#include <cerrno>

#if !defined(_WIN32)
#include <unistd.h>
#endif

#pragma warning(disable:4267)
#pragma warning(disable:4244)

#if !defined(_WIN32)
class fd_streambuf : public std::streambuf {
public:
    fd_streambuf(int fd) : fd_(fd) {
        setg(buffer_, buffer_, buffer_);
    }
protected:
    int_type underflow() override {
        if (gptr() < egptr()) return traits_type::to_int_type(*gptr());

        ssize_t n = ::read(fd_, buffer_, sizeof(buffer_));
        if (n <= 0) return traits_type::eof();

        setg(buffer_, buffer_, buffer_ + n);
        return traits_type::to_int_type(*gptr());
    }

    pos_type seekoff(off_type off, std::ios_base::seekdir dir, std::ios_base::openmode) override {
        int whence;
        if (dir == std::ios_base::beg) whence = SEEK_SET;
        else if (dir == std::ios_base::cur) {
            whence = SEEK_CUR;
            // Adjust offset because the kernel file pointer is at egptr(),
            // but the logical stream pointer is at gptr().
            off -= (egptr() - gptr());
        }
        else whence = SEEK_END;

        off_t res = lseek(fd_, off, whence);
        if (res == -1) return pos_type(off_type(-1));

        setg(buffer_, buffer_, buffer_);
        return pos_type(off_type(res));
    }

    pos_type seekpos(pos_type pos, std::ios_base::openmode which) override {
        return seekoff(off_type(pos), std::ios_base::beg, which);
    }

private:
    int fd_;
    char buffer_[8192];
};
#endif

const unsigned char NeteaseCrypt::sCoreKey[17] = {0x68, 0x7A, 0x48, 0x52, 0x41, 0x6D, 0x73, 0x6F, 0x35, 0x6B, 0x49, 0x6E, 0x62, 0x61, 0x78, 0x57, 0};
const unsigned char NeteaseCrypt::sModifyKey[17] = {0x23, 0x31, 0x34, 0x6C, 0x6A, 0x6B, 0x5F, 0x21, 0x5C, 0x5D, 0x26, 0x30, 0x55, 0x3C, 0x27, 0x28, 0};

const unsigned char NeteaseCrypt::mPng[8] = {0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};

static void aesEcbDecrypt(const unsigned char *key, std::string &src, std::string &dst)
{
    int n, i;

    unsigned char out[16];

    n = src.length() >> 4;

    dst.clear();

    AES aes(key);

    for (i = 0; i < n - 1; i++)
    {
        aes.decrypt((unsigned char *)src.c_str() + (i << 4), out);
        dst += std::string((char *)out, 16);
    }

    aes.decrypt((unsigned char *)src.c_str() + (i << 4), out);
    char pad = out[15];
    if (pad > 16)
    {
        pad = 0;
    }
    dst += std::string((char *)out, 16 - pad);
}

static void replace(std::string &str, const std::string &from, const std::string &to)
{
    if (from.empty())
        return;
    size_t start_pos = 0;
    while ((start_pos = str.find(from, start_pos)) != std::string::npos)
    {
        str.replace(start_pos, from.length(), to);
        start_pos += to.length(); // In case 'to' contains 'from', like replacing 'x' with 'yx'
    }
}

NeteaseMusicMetadata::~NeteaseMusicMetadata()
{
    cJSON_Delete(mRaw);
}

NeteaseMusicMetadata::NeteaseMusicMetadata(cJSON *raw)
{
    if (!raw)
    {
        return;
    }

    cJSON *swap;
    int artistLen, i;

    mRaw = raw;

    swap = cJSON_GetObjectItem(raw, "musicName");
    if (swap)
    {
        mName = std::string(cJSON_GetStringValue(swap));
    }

    swap = cJSON_GetObjectItem(raw, "album");
    if (swap)
    {
        mAlbum = std::string(cJSON_GetStringValue(swap));
    }

    swap = cJSON_GetObjectItem(raw, "artist");
    if (swap)
    {
        artistLen = cJSON_GetArraySize(swap);

        i = 0;
        for (i = 0; i < artistLen; i++)
        {
            auto artist = cJSON_GetArrayItem(swap, i);
            if (cJSON_GetArraySize(artist) > 0)
            {
                if (!mArtist.empty())
                {
                    mArtist += "/";
                }
                mArtist += std::string(cJSON_GetStringValue(cJSON_GetArrayItem(artist, 0)));
            }
        }
    }

    swap = cJSON_GetObjectItem(raw, "bitrate");
    if (swap)
    {
        mBitrate = swap->valueint;
    }

    swap = cJSON_GetObjectItem(raw, "duration");
    if (swap)
    {
        mDuration = swap->valueint;
    }

    swap = cJSON_GetObjectItem(raw, "format");
    if (swap)
    {
        mFormat = std::string(cJSON_GetStringValue(swap));
    }
}

bool NeteaseCrypt::openFile(std::string const &path)
{
    auto file = std::make_unique<std::ifstream>(std::filesystem::u8path(path), std::ios::in | std::ios::binary);
    if (!file->is_open()) return false;
    mStream = std::move(file);
    return true;
}

bool NeteaseCrypt::openFd(int fd)
{
#if defined(_WIN32)
    return false;
#else
    LOGD("openFd: fd=%d", fd);
    int dupFd = dup(fd);
    if (dupFd == -1) {
        LOGE("openFd: dup failed, errno=%d", errno);
        return false;
    }

    mBuf = std::make_unique<fd_streambuf>(dupFd);
    mStream = std::make_unique<std::istream>(mBuf.get());
    if (!mStream) {
        mBuf.reset();
        ::close(dupFd);
        return false;
    }
    mInputFd = dupFd;
    return true;
#endif
}

bool NeteaseCrypt::isNcmFile()
{
    unsigned char header[8];
    try {
        read((char*)header, 8);
        // Original code checked for header1 == 0x4e455443 ('CTEN' in LE)
        // and header2 == 0x4d414446 ('FDAM' in LE).
        // Total bytes: 43 54 45 4e 46 44 41 4d
        if (memcmp(header, "\x43\x54\x45\x4e\x46\x44\x41\x4d", 8) != 0)
        {
            LOGE("isNcmFile: magic mismatch, first 8 bytes: %02x %02x %02x %02x %02x %02x %02x %02x",
                 header[0], header[1], header[2], header[3], header[4], header[5], header[6], header[7]);
            return false;
        }
    } catch (const std::exception& e) {
        LOGE("isNcmFile: read failed: %s", e.what());
        return false;
    }

    return true;
}

int NeteaseCrypt::read(char *s, std::streamsize n)
{
    mStream->read(s, n);
    std::streamsize gcount = mStream->gcount();

    if (gcount <= 0 && n > 0)
    {
        throw std::invalid_argument("Read failed: End of file reached");
    }

    return (int)gcount;
}

void NeteaseCrypt::buildKeyBox(unsigned char *key, int keyLen)
{
    int i;
    for (i = 0; i < 256; ++i)
    {
        mKeyBox[i] = (unsigned char)i;
    }

    unsigned char swap = 0;
    unsigned char c = 0;
    unsigned char last_byte = 0;
    unsigned char key_offset = 0;

    for (i = 0; i < 256; ++i)
    {
        swap = mKeyBox[i];
        c = ((swap + last_byte + key[key_offset++]) & 0xff);
        if (key_offset >= keyLen)
            key_offset = 0;
        mKeyBox[i] = mKeyBox[c];
        mKeyBox[c] = swap;
        last_byte = c;
    }

    // 优化：预先计算 XOR 流映射表，大幅提升 Dump 过程中的解密速度
    for (i = 0; i < 256; i++) {
        unsigned char j = (unsigned char)((i + 1) & 0xff);
        mXorStream[i] = mKeyBox[(mKeyBox[j] + mKeyBox[(mKeyBox[j] + j) & 0xff]) & 0xff];
    }
}

std::string NeteaseCrypt::mimeType(std::string &data)
{
    if (memcmp(data.c_str(), mPng, 8) == 0)
    {
        return std::string("image/png");
    }

    return std::string("image/jpeg");
}

// Helper to read exactly N bytes or throw
void readExactly(std::istream& stream, char* s, std::streamsize n) {
    stream.read(s, n);
    if (stream.gcount() != n) {
        throw std::invalid_argument("Read failed: Unexpected end of file");
    }
}

void NeteaseCrypt::init()
{
    LOGD("init: starting");

    // Safety: Always start from 0 if it's an FD, because offset is shared across dup'd FDs
    if (mInputFd != -1) {
        mStream->clear();
        mStream->seekg(0, std::ios::beg);
    }

    if (!isNcmFile())
    {
        LOGE("init: isNcmFile check failed");
        throw std::invalid_argument("Not netease protected file");
    }

    if (!mStream->seekg(2, mStream->cur))
    {
        LOGE("init: seekg(2) failed");
        throw std::invalid_argument("Can't seek file");
    }

    unsigned int n = 0;
    readExactly(*mStream, reinterpret_cast<char *>(&n), sizeof(n));

    if (n <= 0 || n > 0x1000000) // 16MB Safety Cap
    {
        LOGE("init: Invalid Key data length (%u)", n);
        throw std::invalid_argument("Invalid Key data length");
    }

    std::vector<char> keydata(n);
    readExactly(*mStream, keydata.data(), n);

    for (size_t i = 0; i < n; i++)
    {
        keydata[i] ^= 0x64;
    }

    std::string rawKeyData(keydata.begin(), keydata.end());
    std::string mKeyData;

    aesEcbDecrypt(sCoreKey, rawKeyData, mKeyData);

    buildKeyBox((unsigned char *)mKeyData.c_str() + 17, mKeyData.length() - 17);

    readExactly(*mStream, reinterpret_cast<char *>(&n), sizeof(n));

    if (n <= 0)
    {
        LOGD("init: missing metadata information");
        mMetaData = NULL;
    }
    else
    {
        if (n > 0x1000000) { // 16MB Safety Cap
            LOGE("init: Metadata too large (%u)", n);
            throw std::invalid_argument("Metadata too large");
        }
        std::vector<char> modifyData(n);
        readExactly(*mStream, modifyData.data(), n);

        for (size_t i = 0; i < n; i++)
        {
            modifyData[i] ^= 0x63;
        }

        std::string swapModifyData;
        std::string modifyOutData;
        std::string modifyDecryptData;

        if (modifyData.size() > 22) {
            swapModifyData = std::string(modifyData.begin() + 22, modifyData.end());
            Base64::Decode(swapModifyData, modifyOutData);
            aesEcbDecrypt(sModifyKey, modifyOutData, modifyDecryptData);
            if (modifyDecryptData.length() > 6) {
                modifyDecryptData = std::string(modifyDecryptData.begin() + 6, modifyDecryptData.end());
                mMetaData = new NeteaseMusicMetadata(cJSON_Parse(modifyDecryptData.c_str()));
                if (mMetaData && !mMetaData->name().empty()) {
                    LOGD("init: metadata parsed, name=%s", mMetaData->name().c_str());
                }
            }
        }
    }

    // skip crc32 & image version
    if (!mStream->seekg(5, mStream->cur))
    {
        LOGE("init: seekg(5) failed");
        throw std::invalid_argument("can't seek file");
    }

    uint32_t cover_frame_len{0};
    readExactly(*mStream, reinterpret_cast<char *>(&cover_frame_len), 4);
    readExactly(*mStream, reinterpret_cast<char *>(&n), sizeof(n));

    if (n > 0)
    {
        if (n > 0x2000000) { // 32MB Safety Cap for images
            LOGE("init: image too large (%u)", n);
            throw std::invalid_argument("Image too large");
        }
        mImageData = std::string(n, '\0');
        readExactly(*mStream, &mImageData[0], n);
    }
    mStream->seekg(cover_frame_len - n, mStream->cur);

    // Identify format by peeking
    auto currentPos = mStream->tellg();
    char peekBuffer[4];
    mStream->read(peekBuffer, 4);
    if (mStream->gcount() >= 4) {
        for (int i = 0; i < 4; i++) {
            peekBuffer[i] ^= mXorStream[i & 0xff];
        }
        if (peekBuffer[0] == 0x49 && peekBuffer[1] == 0x44 && peekBuffer[2] == 0x33) {
            mFormat = MP3;
            LOGD("init: format identified as MP3");
        } else {
            mFormat = FLAC;
            LOGD("init: format identified as FLAC");
        }
    }
    mStream->clear();
    mStream->seekg(currentPos);
}

NeteaseCrypt::NeteaseCrypt(std::string const &path)
{
    LOGD("NeteaseCrypt: path=%s", path.c_str());
    if (!openFile(path))
    {
        throw std::invalid_argument("Can't open file");
    }
    mFilepath = path;
    init();
}

NeteaseCrypt::NeteaseCrypt(int fd)
{
    LOGD("NeteaseCrypt: fd=%d", fd);
    if (!openFd(fd))
    {
        LOGE("NeteaseCrypt: openFd failed");
        throw std::invalid_argument("Can't open fd");
    }
    init();
}

void NeteaseCrypt::FixMetadata()
{
    FixMetadata(-1);
}

void NeteaseCrypt::FixMetadata(int outputFd)
{
    TagLib::File *audioFile = nullptr;
    TagLib::Tag *tag = nullptr;
    TagLib::ByteVector vector(mImageData.c_str(), mImageData.length());

    TagLib::IOStream *stream = nullptr;
    if (outputFd != -1) {
#ifndef _WIN32
        // We need to dup the fd because FileStream will close it via fdopen/fclose
        int dupFd = dup(outputFd);
        if (dupFd != -1) {
            // Seek to beginning because offset is shared and Dump left it at the end
            lseek(dupFd, 0, SEEK_SET);
            stream = new TagLib::FileStream(dupFd, false);
            if (!stream->isOpen()) {
                delete stream;
                stream = nullptr;
            }
        }
#endif
    }

    if (mFormat == NeteaseCrypt::MP3)
    {
        if (stream) {
            audioFile = new TagLib::MPEG::File(stream, TagLib::ID3v2::FrameFactory::instance());
        } else if (!mDumpFilepath.empty()) {
            audioFile = new TagLib::MPEG::File(mDumpFilepath.u8string().c_str());
        }

        if (audioFile) {
            tag = dynamic_cast<TagLib::MPEG::File *>(audioFile)->ID3v2Tag(true);

            if (!mImageData.empty())
            {
                TagLib::ID3v2::AttachedPictureFrame *frame = new TagLib::ID3v2::AttachedPictureFrame;

                frame->setMimeType(mimeType(mImageData));
                frame->setPicture(vector);

                dynamic_cast<TagLib::ID3v2::Tag *>(tag)->addFrame(frame);
            }
        }
    }
    else if (mFormat == NeteaseCrypt::FLAC)
    {
        if (stream) {
            audioFile = new TagLib::FLAC::File(stream, TagLib::ID3v2::FrameFactory::instance());
        } else if (!mDumpFilepath.empty()) {
            audioFile = new TagLib::FLAC::File(mDumpFilepath.u8string().c_str());
        }

        if (audioFile) {
            tag = audioFile->tag();

            if (!mImageData.empty())
            {
                TagLib::FLAC::Picture *cover = new TagLib::FLAC::Picture;
                cover->setMimeType(mimeType(mImageData));
                cover->setType(TagLib::FLAC::Picture::FrontCover);
                cover->setData(vector);

                dynamic_cast<TagLib::FLAC::File *>(audioFile)->addPicture(cover);
            }
        }
    }

    if (audioFile && tag && mMetaData != NULL)
    {
        tag->setTitle(TagLib::String(mMetaData->name(), TagLib::String::UTF8));
        tag->setArtist(TagLib::String(mMetaData->artist(), TagLib::String::UTF8));
        tag->setAlbum(TagLib::String(mMetaData->album(), TagLib::String::UTF8));
    }

    // tag->setComment(TagLib::String("Create by taurusxin/ncmdump.", TagLib::String::UTF8));

    if (audioFile) {
        audioFile->save();
        delete audioFile;
    }
    if (stream) {
        delete stream;
    }
}

void NeteaseCrypt::Dump(std::string const &outputDir)
{
    if (outputDir.empty())
    {
        mDumpFilepath = std::filesystem::u8path(mFilepath);
    } else {
        mDumpFilepath = std::filesystem::u8path(outputDir) / std::filesystem::u8path(mFilepath).filename();
    }

    std::vector<unsigned char> buffer(0x20000);
    std::ofstream output;

    while (true)
    {
        mStream->read((char*)buffer.data(), buffer.size());
        std::streamsize n = mStream->gcount();
        if (n <= 0) break;

        for (int i = 0; i < n; i++)
        {
            buffer[i] ^= mXorStream[i & 0xff];
        }

        if (!output.is_open())
        {
            if (buffer[0] == 0x49 && buffer[1] == 0x44 && buffer[2] == 0x33)
            {
                mDumpFilepath = mDumpFilepath.replace_extension("mp3");
                mFormat = NeteaseCrypt::MP3;
            }
            else
            {
                mDumpFilepath = mDumpFilepath.replace_extension("flac");
                mFormat = NeteaseCrypt::FLAC;
            }

            output.open(mDumpFilepath, std::ofstream::out | std::ofstream::binary);
        }

        output.write((char *)buffer.data(), n);
        if (mStream->eof()) break;
    }

    output.flush();
    output.close();
}

void NeteaseCrypt::Dump(int outputFd)
{
    // 优化：增加缓冲区大小至 128KB (0x20000)，减少系统调用次数
    std::vector<unsigned char> buffer(0x20000);

#if defined(_WIN32)
    throw std::invalid_argument("Not supported on Windows");
#else
    LOGD("Dump: outputFd=%d", outputFd);
    while (true)
    {
        mStream->read((char*)buffer.data(), buffer.size());
        std::streamsize n = mStream->gcount();
        if (n <= 0) break;

        // 优化：使用预计算的映射表进行快速 XOR 解密
        for (int i = 0; i < n; i++)
        {
            buffer[i] ^= mXorStream[i & 0xff];
        }

        ssize_t written = ::write(outputFd, buffer.data(), n);
        if (written != n) {
            LOGE("Dump: write failed, written=%zd, expected=%d, errno=%d", written, (int)n, errno);
            throw std::runtime_error("Write failed");
        }

        if (mStream->eof()) break;
    }
    LOGD("Dump: finished");
#endif
}

NeteaseCrypt::~NeteaseCrypt()
{
    if (mMetaData != NULL) {
        delete mMetaData;
    }

    mStream.reset();
    mBuf.reset();
#if !defined(_WIN32)
    if (mInputFd != -1) {
        LOGD("~NeteaseCrypt: closing mInputFd=%d", mInputFd);
        ::close(mInputFd);
    }
#endif
}

