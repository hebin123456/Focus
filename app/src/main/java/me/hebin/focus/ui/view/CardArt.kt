package me.hebin.focus.ui.view

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.util.concurrent.Executors

/**
 * 卡面美术资源加载器：assets/cards/NNN.webp（512px 透明底）。
 * LruCache 按 1/8 堆内存限容 + 单线程串行解码，主线程回调。
 */
object CardArt {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val cache: LruCache<Int, Bitmap> = object : LruCache<Int, Bitmap>(cacheSizeKb()) {
        override fun sizeOf(key: Int, value: Bitmap): Int = value.byteCount / 1024
    }

    private fun cacheSizeKb(): Int {
        val maxMemKb = (Runtime.getRuntime().maxMemory() / 1024).toInt()
        return (maxMemKb / 8).coerceIn(4 * 1024, 48 * 1024)
    }

    fun assetPath(id: Int): String = "cards/%03d.webp".format(id)

    /** 已在缓存里则直接返回（可用于同步判断） */
    fun peek(id: Int): Bitmap? = cache.get(id)

    /**
     * 获取卡面：命中缓存立即回调；否则异步解码后主线程回调。
     * RecyclerView 复用场景下，回调时用 id 比对防止串卡。
     */
    fun load(context: Context, id: Int, onReady: (Bitmap?) -> Unit) {
        cache.get(id)?.let { onReady(it); return }
        val app = context.applicationContext
        executor.execute {
            val bmp = runCatching {
                app.assets.open(assetPath(id)).use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            if (bmp != null) cache.put(id, bmp)
            mainHandler.post { onReady(bmp) }
        }
    }
}
