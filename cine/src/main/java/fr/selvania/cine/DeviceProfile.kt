package fr.selvania.cine

import android.media.MediaCodecList
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ce que la télé sait décoder elle-même : Jellyfin envoie alors le fichier tel quel
 * (pas de conversion, qualité d'origine, aucune charge pour le serveur).
 */
object DeviceProfile {

    private val decoders: Set<String> by lazy {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .filter { !it.isEncoder }
            .flatMap { it.supportedTypes.map(String::lowercase) }
            .toSet()
    }

    private fun has(mime: String) = mime in decoders

    fun build(): JSONObject {
        val video = mutableListOf("h264", "mpeg4", "mpeg2video")
        if (has("video/hevc")) video += "hevc"
        if (has("video/av01")) video += "av1"
        if (has("video/x-vnd.on2.vp9")) video += "vp9"
        if (has("video/x-vnd.on2.vp8")) video += "vp8"

        val audio = mutableListOf("aac", "mp3", "mp2", "opus", "flac", "vorbis", "pcm_s16le", "pcm_s24le", "alac")
        if (has("audio/ac3")) audio += "ac3"
        if (has("audio/eac3")) audio += "eac3"
        if (has("audio/eac3-joc")) audio += "eac3"
        if (has("audio/vnd.dts") || has("audio/vnd.dts.hd")) audio += listOf("dts", "dca")
        if (has("audio/true-hd")) audio += "truehd"

        val v = video.distinct().joinToString(",")
        val a = audio.distinct().joinToString(",")

        val direct = JSONArray()
        for (container in listOf("mkv,webm", "mp4,m4v,mov", "ts,mpegts,m2ts", "avi")) {
            direct.put(JSONObject().put("Container", container).put("Type", "Video").put("VideoCodec", v).put("AudioCodec", a))
        }

        val transcode = JSONArray().put(
            JSONObject()
                .put("Container", "ts").put("Type", "Video").put("Protocol", "hls").put("Context", "Streaming")
                .put("VideoCodec", if ("hevc" in video) "hevc,h264" else "h264")
                .put("AudioCodec", if ("ac3" in audio) "aac,ac3,eac3" else "aac")
                .put("MaxAudioChannels", "6").put("MinSegments", 1).put("BreakOnNonKeyFrames", true),
        )

        // Sous-titres : ceux du fichier sont lus directement ; les fichiers à part sont convertis en WebVTT.
        val subs = JSONArray()
        for (f in listOf("srt", "subrip", "ass", "ssa", "vtt", "webvtt", "pgssub", "dvdsub", "dvbsub")) {
            subs.put(JSONObject().put("Format", f).put("Method", "Embed"))
        }
        subs.put(JSONObject().put("Format", "vtt").put("Method", "External"))

        return JSONObject()
            .put("Name", "Selvania Cine")
            .put("MaxStreamingBitrate", 120_000_000)
            .put("MaxStaticBitrate", 200_000_000)
            .put("DirectPlayProfiles", direct)
            .put("TranscodingProfiles", transcode)
            .put("ContainerProfiles", JSONArray())
            .put("CodecProfiles", JSONArray())
            .put("SubtitleProfiles", subs)
    }

    /**
     * Ce que lit le récepteur par défaut d'un Chromecast : MP4 H.264 jusqu'en 1080p avec son AAC/MP3,
     * le reste est converti par le serveur (HLS).
     */
    fun cast(): JSONObject {
        val h264 = JSONObject().put("Type", "Video").put("Codec", "h264").put(
            "Conditions",
            JSONArray()
                .put(cond("LessThanEqual", "Width", "1920", true))
                .put(cond("LessThanEqual", "Height", "1080", true))
                .put(cond("LessThanEqual", "VideoLevel", "41", false))
                .put(cond("LessThanEqual", "VideoBitDepth", "8", false)),
        )
        return JSONObject()
            .put("Name", "Selvania Cine Chromecast")
            .put("MaxStreamingBitrate", 20_000_000)
            .put("MaxStaticBitrate", 20_000_000)
            .put(
                "DirectPlayProfiles",
                JSONArray()
                    .put(JSONObject().put("Container", "mp4,m4v").put("Type", "Video").put("VideoCodec", "h264").put("AudioCodec", "aac,mp3"))
                    .put(JSONObject().put("Container", "webm").put("Type", "Video").put("VideoCodec", "vp8").put("AudioCodec", "vorbis,opus")),
            )
            .put(
                "TranscodingProfiles",
                JSONArray().put(
                    JSONObject().put("Container", "ts").put("Type", "Video").put("Protocol", "hls").put("Context", "Streaming")
                        .put("VideoCodec", "h264").put("AudioCodec", "aac,mp3").put("MaxAudioChannels", "2")
                        .put("MinSegments", 1).put("BreakOnNonKeyFrames", true),
                ),
            )
            .put("ContainerProfiles", JSONArray())
            .put("CodecProfiles", JSONArray().put(h264))
            .put(
                "SubtitleProfiles",
                JSONArray()
                    .put(JSONObject().put("Format", "vtt").put("Method", "External"))
                    .put(JSONObject().put("Format", "pgssub").put("Method", "Encode"))
                    .put(JSONObject().put("Format", "dvdsub").put("Method", "Encode")),
            )
    }

    private fun cond(op: String, property: String, value: String, required: Boolean) =
        JSONObject().put("Condition", op).put("Property", property).put("Value", value).put("IsRequired", required)
}
