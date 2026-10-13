package com.memeget.nativeapp

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import kotlin.math.min

internal class AppGraph(context: Application) {
    val store: LibraryStore = SqliteLibraryStore(context)
    val engine: InferenceEngine = NativeInferenceEngine(context)
    val files = LibraryFiles(context, store)
    val knowledge = LocalKnowledge(store, engine)
    val cloud = CloudAssistant(context, store, engine)
    val coordinator = IndexCoordinator(context, store, files, engine, knowledge, cloud)
}

class MemegetApplication : Application(), ImageLoaderFactory {
    internal val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
            add(VideoFrameDecoder.Factory())
        }
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizePercent(min(0.125, 32.0 * 1024 * 1024 / Runtime.getRuntime().maxMemory()))
                .build()
        }
        .diskCache {
            DiskCache.Builder().directory(cacheDir.resolve("image_cache"))
                .maxSizeBytes(128L * 1024 * 1024).build()
        }
        .crossfade(false)
        .build()
}
