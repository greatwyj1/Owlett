package com.example.birdingsoundmvp.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ImageNotSupported
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.birdingsoundmvp.taxonomy.BirdTaxonomyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal val LocalOpenSpecies = staticCompositionLocalOf<(String, String) -> Unit> { { _, _ -> } }
internal val LocalThumbnailLoader = staticCompositionLocalOf<suspend (Context, String, String) -> ImageBitmap?> {
    { context, scientific, common -> BirdThumbnails.load(context, scientific, common)?.asImageBitmap() }
}

internal object BirdThumbnails {
    private val mutex = Mutex()
    private val paths = LruCache<String, String>(1024)
    private val bitmaps = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
    }

    suspend fun load(context: Context, scientific: String, common: String): Bitmap? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val key = "${scientific.trim().lowercase()}|${common.trim().lowercase()}"
            val path = paths.get(key) ?: runCatching {
                BirdTaxonomyRepository(context.applicationContext).findSpecies(scientific, common)
                    ?.images?.firstOrNull { it.isLocalAsset }?.url.orEmpty()
            }.getOrNull()?.also { paths.put(key, it) } ?: return@withLock null
            if (path.isBlank()) return@withLock null
            bitmaps.get(path)?.let { return@withLock it }
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
                val options = BitmapFactory.Options().apply {
                    inSampleSize = thumbnailSampleSize(bounds.outWidth, bounds.outHeight)
                }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, options) }
                    ?.also { bitmaps.put(path, it) }
            }.getOrNull()
        }
    }
}

internal fun thumbnailSampleSize(width: Int, height: Int, target: Int = 192): Int {
    var sample = 1
    while (maxOf(width, height) / (sample * 2) >= target) sample *= 2
    return sample
}

@Composable
internal fun BirdThumbnail(scientificName: String, commonName: String, modifier: Modifier = Modifier,
                           onClick: (() -> Unit)? = null) {
    val context = LocalContext.current
    val open = LocalOpenSpecies.current
    val loader = LocalThumbnailLoader.current
    var bitmap by remember(scientificName, commonName, loader) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(scientificName, commonName, loader) { bitmap = loader(context, scientificName, commonName) }
    Box(modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(onClickLabel = "查看${commonName.ifBlank { scientificName }}的资料") {
            if (onClick != null) onClick() else open(scientificName, commonName)
        }, contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap!!, commonName.ifBlank { scientificName }, Modifier.matchParentSize(), contentScale = ContentScale.Fit)
        else Icon(Icons.Outlined.ImageNotSupported, "暂无鸟种图片", Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
