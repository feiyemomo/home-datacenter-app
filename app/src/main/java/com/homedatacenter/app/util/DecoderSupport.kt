package com.homedatacenter.app.util

import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log

/**
 * Hardware/software decode capability probes.
 *
 * v1.8.48: HEVC passthrough live. The backend exposes a zero-transcode
 * HLS stream (<name>_hevc) for native-HEVC cameras; we only use it when
 * this device can actually decode HEVC (H.265). Probe via MediaCodecList
 * instead of assuming — cheap and runs once.
 */
object DecoderSupport {
    private val TAG = "DecoderSupport"

    private val _hevcDecodable: Boolean? by lazy {
        try {
            val decodable = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.contains(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }
            Log.d(TAG, "HEVC hard decode support = $decodable")
            decodable
        } catch (t: Throwable) {
            Log.w(TAG, "HEVC probe failed, assuming unsupported", t)
            false
        }
    }

    /** True when the device can decode HEVC/H.265 video. */
    fun canDecodeHevc(): Boolean = _hevcDecodable == true
}