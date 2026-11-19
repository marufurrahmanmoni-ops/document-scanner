package com.docscanner.app.data

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.docscanner.app.model.PageSize
import com.docscanner.app.model.QualityProfile
import com.docscanner.app.util.Titles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

enum class ExportFormat { PDF, JPG }

data class ExportRequest(
    val docId: String,
    val format: ExportFormat,
    val profile: QualityProfile,
    val pageSize: PageSize,
)

data class ExportResult(val files: List<File>, val mimeType: String)

/**
 * Builds PDF and JPG exports (architecture.md section 6) into cacheDir/exports.
 * These are the only plaintext copies the app ever makes. They are deleted by [cleanup].
 */
class ExportRepository(
    private val context: Context,
    private val documents: DocumentRepository,
) {
    private val exportDir: File get() = File(context.cacheDir, "exports").apply { mkdirs() }

    suspend fun export(request: ExportRequest, onProgress: (done: Int, total: Int) -> Unit): ExportResult =
        withContext(Dispatchers.IO) {
            val doc = documents.document(request.docId) ?: error("document not found")
            val pages = documents.livePages(request.docId)
            require(pages.isNotEmpty()) { "document has no pages" }
            val base = Titles.safeFileName(doc.title)
            val dir = File(exportDir, System.currentTimeMillis().toString()).apply { mkdirs() }
            when (request.format) {
                ExportFormat.PDF -> {
                    val file = File(dir, "$base.pdf")
                    writePdf(file, pages.size, request) { index -> documents.renderForExport(pages[index], request.profile) }
                    onProgress(pages.size, pages.size)
                    ExportResult(listOf(file), "application/pdf")
                }
                ExportFormat.JPG -> {
                    val files = pages.mapIndexed { i, page ->
                        val bytes = documents.renderForExport(page, request.profile)
                        val name = if (pages.size == 1) "$base.jpg" else "$base-%02d.jpg".format(i + 1)
                        File(dir, name).also { it.writeBytes(bytes); onProgress(i + 1, pages.size) }
                    }
                    ExportResult(files, "image/jpeg")
                }
            }
        }

    /**
     * One PDF page per scan page, written page by page so a 100-page document never holds more than one
     * decoded bitmap. FIT pages take the image's aspect with the long edge at A4 height (842 pt).
     */
    private suspend fun writePdf(
        file: File,
        count: Int,
        request: ExportRequest,
        jpegFor: suspend (Int) -> ByteArray,
    ) {
        val pdf = PdfDocument()
        try {
            for (i in 0 until count) {
                val jpeg = jpegFor(i)
                val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("could not decode page ${i + 1}")
                val (pw, ph) = pageSizeFor(request.pageSize, bmp.width, bmp.height)
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
                val scale = min(pw.toFloat() / bmp.width, ph.toFloat() / bmp.height)
                val dw = (bmp.width * scale).roundToInt()
                val dh = (bmp.height * scale).roundToInt()
                val left = (pw - dw) / 2
                val top = (ph - dh) / 2
                page.canvas.drawBitmap(bmp, null, Rect(left, top, left + dw, top + dh), Paint(Paint.FILTER_BITMAP_FLAG))
                pdf.finishPage(page)
                bmp.recycle()
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally {
            pdf.close()
        }
    }

    private fun pageSizeFor(size: PageSize, w: Int, h: Int): Pair<Int, Int> {
        if (size != PageSize.FIT) {
            // Rotate the paper to match the scan's orientation.
            return if (w > h) size.heightPt to size.widthPt else size.widthPt to size.heightPt
        }
        val longPt = 842f
        return if (h >= w) (longPt * w / h).roundToInt() to longPt.roundToInt()
        else longPt.roundToInt() to (longPt * h / w).roundToInt()
    }

    fun uriFor(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)

    /** Copies a finished PDF to a location the user picked with the system file picker. */
    suspend fun copyTo(file: File, target: Uri, resolver: ContentResolver) = withContext(Dispatchers.IO) {
        resolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("could not open the destination")
    }

    /** Writes every file into a folder the user picked (JPG export to device). */
    suspend fun copyToTree(files: List<File>, treeUri: Uri, resolver: ContentResolver, mime: String) =
        withContext(Dispatchers.IO) {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            files.forEach { f ->
                val created = DocumentsContract.createDocument(resolver, parent, mime, f.name)
                    ?: error("could not create ${f.name}")
                resolver.openOutputStream(created)?.use { out -> f.inputStream().use { it.copyTo(out) } }
            }
        }

    /** Deletes export folders older than [maxAgeMs]. Called on start and after each share. */
    fun cleanup(maxAgeMs: Long = 60 * 60 * 1000L) {
        val now = System.currentTimeMillis()
        exportDir.listFiles()?.forEach { dir ->
            if (now - dir.lastModified() > maxAgeMs) dir.deleteRecursively()
        }
    }
}
