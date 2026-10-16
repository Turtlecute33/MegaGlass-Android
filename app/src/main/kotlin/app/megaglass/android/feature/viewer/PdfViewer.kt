package app.megaglass.android.feature.viewer

import app.megaglass.android.core.util.catching

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.megaglass.android.MegaGlassApp
import app.megaglass.android.R
import app.megaglass.android.ui.components.BackButton
import app.megaglass.android.ui.components.ErrorState
import app.megaglass.android.ui.components.IconAction
import app.megaglass.android.ui.nav.LocalNavigator
import app.megaglass.android.ui.nav.PdfViewer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.sqrt
import java.io.File

/**
 * Width / height of a page for the layout. Extreme shapes are drawn stretched: a page 1000 px
 * wide and 5 million px tall cannot be measured (layout sizes stop at about 260,000 px).
 */
internal fun pdfPageRatio(width: Int, height: Int): Float =
    (width.coerceAtLeast(1).toFloat() / height.coerceAtLeast(1)).coerceIn(0.05f, 20f)

/**
 * Renders PDF pages with the platform PdfRenderer. One page renders at a time (the renderer is
 * not thread-safe); rendered pages stay in a memory cache sized from the app memory class.
 */
private class PdfDocument(file: File, context: Context) {
    private val fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    private val renderer = try { PdfRenderer(fd) } catch (e: Exception) { fd.close(); throw e }
    private val lock = Mutex()
    val pageCount = renderer.pageCount
    /** Width / height of each page. */
    private var closed = false
    val ratios: List<Float> = try {
        (0 until pageCount).map { i -> renderer.openPage(i).use { pdfPageRatio(it.width, it.height) } }
    } catch (e: Exception) { renderer.close(); fd.close(); throw e }
    private val cache = object : LruCache<Int, Bitmap>(cacheBytes(context)) {
        override fun sizeOf(key: Int, value: Bitmap) = value.allocationByteCount
    }

    /** Bitmap size of a page. Very tall pages (receipts, long exports) get fewer pixels: a canvas cannot draw a bitmap above 100 MB. */
    private fun size(index: Int, width: Int): Pair<Int, Int> {
        val w = width.coerceAtMost(MAX_WIDTH).coerceAtLeast(1)
        val h = (w / ratios[index]).coerceAtLeast(1f)
        val scale = minOf(1f, sqrt(MAX_PIXELS / (w * h)))
        return (w * scale).toInt().coerceAtLeast(1) to (h * scale).toInt().coerceAtLeast(1)
    }

    suspend fun render(index: Int, width: Int): Bitmap = withContext(Dispatchers.IO) {
        val (w, h) = size(index, width)
        cache.get(index)?.takeIf { it.width >= w }?.let { return@withContext it }
        lock.withLock {
            check(!closed) { "PDF is closed" }
            val bitmap = renderer.openPage(index).use { page ->
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                    b.eraseColor(AndroidColor.WHITE)
                    page.render(b, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                }
            }
            cache.put(index, bitmap)
            bitmap
        }
    }

    /** Closes after the page that is rendering now. */
    fun close() {
        MegaGlassApp.graph.scope.launch(Dispatchers.IO) {
            lock.withLock {
                if (closed) return@withLock
                closed = true
                catching { renderer.close() }
                catching { fd.close() }
                cache.evictAll()
            }
        }
    }

    companion object {
        const val MAX_WIDTH = 2400
        /** 12 MP, 48 MB in ARGB_8888. */
        const val MAX_PIXELS = 12_000_000f
        fun cacheBytes(context: Context): Int {
            val mb = context.getSystemService(ActivityManager::class.java).memoryClass
            return (mb * 1024 * 1024 / 6).coerceAtLeast(16 * 1024 * 1024)
        }
    }
}

@Composable
fun PdfViewerScreen(route: PdfViewer) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val graph = MegaGlassApp.graph
    val nav = LocalNavigator.current
    var file by remember { mutableStateOf(route.localPath?.let(::File)) }
    var title by remember { mutableStateOf(file?.name.orEmpty()) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(route, attempt) {
        if (route.localPath != null) return@LaunchedEffect
        val api = if (route.folderLink) graph.mega.folderApi else graph.mega.api
        val node = withContext(Dispatchers.IO) { api.getNodeByHandle(route.handle) }
        if (node == null) {
            error = resources.getString(R.string.viewer_not_found)
            return@LaunchedEffect
        }
        title = node.name ?: ""
        catching { PreviewCache.fetch(route.handle, node.name ?: "file.pdf", node.size, route.folderLink) { progress = it } }
            .onSuccess { file = it }
            .onFailure { error = it.message ?: resources.getString(R.string.error_title) }
    }

    var document by remember { mutableStateOf<PdfDocument?>(null) }
    LaunchedEffect(file) {
        val f = file ?: return@LaunchedEffect
        // Opening reads every page size: off the main thread.
        document = withContext(Dispatchers.IO) { catching { PdfDocument(f, context) }.getOrNull() }
        if (document == null) error = resources.getString(R.string.viewer_pdf_error)
    }
    DisposableEffect(document) {
        val doc = document
        onDispose { doc?.close() }
    }

    val listState = rememberLazyListState()
    val page by remember { derivedStateOf { listState.firstVisibleItemIndex } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
                        document?.let {
                            Text(
                                stringResource(R.string.viewer_page, page + 1, it.pageCount),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                navigationIcon = { BackButton { nav.back() } },
                actions = {
                    file?.let { f -> IconAction(R.drawable.ic_open_in_new, stringResource(R.string.action_open_with), { graph.opener.view(f) }) }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).background(MaterialTheme.colorScheme.surfaceContainer)) {
            val doc = document
            when {
                error != null -> ErrorState(error!!, onRetry = if (route.localPath == null) { { error = null; attempt++ } } else null)
                doc == null -> Column(
                    Modifier.align(Alignment.Center).padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    LoadingIndicator(Modifier.size(64.dp))
                    if (progress > 0f) {
                        Spacer(Modifier.height(16.dp))
                        LinearWavyProgressIndicator(progress = { progress }, modifier = Modifier.width(200.dp))
                    }
                }
                else -> PdfPages(doc, listState)
            }
        }
    }
}

/** Pages in a vertical list. Pinch zooms the whole column; one finger pans when zoomed. */
@Composable
private fun PdfPages(doc: PdfDocument, listState: androidx.compose.foundation.lazy.LazyListState) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val maxOffset = { (size.width * (scale - 1f)) / 2f }
    BoxWithConstraints(
        Modifier.fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pointers = event.changes.count { it.pressed }
                        if (pointers >= 2) {
                            scale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxOffset(), maxOffset())
                            event.changes.forEach { it.consume() }
                        } else if (pointers == 1 && scale > 1f) {
                            // Horizontal pan only; vertical movement scrolls the list.
                            offsetX = (offsetX + event.calculatePan().x).coerceIn(-maxOffset(), maxOffset())
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    if (scale > 1f) {
                        scale = 1f
                        offsetX = 0f
                    } else {
                        scale = 2.5f
                    }
                })
            },
    ) {
        val density = LocalDensity.current
        // Render at twice the screen width so that pages stay sharp when zoomed.
        val renderWidth = with(density) { (maxWidth.toPx() * 2).toInt() }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offsetX
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(doc.pageCount, key = { it }) { index ->
                PdfPage(doc, index, renderWidth)
            }
        }
    }
}

@Composable
private fun PdfPage(doc: PdfDocument, index: Int, width: Int) {
    var bitmap by remember(index) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(index, width) { bitmap = catching { doc.render(index, width) }.getOrNull() }
    Surface(
        shape = MaterialTheme.shapes.small,
        shadowElevation = 2.dp,
        color = Color.White,
        modifier = Modifier.fillMaxWidth().aspectRatio(doc.ratios[index]),
    ) {
        val b = bitmap
        if (b != null) {
            Image(b.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize())
        } else {
            Box(contentAlignment = Alignment.Center) {
                Surface(shape = CircleShape, color = Color.Transparent) { LoadingIndicator(Modifier.size(40.dp)) }
            }
        }
    }
}
