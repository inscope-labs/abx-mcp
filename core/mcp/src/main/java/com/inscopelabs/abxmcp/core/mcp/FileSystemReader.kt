package com.inscopelabs.abxmcp.core.mcp

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileNotFoundException

data class FileMetadata(
    val name: String,
    val path: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
    val isFile: Boolean,
    val mimeType: String
)

interface FileSystemReader {
    fun exists(path: String): Boolean
    fun getMetadata(path: String): FileMetadata?
    fun getLastModified(path: String): Long
    fun readFile(path: String): ByteArray
    fun listDirectory(path: String): List<String>
    fun writeFile(path: String, content: ByteArray)
    fun appendFile(path: String, content: ByteArray)
    fun deleteFile(path: String): Boolean
}

@android.annotation.SuppressLint("NewApi")
class FileSystemReaderImpl(private val context: Context) : FileSystemReader {

    var writeInterceptor: ((File) -> Unit)? = null

    private fun isContentUri(path: String): Boolean {
        return path.startsWith("content://")
    }

    override fun exists(path: String): Boolean {
        return if (isContentUri(path)) {
            try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.exists() == true
            } catch (e: Exception) {
                false
            }
        } else {
            File(path).exists()
        }
    }

    override fun getMetadata(path: String): FileMetadata? {
        if (!exists(path)) return null
        return if (isContentUri(path)) {
            try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri) ?: return null
                FileMetadata(
                    name = doc.name ?: "",
                    path = path,
                    size = doc.length(),
                    lastModified = doc.lastModified(),
                    isDirectory = doc.isDirectory,
                    isFile = doc.isFile,
                    mimeType = doc.type ?: "application/octet-stream"
                )
            } catch (e: Exception) {
                null
            }
        } else {
            val file = File(path)
            FileMetadata(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                lastModified = file.lastModified(),
                isDirectory = file.isDirectory,
                isFile = file.isFile,
                mimeType = getMimeType(file)
            )
        }
    }

    override fun getLastModified(path: String): Long {
        return if (isContentUri(path)) {
            try {
                val uri = Uri.parse(path)
                val doc = DocumentFile.fromSingleUri(context, uri)
                doc?.lastModified() ?: 0L
            } catch (e: Exception) {
                0L
            }
        } else {
            File(path).lastModified()
        }
    }

    override fun readFile(path: String): ByteArray {
        if (!exists(path)) {
            throw FileNotFoundException("File not found: $path")
        }
        return if (isContentUri(path)) {
            val uri = Uri.parse(path)
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                inputStream.readBytes()
            } ?: throw FileNotFoundException("Could not open input stream for: $path")
        } else {
            File(path).readBytes()
        }
    }

    override fun listDirectory(path: String): List<String> {
        if (!exists(path)) {
            throw FileNotFoundException("Directory not found: $path")
        }
        return if (isContentUri(path)) {
            val uri = Uri.parse(path)
            val doc = DocumentFile.fromTreeUri(context, uri)
                ?: DocumentFile.fromSingleUri(context, uri)
                ?: throw FileNotFoundException("Could not resolve document for path: $path")
            
            if (doc.isDirectory) {
                doc.listFiles().mapNotNull { it.uri.toString() }
            } else {
                throw IllegalArgumentException("Path is not a directory: $path")
            }
        } else {
            val file = File(path)
            if (file.isDirectory) {
                file.list()?.toList() ?: emptyList()
            } else {
                throw IllegalArgumentException("Path is not a directory: $path")
            }
        }
    }

    private fun getMimeType(file: File): String {
        return when (file.extension.lowercase()) {
            "txt" -> "text/plain"
            "html", "htm" -> "text/html"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            "json" -> "application/json"
            "xml" -> "application/xml"
            else -> "application/octet-stream"
        }
    }

    override fun writeFile(path: String, content: ByteArray) {
        val targetFile = File(path)
        val parent = targetFile.parentFile ?: throw FileNotFoundException("Parent directory not found")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        val tempFile = File(parent, "${targetFile.name}.tmp")
        try {
            tempFile.outputStream().use { out ->
                val chunkSize = 1024
                var offset = 0
                while (offset < content.size) {
                    val len = minOf(chunkSize, content.size - offset)
                    out.write(content, offset, len)
                    out.flush()
                    offset += len
                    
                    // Call interceptor to allow injecting failures
                    writeInterceptor?.invoke(tempFile)
                    
                    if (Thread.currentThread().isInterrupted) {
                        throw InterruptedException("Write interrupted")
                    }
                }
            }
            // atomic rename
            if (!tempFile.renameTo(targetFile)) {
                try {
                    java.nio.file.Files.move(
                        tempFile.toPath(),
                        targetFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING
                    )
                } catch (e: Exception) {
                    throw java.io.IOException("Failed to rename temp file to target file: ${e.message}")
                }
            }
        } catch (e: Exception) {
            if (tempFile.exists()) {
                tempFile.delete()
            }
            throw e
        }
    }

    override fun appendFile(path: String, content: ByteArray) {
        val targetFile = File(path)
        val parent = targetFile.parentFile ?: throw FileNotFoundException("Parent directory not found")
        if (!parent.exists()) {
            parent.mkdirs()
        }
        targetFile.appendBytes(content)
    }

    override fun deleteFile(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return file.delete()
    }
}
