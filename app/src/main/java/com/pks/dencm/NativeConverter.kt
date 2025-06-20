package com.pks.dencm // 保持你的包名

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException

object NativeConverter {
    init {
        System.loadLibrary("ncmdump") // 你的库名
    }

    external fun DumpFileTo(inputFilePath: String, outputCachePath: String): Int // 第二个参数是 cache 目录

    /**
     * Finds the actual output file in the cache directory based on the original NCM base name.
     * Assumes C++ creates a file with this base name and some non-NCM extension.
     */
    fun findActualOutputFileInCache(cacheDir: String, originalNcmFileBaseName: String): File? {
        // We expect the C++ to output a file with the same base name as the original NCM file,
        // but with a different extension (e.g., .mp3, .flac).
        // The excludedExtensions helps to not pick up the original .ncm file if it was
        // somehow named without an extension initially.
        return findFileWithBaseNameAndExcludedExtensions(
            cacheDir,
            originalNcmFileBaseName,
            setOf("ncm") // Exclude "ncm" extension to ensure we find the converted file
        )
    }

    /**
     * Gets a display name for a URI, preferring OpenableColumns.DISPLAY_NAME.
     * This version is more robust for general URIs including DocumentFile URIs.
     */
    fun getDisplayNameFromUri(context: Context, uri: Uri): String {
        var name: String? = null
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex != -1) {
                            name = cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("NativeConverter", "Error getting display name for content URI: $uri", e)
            }
        }
        // Fallback for URIs that might not be queryable or don't have display name
        if (name.isNullOrBlank()) {
            // DocumentFile.getName() can be more reliable for tree/document uris if query fails
            try {
                val docFile = androidx.documentfile.provider.DocumentFile.fromSingleUri(context, uri)
                if (docFile != null && docFile.exists()) { // Check exists for DocumentFile
                    name = docFile.name
                }
            } catch (e: Exception) {
                Log.w("NativeConverter", "Failed to get name via DocumentFile for URI: $uri", e)
            }
        }
        if (name.isNullOrBlank()) {
            name = uri.lastPathSegment // Further fallback
        }
        // If still null or blank, return a generic name.
        return if (name.isNullOrBlank()) "unknown_file_${System.currentTimeMillis()}" else name
    }


    /**
     * Extracts the base name of a file (name without the last extension).
     * e.g., "song.mp3" -> "song", "archive.tar.gz" -> "archive.tar"
     */
    fun getBaseName(fileName: String): String {
        return fileName.substringBeforeLast('.', fileName)
    }

    /**
     * Finds a file in the given directory path that matches the target base file name
     * and whose extension is NOT in the excludedExtensions set.
     * Prioritizes files with any extension over files with no extension if base names match.
     */
    @Throws(IOException::class)
    private fun findFileWithBaseNameAndExcludedExtensions(
        directoryPath: String,
        targetFileBaseName: String,
        excludedExtensions: Set<String>
    ): File? {
        val dir = File(directoryPath)

        if (!dir.exists()) {
            Log.w("FindFile", "Directory does not exist: $directoryPath")
            return null // Return null instead of throwing for batch processing
        }
        if (!dir.isDirectory) {
            Log.w("FindFile", "Path is not a directory: $directoryPath")
            return null
        }

        val files = dir.listFiles()
        if (files == null) {
            Log.w("FindFile", "Cannot read directory contents: $directoryPath (listFiles returned null)")
            return null
        }
        Log.d("FindFile", "Searching for base '$targetFileBaseName' in '$directoryPath', excluding extensions $excludedExtensions. Files found: ${files.size}")


        var bestMatch: File? = null
        var foundWithExtension = false

        for (file in files) {
            if (file.isFile) {
                val currentFileName = file.name
                val currentFileBaseName = getBaseName(currentFileName) // Use our getBaseName
                val currentFileExtension = currentFileName.substringAfterLast('.', "").lowercase()

                // Log.d("FindFile", "Checking file: ${file.name}, base: $currentFileBaseName, ext: $currentFileExtension")


                if (currentFileBaseName.equals(targetFileBaseName, ignoreCase = true)) {
                    if (!excludedExtensions.contains(currentFileExtension)) {
                        // This is a potential match
                        if (currentFileExtension.isNotEmpty()) {
                            // Prefer files with an extension if multiple matches exist
                            // This logic might need refinement if C++ can output extensionless files
                            // For now, take the first valid one found with an extension
                            Log.d("FindFile", "Found potential match with extension: ${file.name}")
                            return file // Return the first good match
                        } else if (!foundWithExtension) {
                            // If we haven't found one with an extension yet, this is a candidate
                            bestMatch = file
                            Log.d("FindFile", "Found potential match without extension: ${file.name}")
                        }
                    } else {
                        // Log.d("FindFile", "File base matched but extension '$currentFileExtension' is excluded: ${file.name}")
                    }
                }
            }
        }
        if (bestMatch != null) {
            Log.d("FindFile", "Returning best match (likely no extension or first found): ${bestMatch.name}")
        } else {
            Log.d("FindFile", "No matching file found for base '$targetFileBaseName' in '$directoryPath'")
        }
        return bestMatch // Could be null if no match found
    }
}