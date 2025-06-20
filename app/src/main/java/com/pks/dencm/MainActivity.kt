package com.pks.dencm

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.net.toUri

private const val PREFS_NAME = "user_directory_prefs"

class MainActivity : AppCompatActivity() {

    private lateinit var buttonSelectNcmDir: Button
    private lateinit var textViewSelectedNcmDir: TextView
    private lateinit var buttonSelectOutputDir: Button
    private lateinit var textViewSelectedOutputDir: TextView
    private lateinit var buttonStartConversion: Button
    private lateinit var textViewStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewProgressDetails: TextView

    private var selectedNcmDirUri: Uri? = null
    private var selectedOutputDirUri: Uri? = null

    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private fun getLastSelectedDirectoryUri(KEY_LAST_SELECTED_DIRECTORY_URI: String, RorW: Int): Uri? {
        val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val uriString = sharedPref.getString(KEY_LAST_SELECTED_DIRECTORY_URI, null)
        return if (uriString != null) {
            try {
                val storedUri = uriString.toUri()
                val persistedPermissions = contentResolver.persistedUriPermissions
                /*Log.d("DirectoryPrefs", "Current persisted URI permissions: ${persistedPermissions.size} items")
                persistedPermissions.forEachIndexed { index, perm ->
                    Log.d("DirectoryPrefs", "  [$index] URI: ${perm.uri}, isRead: ${perm.isReadPermission}, isWrite: ${perm.isWritePermission}, persistedTime: ${perm.persistedTime}")
                }*/

                // 找到对应的权限条目
                val specificPermission = persistedPermissions.find { it.uri == storedUri }

                if (specificPermission != null){
                    when(RorW){
                        0-> {
                            if (specificPermission.isReadPermission) {
                                Log.i("DirectoryPrefs", "Loaded directory URI with R permission: $uriString")
                                storedUri
                            } else {
                                Log.w("DirectoryPrefs", "Stored URI permission for $storedUri is INSUFFICIENT (isRead: ${specificPermission.isReadPermission}, isWrite: ${specificPermission.isWritePermission}).")
                                null
                            }
                        }
                        1-> {
                            if (specificPermission.isReadPermission) {
                                Log.i("DirectoryPrefs", "Loaded directory URI with W permission: $uriString")
                                storedUri
                            } else {
                                Log.w("DirectoryPrefs", "Stored URI permission for $storedUri is INSUFFICIENT (isRead: ${specificPermission.isReadPermission}, isWrite: ${specificPermission.isWritePermission}).")
                                null
                            }
                        }
                        else -> {
                            if (specificPermission.isReadPermission && specificPermission.isWritePermission) {
                                Log.i("DirectoryPrefs", "Loaded directory URI with R&W permission: $uriString")
                                storedUri
                            } else {
                                Log.w("DirectoryPrefs", "Stored URI permission for $storedUri is INSUFFICIENT (isRead: ${specificPermission.isReadPermission}, isWrite: ${specificPermission.isWritePermission}).")
                                null
                            }
                        }
                    }
                }else{
                    Log.w("DirectoryPrefs", "Stored URI permission for $storedUri is MISSING. Clearing and requesting re-selection.")
                    null
                }
            } catch (e: Exception) {
                Log.e("DirectoryPrefs", "Error parsing or checking stored URI: $uriString", e)
                null
            }
        } else {
            null
        }
    }

    @SuppressLint("SetTextI18n")
    private val ncmDirPickerLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                selectedNcmDirUri = it
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(it, takeFlags)
                    textViewSelectedNcmDir.text = "NCM 目录: ${getDirectoryNameFromTreeUri(it)}"
                    val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return@let
                    with(sharedPref.edit()) {
                        putString("ncmDir", selectedNcmDirUri.toString())
                        if(commit()){
                            Log.d("DirectoryPrefs", "Ncm directory stored.")
                        }else{
                            Log.d("DirectoryPrefs", "Fail to store ncm directory.")
                        }
                    }
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Cannot persist NCM dir permission", e)
                    selectedNcmDirUri = null
                    textViewSelectedNcmDir.text = "未选 NCM 目录 (权限问题)"
                    Toast.makeText(this, "无法获取NCM目录读取权限", Toast.LENGTH_SHORT).show()
                }
            }
        }

    @SuppressLint("SetTextI18n")
    private val outputDirPickerLauncher: ActivityResultLauncher<Uri?> =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            uri?.let {
                selectedOutputDirUri = it
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(it, takeFlags)
                    textViewSelectedOutputDir.text = "输出位置: ${getDirectoryNameFromTreeUri(it)}"
                    val sharedPref = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return@let
                    with(sharedPref.edit()) {
                        putString("outputDir", selectedOutputDirUri.toString())
                        if(commit()){
                            Log.d("DirectoryPrefs", "Output directory stored.")
                        }else{
                            Log.d("DirectoryPrefs", "Fail to store output directory.")
                        }
                    }
                    Log.d("MainActivity", "Output directory stored.")
                } catch (e: SecurityException) {
                    Log.e("MainActivity", "Cannot persist output dir permission", e)
                    selectedOutputDirUri = null
                    textViewSelectedOutputDir.text = "未选输出位置 (权限问题)"
                    Toast.makeText(this, "无法获取输出目录权限", Toast.LENGTH_SHORT).show()
                }
            }
        }

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        buttonSelectNcmDir = findViewById(R.id.button_select_ncm_dir)
        textViewSelectedNcmDir = findViewById(R.id.textview_selected_ncm_dir)
        buttonSelectOutputDir = findViewById(R.id.button_select_output_dir)
        textViewSelectedOutputDir = findViewById(R.id.textview_selected_output_dir)
        buttonStartConversion = findViewById(R.id.button_start_conversion)
        textViewStatus = findViewById(R.id.textview_status)
        progressBar = findViewById(R.id.progress_bar)
        textViewProgressDetails = findViewById(R.id.textview_progress_details)

        val selectedNcmDirUri = getLastSelectedDirectoryUri("ncmDir",0)
        if (selectedNcmDirUri != null) {
            textViewSelectedNcmDir.text = "NCM 目录: ${getDirectoryNameFromTreeUri(selectedNcmDirUri)}"
        } else {
            Log.d("MainActivity", "No last used ncm directory found or permission lost.")
        }

        val selectedOutputDirUri = getLastSelectedDirectoryUri("outputDir",2)
        if (selectedOutputDirUri != null) {
            textViewSelectedOutputDir.text = "输出位置: ${getDirectoryNameFromTreeUri(selectedOutputDirUri)}"
        } else {
            Log.d("MainActivity", "No last used output directory found or permission lost.")
        }

        buttonSelectNcmDir.setOnClickListener {
            ncmDirPickerLauncher.launch(null)
        }

        buttonSelectOutputDir.setOnClickListener {
            outputDirPickerLauncher.launch(null)
        }

        buttonStartConversion.setOnClickListener {
            val ncmDirUriToProcess = selectedNcmDirUri
            val outputDirUriToProcess = selectedOutputDirUri

            if (ncmDirUriToProcess == null) {
                Toast.makeText(this, "请先选择NCM文件所在的目录", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (outputDirUriToProcess == null) {
                Toast.makeText(this, "请先选择输出位置", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            activityScope.launch {
                performBatchConversion(ncmDirUriToProcess, outputDirUriToProcess)
            }
        }
    }

    private fun getDirectoryNameFromTreeUri(uri: Uri): String {
        return DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "已选目录"
    }


    @SuppressLint("SetTextI18n")
    private suspend fun performBatchConversion(ncmDirSafUri: Uri, outputDirSafUri: Uri) {
        withContext(Dispatchers.Main) {
            buttonStartConversion.isEnabled = false
            progressBar.isIndeterminate = true
            progressBar.visibility = View.VISIBLE
            textViewProgressDetails.text = "正在扫描文件..."
            textViewStatus.text = "状态：开始批量转换..."
        }

        val inputDirDocFile = DocumentFile.fromTreeUri(this, ncmDirSafUri)
        val outputDirDocFile = DocumentFile.fromTreeUri(this, outputDirSafUri)

        if (inputDirDocFile == null || !inputDirDocFile.isDirectory) {
            updateStatusAndDetailsOnMainThread("错误：无效的NCM输入文件夹。", "")
            finalizeConversionUi(success = 0, failed = 0, attempted = 0, skipped = 0)
            return
        }
        if (outputDirDocFile == null || !outputDirDocFile.isDirectory) {
            updateStatusAndDetailsOnMainThread("错误：无效的输出文件夹。", "")
            finalizeConversionUi(success = 0, failed = 0, attempted = 0, skipped = 0)
            return
        }

        val allNcmDocumentFiles = withContext(Dispatchers.IO) {
            inputDirDocFile.listFiles().filter {
                it.isFile && it.name?.endsWith(".ncm", ignoreCase = true) == true
            }
        }
        Log.d("Batch", "Found ${allNcmDocumentFiles.size} .ncm files in input directory.")

        if (allNcmDocumentFiles.isEmpty()) {
            updateStatusAndDetailsOnMainThread("提示：在选定NCM文件夹中未找到 .ncm 文件。", "")
            finalizeConversionUi(success = 0, failed = 0, attempted = 0, skipped = 0)
            return
        }

        val existingOutputBaseNames = withContext(Dispatchers.IO) {
            outputDirDocFile.listFiles()
                .filter { it.isFile && it.name != null }
                .mapNotNull { NativeConverter.getBaseName(it.name!!) }
                .toSet()
        }
        Log.d("Batch", "Found ${existingOutputBaseNames.size} existing output base names: $existingOutputBaseNames")

        val ncmFilesToConvert = mutableListOf<DocumentFile>()
        for (ncmDocFile in allNcmDocumentFiles) {
            val originalNcmFileName = NativeConverter.getDisplayNameFromUri(this, ncmDocFile.uri)
            val ncmBaseName = NativeConverter.getBaseName(originalNcmFileName)
            if (!existingOutputBaseNames.contains(ncmBaseName)) {
                ncmFilesToConvert.add(ncmDocFile)
            } else {
                Log.i("Batch", "Skipping already converted (basename match): $originalNcmFileName (base: $ncmBaseName)")
            }
        }

        val totalFilesToActuallyConvert = ncmFilesToConvert.size
        Log.d("Batch", "$totalFilesToActuallyConvert files need conversion.")

        if (totalFilesToActuallyConvert == 0) {
            val skippedCount = allNcmDocumentFiles.size
            updateStatusAndDetailsOnMainThread("提示：所有找到的 .ncm 文件似乎都已转换。",
                if(skippedCount > 0) "已跳过 $skippedCount 个" else "0/$skippedCount")
            finalizeConversionUi(0,0, 0, skippedCount)
            return
        }

        withContext(Dispatchers.Main) {
            progressBar.isIndeterminate = false
            progressBar.max = totalFilesToActuallyConvert
            progressBar.progress = 0
        }

        var successCount = 0
        var failCount = 0

        for ((index, ncmDocFileToProcess) in ncmFilesToConvert.withIndex()) {
            if (!activityScope.isActive) {
                Log.w("Batch", "Conversion job cancelled or inactive. Stopping batch.")
                break
            }

            val currentFileOriginalName = NativeConverter.getDisplayNameFromUri(this, ncmDocFileToProcess.uri)
            updateStatusAndDetailsOnMainThread(
                "状态：转换中 ${currentFileOriginalName}...",
                "文件 ${index + 1} / $totalFilesToActuallyConvert"
            )

            var tempNcmInputFileForThisLoop: File? = null
            var actualCppOutputFileInCacheForThisLoop: File? = null
            var individualFileSuccess = false

            try {
                val tempInputFileNameInCache = "${NativeConverter.getBaseName(currentFileOriginalName)}.ncm"
                tempNcmInputFileForThisLoop = copyUriToAppCache(ncmDocFileToProcess.uri, tempInputFileNameInCache)

                Log.d("BatchItem", "Temp input for $currentFileOriginalName: ${tempNcmInputFileForThisLoop.absolutePath}")

                val nativeResult = withContext(Dispatchers.Default) {
                    NativeConverter.DumpFileTo(
                        tempNcmInputFileForThisLoop.absolutePath,
                        cacheDir.absolutePath
                    )
                }

                if (nativeResult == 0) {
                    Log.d("BatchItem", "JNI success for $currentFileOriginalName")
                    actualCppOutputFileInCacheForThisLoop = NativeConverter.findActualOutputFileInCache(
                        cacheDir.absolutePath,
                        NativeConverter.getBaseName(currentFileOriginalName)
                    )

                    if (actualCppOutputFileInCacheForThisLoop == null || !actualCppOutputFileInCacheForThisLoop.exists() || actualCppOutputFileInCacheForThisLoop.length() == 0L) {
                        Log.e("BatchItem", "C++ success, but output file not found/empty for $currentFileOriginalName (Base: ${NativeConverter.getBaseName(currentFileOriginalName)})")
                        failCount++
                    } else {
                        Log.d("BatchItem", "Actual C++ output for $currentFileOriginalName: ${actualCppOutputFileInCacheForThisLoop.absolutePath}")
                        val finalOutputFileNameForSAF = NativeConverter.getBaseName(currentFileOriginalName) +
                                "." + actualCppOutputFileInCacheForThisLoop.extension
                        Log.d("BatchItem", "Final SAF name for $currentFileOriginalName: $finalOutputFileNameForSAF")

                        val successCopy = copyFileFromCacheToSAF(
                            actualCppOutputFileInCacheForThisLoop,
                            outputDirSafUri,
                            finalOutputFileNameForSAF
                        )
                        if (successCopy) {
                            Log.i("BatchItem", "Successfully converted and saved: $finalOutputFileNameForSAF")
                            successCount++
                            individualFileSuccess = true
                        } else {
                            Log.e("BatchItem", "Failed to save to SAF: $finalOutputFileNameForSAF")
                            failCount++
                        }
                    }
                } else {
                    Log.e("BatchItem", "JNI conversion failed for $currentFileOriginalName (code: $nativeResult)")
                    failCount++
                }
            } catch (e: CancellationException) {
                Log.w("BatchItem", "Conversion of $currentFileOriginalName cancelled.", e)
                //failCount++
                throw e
            } catch (e: IOException) { // 特别捕获 IOException 从 copyUriToAppCache
                Log.e("BatchItem", "IOException during processing (likely copyToCache) for $currentFileOriginalName: ${e.message}", e)
                updateStatusOnMainThread("文件 ${currentFileOriginalName} 复制失败: ${e.message}")
                delay(1000)
                failCount++
            } catch (e: Exception) { // 捕获其他所有预料之外的异常
                Log.e("BatchItem", "Generic error processing $currentFileOriginalName", e)
                updateStatusOnMainThread("文件 ${currentFileOriginalName} 处理失败: ${e.message}")
                delay(1000)
                failCount++
            } finally {
                tempNcmInputFileForThisLoop?.delete()
                if (individualFileSuccess) {
                    actualCppOutputFileInCacheForThisLoop?.delete()
                } else if (actualCppOutputFileInCacheForThisLoop != null && actualCppOutputFileInCacheForThisLoop.exists()){
                    Log.w("BatchItem", "Deleting failed/uncopied C++ output from cache: ${actualCppOutputFileInCacheForThisLoop.name}")
                    actualCppOutputFileInCacheForThisLoop.delete()
                }
                Log.d("BatchItem", "Cleaned cache for $currentFileOriginalName")
                withContext(Dispatchers.Main) {
                    progressBar.progress = index + 1
                }
            }
        } // End of for loop

        finalizeConversionUi(successCount, failCount, totalFilesToActuallyConvert, allNcmDocumentFiles.size - totalFilesToActuallyConvert)
    }


    @SuppressLint("SetTextI18n")
    private suspend fun finalizeConversionUi(
        success: Int,
        failed: Int,
        attempted: Int, // Make non-nullable as it's always known by now
        skipped: Int
    ) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            buttonStartConversion.isEnabled = true
            textViewProgressDetails.text = ""

            val attemptedStr = "尝试转换 $attempted 个文件。"
            val skippedStr = if (skipped > 0) "已跳过 $skipped 个文件。" else ""

            if (attempted == 0 && skipped == 0) { // No NCM files found at all
                textViewStatus.text = "状态：在输入目录中未找到 .ncm 文件。"
            } else if (attempted == 0 && skipped > 0) { // All NCM files found were already converted
                textViewStatus.text = "状态：所有找到的 $skipped 个 .ncm 文件都已转换。"
            } else { // Some files were attempted
                textViewStatus.text = "批量转换完成！成功: $success, 失败: $failed. $attemptedStr $skippedStr"
                Toast.makeText(this@MainActivity, "完成！成功: $success, 失败: $failed. $skippedStr", Toast.LENGTH_LONG).show()
            }
        }
    }


    @Throws(IOException::class) // 保持声明，因为现在确实会抛出
    private fun copyUriToAppCache(uri: Uri, targetCacheFileName: String): File { // 返回类型变为 File (非可空)
        if (!cacheDir.exists()) {
            cacheDir.mkdirs()
        }
        val file = File(cacheDir, targetCacheFileName)
        if (file.exists()) {
            Log.w("copyUriToAppCache", "Temp file $targetCacheFileName already exists, overwriting.")
            if (!file.delete()) { // 尝试删除，如果失败则抛异常
                throw IOException("Failed to delete existing temp file: ${file.absolutePath}")
            }
        }
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            } ?: throw IOException("Cannot open input stream for $uri") // 如果 openInputStream 返回 null

            // 检查文件是否真的创建成功并且有内容
            if (!file.exists() || file.length() == 0L) {
                // 尝试删除可能创建的空文件
                file.delete()
                throw IOException("Failed to copy URI to cache or resulting file is empty: ${file.absolutePath}")
            }
            return file // 成功时返回 File 对象
        } catch (e: Exception) { // 捕获更广泛的异常以防万一
            Log.e("copyUriToAppCache", "Error copying URI to cache: ${file.absolutePath}", e)
            file.delete() // 尝试清理部分写入的文件
            if (e is IOException) throw e // 如果已经是 IOException，重新抛出
            throw IOException("Error during URI copy to cache: ${e.message}", e) // 包装其他异常
        }
    }

    @Throws(IOException::class)
    private fun copyFileFromCacheToSAF(
        sourceCacheFile: File,
        targetSAFDirUri: Uri,
        targetFileNameInSAF: String
    ): Boolean {
        val parentDocumentFile = DocumentFile.fromTreeUri(this, targetSAFDirUri)
            ?: throw IOException("Cannot access SAF directory Uri: $targetSAFDirUri")

        if (!parentDocumentFile.isDirectory) throw IOException("SAF Uri is not a directory: $targetSAFDirUri")
        if (!parentDocumentFile.canWrite()) throw IOException("Cannot write to SAF directory: $targetSAFDirUri")

        var outputFileInSAF = parentDocumentFile.findFile(targetFileNameInSAF)
        if (outputFileInSAF != null && outputFileInSAF.exists()) {
            Log.w("ConverterSAF", "Target file $targetFileNameInSAF already exists in SAF. Overwriting...")
            if (!outputFileInSAF.delete()) {
                // If deletion fails, we'll try to create anyway. createFile might fail if the file
                // is locked or if the system doesn't allow overwriting this way.
                Log.e("ConverterSAF", "Failed to delete existing SAF file: ${outputFileInSAF.uri}. Will attempt to create/overwrite.")
                // No need to set outputFileInSAF to null, createFile will handle it.
            }
        }

        val extension = targetFileNameInSAF.substringAfterLast('.', "").lowercase()
        val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
        Log.d("ConverterSAF", "Attempting to create/write SAF file: name='$targetFileNameInSAF', mimeType='$mimeType' in dir ${parentDocumentFile.uri}")

        // Attempt to create the file. If it exists and wasn't deleted,
        // DocumentFile.createFile should ideally handle overwriting or return the existing one if names match.
        // However, behavior can vary. For safety, deletion was attempted first.
        outputFileInSAF = parentDocumentFile.createFile(mimeType, targetFileNameInSAF)
            ?: throw IOException("Cannot create file '$targetFileNameInSAF' in SAF directory (URI: ${parentDocumentFile.uri})")

        Log.d("ConverterSAF", "Successfully created/obtained SAF DocumentFile: ${outputFileInSAF.uri}, Name: ${outputFileInSAF.name}")

        try {
            contentResolver.openOutputStream(outputFileInSAF.uri)?.use { outputStreamToSAF ->
                sourceCacheFile.inputStream().use { inputStreamFromCache ->
                    inputStreamFromCache.copyTo(outputStreamToSAF)
                }
            } ?: throw IOException("Cannot open OutputStream to SAF file: ${outputFileInSAF.uri}")
            Log.d("ConverterSAF", "Successfully copied data to SAF file: ${outputFileInSAF.uri}")
            return true
        } catch (e: Exception) {
            Log.e("ConverterSAF", "Error during copy to SAF: ${outputFileInSAF.uri}", e)
            try {
                if (outputFileInSAF.exists() && outputFileInSAF.length() == 0L) { // Delete if empty on error
                    Log.w("ConverterSAF", "Copy to SAF failed, deleting empty target file: ${outputFileInSAF.uri}")
                    outputFileInSAF.delete()
                }
            } catch (delEx: Exception) {
                Log.w("ConverterSAF", "Failed to delete partially written/empty SAF file after error: ${outputFileInSAF.uri}", delEx)
            }
            throw e
        }
    }

    private suspend fun updateStatusAndDetailsOnMainThread(status: String, details: String) {
        withContext(Dispatchers.Main) {
            textViewStatus.text = status
            textViewProgressDetails.text = details
        }
    }

    // Overload for just status
    private suspend fun updateStatusOnMainThread(status: String) {
        withContext(Dispatchers.Main) {
            textViewStatus.text = status
        }
    }

    // You had showToastOnMainThread, which is fine, but for batch, less frequent toasts are better.
    // The finalizeConversionUi function now shows a summary toast.
    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel() // Cancel all coroutines when the activity is destroyed
    }
}