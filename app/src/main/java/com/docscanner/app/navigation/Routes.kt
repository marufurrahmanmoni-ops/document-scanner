package com.docscanner.app.navigation

import kotlinx.serialization.Serializable

/** Type-safe routes (architecture.md section 10). Screen ids from recon.md in comments. */

@Serializable data object HomeRoute                 // S03
@Serializable data object FilesRoute                // S04
@Serializable data object ToolsRoute                // S05
@Serializable data object SettingsRoute             // S19
@Serializable data object AccountRoute              // S06 account, backup, subscription

/** S07. [mode] = single | batch. [targetDocId] adds pages to an existing document. [replacePageId] = retake. */
@Serializable
data class CameraRoute(
    val mode: String,
    val targetDocId: String? = null,
    val replacePageId: String? = null,
)

/**
 * How an edit session ends:
 *  single  - new one-page document: Enhance shows Save, which opens the document
 *  review  - after a capture in a multi-page session: Enhance shows Done, which opens Review
 *  edit    - editing a page from Review: Enhance shows Done, which returns to Review
 */
object EditFlow {
    const val SINGLE = "single"
    const val REVIEW = "review"
    const val EDIT = "edit"
}

@Serializable data class CropRoute(val docId: String, val pageId: String, val flow: String)        // S09
@Serializable data class EnhanceRoute(val docId: String, val pageId: String, val flow: String)     // S10
@Serializable data class ReviewRoute(val docId: String)                                            // S11
@Serializable data class DocumentRoute(val docId: String)                                          // S12
