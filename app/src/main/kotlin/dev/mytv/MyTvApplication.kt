package dev.mytv

import android.app.Application
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.allowRgb565
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import javax.inject.Inject

@HiltAndroidApp
class MyTvApplication : Application(), SingletonImageLoader.Factory {

    @Inject lateinit var okHttpClient: OkHttpClient

    /**
     * Chargeur d'images unique (logos de chaînes). Cache mémoire limité à 15 % de la RAM allouée
     * à l'appli pour rester sobre sur le Fire TV Stick, et cache disque pour un affichage
     * instantané hors ligne.
     */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val lowEnd = DeviceProfile.isLowEnd(this)
        return ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { okHttpClient })) }
            // Machine faible : cache mémoire réduit et images sans transparence en 16 bits
            // (RGB_565, deux fois moins de RAM par affiche ; les logos transparents restent en 32 bits).
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, if (lowEnd) 0.10 else 0.15).build() }
            .allowRgb565(lowEnd)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("images").toOkioPath())
                    .maxSizeBytes(64L * 1024 * 1024)
                    .build()
            }
            .build()
    }
}
