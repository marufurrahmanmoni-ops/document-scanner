package com.docscanner.app.feature.common

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.docscanner.app.R
import com.docscanner.app.model.DocumentSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "Today 18:05", "Yesterday", or "19 Nov" in the device time zone. */
@Composable
fun shortDate(epochMs: Long): String {
    val context = LocalContext.current
    return when {
        DateUtils.isToday(epochMs) ->
            stringResource(R.string.date_today, DateUtils.formatDateTime(context, epochMs, DateUtils.FORMAT_SHOW_TIME))
        DateUtils.isToday(epochMs + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.date_yesterday)
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(epochMs))
    }
}

@Composable
fun pagesLabel(count: Int): String = pluralStringResource(R.plurals.pages, count, count)

@Composable
fun docMeta(doc: DocumentSummary): String = stringResource(R.string.doc_meta, pagesLabel(doc.pageCount), shortDate(doc.updatedAt))

@Composable
fun docA11y(doc: DocumentSummary): String =
    stringResource(R.string.doc_a11y, doc.title, pagesLabel(doc.pageCount), shortDate(doc.updatedAt))
