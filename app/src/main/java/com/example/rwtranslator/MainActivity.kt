package com.example.rwtranslator

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.provider.OpenableColumns
import android.view.View
import android.provider.Settings
import android.widget.*
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.appcompat.app.AlertDialog

class MainActivity : AppCompatActivity() {
    private lateinit var sourceLanguageSpinner: Spinner
    private lateinit var targetLanguageSpinner: Spinner
    private lateinit var filePathTextView: TextView
    private lateinit var selectFileButton: Button
    private lateinit var translateButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var statusTextView: TextView
    private lateinit var modeRadioGroup: RadioGroup
    private lateinit var progressTextView: TextView

    private val fileProcessor = FileProcessor(Translator())
    private var selectedFileUri: Uri? = null

    private val permissionsToRequest = mutableListOf(
        Manifest.permission.INTERNET,
        Manifest.permission.READ_EXTERNAL_STORAGE,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        handlePermissionsResult(permissions)
    }

    private val manageExternalStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(this, "All Permissions Granted", Toast.LENGTH_SHORT).show()
            } else {
                showPermissionExplanationDialog()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeViews()
        setupListeners()
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val permissionsNeeded = mutableListOf<String>()

        // Check permissions based on Android version
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For Android 11+ check if MANAGE_EXTERNAL_STORAGE is required
            if (!Environment.isExternalStorageManager()) {
                permissionsNeeded.add(Manifest.permission.MANAGE_EXTERNAL_STORAGE)
            }
        } else {
            // For Android 10 and below, check regular storage permissions
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }

        // If any permissions are needed, request them
        if (permissionsNeeded.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsNeeded.toTypedArray())
        }
    }

    private fun areAllPermissionsGranted(): Boolean {
        val basicPermissionsGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        val externalStoragePermissionGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }

        return basicPermissionsGranted && externalStoragePermissionGranted
    }

    private fun showPermissionExplanationDialog() {
        AlertDialog.Builder(this)
            .setTitle("Permissions Required")
            .setMessage("To function correctly, the app requires permissions to access files and the internet. Please grant the necessary permissions in the next window.")
            .setPositiveButton("OK") { _, _ ->
                requestPermissions()
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                Toast.makeText(this, "The app may not work properly without the required permissions", Toast.LENGTH_LONG).show()
            }
            .create()
            .show()
    }

    private fun requestPermissions() {
        val permissionsToRequestFiltered = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (permissionsToRequestFiltered.isNotEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequestFiltered.toTypedArray())
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            requestManageExternalStoragePermission()
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun requestManageExternalStoragePermission() {
        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
        manageExternalStorageLauncher.launch(intent)
    }

    private fun handlePermissionsResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            return
        } else {
            Toast.makeText(this, "Permissions are required to proceed", Toast.LENGTH_SHORT).show()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Show dialog to ask for manual permission for MANAGE_EXTERNAL_STORAGE
                AlertDialog.Builder(this)
                    .setMessage("This app needs permission to manage external storage. Please enable it in the settings.")
                    .setPositiveButton("Go to Settings") { _, _ ->
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                        startActivity(intent)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        }
    }

    private fun initializeViews() {
        sourceLanguageSpinner = findViewById(R.id.sourceLanguageSpinner)
        targetLanguageSpinner = findViewById(R.id.targetLanguageSpinner)
        filePathTextView = findViewById(R.id.filePathTextView)
        selectFileButton = findViewById(R.id.selectFileButton)
        translateButton = findViewById(R.id.translateButton)
        progressBar = findViewById(R.id.progressBar)
        statusTextView = findViewById(R.id.statusTextView)
        modeRadioGroup = findViewById(R.id.modeRadioGroup)
        progressTextView = findViewById(R.id.progressTextView)

        // Setup language spinners
        val languages = resources.getStringArray(R.array.languages)
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, languages)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        sourceLanguageSpinner.adapter = adapter
        targetLanguageSpinner.adapter = adapter

        // Set default values for spinners
        val defaultSourceLanguage = "zh_cn"
        val defaultTargetLanguage = "en"

        val defaultSourceIndex = languages.indexOf(defaultSourceLanguage)
        val defaultTargetIndex = languages.indexOf(defaultTargetLanguage)

        if (defaultSourceIndex >= 0) {
            sourceLanguageSpinner.setSelection(defaultSourceIndex)
        }
        if (defaultTargetIndex >= 0) {
            targetLanguageSpinner.setSelection(defaultTargetIndex)
        }
    }

    companion object {
        private const val PICK_FILE_REQUEST_CODE = 1
    }

    private fun setupListeners() {
        selectFileButton.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/octet-stream"))
            }
            startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
        }

        translateButton.setOnClickListener {
            val sourceLang = sourceLanguageSpinner.selectedItem.toString()
            val targetLang = targetLanguageSpinner.selectedItem.toString()
            val mode = if (modeRadioGroup.checkedRadioButtonId == R.id.addModeRadioButton) "add" else "replace"

            selectedFileUri?.let { uri ->
                CoroutineScope(Dispatchers.Main).launch {
                    processFile(uri, sourceLang, targetLang, mode)
                }
            } ?: run {
                Toast.makeText(this, "Please select a file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_FILE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            data?.data?.let { uri ->
                val fileName = getFileName(uri)
                if (fileName.endsWith(".zip") || fileName.endsWith(".rwmod")) {
                    selectedFileUri = uri
                    filePathTextView.text = getReadableFilePath(uri)
                } else {
                    Toast.makeText(this, "Please select a .zip or .rwmod file", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun getReadableFilePath(uri: Uri): String {
        val fileName = getFileName(uri)
        return fileName
    }

    @SuppressLint("SetTextI18n")
    private suspend fun processFile(uri: Uri, sourceLang: String, targetLang: String, mode: String) {
        progressBar.visibility = View.VISIBLE
        translateButton.isEnabled = false
        statusTextView.text = "Processing..."
        progressTextView.visibility = View.VISIBLE

        try {
            withContext(Dispatchers.IO) {
                val inputStream = contentResolver.openInputStream(uri)

                if (inputStream != null) {
                    val originalFileName = getFileName(uri)
                    val newFileName = createNewFileName(originalFileName)
                    val newUri = createNewFileUri(newFileName)

                    val outputStream = contentResolver.openOutputStream(newUri)

                    if (outputStream != null) {
                        fileProcessor.processAllFiles(inputStream, outputStream, sourceLang, targetLang, mode) { processed, total ->
                            val progress = (processed.toFloat() / total * 100).toInt()
                            CoroutineScope(Dispatchers.Main).launch {
                                progressBar.progress = progress
                                progressTextView.text = "Progress: $processed / $total files"
                            }
                        }
                        withContext(Dispatchers.Main) {
                            statusTextView.text = "Translation completed successfully. New file: $newFileName"
                        }
                    } else {
                        throw Exception("Unable to create output file")
                    }
                } else {
                    throw Exception("Unable to access the input file")
                }
            }
        } catch (e: Exception) {
            statusTextView.text = "Error: ${e.message}"
        } finally {
            progressBar.visibility = View.GONE
            progressTextView.visibility = View.GONE
            translateButton.isEnabled = true
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        result = it.getString(index)
                    }
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result ?: "unknown"
    }

    private fun createNewFileName(originalFileName: String): String {
        val dotIndex = originalFileName.lastIndexOf('.')
        return if (dotIndex != -1) {
            val nameWithoutExtension = originalFileName.substring(0, dotIndex)
            val extension = originalFileName.substring(dotIndex)
            "${nameWithoutExtension}_translated$extension"
        } else {
            "${originalFileName}_translated"
        }
    }

    private fun createNewFileUri(newFileName: String): Uri {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val newFile = File(downloadsDir, newFileName)
        return Uri.fromFile(newFile)
    }
}