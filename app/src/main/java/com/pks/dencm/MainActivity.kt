package com.pks.dencm

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import androidx.core.net.toUri

private const val PREFS_NAME = "user_directory_prefs"

class MainActivity : AppCompatActivity() {

    companion object {
        private const val KEY_STATUS_TEXT = "statusText"
        private const val KEY_DETAILS_TEXT = "detailsText"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_MAX = "max"
        private const val KEY_IS_INDETERMINATE = "isIndeterminate"
        private const val KEY_PROGRESS_BAR_VISIBILITY = "progressBarVisibility"
        private const val KEY_BUTTON_ENABLED = "buttonEnabled"
        private const val KEY_NCM_DIR_URI = "ncmDirUri"
        private const val KEY_OUTPUT_DIR_URI = "outputDirUri"
    }

    private lateinit var buttonSelectNcmDir: Button
    private lateinit var textViewSelectedNcmDir: TextView
    private lateinit var buttonSelectOutputDir: Button
    private lateinit var textViewSelectedOutputDir: TextView
    private lateinit var buttonStartConversion: Button
    private lateinit var textViewStatus: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var textViewProgressDetails: TextView
    private lateinit var checkBoxDeleteOriginal: CheckBox
    private lateinit var checkBoxDeleteConvertedSource: CheckBox
    private lateinit var checkBoxOverwrite: CheckBox
    private lateinit var checkBoxForegroundService: CheckBox
    private lateinit var checkBoxCopyNonNcm: CheckBox

    private var selectedNcmDirUri: Uri? = null
    private var selectedOutputDirUri: Uri? = null

    private val activityJob = SupervisorJob()
    private val activityScope = CoroutineScope(Dispatchers.Main + activityJob)

    private val conversionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ConversionService.ACTION_CONVERSION_PROGRESS) {
                val status = intent.getStringExtra(ConversionService.EXTRA_STATUS) ?: ""
                val details = intent.getStringExtra(ConversionService.EXTRA_DETAILS) ?: ""
                val progress = intent.getIntExtra(ConversionService.EXTRA_PROGRESS, 0)
                val max = intent.getIntExtra(ConversionService.EXTRA_MAX, 0)
                val finished = intent.getBooleanExtra(ConversionService.EXTRA_FINISHED, false)

                if (finished) {
                    val success = intent.getIntExtra(ConversionService.EXTRA_SUCCESS_COUNT, 0)
                    val fail = intent.getIntExtra(ConversionService.EXTRA_FAIL_COUNT, 0)
                    val attempted = intent.getIntExtra(ConversionService.EXTRA_ATTEMPTED_COUNT, 0)
                    val skipped = intent.getIntExtra(ConversionService.EXTRA_SKIPPED_COUNT, 0)

                    // Call finalize on Main thread
                    activityScope.launch {
                        finalizeConversionUi(success, fail, attempted, skipped, status)
                    }
                } else {
                    // Update UI directly on Main thread since onReceive is already there
                    textViewStatus.text = status
                    textViewProgressDetails.text = details
                    progressBar.visibility = View.VISIBLE // 确保进度条可见
                    progressBar.isIndeterminate = max == 0
                    if (max > 0) {
                        progressBar.max = max
                        progressBar.progress = progress
                    }
                }
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(ConversionService.ACTION_CONVERSION_PROGRESS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(conversionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(conversionReceiver, filter)
        }
    }

    override fun onResume() {
        super.onResume()
        checkLastConversionResult()
    }

    private fun checkLastConversionResult() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val finished = prefs.getBoolean("last_conversion_finished", false)
        if (finished) {
            val success = prefs.getInt("last_success_count", 0)
            val fail = prefs.getInt("last_fail_count", 0)
            val skipped = prefs.getInt("last_skipped_count", 0)
            val attempted = prefs.getInt("last_attempted_count", 0)
            val status = prefs.getString("last_status", "处理完成") ?: "处理完成"

            // 如果当前界面还显示在扫描或正在转换，说明错过了广播，需要手动更新 UI
            if (progressBar.visibility == View.VISIBLE || textViewStatus.text == "正在启动后台服务..." || textViewStatus.text == "正在扫描文件...") {
                activityScope.launch {
                    finalizeConversionUi(success, fail, attempted, skipped, status)
                }
            }

            // 清除标记，防止重复处理
            prefs.edit().putBoolean("last_conversion_finished", false).apply()
        }
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(conversionReceiver)
    }

    private fun getLastSelectedDirectoryUri(KEY_LAST_SELECTED_DIRECTORY_URI: String, RorW: Int): Uri? {
        val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
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
                    val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE) ?: return@let
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
                    val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE) ?: return@let
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

        bindViews()
        loadPreferences()
        setupListeners()
        restoreInstanceState(savedInstanceState)
    }

    private fun bindViews() {
        buttonSelectNcmDir = findViewById(R.id.button_select_ncm_dir)
        textViewSelectedNcmDir = findViewById(R.id.textview_selected_ncm_dir)
        buttonSelectOutputDir = findViewById(R.id.button_select_output_dir)
        textViewSelectedOutputDir = findViewById(R.id.textview_selected_output_dir)
        buttonStartConversion = findViewById(R.id.button_start_conversion)
        textViewStatus = findViewById(R.id.textview_status)
        progressBar = findViewById(R.id.progress_bar)
        textViewProgressDetails = findViewById(R.id.textview_progress_details)
        checkBoxDeleteOriginal = findViewById(R.id.checkbox_delete_original)
        checkBoxDeleteConvertedSource = findViewById(R.id.checkbox_delete_converted_source)
        checkBoxOverwrite = findViewById(R.id.checkbox_overwrite)
        checkBoxForegroundService = findViewById(R.id.checkbox_foreground_service)
        checkBoxCopyNonNcm = findViewById(R.id.checkbox_copy_non_ncm)
    }

    private fun loadPreferences() {
        val sharedPref = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        // 恢复目录
        val selectedNcmDirUriCached = getLastSelectedDirectoryUri("ncmDir", 0)
        if (selectedNcmDirUriCached != null) {
            selectedNcmDirUri = selectedNcmDirUriCached
            textViewSelectedNcmDir.text = "NCM 目录: ${getDirectoryNameFromTreeUri(selectedNcmDirUriCached)}"
        }

        val selectedOutputDirUriCached = getLastSelectedDirectoryUri("outputDir", 2)
        if (selectedOutputDirUriCached != null) {
            selectedOutputDirUri = selectedOutputDirUriCached
            textViewSelectedOutputDir.text = "输出位置: ${getDirectoryNameFromTreeUri(selectedOutputDirUriCached)}"
        }

        // 恢复选项勾选状态
        checkBoxDeleteOriginal.isChecked = sharedPref.getBoolean("deleteOriginal", false)
        checkBoxDeleteConvertedSource.isChecked = sharedPref.getBoolean("deleteConvertedSource", false)
        checkBoxOverwrite.isChecked = sharedPref.getBoolean("overwrite", false)
        checkBoxForegroundService.isChecked = sharedPref.getBoolean("foregroundService", true)
        checkBoxCopyNonNcm.isChecked = sharedPref.getBoolean("copyNonNcm", false)

        // 处理联动 UI 显隐
        checkBoxDeleteConvertedSource.visibility = if (checkBoxDeleteOriginal.isChecked) View.VISIBLE else View.GONE
    }

    private fun setupListeners() {
        checkBoxDeleteOriginal.setOnCheckedChangeListener { _, isChecked ->
            checkBoxDeleteConvertedSource.visibility = if (isChecked) View.VISIBLE else View.GONE
            if (!isChecked) checkBoxDeleteConvertedSource.isChecked = false
            savePreference("deleteOriginal", isChecked)
        }
        checkBoxDeleteConvertedSource.setOnCheckedChangeListener { _, isChecked -> savePreference("deleteConvertedSource", isChecked) }
        checkBoxOverwrite.setOnCheckedChangeListener { _, isChecked -> savePreference("overwrite", isChecked) }
        checkBoxForegroundService.setOnCheckedChangeListener { _, isChecked -> savePreference("foregroundService", isChecked) }
        checkBoxCopyNonNcm.setOnCheckedChangeListener { _, isChecked -> savePreference("copyNonNcm", isChecked) }

        buttonSelectNcmDir.setOnClickListener { ncmDirPickerLauncher.launch(null) }
        buttonSelectOutputDir.setOnClickListener { outputDirPickerLauncher.launch(null) }

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

            val permissionsToRequest = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
                }
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // For Android 11 & 12, storage permission might help with FUSE stability
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE)
                }
            }

            if (permissionsToRequest.isNotEmpty()) {
                ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), 101)
                return@setOnClickListener
            }

            if (checkBoxForegroundService.isChecked) {
                checkBatteryOptimizationAndStart(ncmDirUriToProcess, outputDirUriToProcess)
            } else {
                activityScope.launch {
                    performBatchConversion(
                        ncmDirUriToProcess,
                        outputDirUriToProcess,
                        checkBoxDeleteOriginal.isChecked,
                        checkBoxOverwrite.isChecked,
                        checkBoxCopyNonNcm.isChecked,
                        checkBoxDeleteConvertedSource.isChecked
                    )
                }
            }
        }
    }

    private fun checkBatteryOptimizationAndStart(ncmDirUri: Uri, outputDirUri: Uri) {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoring = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            powerManager.isIgnoringBatteryOptimizations(packageName)
        } else {
            true
        }

        if (!isIgnoring) {
            AlertDialog.Builder(this)
                .setTitle("提示")
                .setMessage("为了确保后台转换任务不被系统中断，建议将应用的省电策略配置为“无限制”。")
                .setPositiveButton("去配置") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_SETTINGS)
                        startActivity(intent)
                    }
                    // Start anyway after user dismisses or clicks
                    startConversionService(ncmDirUri, outputDirUri)
                }
                .setNegativeButton("直接开始") { _, _ ->
                    startConversionService(ncmDirUri, outputDirUri)
                }
                .show()
        } else {
            startConversionService(ncmDirUri, outputDirUri)
        }
    }

    private fun savePreference(key: String, value: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(key, value).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STATUS_TEXT, textViewStatus.text.toString())
        outState.putString(KEY_DETAILS_TEXT, textViewProgressDetails.text.toString())
        outState.putInt(KEY_PROGRESS, progressBar.progress)
        outState.putInt(KEY_MAX, progressBar.max)
        outState.putBoolean(KEY_IS_INDETERMINATE, progressBar.isIndeterminate)
        outState.putInt(KEY_PROGRESS_BAR_VISIBILITY, progressBar.visibility)
        outState.putBoolean(KEY_BUTTON_ENABLED, buttonStartConversion.isEnabled)
        selectedNcmDirUri?.let { outState.putString(KEY_NCM_DIR_URI, it.toString()) }
        selectedOutputDirUri?.let { outState.putString(KEY_OUTPUT_DIR_URI, it.toString()) }
    }

    private fun restoreInstanceState(savedInstanceState: Bundle?) {
        savedInstanceState?.let { bundle ->
            textViewStatus.text = bundle.getString(KEY_STATUS_TEXT)
            textViewProgressDetails.text = bundle.getString(KEY_DETAILS_TEXT)
            progressBar.max = bundle.getInt(KEY_MAX, 100)
            progressBar.progress = bundle.getInt(KEY_PROGRESS, 0)
            progressBar.isIndeterminate = bundle.getBoolean(KEY_IS_INDETERMINATE, false)
            progressBar.visibility = bundle.getInt(KEY_PROGRESS_BAR_VISIBILITY, View.GONE)
            buttonStartConversion.isEnabled = bundle.getBoolean(KEY_BUTTON_ENABLED, true)

            bundle.getString(KEY_NCM_DIR_URI)?.let { selectedNcmDirUri = Uri.parse(it) }
            bundle.getString(KEY_OUTPUT_DIR_URI)?.let { selectedOutputDirUri = Uri.parse(it) }
        }
    }

    private fun startConversionService(ncmDirUri: Uri, outputDirUri: Uri) {
        val intent = Intent(this, ConversionService::class.java).apply {
            action = ConversionService.ACTION_START_CONVERSION
            putExtra(ConversionService.EXTRA_NCM_DIR_URI, ncmDirUri)
            putExtra(ConversionService.EXTRA_OUTPUT_DIR_URI, outputDirUri)
            putExtra(ConversionService.EXTRA_DELETE_ORIGINAL, checkBoxDeleteOriginal.isChecked)
            putExtra(ConversionService.EXTRA_OVERWRITE, checkBoxOverwrite.isChecked)
            putExtra(ConversionService.EXTRA_COPY_NON_NCM, checkBoxCopyNonNcm.isChecked)
            putExtra(ConversionService.EXTRA_DELETE_CONVERTED_SOURCE, checkBoxDeleteConvertedSource.isChecked)
        }
        buttonStartConversion.isEnabled = false
        progressBar.isIndeterminate = true
        progressBar.visibility = View.VISIBLE
        textViewStatus.text = "正在启动后台服务..."

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    @SuppressLint("SetTextI18n")
    private suspend fun performBatchConversion(
        ncmDirSafUri: Uri,
        outputDirSafUri: Uri,
        deleteOriginal: Boolean = false,
        overwrite: Boolean = false,
        copyNonNcm: Boolean = false,
        deleteConvertedSource: Boolean = false
    ) {
        withContext(Dispatchers.Main) {
            buttonStartConversion.isEnabled = false
            progressBar.isIndeterminate = true
            progressBar.visibility = View.VISIBLE
            textViewProgressDetails.text = "正在扫描文件..."
            textViewStatus.text = "开始批量处理..."
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

        // 优化：一次性获取输出目录下的所有文件并存入 Map，避免重复调用耗时的 findFile
        val outputFilesMap = withContext(Dispatchers.IO) {
            outputDirDocFile.listFiles()
                .filter { it.name != null }
                .associateBy { it.name!! }
        }
        val existingOutputNamesFromMap = outputFilesMap.keys

        val allDocumentFiles = withContext(Dispatchers.IO) {
            inputDirDocFile.listFiles()
        }
        val allNcmDocumentFiles = allDocumentFiles.filter {
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

        Log.d("Batch", "Found ${allNcmDocumentFiles.size} .ncm files and ${otherAudioFiles.size} other audio files.")

        if (allNcmDocumentFiles.isEmpty() && otherAudioFiles.isEmpty()) {
            updateStatusAndDetailsOnMainThread("提示：未找到可处理的文件。", "")
            finalizeConversionUi(success = 0, failed = 0, attempted = 0, skipped = 0)
            return
        }

        val ncmFilesToDelete = if (deleteConvertedSource) {
            allNcmDocumentFiles.filter { ncmDocFile ->
                val originalNcmFileName = ncmDocFile.name ?: ""
                val ncmBaseName = NativeConverter.getBaseName(originalNcmFileName) ?: ""
                // If any output file (mp3/flac/etc.) matches this basename, it's considered "converted"
                existingOutputNamesFromMap.any { it.startsWith(ncmBaseName) && it != originalNcmFileName }
            }
        } else emptyList()

        val otherAudioFilesToDelete = if (deleteConvertedSource && copyNonNcm) {
            otherAudioFiles.filter { existingOutputNamesFromMap.contains(it.name) }
        } else emptyList()

        val ncmFilesToConvert = allNcmDocumentFiles.filter { ncmDocFile ->
            if (ncmFilesToDelete.contains(ncmDocFile)) return@filter false
            if (overwrite) return@filter true

            val originalNcmFileName = ncmDocFile.name ?: ""
            val ncmBaseName = NativeConverter.getBaseName(originalNcmFileName)
            existingOutputNamesFromMap.none { it.startsWith(ncmBaseName ?: "") }
        }

        val otherFilesToCopy = otherAudioFiles.filter { docFile ->
            if (otherAudioFilesToDelete.contains(docFile)) return@filter false
            if (overwrite) return@filter true

            !existingOutputNamesFromMap.contains(docFile.name)
        }

        val totalTasks = ncmFilesToConvert.size + otherFilesToCopy.size + ncmFilesToDelete.size + otherAudioFilesToDelete.size
        val totalFiles = allNcmDocumentFiles.size + otherAudioFiles.size
        val processedCount = ncmFilesToConvert.size + otherFilesToCopy.size + ncmFilesToDelete.size + otherAudioFilesToDelete.size
        val skippedCount = totalFiles - processedCount

        if (totalTasks == 0) {
            updateStatusAndDetailsOnMainThread("提示：所有文件似乎都已处理。",
                if(skippedCount > 0) "已跳过 $skippedCount 个" else "0/$skippedCount")
            finalizeConversionUi(0,0, 0, skippedCount)
            return
        }

        withContext(Dispatchers.Main) {
            progressBar.isIndeterminate = false
            progressBar.max = totalTasks
            progressBar.progress = 0
            progressBar.visibility = View.VISIBLE
        }

        var successCount = 0
        var failCount = 0
        var currentTaskIndex = 0

        // 0. Delete already converted/copied source files
        if (deleteConvertedSource && (ncmFilesToDelete.isNotEmpty() || otherAudioFilesToDelete.isNotEmpty())) {
            val allToDelete = ncmFilesToDelete + otherAudioFilesToDelete
            for (docFile in allToDelete) {
                if (!activityScope.isActive) break
                val fileName = docFile.name ?: "Unknown"
                updateStatusAndDetailsOnMainThread("清理已处理文件 ${fileName}...", "文件 ${currentTaskIndex + 1} / $totalTasks")
                try {
                    withContext(Dispatchers.IO) { docFile.delete() }
                    successCount++
                } catch (e: Exception) {
                    Log.e("Batch", "Failed to delete $fileName", e)
                    failCount++
                } finally {
                    currentTaskIndex++
                    withContext(Dispatchers.Main) { progressBar.progress = currentTaskIndex }
                }
            }
        }

        // 1. Process NCM files
        for (ncmDocFileToProcess in ncmFilesToConvert) {
            if (!activityScope.isActive) break

            val currentFileOriginalName = ncmDocFileToProcess.name ?: "Unknown"
            updateStatusAndDetailsOnMainThread(
                "转换中 ${currentFileOriginalName}...",
                "文件 ${currentTaskIndex + 1} / $totalTasks"
            )

            try {
                val individualFileSuccess = withContext(Dispatchers.IO) {
                    processNcmViaFd(ncmDocFileToProcess.uri, currentFileOriginalName, outputDirDocFile, outputFilesMap, overwrite)
                }

                if (individualFileSuccess) {
                    successCount++
                    if (deleteOriginal) {
                        withContext(Dispatchers.IO) { ncmDocFileToProcess.delete() }
                    }
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                Log.e("BatchItem", "Error processing $currentFileOriginalName", e)
                failCount++
            } finally {
                currentTaskIndex++
                withContext(Dispatchers.Main) { progressBar.progress = currentTaskIndex }
            }
        }

        // 2. Process other audio files
        for (otherFileToCopy in otherFilesToCopy) {
            if (!activityScope.isActive) break

            val fileName = otherFileToCopy.name ?: "Unknown"
            updateStatusAndDetailsOnMainThread(
                "复制中 ${fileName}...",
                "文件 ${currentTaskIndex + 1} / $totalTasks"
            )

            try {
                val individualFileSuccess = withContext(Dispatchers.IO) {
                    copyFileSaf(otherFileToCopy, outputDirDocFile, outputFilesMap, overwrite)
                }

                if (individualFileSuccess) {
                    successCount++
                    // For non-ncm files, we don't delete original unless specifically requested?
                    // Usually "delete original" refers to NCM files. Keep it consistent.
                    if (deleteOriginal) {
                        withContext(Dispatchers.IO) { otherFileToCopy.delete() }
                    }
                } else {
                    failCount++
                }
            } catch (e: Exception) {
                Log.e("BatchItem", "Error copying $fileName", e)
                failCount++
            } finally {
                currentTaskIndex++
                withContext(Dispatchers.Main) { progressBar.progress = currentTaskIndex }
            }
        }

        finalizeConversionUi(successCount, failCount, totalTasks, skippedCount)
    }

    private suspend fun processNcmViaFd(
        ncmUri: Uri,
        ncmFileName: String, // Added parameter
        outputDirDocFile: DocumentFile,
        outputFilesMap: Map<String, DocumentFile>,
        overwrite: Boolean = false
    ): Boolean {
        var inputPfd: android.os.ParcelFileDescriptor? = null
        var outputPfd: android.os.ParcelFileDescriptor? = null
        try {
            Log.d("processNcmViaFd", "Opening input FD for $ncmUri")
            inputPfd = contentResolver.openFileDescriptor(ncmUri, "r")
                ?: throw IOException("Cannot open input FD for $ncmUri")
            
            val format = NativeConverter.getFormatFromFd(inputPfd.fd)
                ?: throw IOException("Cannot detect format for $ncmUri")
            
            val baseName = NativeConverter.getBaseName(ncmFileName)
            val targetFileName = "$baseName.$format"

            // 优化：从 Map 中直接获取文件，避免 findFile
            val existingFile = outputFilesMap[targetFileName]
            val outputFile = if (existingFile != null) {
                if (!overwrite) {
                    Log.i("processNcmViaFd", "File already exists, skipping: $targetFileName")
                    return true
                }
                Log.d("processNcmViaFd", "Using existing file for overwrite: $targetFileName")
                existingFile
            } else {
                val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(format) ?: "application/octet-stream"
                outputDirDocFile.createFile(mimeType, targetFileName)
                    ?: throw IOException("Cannot create output file $targetFileName")
            }

            Log.d("processNcmViaFd", "Opening output FD for ${outputFile.uri}")
            // Use "rwt" to allow TagLib to read the file during FixMetadata
            outputPfd = contentResolver.openFileDescriptor(outputFile.uri, "rwt")
                ?: throw IOException("Cannot open output FD for ${outputFile.uri}")
            
            val result = NativeConverter.processFdToFd(inputPfd.fd, outputPfd.fd)
            Log.d("processNcmViaFd", "Native result: $result")

            return result == 0
        } catch (e: Exception) {
            Log.e("processNcmViaFd", "Error: ${e.message}", e)
            return false
        } finally {
            inputPfd?.close()
            outputPfd?.close()
        }
    }


    @SuppressLint("SetTextI18n")
    private suspend fun finalizeConversionUi(
        success: Int,
        failed: Int,
        attempted: Int, // Make non-nullable as it's always known by now
        skipped: Int,
        customStatus: String? = null
    ) {
        withContext(Dispatchers.Main) {
            progressBar.visibility = View.GONE
            buttonStartConversion.isEnabled = true
            textViewProgressDetails.text = ""

            val attemptedStr = "尝试转换 $attempted 个文件。"
            val skippedStr = if (skipped > 0) "已跳过 $skipped 个文件。" else ""

            if (attempted == 0 && skipped == 0) { // No NCM files found at all
                textViewStatus.text = customStatus ?: "在输入目录中未找到 .ncm 文件。"
            } else if (attempted == 0 && skipped > 0) { // All NCM files found were already converted
                textViewStatus.text = customStatus ?: "所有找到的 $skipped 个 .ncm 文件都已转换。"
            } else { // Some files were attempted
                textViewStatus.text = customStatus ?: "批量转换完成！成功: $success, 失败: $failed. $attemptedStr $skippedStr"
                Toast.makeText(this@MainActivity, "完成！成功: $success, 失败: $failed. $skippedStr", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun updateStatusAndDetailsOnMainThread(status: String, details: String) {
        withContext(Dispatchers.Main) {
            textViewStatus.text = status
            textViewProgressDetails.text = details
        }
    }

    private suspend fun updateStatusOnMainThread(status: String) {
        withContext(Dispatchers.Main) {
            textViewStatus.text = status
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        activityJob.cancel()
    }

    private fun getDirectoryNameFromTreeUri(uri: Uri): String {
        return DocumentFile.fromTreeUri(this, uri)?.name ?: uri.lastPathSegment?.substringAfterLast(':') ?: "已选目录"
    }

    private suspend fun copyFileSaf(
        sourceFile: DocumentFile,
        targetDir: DocumentFile,
        outputFilesMap: Map<String, DocumentFile>,
        overwrite: Boolean
    ): Boolean {
        return try {
            val fileName = sourceFile.name ?: return false
            // 优化：从 Map 中直接获取文件，避免 findFile
            val existingFile = outputFilesMap[fileName]

            val targetFile = if (existingFile != null) {
                if (!overwrite) return true
                existingFile
            } else {
                targetDir.createFile(sourceFile.type ?: "application/octet-stream", fileName) ?: return false
            }

            contentResolver.openInputStream(sourceFile.uri)?.use { input ->
                contentResolver.openOutputStream(targetFile.uri)?.use { output ->
                    // 优化：使用更大的缓冲区提高复制速度 (128KB)
                    input.copyTo(output, bufferSize = 128 * 1024)
                }
            }
            true
        } catch (e: Exception) {
            Log.e("copyFileSaf", "Failed to copy ${sourceFile.name}", e)
            false
        }
    }
}

