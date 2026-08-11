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
            val destFilesByName = destDir?.listFiles()?.associateBy { it.name } ?: emptyMap()

            for (file in sourceDir.listFiles()) {
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

        val sourceEntriesByName = source
            .listFiles()
            .filter { !isHidden(it) }
            .mapNotNull { entry -> entry.name?.let { it to entry } }
            .toMap()

        for (destEntry in destination.listFiles()) {
            val destName = destEntry.name ?: continue
            if (destName.startsWith(".")) continue
            val sourceEntry = sourceEntriesByName[destName]

            if (sourceEntry == null) {
                Log.d(TAG, "Deleting extra entry in destination: $destName")
                deleteRecursively(destEntry)
                continue
            }

            if (destEntry.isDirectory != sourceEntry.isDirectory) {
                Log.d(TAG, "Type mismatch for '$destName', deleting destination entry")
                deleteRecursively(destEntry)
            }
        }

        for (file in source.listFiles()) {
            val fileName = file.name ?: continue
            if (isHidden(file)) continue
            val existingFile = destination.findFile(fileName)
            val targetFile: DocumentFile?

            if (file.isDirectory) {
                targetFile = if (existingFile != null) {
                    if (!existingFile.isDirectory) {
                        deleteRecursively(existingFile)
                        destination.createDirectory(fileName)
                    } else {
                        existingFile
                    }
                } else {
                    Log.i(TAG, "Creating directory: $fileName")
                    destination.createDirectory(fileName)
                }

                if (targetFile != null) {
                    syncDirectory(context, file, targetFile, listener, syncContext)
                } else {
                    Log.e(TAG, "Failed to create or access directory: $fileName")
                }
            } else { // It's a file
                targetFile = if (existingFile != null) {
                    if (existingFile.isDirectory) {
                        deleteRecursively(existingFile)
                        destination.createFile(file.type ?: "application/octet-stream", fileName)
                    } else {
                        existingFile
                    }
                } else {
                    destination.createFile(file.type ?: "application/octet-stream", fileName)
                }

                if (targetFile != null) {
                    val alreadyExists = existingFile != null && !existingFile.isDirectory
                    if (alreadyExists) {
                        Log.i(TAG, "Skipped (already exists): $fileName")
                    } else {
                        copyFile(context, file, targetFile, listener, syncContext)
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
                    val progress = (syncContext.copiedFiles * 100 / syncContext.totalFiles)
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
