package com.appx.syncmedia

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
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
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private lateinit var sourceDirTextView: TextView
    private lateinit var destDirTextView: TextView
    private lateinit var syncButton: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var syncProgressContainer: View
    private lateinit var syncStatusTextView: TextView
    private lateinit var syncCountTextView: TextView

    private var sourceDirUri: Uri? = null
    private var destDirUri: Uri? = null
    private var isSourceSelected = false


    private lateinit var sharedPreferences: SharedPreferences

    private val syncReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                SyncForegroundService.ACTION_SYNC_STARTED -> {
                    syncProgressContainer.visibility = View.VISIBLE
                    syncStatusTextView.text = "Checking files..."
                    syncCountTextView.text = ""
                    progressBar.isIndeterminate = true
                }
                SyncForegroundService.ACTION_SYNC_PROGRESS -> {
                    val current = intent.getIntExtra(SyncForegroundService.EXTRA_CURRENT, 0)
                    val total = intent.getIntExtra(SyncForegroundService.EXTRA_TOTAL, 0)

                    syncProgressContainer.visibility = View.VISIBLE
                    syncStatusTextView.text = "Synchronizing..."
                    syncCountTextView.text = "$current/$total"
                    progressBar.isIndeterminate = false
                    if (total > 0) {
                        progressBar.max = total
                        progressBar.progress = current
                    }
                }
                SyncForegroundService.ACTION_SYNC_COMPLETE -> {
                    syncProgressContainer.visibility = View.GONE
                }
            }
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            Toast.makeText(this, "Notification permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    private val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { treeUri ->
        if (treeUri != null) {
            val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            contentResolver.takePersistableUriPermission(treeUri, takeFlags)
            Log.d("MainActivity", "Directory URI: $treeUri")
            if (isSourceSelected) {
                sourceDirUri = treeUri
                saveUri(KEY_SOURCE_DIR, treeUri)
                sourceDirTextView.text = "Source: ${getDisplayablePath(treeUri)}"
            } else {
                destDirUri = treeUri
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
        syncProgressContainer = findViewById(R.id.syncProgressContainer)
        syncStatusTextView = findViewById(R.id.syncStatusTextView)
        syncCountTextView = findViewById(R.id.syncCountTextView)

        ensureNotificationPermissionIfNeeded()
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

    override fun onResume() {
        super.onResume()
        if (!SyncForegroundService.isServiceRunning) {
            syncProgressContainer.visibility = View.GONE
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(SyncForegroundService.ACTION_SYNC_STARTED)
            addAction(SyncForegroundService.ACTION_SYNC_PROGRESS)
            addAction(SyncForegroundService.ACTION_SYNC_COMPLETE)
        }
        ContextCompat.registerReceiver(this, syncReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        super.onStop()
        unregisterReceiver(syncReceiver)
    }

    private fun openDirectory(isSource: Boolean) {

        val requestDirUri = if (isSource) sourceDirUri else destDirUri
        Log.d("MainActivity", "Current directory URI: $requestDirUri")

        val initialUri = requestDirUri?.let {
            DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", DocumentsContract.getTreeDocumentId(requestDirUri)
            )
        }

        isSourceSelected = isSource
        folderPickerLauncher.launch(initialUri)
    }

    private fun syncFiles() {
        if (sourceDirUri != null && destDirUri != null) {
            val sourceDir = DocumentFile.fromTreeUri(this, sourceDirUri!!)
            val destDir = DocumentFile.fromTreeUri(this, destDirUri!!)

            if (sourceDir != null && destDir != null) {
                val serviceIntent = SyncForegroundService.createStartIntent(
                    this,
                    sourceDirUri!!,
                    destDirUri!!
                )
                ContextCompat.startForegroundService(this, serviceIntent)
                syncProgressContainer.visibility = View.VISIBLE
                syncStatusTextView.text = "Starting service..."
                progressBar.isIndeterminate = true
                Toast.makeText(this, "Sync started in background", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Invalid directories selected", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "Please select source and destination directories", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return
        }

        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED

        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
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