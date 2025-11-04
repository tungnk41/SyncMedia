package com.appx.syncmedia

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity(), FileSyncer.SyncProgressListener {

    private lateinit var sourceDirTextView: TextView
    private lateinit var destDirTextView: TextView
    private lateinit var syncButton: Button
    private lateinit var progressBar: ProgressBar

    private var sourceDirUri: Uri? = null
    private var destDirUri: Uri? = null
    private var isSourceSelected = false


    private lateinit var sharedPreferences: SharedPreferences

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            Log.d("MainActivity", "Directory URI: $treeUri")
            if (isSourceSelected) {
                sourceDirUri = treeUri // Correct: Store the treeUri directly
                saveUri(KEY_SOURCE_DIR, treeUri)
                sourceDirTextView.text = "Source: ${getDisplayablePath(treeUri)}"
            } else {
                destDirUri = treeUri // Correct: Store the treeUri directly
                saveUri(KEY_DEST_DIR, treeUri)
                destDirTextView.text = "Destination: ${getDisplayablePath(treeUri)}"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        sharedPreferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        sourceDirTextView = findViewById(R.id.sourceDirTextView)
        destDirTextView = findViewById(R.id.destDirTextView)
        syncButton = findViewById(R.id.syncButton)
        progressBar = findViewById(R.id.progressBar)

        loadSavedUris()

        val selectSourceDirButton: Button = findViewById(R.id.selectSourceDirButton)
        selectSourceDirButton.setOnClickListener {
            openDirectory(true)
        }

        val selectDestDirButton: Button = findViewById(R.id.selectDestDirButton)
        selectDestDirButton.setOnClickListener {
            openDirectory(false)
        }

        syncButton.setOnClickListener {
            syncFiles()
        }
    }

    private fun openDirectory(isSource: Boolean) {

        val requestDirUri = if (isSource) sourceDirUri else destDirUri
        Log.d("MainActivity", "Current directory URI: $requestDirUri")

        val initialUri = requestDirUri?.let{
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", DocumentsContract.getTreeDocumentId(requestDirUri))
        }

        sourceDirUri
        isSourceSelected = isSource
        folderPickerLauncher.launch(initialUri)
    }

    private fun syncFiles() {
        if (sourceDirUri != null && destDirUri != null) {
            val sourceDir = DocumentFile.fromTreeUri(this, sourceDirUri!!)
            val destDir = DocumentFile.fromTreeUri(this, destDirUri!!)

            if (sourceDir != null && destDir != null) {
                syncButton.isEnabled = false
                progressBar.visibility = View.VISIBLE

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        FileSyncer().sync(this@MainActivity, sourceDir, destDir, this@MainActivity)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            e.printStackTrace()
                            Toast.makeText(this@MainActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                            onSyncComplete()
                        }
                    }
                }
            } else {
                Toast.makeText(this, "Invalid directories selected", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Please select source and destination directories", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onProgressUpdate(progress: Int) {
        runOnUiThread {
            progressBar.progress = progress
        }
    }

    override fun onSyncComplete() {
        runOnUiThread {
            syncButton.isEnabled = true
            progressBar.visibility = View.GONE
            Toast.makeText(this, "Sync complete!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveUri(key: String, uri: Uri) {
        sharedPreferences.edit().putString(key, uri.toString()).apply()
    }

    private fun loadSavedUris() {
        val sourceUriString = sharedPreferences.getString(KEY_SOURCE_DIR, null)
        if (sourceUriString != null) {
            sourceDirUri = Uri.parse(sourceUriString)
            sourceDirTextView.text = "Source: ${getDisplayablePath(sourceDirUri!!)}"
        }

        val destUriString = sharedPreferences.getString(KEY_DEST_DIR, null)
        if (destUriString != null) {
            destDirUri = Uri.parse(destUriString)
            destDirTextView.text = "Destination: ${getDisplayablePath(destDirUri!!)}"
        }
    }

    private fun getDisplayablePath(uri: Uri): String? {
        return when {
            DocumentsContract.isTreeUri(uri) -> DocumentsContract.getTreeDocumentId(uri)
            DocumentsContract.isDocumentUri(this@MainActivity, uri) -> DocumentsContract.getDocumentId(uri)
            else -> null
        }
    }

    companion object {
        private const val PREFS_NAME = "SyncMediaPrefs"
        private const val KEY_SOURCE_DIR = "sourceDir"
        private const val KEY_DEST_DIR = "destDir"
    }
}