package dev.mytv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Construit l'ExoPlayer réglé pour le zapping rapide sur Fire TV Stick (2 Go de RAM).
 *
 * Choix non évidents :
 * - **Buffer de démarrage court** (1 s) : l'image apparaît vite après un zap ; le buffer max
 *   reste modeste (30 s) pour ne pas gaspiller la RAM du Stick.
 * - **Extracteur TS permissif** : beaucoup de flux IPTV commencent sans image-clé IDR ; sans
 *   FLAG_ALLOW_NON_IDR_KEYFRAMES, l'image peut rester noire plusieurs secondes.
 * - **Repli de décodeur** activé : si le décodeur matériel refuse un flux, on tente le suivant.
 * - Le réseau passe par le même OkHttpClient que l'API (User-Agent, pool de connexions).
 */
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    @OptIn(UnstableApi::class)
    fun create(): ExoPlayer {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
        val dataSource = OkHttpDataSource.Factory(okHttpClient)
        val mediaSources = DefaultMediaSourceFactory(dataSource, extractors)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 30_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 3_000,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            .build()
            .apply { playWhenReady = true }
    }
}
