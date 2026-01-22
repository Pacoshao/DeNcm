#pragma once

#include "aes.h"
#include "cJSON.h"
#include "logger.h"

#include <iostream>
#include <fstream>
#include <memory>
#include <filesystem>

class NeteaseMusicMetadata {

private:
	std::string mAlbum;
	std::string mArtist;
	std::string mFormat;
	std::string mName;
	int mDuration;
	int mBitrate;

private:
	cJSON* mRaw;

public:
	NeteaseMusicMetadata(cJSON*);
	~NeteaseMusicMetadata();
    const std::string& name() const { return mName; }
    const std::string& album() const { return mAlbum; }
    const std::string& artist() const { return mArtist; }
    const std::string& format() const { return mFormat; }
    const int duration() const { return mDuration; }
    const int bitrate() const { return mBitrate; }

};

class NeteaseCrypt {

private:
	static const unsigned char sCoreKey[17];
	static const unsigned char sModifyKey[17];
	static const unsigned char mPng[8];
	enum NcmFormat { MP3, FLAC };

private:
	std::string mFilepath;
	std::filesystem::path mDumpFilepath;
	NcmFormat mFormat;
	std::string mImageData;
	std::unique_ptr<std::streambuf> mBuf;
	std::unique_ptr<std::istream> mStream;
	int mInputFd = -1;
	unsigned char mKeyBox[256]{};
	unsigned char mXorStream[256]{};
	NeteaseMusicMetadata* mMetaData;

private:
	bool isNcmFile();
	bool openFile(std::string const&);
	bool openFd(int fd);
	int read(char *s, std::streamsize n);
	void buildKeyBox(unsigned char *key, int keyLen);
	std::string mimeType(std::string& data);

public:
	const std::string& filepath() const { return mFilepath; }
	const std::filesystem::path dumpFilepath() const { return mDumpFilepath; }
	std::string getFormat() const { return mFormat == MP3 ? "mp3" : "flac"; }

public:
	NeteaseCrypt(std::string const&);
	NeteaseCrypt(int fd);
	~NeteaseCrypt();

private:
	void init();

public:
	void Dump(std::string const&);
	void Dump(int outputFd);
	void FixMetadata();
	void FixMetadata(int outputFd);
};