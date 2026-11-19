package com.docscanner.app.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import com.docscanner.app.designsystem.theme.Dimens
import com.docscanner.app.designsystem.theme.ScannerTheme

enum class ButtonVariant { PRIMARY, SECONDARY, TONAL, TEXT, DANGER }
enum class ButtonSize(val height: Dp, val hPad: Dp) {
    SM(Dimens.buttonSm, Dimens.space4),
    MD(Dimens.buttonMd, Dimens.space5),
    LG(Dimens.buttonLg, Dimens.space6),
}

/** components.md "Button": five variants, three sizes, loading keeps the label for TalkBack. */
@Composable
fun ScannerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.PRIMARY,
    size: ButtonSize = ButtonSize.MD,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val shape = MaterialTheme.shapes.medium
    val padding = PaddingValues(horizontal = size.hPad)
    val textStyle = if (size == ButtonSize.LG) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge
    val mod = modifier
        .heightIn(min = size.height)
        .semantics { if (loading) stateDescription = "Working" }
    val content: @Composable () -> Unit = {
        Row(horizontalArrangement = Arrangement.spacedBy(Dimens.space2), verticalAlignment = Alignment.CenterVertically) {
            when {
                loading -> CircularProgressIndicator(
                    modifier = Modifier.size(Dimens.iconSm),
                    strokeWidth = Dimens.focusRing,
                    color = MaterialTheme.colorScheme.onPrimary.takeIf { variant == ButtonVariant.PRIMARY || variant == ButtonVariant.DANGER }
                        ?: MaterialTheme.colorScheme.primary,
                )
                icon != null -> Icon(icon, contentDescription = null, modifier = Modifier.size(Dimens.iconSm))
            }
            Text(text, style = textStyle)
        }
    }
    val isEnabled = enabled && !loading
    when (variant) {
        ButtonVariant.PRIMARY -> Button(
            onClick = onClick, modifier = mod, enabled = isEnabled, shape = shape, contentPadding = padding,
        ) { content() }
        ButtonVariant.DANGER -> Button(
            onClick = onClick, modifier = mod, enabled = isEnabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            ),
        ) { content() }
        ButtonVariant.TONAL -> Button(
            onClick = onClick, modifier = mod, enabled = isEnabled, shape = shape, contentPadding = padding,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
        ) { content() }
        ButtonVariant.SECONDARY -> OutlinedButton(
            onClick = onClick, modifier = mod, enabled = isEnabled, shape = shape, contentPadding = padding,
            border = BorderStroke(Dimens.hairline, ScannerTheme.colors.borderStrong),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
        ) { content() }
        ButtonVariant.TEXT -> TextButton(
            onClick = onClick, modifier = mod, enabled = isEnabled, shape = shape,
            contentPadding = PaddingValues(horizontal = Dimens.space3),
        ) { content() }
    }
}
