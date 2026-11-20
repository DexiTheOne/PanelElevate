package io.github.simllll.panelelevate.dashboard.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Movie
import android.graphics.RectF
import android.util.Log
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import com.caverock.androidsvg.SVG
import io.github.simllll.panelelevate.dashboard.hass.HassClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.CacheControl
import java.io.File

/**
 * Small image loader (no Coil/Glide to keep the APK lean): OkHttp with a disk cache, decoding
 * subsampled to the display size (SVGs rasterized at that size via AndroidSVG), and a memory LRU.
 * HA-relative URLs (`/local/…`, `/api/…`) are resolved against the HA base URL and get the bearer
 * token.
 */
class ImageLoader(private val hass: HassClient, cacheDir: File) {
    private val client = hass.http.newBuilder()
        .cache(Cache(File(cacheDir, "images"), 20L * 1024 * 1024))
        .build()

    private val memory = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    // Raw bytes of animated GIFs, so RemoteImage can play them (the bitmap cache only holds frame 1)
    private val gifs = object : LruCache<String, ByteArray>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ByteArray) = value.size
    }

    /** The raw GIF of [url] if [load] found one (null for other formats or before loading). */
    fun gifBytes(url: String): ByteArray? = gifs.get(url)

    fun cached(url: String, targetWidth: Int): ImageBitmap? = memory.get(key(url, targetWidth))

    /**
     * Loads [url] decoded to roughly [targetWidth] px wide (never upscaled). [bypassCache] skips
     * both caches (for refreshing camera/webcam pictures). Returns null on any error.
     */
    suspend fun load(url: String, targetWidth: Int, bypassCache: Boolean = false): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = key(url, targetWidth)
        if (!bypassCache) memory.get(key)?.let { return@withContext it }
        try {
            val request = hass.request(url).newBuilder()
                .apply { if (bypassCache) cacheControl(CacheControl.FORCE_NETWORK) }
                .build()
            var svg = false
            val bytes = client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code} for $url")
                    return@withContext null
                }
                svg = response.body.contentType()?.subtype?.contains("svg") == true
                response.body.bytes()
            }
            if (isGif(bytes)) gifs.put(url, bytes)
            if (svg || isSvg(url, bytes)) {
                return@withContext renderSvg(bytes, targetWidth)?.asImageBitmap()?.also { memory.put(key, it) }
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            if (targetWidth > 0) while (bounds.outWidth / (sample * 2) >= targetWidth) sample *= 2
            val bitmap: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return@withContext null
            bitmap.prepareToDraw()
            bitmap.asImageBitmap().also { memory.put(key, it) }
        } catch (e: Exception) {
            Log.w(TAG, "loading $url failed: ${e.message}")
            null
        }
    }

    private fun key(url: String, width: Int) = "$width|$url"

    private fun isGif(bytes: ByteArray) = bytes.size > 6 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() && bytes[2] == 'F'.code.toByte()


    private fun isSvg(url: String, bytes: ByteArray): Boolean {
        if (url.substringBefore('?').endsWith(".svg", ignoreCase = true)) return true
        val head = String(bytes, 0, minOf(bytes.size, 256), Charsets.UTF_8).trimStart()
        return head.startsWith("<svg") || (head.startsWith("<?xml") && head.contains("<svg"))
    }

    /**
     * Rasterizes an SVG (e.g. animated weather icons) at [targetWidth] px, keeping its aspect
     * ratio. Animations and filters are not supported: the static first frame is drawn.
     */
    private fun renderSvg(bytes: ByteArray, targetWidth: Int): Bitmap? {
        val svg = SVG.getFromInputStream(bytes.inputStream())
        val docWidth = svg.documentWidth
        val docHeight = svg.documentHeight
        if (svg.documentViewBox == null && docWidth > 0 && docHeight > 0) svg.setDocumentViewBox(0f, 0f, docWidth, docHeight)
        val box = svg.documentViewBox ?: return null
        // A root width/height (e.g. width="64") makes AndroidSVG draw at that size from the top
        // left of the viewport instead of scaling the viewBox into it: only a corner of the image
        // landed in a small bitmap. 100% maps the viewBox onto the whole bitmap.
        svg.setDocumentWidth("100%")
        svg.setDocumentHeight("100%")
        if (box.width() <= 0f || box.height() <= 0f) return null
        val width = (if (targetWidth > 0) targetWidth else docWidth.toInt()).coerceIn(1, MAX_SVG_PX)
        val height = (width * box.height() / box.width()).toInt().coerceIn(1, MAX_SVG_PX)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        svg.renderToCanvas(Canvas(bitmap), RectF(0f, 0f, width.toFloat(), height.toFloat()))
        bitmap.prepareToDraw()
        return bitmap
    }

    private companion object {
        const val TAG = "ImageLoader"
        const val MAX_SVG_PX = 2048
    }
}

/**
 * Shows a remote image. With [keepAspectRatio] the height follows the image (like `<img>` with
 * `width: 100%`); otherwise the image fills the given size with [contentScale].
 * Changing [refreshKey] reloads from the network while the previous image stays visible.
 */
@Composable
fun RemoteImage(
    loader: ImageLoader,
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    keepAspectRatio: Boolean = false,
    alignment: Alignment = Alignment.Center,
    refreshKey: Any? = null,
    contentDescription: String? = null,
    /** Called after each load attempt: false when the image could not be loaded or decoded. */
    onResult: ((loaded: Boolean) -> Unit)? = null,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    var bitmap by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    if (url != null && size.width > 0) {
        LaunchedEffect(url, size.width, refreshKey) {
            val fresh = refreshKey != null && bitmap != null
            val loaded = loader.load(url, size.width, bypassCache = fresh)
            loaded?.let { bitmap = it }
            onResult?.invoke(loaded != null || bitmap != null)
        }
    }
    val image = bitmap
    // Animated GIF: played with android.graphics.Movie (AnimatedImageDrawable needs API 28, the
    // NSPanel Pro runs 27). Movie is deprecated but still decodes GIFs on every version we support.
    @Suppress("DEPRECATION")
    val movie = remember(url, image) {
        url?.let { loader.gifBytes(it) }?.let { Movie.decodeByteArray(it, 0, it.size) }
            ?.takeIf { it.width() > 0 && it.height() > 0 && it.duration() > 0 }
    }
    val sized = if (keepAspectRatio && image != null) modifier.aspectRatio(image.width.toFloat() / image.height.coerceAtLeast(1)) else modifier
    Box(sized.onSizeChanged { size = it }) {
        if (movie != null) {
            GifFrames(movie, contentScale, Modifier.matchParentSize())
        } else if (image != null) {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                contentScale = contentScale,
                alignment = alignment,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun GifFrames(movie: Movie, contentScale: ContentScale, modifier: Modifier) {
    val time by produceState(0L, movie) {
        val start = withFrameMillis { it }
        while (true) withFrameMillis { value = it - start }
    }
    // Movie draws scaled by the canvas density; render each frame 1:1 into an offscreen bitmap
    // (DENSITY_NONE) and scale that ourselves, so the frame size matches the layout exactly.
    val frame = remember(movie) { Bitmap.createBitmap(movie.width(), movie.height(), Bitmap.Config.ARGB_8888) }
    val frameCanvas = remember(frame) { Canvas(frame).apply { density = Bitmap.DENSITY_NONE } }
    val frameImage = remember(frame) { frame.asImageBitmap() }
    ComposeCanvas(modifier.clipToBounds()) {
        frame.eraseColor(android.graphics.Color.TRANSPARENT)
        movie.setTime((time % movie.duration()).toInt())
        movie.draw(frameCanvas, 0f, 0f)
        val mw = frame.width.toFloat()
        val mh = frame.height.toFloat()
        val fit = minOf(size.width / mw, size.height / mh)
        val crop = maxOf(size.width / mw, size.height / mh)
        val scale = if (contentScale == ContentScale.Crop) crop else fit
        val w = (mw * scale).toInt()
        val h = (mh * scale).toInt()
        drawImage(
            frameImage,
            dstOffset = IntOffset(((size.width - w) / 2f).toInt(), ((size.height - h) / 2f).toInt()),
            dstSize = IntSize(w, h),
            filterQuality = FilterQuality.Low,
        )
    }
}
