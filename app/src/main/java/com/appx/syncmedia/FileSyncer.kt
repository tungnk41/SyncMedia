package com.appx.syncmedia

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

class FileSyncer {

    interface SyncProgressListener {
        fun onProgressUpdate(progress: Int, current: Int, total: Int)
        fun onSyncComplete(synced: Int, total: Int)
    }

    private data class SyncContext(
        val totalFiles: Int,
        var copiedFiles: Int = 0
    )

    @Throws(IOException::class)
    fun sync(context: Context, sourceDir: DocumentFile, destDir: DocumentFile, listener: SyncProgressListener) {
        Log.d(TAG, "Starting sync from ${sourceDir.uri} to ${destDir.uri}")

        val totalFiles = countFiles(sourceDir, destDir)
        val syncContext = SyncContext(totalFiles)

        // Initial update to show 0% progress and total file count
        listener.onProgressUpdate(0, 0, totalFiles)

        syncDirectory(context, sourceDir, destDir, listener, syncContext)
        listener.onSyncComplete(syncContext.copiedFiles, totalFiles)
        Log.d(TAG, "Sync finished. Copied ${syncContext.copiedFiles} of $totalFiles files.")
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            for (child in file.listFiles()) {
                deleteRecursively(child)
            }
        }
        file.delete()
    }

    private fun countFiles(sourceDir: DocumentFile, destDir: DocumentFile? = null): Int {
        var count = 0
        if (sourceDir.isDirectory) {
            val sourceFiles = sourceDir.listFiles()
            val destFilesByName = destDir?.listFiles()?.associateBy { it.name } ?: emptyMap()

            for (file in sourceFiles) {
                if (isHidden(file)) continue
                val fileName = file.name ?: continue

                if (file.isDirectory) {
                    val existingDestDir = destFilesByName[fileName]?.takeIf { it.isDirectory }
                    count += countFiles(file, existingDestDir)
                } else {
                    val existingFile = destFilesByName[fileName]
                    if (existingFile == null || existingFile.isDirectory) {
                        count++
                    }
                }
            }
        }
        return count
    }

    @Throws(IOException::class)
    private fun syncDirectory(
        context: Context,
        source: DocumentFile,
        destination: DocumentFile,
        listener: SyncProgressListener,
        syncContext: SyncContext
    ) {
        if (!source.isDirectory) {
            return
        }

        val sourceFiles = source.listFiles()
        val destFiles = destination.listFiles()

        val sourceEntriesByName = sourceFiles
            .filter { !isHidden(it) }
            .mapNotNull { entry -> entry.name?.let { it to entry } }
            .toMap()

        val destEntriesByName = destFiles
            .mapNotNull { entry -> entry.name?.let { it to entry } }
            .toMap()

        // Delete extra entries in destination
        for (destEntry in destFiles) {
            val destName = destEntry.name ?: continue
            if (destName.startsWith(".")) continue
            if (!sourceEntriesByName.containsKey(destName)) {
                Log.d(TAG, "Deleting extra entry in destination: $destName")
                deleteRecursively(destEntry)
            }
        }

        // Sync files and directories
        for ((fileName, sourceFile) in sourceEntriesByName) {
            val existingDestEntry = destEntriesByName[fileName]
            val targetFile: DocumentFile?

            if (sourceFile.isDirectory) {
                targetFile = if (existingDestEntry != null) {
                    if (!existingDestEntry.isDirectory) {
                        deleteRecursively(existingDestEntry)
                        destination.createDirectory(fileName)
                    } else {
                        existingDestEntry
                    }
                } else {
                    Log.i(TAG, "Creating directory: $fileName")
                    destination.createDirectory(fileName)
                }

                if (targetFile != null) {
                    syncDirectory(context, sourceFile, targetFile, listener, syncContext)
                } else {
                    Log.e(TAG, "Failed to create or access directory: $fileName")
                }
            } else { // It's a file
                targetFile = if (existingDestEntry != null) {
                    if (existingDestEntry.isDirectory) {
                        deleteRecursively(existingDestEntry)
                        destination.createFile(sourceFile.type ?: "application/octet-stream", fileName)
                    } else {
                        existingDestEntry
                    }
                } else {
                    destination.createFile(sourceFile.type ?: "application/octet-stream", fileName)
                }

                if (targetFile != null) {
                    val alreadyExists = existingDestEntry != null && !existingDestEntry.isDirectory
                    if (alreadyExists) {
                        Log.i(TAG, "Skipped (already exists): $fileName")
                    } else {
                        copyFile(context, sourceFile, targetFile, listener, syncContext)
                    }
                } else {
                    Log.e(TAG, "Failed to create or access file: $fileName")
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(
        context: Context,
        source: DocumentFile,
        destination: DocumentFile,
        listener: SyncProgressListener,
        syncContext: SyncContext
    ) {
        val fileName = source.name ?: "unknown"
        try {
            context.contentResolver.openInputStream(source.uri)?.use { inputStream ->
                context.contentResolver.openOutputStream(destination.uri)?.use { outputStream ->
                    inputStream.copyTo(outputStream)
                    syncContext.copiedFiles++
                    Log.i(TAG, "[${syncContext.copiedFiles}/${syncContext.totalFiles}] Synced: $fileName")
                    val progress = if (syncContext.totalFiles > 0) {
                        (syncContext.copiedFiles * 100 / syncContext.totalFiles)
                    } else {
                        100
                    }
                    listener.onProgressUpdate(progress, syncContext.copiedFiles, syncContext.totalFiles)
                } ?: Log.e(TAG, "Could not open output stream for $fileName")
            } ?: Log.e(TAG, "Could not open input stream for $fileName")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file $fileName", e)
        }
    }

    private fun isHidden(file: DocumentFile): Boolean {
        return file.name?.startsWith(".") == true
    }

    companion object {
        private const val TAG = "FileSyncer"
    }
}
