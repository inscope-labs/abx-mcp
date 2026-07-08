package androidx.documentfile.provider

import android.net.Uri

class MockDocumentFile(
    var canReadVal: Boolean,
    var canWriteVal: Boolean
) : DocumentFile(null) {
    override fun createFile(mimeType: String, displayName: String): DocumentFile? = null
    override fun createDirectory(displayName: String): DocumentFile? = null
    override fun getUri(): Uri = Uri.EMPTY
    override fun getName(): String? = null
    override fun getType(): String? = null
    override fun getParentFile(): DocumentFile? = null
    override fun isDirectory(): Boolean = false
    override fun isFile(): Boolean = false
    override fun lastModified(): Long = 0
    override fun length(): Long = 0
    override fun canRead(): Boolean = canReadVal
    override fun canWrite(): Boolean = canWriteVal
    override fun delete(): Boolean = false
    override fun exists(): Boolean = false
    override fun listFiles(): Array<DocumentFile> = emptyArray()
    override fun findFile(displayName: String): DocumentFile? = null
    override fun isVirtual(): Boolean = false
    override fun renameTo(p0: String): Boolean = false
}
