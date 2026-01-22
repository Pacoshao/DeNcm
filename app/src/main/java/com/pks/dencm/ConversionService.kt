package com.pks.dencm

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.webkit.MimeTypeMap
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*

class ConversionService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    companion object {
        const val CHANNEL_ID = "ConversionServiceChannel"
        const val PROGRESS_NOTIFICATION_ID = 1
        const val RESULT_NOTIFICATION_ID = 2

        const val ACTION_START_CONVERSION = "start_conversion"
        const val EXTRA_NCM_DIR_URI = "ncm_dir_uri"
        const val EXTRA_OUTPUT_DIR_URI = "output_dir_uri"
        const val EXTRA_DELETE_ORIGINAL = "delete_original"
        const val EXTRA_OVERWRITE = "overwrite"
        const val EXTRA_COPY_NON_NCM = "copy_non_ncm"
        const val EXTRA_DELETE_CONVERTED_SOURCE = "delete_converted_source"

        const val ACTION_CONVERSION_PROGRESS = "com.pks.dencm.CONVERSION_PROGRESS"
        const val EXTRA_STATUS = "status"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_MAX = "max"
        const val EXTRA_DETAILS = "details"
        const val EXTRA_FINISHED = "finished"
        const val EXTRA_SUCCESS_COUNT = "success_count"
        const val EXTRA_FAIL_COUNT = "fail_count"
        const val EXTRA_SKIPPED_COUNT = "skipped_count"
        const val EXTRA_ATTEMPTED_COUNT = "attempted_count"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_START_CONVERSION) {
            val ncmDirUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_NCM_DIR_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_NCM_DIR_URI)
            }
            val outputDirUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_OUTPUT_DIR_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(EXTRA_OUTPUT_DIR_URI)
            }
            val deleteOriginal = intent.getBooleanExtra(EXTRA_DELETE_ORIGINAL, false)
            val overwrite = intent.getBooleanExtra(EXTRA_OVERWRITE, false)
            val copyNonNcm = intent.getBooleanExtra(EXTRA_COPY_NON_NCM, false)
            val deleteConvertedSource = intent.getBooleanExtra(EXTRA_DELETE_CONVERTED_SOURCE, false)

            if (ncmDirUri != null && outputDirUri != null) {
                val notification = createNotification("正在准备转换...", 0, 0)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    startForeground(PROGRESS_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } else {
                    startForeground(PROGRESS_NOTIFICATION_ID, notification)
                }
                serviceScope.launch {
                    performBatchConversion(ncmDirUri, outputDirUri, deleteOriginal, overwrite, copyNonNcm, deleteConvertedSource)

                    // 结束后关闭原本通知 (STOP_FOREGROUND_REMOVE)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                    stopSelf()
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun performBatchConversion(
        ncmDirSafUri: Uri,
        outputDirSafUri: Uri,
        deleteOriginal: Boolean,
        overwrite: Boolean,
        copyNonNcm: Boolean = false,
        deleteConvertedSource: Boolean = false
    ) {
        broadcastProgress("正在扫描文件...", 0, 0, "")

        val inputDirDocFile = DocumentFile.fromTreeUri(this, ncmDirSafUri)
        val outputDirDocFile = DocumentFile.fromTreeUri(this, outputDirSafUri)

        if (inputDirDocFile == null || !inputDirDocFile.isDirectory || outputDirDocFile == null || !outputDirDocFile.isDirectory) {
            broadcastFinished(0, 0, 0, 0, "错误：无效的文件夹。")
            return
        }

        // 优化：缓存输出目录文件列表以加速查找 (替换耗时的 findFile)
        val outputFilesMap = outputDirDocFile.listFiles()
            .filter { it.name != null }
            .associateBy { it.name!! }
        val existingOutputNames = outputFilesMap.keys

        val allDocumentFiles = inputDirDocFile.listFiles()
        val allNcmFiles = allDocumentFiles.filter {
            it.isFile && it.name?.endsWith(".ncm", ignoreCase = true) == true
        }

        val audioExtensions = setOf("mp3", "flac", "wav", "m4a", "ogg", "ape")
        val otherAudioFiles = if (copyNonNcm) {
            allDocumentFiles.filter {
                it.isFile && it.name != null &&
                        !it.name!!.endsWith(".ncm", ignoreCase = true) &&
                        audioExtensions.contains(it.name!!.substringAfterLast('.', "").lowercase())
            }
        } else emptyList()

        if (allNcmFiles.isEmpty() && otherAudioFiles.isEmpty()) {
            broadcastFinished(0, 0, 0, 0, "提示：未找到可处理的文件。")
            return
        }

        val ncmFilesToDelete = if (deleteConvertedSource) {
            allNcmFiles.filter { ncmDocFile ->
                val originalNcmFileName = ncmDocFile.name ?: ""
                val ncmBaseName = NativeConverter.getBaseName(originalNcmFileName) ?: ""
                existingOutputNames.any { it.startsWith(ncmBaseName) && it != originalNcmFileName }
            }
        } else emptyList()

        val otherAudioFilesToDelete = if (deleteConvertedSource && copyNonNcm) {
            otherAudioFiles.filter { existingOutputNames.contains(it.name) }
        } else emptyList()

        val ncmFilesToConvert = allNcmFiles.filter { ncmDocFile ->
            if (ncmFilesToDelete.contains(ncmDocFile)) return@filter false
            if (overwrite) return@filter true

            val originalNcmFileName = ncmDocFile.name ?: ""
            val ncmBaseName = NativeConverter.getBaseName(originalNcmFileName)
            existingOutputNames.none { it.startsWith(ncmBaseName ?: "") }
        }

        val otherFilesToCopy = otherAudioFiles.filter { docFile ->
            if (otherAudioFilesToDelete.contains(docFile)) return@filter false
            if (overwrite) return@filter true

            !existingOutputNames.contains(docFile.name)
        }

        val totalTasks = ncmFilesToConvert.size + otherFilesToCopy.size + ncmFilesToDelete.size + otherAudioFilesToDelete.size
        val processedCount = ncmFilesToConvert.size + otherFilesToCopy.size + ncmFilesToDelete.size + otherAudioFilesToDelete.size
        val skippedCount = (allNcmFiles.size + otherAudioFiles.size) - processedCount

        if (totalTasks == 0) {
            broadcastFinished(0, 0, 0, skippedCount, "提示：所有文件均已处理。")
            return
        }

        var successCount = 0
        var failCount = 0
        var currentTaskIndex = 0

        // 0. Delete already converted/copied source files
        if (deleteConvertedSource && (ncmFilesToDelete.isNotEmpty() || otherAudioFilesToDelete.isNotEmpty())) {
            val allToDelete = ncmFilesToDelete + otherAudioFilesToDelete
            for (docFile in allToDelete) {
                val fileName = docFile.name ?: "Unknown"
                val progressText = "正在清理已处理文件: $fileName"
                val detailsText = "文件 ${currentTaskIndex + 1} / $totalTasks"
                updateNotification(progressText, currentTaskIndex + 1, totalTasks)
                broadcastProgress(progressText, currentTaskIndex + 1, totalTasks, detailsText)
                try {
                    if (docFile.delete()) {
                        successCount++
                    } else {
                        failCount++
                    }
                } catch (e: Exception) {
                    Log.e("ConversionService", "Failed to delete $fileName", e)
                    failCount++
                } finally {
                    currentTaskIndex++
                }
            }
        }

        // 1. Process NCM files
        for (ncmDocFile in ncmFilesToConvert) {
            val fileName = ncmDocFile.name ?: "Unknown"
            val progressText = "正在转换: $fileName"
            val detailsText = "文件 ${currentTaskIndex + 1} / $totalTasks"

            updateNotification(progressText, currentTaskIndex + 1, totalTasks)
            broadcastProgress(progressText, currentTaskIndex + 1, totalTasks, detailsText)

            try {
                val success = processNcmViaFd(ncmDocFile.uri, fileName, outputDirDocFile, outputFilesMap, overwrite)
                if (success) {
                    successCount++
                    if (deleteOriginal) {
                        ncmDocFile.delete()
                    }
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                Log.e("ConversionService", "Error converting $fileName", e)
                failCount++
            } finally {
                currentTaskIndex++
            }
        }

        // 2. Process other audio files
        for (otherFile in otherFilesToCopy) {
            val fileName = otherFile.name ?: "Unknown"
            val progressText = "正在复制: $fileName"
            val detailsText = "文件 ${currentTaskIndex + 1} / $totalTasks"

            updateNotification(progressText, currentTaskIndex + 1, totalTasks)
            broadcastProgress(progressText, currentTaskIndex + 1, totalTasks, detailsText)

            try {
                val success = copyFileSaf(otherFile, outputDirDocFile, outputFilesMap, overwrite)
                if (success) {
                    successCount++
                    if (deleteOriginal) {
                        otherFile.delete()
                    }
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                Log.e("ConversionService", "Error copying $fileName", e)
                failCount++
            } finally {
                currentTaskIndex++
            }
        }

        broadcastFinished(successCount, failCount, totalTasks, skippedCount, "处理完成！")
    }

    private fun processNcmViaFd(
        ncmUri: Uri,
        ncmFileName: String, // Added parameter
        outputDirDocFile: DocumentFile,
        outputFilesMap: Map<String, DocumentFile>,
        overwrite: Boolean
    ): Boolean {
        var inputPfd: android.os.ParcelFileDescriptor? = null
        var outputPfd: android.os.ParcelFileDescriptor? = null
        try {
            inputPfd = contentResolver.openFileDescriptor(ncmUri, "r") ?: return false
            val format = NativeConverter.getFormatFromFd(inputPfd.fd) ?: return false

            val baseName = NativeConverter.getBaseName(ncmFileName)
            val targetFileName = "$baseName.$format"

            // 优化：直接使用缓存的 Map
            val existingFile = outputFilesMap[targetFileName]
            val outputFile = if (existingFile != null) {
                if (!overwrite) {
                    return true // Should have been filtered out but just in case
                }
                existingFile
            } else {
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(format) ?: "application/octet-stream"
                outputDirDocFile.createFile(mimeType, targetFileName) ?: return false
            }

            outputPfd = contentResolver.openFileDescriptor(outputFile.uri, "rwt") ?: return false
            val result = NativeConverter.processFdToFd(inputPfd.fd, outputPfd.fd)

            return result == 0
        } catch (e: Exception) {
            Log.e("ConversionService", "Exception in processNcmViaFd", e)
            return false
        } finally {
            inputPfd?.close()
            outputPfd?.close()
        }
    }

    private fun copyFileSaf(
        sourceFile: DocumentFile,
        targetDir: DocumentFile,
        outputFilesMap: Map<String, DocumentFile>,
        overwrite: Boolean
    ): Boolean {
        return try {
            val fileName = sourceFile.name ?: return false
            // 优化：直接使用缓存的 Map
            val existingFile = outputFilesMap[fileName]

            val targetFile = if (existingFile != null) {
                if (!overwrite) return true
                existingFile
            } else {
                targetDir.createFile(sourceFile.type ?: "application/octet-stream", fileName) ?: return false
            }

            contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    // 优化：增加缓冲区大小 (128KB)
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("ConversionService", "Failed to copy ${sourceFile.name}", e)
            false
        }
    }

    private fun createNotificationChannel() {
        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "文件转换服务",
            NotificationManager.IMPORTANCE_LOW
        )
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(serviceChannel)
    }

    private fun createNotification(content: String, progress: Int, max: Int): Notification {
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("NCM 文件转换")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentIntent(pendingIntent)
            .setProgress(max, progress, max == 0)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(content: String, progress: Int, max: Int) {
        val notification = createNotification(content, progress, max)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(PROGRESS_NOTIFICATION_ID, notification)
    }

    private fun broadcastProgress(status: String, progress: Int, max: Int, details: String) {
        val intent = Intent(ACTION_CONVERSION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_PROGRESS, progress)
            putExtra(EXTRA_MAX, max)
            putExtra(EXTRA_DETAILS, details)
            putExtra(EXTRA_FINISHED, false)
        }
        sendBroadcast(intent)
    }

    private fun broadcastFinished(success: Int, fail: Int, attempted: Int, skipped: Int, status: String) {
        val intent = Intent(ACTION_CONVERSION_PROGRESS).apply {
            setPackage(packageName)
            putExtra(EXTRA_STATUS, status)
            putExtra(EXTRA_FINISHED, true)
            putExtra(EXTRA_SUCCESS_COUNT, success)
            putExtra(EXTRA_FAIL_COUNT, fail)
            putExtra(EXTRA_ATTEMPTED_COUNT, attempted)
            putExtra(EXTRA_SKIPPED_COUNT, skipped)
        }
        sendBroadcast(intent)

        // 保存状态到 SharedPreferences 以便 Activity 恢复时读取
        val prefs = getSharedPreferences("user_directory_prefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putBoolean("last_conversion_finished", true)
            putInt("last_success_count", success)
            putInt("last_fail_count", fail)
            putInt("last_skipped_count", skipped)
            putInt("last_attempted_count", attempted)
            putString("last_status", status)
            putLong("last_finish_time", System.currentTimeMillis())
            apply()
        }

        // 发出结果通知
        sendResultNotification(success, fail, skipped)
    }

    private fun sendResultNotification(success: Int, fail: Int, skipped: Int) {
        val manager = getSystemService(NotificationManager::class.java)

        val summary = "完成！成功: $success, 失败: $fail" + if (skipped > 0) ", 跳过: $skipped" else ""

        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notificationIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("转换任务已结束")
            .setContentText(summary)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true) // 用户点击后自动消失
            .setOngoing(false)   // 非持续性
            .build()

        manager.notify(RESULT_NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
    }
}