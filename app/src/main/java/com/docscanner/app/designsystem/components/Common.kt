package com.docscanner.app.designsystem.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.docscanner.app.data.ImageLoader
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme

val LocalImageLoader = staticCompositionLocalOf<ImageLoader> { error("ImageLoader not provided") }

/** Decrypts and shows a stored image. Shows a sunken placeholder while loading or if [sha] is null. */
@Composable
fun BlobImage(
    sha: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxEdge: Int = 512,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val loader = LocalImageLoader.current
    val image by produceState<ImageBitmap?>(initialValue = sha?.let { loader.cached(it, maxEdge) }, sha, maxEdge) {
        value = sha?.let { loader.load(it, maxEdge) }
    }
    Box(modifier.background(ScannerTheme.colors.surfaceSunken), contentAlignment = Alignment.Center) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

/** components.md "DocumentTile": paper thumbnail on the sunken desk, title (2 lines), meta, page badge. */
@Composable
fun DocumentTile(
    title: String,
    meta: String,
    pageCount: Int,
    thumbSha: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    a11yLabel: String,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.clearAndSetSemantics { contentDescription = a11yLabel },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .clip(MaterialTheme.shapes.medium)
                    .background(ScannerTheme.colors.surfaceSunken)
                    .padding(Dimens.space3),
            ) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    shadowElevation = Dimens.elevPage,
                    color = ScannerTheme.colors.page,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    BlobImage(sha = thumbSha, contentDescription = null, modifier = Modifier.fillMaxSize(), maxEdge = 512)
                }
                Text(
                    text = pageCount.toString(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.inverseOnSurface,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(Dimens.space1)
                        .clip(MaterialTheme.shapes.small)
                        .background(MaterialTheme.colorScheme.inverseSurface)
                        .padding(horizontal = Dimens.space1 + Dimens.space0_5, vertical = Dimens.space0_5),
                )
            }
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Dimens.space2),
            )
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** components.md "EmptyState": icon in a soft circle, title, body, one action. */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier.padding(Dimens.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Dimens.space3),
    ) {
        Box(
            Modifier
                .size(Dimens.space16 + Dimens.space8)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .border(Dimens.hairline, ScannerTheme.colors.border, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Dimens.iconLg + 8.dp))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        action?.invoke()
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = ScannerTheme.colors.textSubtle,
        modifier = modifier.padding(top = Dimens.space6, bottom = Dimens.space2),
    )
}
