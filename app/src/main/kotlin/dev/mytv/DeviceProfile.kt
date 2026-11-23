package dev.mytv

import android.app.ActivityManager
import android.content.Context

/**
 * Détection des machines faibles (box Android bas de gamme, vieux Fire TV Stick, 1 Go de RAM...).
 *
 * Sur ces appareils, l'appli réduit d'elle-même sa consommation : images en 16 bits et cache plus
 * petit, tampon vidéo plafonné en octets. Le comportement reste identique pour l'utilisateur.
 */
object DeviceProfile {

    @Volatile private var cached: Boolean? = null

    fun isLowEnd(context: Context): Boolean = cached ?: compute(context).also { cached = it }

    private fun compute(context: Context): Boolean {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return am.isLowRamDevice ||
            // Mémoire allouée à l'appli (hors largeHeap) : 128 Mo et moins = appareil d'entrée de gamme.
            am.memoryClass <= 128 ||
            // Moins de ~1,5 Go de RAM au total.
            info.totalMem < 1_600L * 1024 * 1024
    }
}
