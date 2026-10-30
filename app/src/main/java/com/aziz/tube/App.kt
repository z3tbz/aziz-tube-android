package com.aziz.tube

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

// The app: one HTTP client for everything, and pictures kept on the phone's storage (a small memory share, a larger
// disk one), so lists open again at once.
class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        instance = this
        Store.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(Net.http)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.12).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("thumbs")).maxSizeBytes(300L * 1024 * 1024).build() }
        .crossfade(false)
        .build()

    companion object {
        lateinit var instance: App
    }
}
