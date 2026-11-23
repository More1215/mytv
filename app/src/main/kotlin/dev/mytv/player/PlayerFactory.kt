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
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.domain.model.DecoderMode
import dev.mytv.domain.model.PlayerSettings
import okhttp3.OkHttpClient
import javax.inject.Inject

/**
 * Construit un ExoPlayer selon les réglages (tampon, décodage), pensé pour le Fire TV Stick (2 Go).
 *
 * Choix non évidents :
 * - **Tampon de démarrage court** par défaut (1 s) : l'image apparaît vite après un zap ;
 *   les profils « Équilibré » / « Stabilité » privilégient l'absence de coupures.
 * - **Extracteur TS permissif** : beaucoup de flux IPTV commencent sans image-clé IDR ; sans
 *   FLAG_ALLOW_NON_IDR_KEYFRAMES, l'image peut rester noire plusieurs secondes.
 * - **Décodage « logiciel »** : on garde tous les décodeurs mais on place les décodeurs logiciels
 *   d'Android en premier. Utile quand le décodeur matériel affiche mal un flux exotique.
 * - **Repli de décodeur** activé : si un décodeur refuse un flux, on tente le suivant.
 * - Le réseau passe par le même OkHttpClient que l'API (User-Agent, DNS chiffré, kill switch).
 */
class PlayerFactory @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
) {
    @OptIn(UnstableApi::class)
    fun create(settings: PlayerSettings): ExoPlayer {
        val extractors = DefaultExtractorsFactory()
            .setTsExtractorFlags(
                DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES or
                    DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS,
            )
        val mediaSources = DefaultMediaSourceFactory(OkHttpDataSource.Factory(okHttpClient), extractors)

        val b = settings.buffer
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.afterRebufferMs)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        val renderers = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .apply { if (settings.decoder == DecoderMode.SOFTWARE) setMediaCodecSelector(softwareFirst) }

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

    /** Mêmes décodeurs que par défaut, mais les décodeurs logiciels passent devant. */
    @OptIn(UnstableApi::class)
    private val softwareFirst = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            .sortedBy { !it.softwareOnly }
    }
}
