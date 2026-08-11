package com.appx.syncmedia

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

class FileSyncer {

    interface SyncProgressListener {
        fun onProgressUpdate(progress: Int)
        fun onSyncComplete()
    }

    private var totalFiles = 0
    private var copiedFiles = 0

    @Throws(IOException::class)
    fun sync(context: Context, sourceDir: DocumentFile, destDir: DocumentFile, listener: SyncProgressListener) {
        Log.d(TAG, "Starting sync from ${sourceDir.uri} to ${destDir.uri}")

        totalFiles = countFiles(sourceDir)
        copiedFiles = 0
        if (totalFiles == 0) {
            listener.onSyncComplete()
            return
        }

        syncDirectory(context, sourceDir, destDir, listener)
        listener.onSyncComplete()
        Log.d(TAG, "Sync finished")
    }

    private fun deleteRecursively(file: DocumentFile) {
        if (file.isDirectory) {
            for (child in file.listFiles()) {
                deleteRecursively(child)
            }
        }
        file.delete()
    }

    private fun countFiles(dir: DocumentFile): Int {
        var count = 0
        if (dir.isDirectory) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    count += countFiles(file)
                } else {
                    count++
                }
            }
        }
        return count
    }

    @Throws(IOException::class)
    private fun syncDirectory(context: Context, source: DocumentFile, destination: DocumentFile, listener: SyncProgressListener) {
        if (!source.isDirectory) {
            return
        }

        val sourceEntriesByName = source
            .listFiles()
            .mapNotNull { entry -> entry.name?.let { it to entry } }
            .toMap()

        for (destEntry in destination.listFiles()) {
            val destName = destEntry.name ?: continue
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
                    destination.createDirectory(fileName)
                }

                if (targetFile != null) {
                    syncDirectory(context, file, targetFile, listener)
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
                    copyFile(context, file, targetFile, listener)
                }
            }
        }
    }

    @Throws(IOException::class)
    private fun copyFile(context: Context, source: DocumentFile, destination: DocumentFile, listener: SyncProgressListener) {
        Log.d(TAG, "Copying file from ${source.uri} to ${destination.uri}")
        context.contentResolver.openInputStream(source.uri)?.use { inputStream ->
            context.contentResolver.openOutputStream(destination.uri)?.use { outputStream ->
                inputStream.copyTo(outputStream)
                copiedFiles++
                val progress = (copiedFiles * 100 / totalFiles)
                listener.onProgressUpdate(progress)
            }
        }
    }

    companion object {
        private const val TAG = "FileSyncer"
    }
}
