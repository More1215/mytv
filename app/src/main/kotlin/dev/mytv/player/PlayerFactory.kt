package dev.mytv.player

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import android.os.Handler
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.mytv.DeviceProfile
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
        // Plafond en OCTETS : 80 s d'un flux 4K (~25 Mbit/s) pèseraient ~250 Mo et feraient tuer
        // l'appli sur une box à 1 Go. Le plafond l'emporte sur la durée demandée.
        val lowEnd = DeviceProfile.isLowEnd(context)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(b.minMs, b.maxMs, b.startMs, b.afterRebufferMs)
            .setTargetBufferBytes(if (lowEnd) LOW_END_BUFFER_BYTES else BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()

        // En HLS adaptatif, ne pas choisir une qualité supérieure à l'écran réel (box 720p...).
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(buildUponParameters().setViewportSizeToPhysicalDisplaySize(true))
        }

        val renderers = SafeSurfaceRenderersFactory(context)
            .setEnableDecoderFallback(true)
            .apply { if (settings.decoder == DecoderMode.SOFTWARE) setMediaCodecSelector(softwareFirst) }

        return ExoPlayer.Builder(context, renderers)
            .setMediaSourceFactory(mediaSources)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
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

    private companion object {
        const val BUFFER_BYTES = 64 * 1024 * 1024
        const val LOW_END_BUFFER_BYTES = 24 * 1024 * 1024
    }

    /** Mêmes décodeurs que par défaut, mais les décodeurs logiciels passent devant. */
    @OptIn(UnstableApi::class)
    private val softwareFirst = MediaCodecSelector { mimeType, requiresSecure, requiresTunneling ->
        MediaCodecUtil.getDecoderInfos(mimeType, requiresSecure, requiresTunneling)
            .sortedBy { !it.softwareOnly }
    }
}

/**
 * Passer du plein écran (SurfaceView) à l'aperçu (TextureView) change la surface du décodeur
 * vidéo. Par défaut, ExoPlayer la change « à chaud » (MediaCodec.setOutputSurface) ; sur
 * certaines puces de box bon marché (Amlogic des Xiaomi Mi Box / TV Stick...), c'est mal géré :
 * l'image se brouille et des zones noires envahissent l'écran.
 *
 * Ici, chaque changement de surface recrée proprement le décodeur : une fraction de seconde de
 * noir au lieu d'un affichage cassé, et un comportement identique sur toutes les machines.
 */
@OptIn(UnstableApi::class)
private class SafeSurfaceRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>,
    ) {
        out.add(
            SafeSurfaceVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ),
        )
    }
}

@OptIn(UnstableApi::class)
private class SafeSurfaceVideoRenderer(
    context: Context,
    selector: MediaCodecSelector,
    joiningMs: Long,
    decoderFallback: Boolean,
    handler: Handler,
    listener: VideoRendererEventListener,
    maxDropped: Int,
) : MediaCodecVideoRenderer(context, selector, joiningMs, decoderFallback, handler, listener, maxDropped) {
    override fun codecNeedsSetOutputSurfaceWorkaround(name: String): Boolean = true
}
