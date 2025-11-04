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

        deleteDirectoryContents(destDir)

        totalFiles = countFiles(sourceDir)
        copiedFiles = 0
        if (totalFiles == 0) {
            listener.onSyncComplete()
            return
        }

        copyDirectory(context, sourceDir, destDir, listener)
        listener.onSyncComplete()
        Log.d(TAG, "Sync finished")
    }

    private fun deleteDirectoryContents(dir: DocumentFile) {
        if (dir.isDirectory) {
            for (file in dir.listFiles()) {
                if (file.isDirectory) {
                    deleteDirectoryContents(file)
                }
                file.delete()
            }
        }
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
    private fun copyDirectory(context: Context, source: DocumentFile, destination: DocumentFile, listener: SyncProgressListener) {
        if (!source.isDirectory) {
            return
        }

        for (file in source.listFiles()) {
            val existingFile = destination.findFile(file.name!!)
            val targetFile: DocumentFile?

            if (file.isDirectory) {
                targetFile = if (existingFile != null) {
                    if (!existingFile.isDirectory) {
                        Log.e(TAG, "Conflict: File exists with same name as directory '${file.name}'")
                        continue
                    }
                    existingFile
                } else {
                    destination.createDirectory(file.name!!)
                }

                if (targetFile != null) {
                    copyDirectory(context, file, targetFile, listener)
                }
            } else { // It's a file
                targetFile = if (existingFile != null) {
                    if (existingFile.isDirectory) {
                        Log.e(TAG, "Conflict: Directory exists with same name as file '${file.name}'")
                        continue
                    }
                    existingFile
                } else {
                    destination.createFile(file.type ?: "application/octet-stream", file.name!!)
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
