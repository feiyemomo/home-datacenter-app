package com.homedatacenter.app.util

import android.os.Build
import com.google.android.exoplayer2.DefaultRenderersFactory
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil

/**
 * Renderer factory helpers for video playback.
 *
 * The Android emulator (and a handful of cheap set-top boxes) ship a
 * Codec2 "hardware" decoder named `c2.goldfish.h264.decoder` that
 * initializes cleanly and even accepts buffers, but returns
 * "decoder function returned error but continuing for this codec" on
 * every frame and renders nothing. ExoPlayer never sees a hard
 * failure (so `setEnableDecoderFallback(true)` does NOT trigger), the
 * user sees an endless spinner, and `onPlayerError` is never called.
 *
 * The fix is to filter `goldfish` decoders out of the candidate list
 * on emulators, so ExoPlayer falls through to the platform software
 * AVC decoder (`c2.android.avc.decoder`) which decodes correctly.
 *
 * On real devices we keep the default `MediaCodecSelector.DEFAULT`,
 * so hardware decoders (which work) are still preferred.
 */
object ExoPlayerRendererFactory {

    /**
     * True if the running device looks like an Android emulator.
     * Based on the standard checks used by the Android team's own
     * emulator detection (Fingerprint / Model / Manufacturer / Brand
     * / Device / Product). The `sdk_gphone` product check is the
     * strongest signal for modern AOSP / Google emulators.
     */
    val isEmulator: Boolean by lazy {
        val fp = Build.FINGERPRINT.orEmpty()
        val model = Build.MODEL.orEmpty()
        val product = Build.PRODUCT.orEmpty()
        val brand = Build.BRAND.orEmpty()
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val device = Build.DEVICE.orEmpty()
        val hardware = Build.HARDWARE.orEmpty()

        fp.startsWith("generic") ||
            fp.contains("generic") ||
            model.contains("Emulator") ||
            model.contains("Android SDK") ||
            manufacturer.contains("Genymotion") ||
            brand.startsWith("generic") ||
            device.startsWith("generic") ||
            product.contains("sdk_gphone") ||
            product.contains("vbox") ||
            product.contains("emulator") ||
            hardware.contains("goldfish") ||
            hardware.contains("ranchu")
    }

    /**
     * Returns a [DefaultRenderersFactory] tuned for this device.
     *
     * - On emulators: uses a [MediaCodecSelector] that filters out
     *   `goldfish` (hardware) decoders so ExoPlayer uses the
     *   platform software decoder instead.
     * - On real devices: uses the default selector with
     *   `setEnableDecoderFallback(true)` so ExoPlayer falls back to
     *   the next available decoder if the primary one fails to init.
     *
     * Both paths set `setEnableDecoderFallback(true)` for safety.
     */
    fun create(context: android.content.Context): DefaultRenderersFactory {
        val factory = DefaultRenderersFactory(context)
            .setEnableDecoderFallback(true)

        if (isEmulator) {
            factory.setMediaCodecSelector(SoftwareOnlyMediaCodecSelector)
        }
        return factory
    }

    /**
     * MediaCodecSelector that filters out the emulator's broken
     * `c2.goldfish.*` and `OMX.google.*` hardware decoders, leaving
     * only the platform software decoders (`c2.android.*`).
     *
     * If for some reason the filter returns an empty list (no
     * software decoder available for the mime type), we fall back to
     * the default selector — better to try the goldfish decoder than
     * to have no decoder at all.
     */
    private object SoftwareOnlyMediaCodecSelector : MediaCodecSelector {

        private val blockedPrefixes = listOf(
            "c2.goldfish.",
            "OMX.google.goldfish",
            "OMX.GES.goldfish",
        )

        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean
        ): List<MediaCodecInfo> {
            val all = MediaCodecUtil.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            val filtered = all.filter { info ->
                blockedPrefixes.none { info.name.startsWith(it) }
            }
            // If filtering wiped out everything, fall back to the
            // original list. The caller will still benefit from
            // `setEnableDecoderFallback(true)` if those decoders fail.
            return filtered.ifEmpty { all }
        }
    }
}
