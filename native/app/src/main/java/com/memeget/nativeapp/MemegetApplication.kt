package com.memeget.nativeapp

import android.app.Application
import android.os.Build
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.decode.VideoFrameDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.memeget.nativeapp.knowledge.LookalikeKernels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.merge
import kotlin.math.min

internal class AppGraph(context: Application) {
    val store: LibraryStore = SqliteLibraryStore(context)
    val engine: InferenceEngine = NativeInferenceEngine(context)
    val files = LibraryFiles(context, store)
    val knowledge = LocalKnowledge(store, engine, LookalikeKernels.Native)
    val cloud = CloudAssistant(context, store, engine)
    val coordinator = IndexCoordinator(context, store, files, engine, knowledge, cloud)
    /** Started with the graph, so changes made by a background session get backed up as well. */
    val backups = FolderBackupSync(
        changes = merge(store.revision.drop(1), store.labelRevision.drop(1)),
        backupAll = { files.backupAll() },
        logFailure = { folder, reason -> store.addError(folder.name, "backup", reason) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    ).also { it.start() }
}

class MemegetApplication : Application(), ImageLoaderFactory {
    internal val graph: AppGraph by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Leaving the screen is the last sure chance to back up a change made just before: the app may not live
        // through the backup's quiet wait.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) = graph.backups.flush()
        })
    }

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
