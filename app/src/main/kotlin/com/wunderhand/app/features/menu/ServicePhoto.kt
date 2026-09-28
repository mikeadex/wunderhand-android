package com.wunderhand.app.features.menu

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.NoteCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import com.wunderhand.network.ApiError
import com.wunderhand.network.WunderhandApi
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The photograph of a service (chairtime `app/(pro)/menu/[id]/edit`): what a
 * client sees on the booking page. Until now it could only be changed on the
 * web; a pro with the finished cut in front of them has the phone.
 *
 * The picture is brought down to size here, not on the server: a 12 MP
 * photograph is 4–8 MB and the API refuses bodies over 4.5 MB, so the long
 * edge is capped at 2048 px and it goes as a JPEG. The server still
 * re-processes it, as it does the web's uploads.
 */
@Composable
fun ServicePhotoSection(serviceId: String, imageUrl: String?, api: WunderhandApi, handle: suspend (ApiError) -> Unit, changed: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var working by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmingRemove by remember { mutableStateOf(false) }

    suspend fun attempt(block: suspend () -> Unit) {
        working = true
        try {
            block(); problem = null; changed()
        } catch (error: ApiError) {
            // Signed out underneath us is the app's to deal with; anything else is said here.
            if (error is ApiError.Unauthorized) handle(error) else problem = error.message
        } finally { working = false }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val jpeg = withContext(Dispatchers.IO) { ServicePhoto.jpeg(context, uri) }
            if (jpeg == null) { problem = "That picture could not be read. Try another."; return@launch }
            attempt { api.uploadServicePhoto(serviceId, jpeg) }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Eyebrow("Photograph", Modifier.padding(start = 4.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WHColors.Surface).padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (imageUrl != null) {
                AsyncImage(imageUrl, contentDescription = "The photograph", Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(10.dp)).testTag("servicePhoto"), contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.size(96.dp, 72.dp).clip(RoundedCornerShape(10.dp)).background(WHColors.Bg), contentAlignment = Alignment.Center) {
                    WHIcon(WHIcons.Plus, size = 18.dp, tint = WHColors.Neutral500)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (imageUrl == null) "On the booking page, a picture is what a client chooses by." else "What a client sees on the booking page.", style = WHType.Meta, color = WHColors.Neutral700)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    WordsButton(if (imageUrl == null) "Add a photo" else "Change", { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, Modifier.testTag("pickServicePhoto"), enabled = !working)
                    if (imageUrl != null) WordsButton("Remove", { confirmingRemove = true }, Modifier.testTag("removeServicePhoto"), color = WHColors.Accent, enabled = !working)
                    if (working) CircularProgressIndicator(Modifier.size(16.dp), color = WHColors.Ink, strokeWidth = 2.dp)
                }
            }
        }
        problem?.let { NoteCard(it, Modifier.testTag("servicePhotoProblem")) }
    }
    if (confirmingRemove) ConfirmDialog(
        title = "Remove the photograph?", message = "The booking page shows the service without a picture.", confirm = "Remove",
        onConfirm = { confirmingRemove = false; scope.launch { attempt { api.removeServicePhoto(serviceId) } } },
        onDismiss = { confirmingRemove = false },
    )
}

object ServicePhoto {
    /** The longest edge the API is sent. Plenty for a booking page; well under the 4.5 MB the API refuses. */
    const val MAX_EDGE = 2048

    /** A JPEG at most [MAX_EDGE] on its long side, or null for something that is not a picture. */
    fun jpeg(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE, quality: Int = 80): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        // Decode at a power-of-two fraction first, so a 12 MP file never sits whole in memory.
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        return jpeg(decoded, maxEdge, quality)
    }

    fun jpeg(bitmap: Bitmap, maxEdge: Int = MAX_EDGE, quality: Int = 80): ByteArray {
        val longest = maxOf(bitmap.width, bitmap.height)
        val scaled = if (longest > maxEdge) {
            val scale = maxEdge.toFloat() / longest
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
        } else bitmap
        return ByteArrayOutputStream().also { scaled.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
    }
}
