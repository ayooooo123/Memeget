package com.memeget.nativeapp.storage

import com.memeget.nativeapp.storage.ubit.UbitBinBuf
import com.memeget.nativeapp.storage.ubit.UbitMp4Muxer
import com.memeget.nativeapp.storage.ubit.UbitMp4Sample
import com.memeget.nativeapp.storage.ubit.UbitMp4Track
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

// Verifies uBit's multi-platform video extraction, Range/Cookie transport, MPEG-TS + AES-128 HLS demuxing,
// and fast-start two-pass co64 ISO-BMFF MP4 muxing inside Memeget's LinkResolver.

class UbitVideoLinkResolverTest {

    private fun buildSyntheticMp4File(dir: File, name: String, kind: String, payloadA: ByteArray, payloadB: ByteArray): ByteArray {
        val stsd = UbitBinBuf(128).apply {
            u8(0); u24(0); u32(1L)
            if (kind == "vide") {
                box("avc1") { b ->
                    b.zeros(6); b.u16(1); b.zeros(16); b.u16(640); b.u16(360)
                    b.u32(0x00480000L); b.u32(0x00480000L); b.u32(0L); b.u16(1); b.zeros(32); b.u16(0x0018); b.u16(0xffff)
                }
            } else {
                box("mp4a") { b ->
                    b.zeros(6); b.u16(1); b.zeros(8); b.u16(2); b.u16(16); b.u32(0L); b.u32(44100L shl 16)
                }
            }
        }.toByteArray()

        val track = UbitMp4Track(
            trackId = 1,
            kind = kind,
            timescale = if (kind == "vide") 30000L else 44100L,
            width = if (kind == "vide") 640 else 0,
            height = if (kind == "vide") 360 else 0,
            stsdPayload = stsd,
            samples = mutableListOf(
                UbitMp4Sample(size = payloadA.size, duration = 1000L, isSync = true, inlineData = payloadA),
                UbitMp4Sample(size = payloadB.size, duration = 1000L, isSync = kind != "vide", inlineData = payloadB),
            ),
        )
        val out = File(dir, name)
        UbitMp4Muxer.writeMp4(listOf(track), emptyList(), out, isAudioOnlyM4a = kind == "soun")
        return out.readBytes()
    }

    private fun makeTsPacket(pid: Int, pusi: Boolean, payload: ByteArray, cc: Int = 0): ByteArray {
        val pkt = ByteArray(188)
        pkt[0] = 0x47
        pkt[1] = (((if (pusi) 0x40 else 0x00) or ((pid ushr 8) and 0x1f))).toByte()
        pkt[2] = (pid and 0xff).toByte()
        if (payload.size == 184) {
            pkt[3] = (0x10 or (cc and 0x0f)).toByte()
            System.arraycopy(payload, 0, pkt, 4, 184)
        } else {
            val stuffLen = 183 - payload.size
            pkt[3] = (0x30 or (cc and 0x0f)).toByte()
            pkt[4] = (stuffLen and 0xff).toByte()
            if (stuffLen > 0) {
                pkt[5] = 0x00
                for (i in 6 until 5 + stuffLen) pkt[i] = 0xff.toByte()
            }
            System.arraycopy(payload, 0, pkt, 5 + stuffLen, payload.size)
        }
        return pkt
    }

    private fun makePatPacket(pmtPid: Int): ByteArray {
        val sec = UbitBinBuf(32).apply {
            u8(0) // pointer_field
            u8(0x00) // table_id PAT
            u16(0xb00d) // section_length = 13
            u16(0x0001); u8(0xc1); u8(0x00); u8(0x00)
            u16(0x0001) // program_number = 1
            u16(0xe000 or (pmtPid and 0x1fff))
            u32(0L) // CRC placeholder
        }.toByteArray()
        return makeTsPacket(0x0000, pusi = true, payload = sec)
    }

    private fun makePmtPacket(pmtPid: Int, streamType: Int, elemPid: Int): ByteArray {
        val sec = UbitBinBuf(32).apply {
            u8(0) // pointer_field
            u8(0x02) // table_id PMT
            u16(0xb012) // section_length = 18
            u16(0x0001); u8(0xc1); u8(0x00); u8(0x00)
            u16(0xe000 or (elemPid and 0x1fff)) // PCR_PID
            u16(0xf000) // program_info_length = 0
            u8(streamType)
            u16(0xe000 or (elemPid and 0x1fff))
            u16(0xf000) // ES_info_length = 0
            u32(0L) // CRC placeholder
        }.toByteArray()
        return makeTsPacket(pmtPid, pusi = true, payload = sec)
    }

    private fun wrapPes(streamId: Int, pts90k: Long, es: ByteArray): ByteArray {
        val p0 = 0x20 or (((pts90k ushr 30) and 0x07L).toInt() shl 1) or 1
        val p1 = ((pts90k ushr 22) and 0xffL).toInt()
        val p2 = (((pts90k ushr 15) and 0x7fL).toInt() shl 1) or 1
        val p3 = ((pts90k ushr 7) and 0xffL).toInt()
        val p4 = ((pts90k and 0x7fL).toInt() shl 1) or 1
        return UbitBinBuf(14 + es.size).apply {
            u8(0x00); u8(0x00); u8(0x01)
            u8(streamId)
            u16(0) // unbounded PES length
            u8(0x80)
            u8(0x80) // PTS present
            u8(5)    // header data len
            u8(p0); u8(p1); u8(p2); u8(p3); u8(p4)
            bytes(es)
        }.toByteArray()
    }

    private fun buildSyntheticVideoTs(): ByteArray {
        // Minimal Baseline 320x240 H.264 SPS (67 42 C0 1E ...) + PPS (68 CE 3C 80) + IDR slice (65 88 84 21)
        val sps = byteArrayOf(0x67, 0x42, 0xc0.toByte(), 0x1e, 0xd9.toByte(), 0x01, 0x41, 0xfb.toByte(), 0x01, 0x10, 0x00, 0x00, 0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0xc0.toByte(), 0xf1.toByte(), 0x62, 0xe4.toByte(), 0x80.toByte())
        val pps = byteArrayOf(0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
        val idr = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21, 0xa0.toByte(), 0x55)
        val annexB = UbitBinBuf(128).apply {
            u32(1L); bytes(sps)
            u32(1L); bytes(pps)
            u32(1L); bytes(idr)
        }.toByteArray()
        val pes = wrapPes(0xe0, 9000L, annexB)
        return ByteArrayOutputStream().apply {
            write(makePatPacket(0x0100))
            write(makePmtPacket(0x0100, 0x1b, 0x0101))
            write(makeTsPacket(0x0101, pusi = true, payload = pes))
            write(makeTsPacket(0x0101, pusi = false, payload = ByteArray(184))) // second 0x47 sync packet for >=376 bytes
        }.toByteArray()
    }

    private fun buildSyntheticAudioTs(): ByteArray {
        // 7-byte ADTS header (44.1 kHz stereo AAC-LC) + 8-byte raw AAC payload => frame length = 15
        val rawAac = byteArrayOf(0x21, 0x10, 0x04, 0x60, 0x8c.toByte(), 0x1c, 0x55, 0xaa.toByte())
        val flen = 7 + rawAac.size
        val adts = ByteArray(flen)
        adts[0] = 0xff.toByte()
        adts[1] = 0xf1.toByte() // no CRC (protection_absent = 1)
        adts[2] = ((1 shl 6) or (4 shl 2) or 0).toByte() // AAC-LC (profile=1), 44100Hz (sri=4)
        adts[3] = ((2 shl 6) or ((flen ushr 11) and 0x03)).toByte() // stereo (ch=2)
        adts[4] = ((flen ushr 3) and 0xff).toByte()
        adts[5] = (((flen and 0x07) shl 5) or 0x1f).toByte()
        adts[6] = 0xfc.toByte()
        System.arraycopy(rawAac, 0, adts, 7, rawAac.size)

        val pes = wrapPes(0xc0, 9000L, adts)
        return ByteArrayOutputStream().apply {
            write(makePatPacket(0x0100))
            write(makePmtPacket(0x0100, 0x0f, 0x0102))
            write(makeTsPacket(0x0102, pusi = true, payload = pes))
            write(makeTsPacket(0x0102, pusi = false, payload = ByteArray(184)))
        }.toByteArray()
    }

    @Test
    fun youtubeAdaptiveVideoAndAudioStreamsMuxIntoFastStartMp4() {
        val tmpDir = Files.createTempDirectory("ubit-yt-test").toFile()
        try {
            val videoSample1 = "VID_KEYFRAME_1_PAYLOAD".toByteArray()
            val videoSample2 = "VID_PFRAME_2_PAYLOAD".toByteArray()
            val audioSample1 = "AUD_AAC_FRAME_1".toByteArray()
            val audioSample2 = "AUD_AAC_FRAME_2".toByteArray()

            val videoMp4 = buildSyntheticMp4File(tmpDir, "src_v.mp4", "vide", videoSample1, videoSample2)
            val audioM4a = buildSyntheticMp4File(tmpDir, "src_a.m4a", "soun", audioSample1, audioSample2)

            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val path = request.path.orEmpty()
                        return when {
                            path.startsWith("/watch") -> MockResponse().setBody(
                                """<html><script>var ytcfg = {"VISITOR_DATA":"CgtTestVisitor123"};</script></html>"""
                            )
                            path.startsWith("/youtubei/v1/player") -> {
                                val base = server.url("").toString().trimEnd('/')
                                MockResponse()
                                    .setHeader("Content-Type", "application/json")
                                    .setBody(
                                        """
                                        {
                                          "playabilityStatus": { "status": "OK" },
                                          "videoDetails": {
                                            "videoId": "aqz-KE-bpKQ",
                                            "title": "Big Buck Bunny Clip",
                                            "author": "Blender",
                                            "lengthSeconds": "10",
                                            "isLiveContent": false
                                          },
                                          "streamingData": {
                                            "adaptiveFormats": [
                                              {
                                                "itag": 137,
                                                "url": "$base/media/video1080.mp4",
                                                "mimeType": "video/mp4; codecs=\"avc1.640028\"",
                                                "width": 1920,
                                                "height": 1080,
                                                "fps": 30,
                                                "bitrate": 2500000,
                                                "contentLength": "${videoMp4.size}"
                                              },
                                              {
                                                "itag": 140,
                                                "url": "$base/media/audio128.m4a",
                                                "mimeType": "audio/mp4; codecs=\"mp4a.40.2\"",
                                                "bitrate": 128000,
                                                "contentLength": "${audioM4a.size}"
                                              }
                                            ]
                                          }
                                        }
                                        """.trimIndent()
                                    )
                            }
                            path == "/media/video1080.mp4" -> serveRangeAware(request, videoMp4, "video/mp4")
                            path == "/media/audio128.m4a" -> serveRangeAware(request, audioM4a, "audio/mp4")
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()

                val client = rewriteHostsToMockServer(server)
                val saved = LinkResolver.downloadUrl("https://youtu.be/aqz-KE-bpKQ", tmpDir, client)

                assertEquals("youtube_aqz-KE-bpKQ.mp4", saved.fileName)
                assertEquals("video/mp4", saved.mimeType)
                assertTrue(saved.file.exists() && saved.file.length() > 100L)

                // Verify fast-start layout: ftyp -> moov -> mdat
                val muxedBytes = saved.file.readBytes()
                val topBoxes = UbitMp4Muxer.listBoxes(muxedBytes, 0, muxedBytes.size).map { it.type }
                assertEquals(listOf("ftyp", "moov", "mdat"), topBoxes)

                // Verify both video and audio tracks survived with byte-exact sample payloads!
                val tracks = UbitMp4Muxer.readTracks(saved.file)
                assertEquals(2, tracks.size)
                assertEquals("vide", tracks[0].kind)
                assertEquals("soun", tracks[1].kind)
                assertEquals(2, tracks[0].samples.size)
                assertEquals(2, tracks[1].samples.size)
                assertArrayEquals(
                    videoSample1,
                    muxedBytes.copyOfRange(tracks[0].samples[0].offset.toInt(), (tracks[0].samples[0].offset + tracks[0].samples[0].size).toInt())
                )
                assertArrayEquals(
                    audioSample2,
                    muxedBytes.copyOfRange(tracks[1].samples[1].offset.toInt(), (tracks[1].samples[1].offset + tracks[1].samples[1].size).toInt())
                )
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun redditSeparateAudioHlsWithAes128TsSegmentsDemuxesIntoPlayableMp4WithSound() {
        val tmpDir = Files.createTempDirectory("ubit-reddit-hls").toFile()
        try {
            val aesKey = ByteArray(16) { (it + 0x11).toByte() }
            val aesIv = ByteArray(16) { (0x90 - it).toByte() }
            val ivHex = aesIv.joinToString("") { "%02x".format(it) }

            val rawVideoTs = buildSyntheticVideoTs()
            val rawAudioTs = buildSyntheticAudioTs()

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
            val encVideoTs = cipher.doFinal(rawVideoTs)

            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val base = server.url("").toString().trimEnd('/')
                        return when (request.path) {
                            "/r/memes/comments/xyz987/cooked_reaction" -> MockResponse().setBody("<html></html>")
                            "/r/memes/comments/xyz987/cooked_reaction.json?raw_json=1" -> MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    [{
                                      "data": {
                                        "children": [{
                                          "data": {
                                            "id": "xyz987",
                                            "title": "Cooked Reaction",
                                            "author": "memelord",
                                            "secure_media": {
                                              "reddit_video": {
                                                "duration": 4,
                                                "hls_url": "$base/hls/master.m3u8",
                                                "fallback_url": "$base/hls/silent_fallback.mp4"
                                              }
                                            }
                                          }
                                        }]
                                      }
                                    }]
                                    """.trimIndent()
                                )
                            "/hls/master.m3u8" -> MockResponse().setBody(
                                """
                                #EXTM3U
                                #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-grp",NAME="English",DEFAULT=YES,URI="$base/hls/audio.m3u8"
                                #EXT-X-STREAM-INF:BANDWIDTH=1800000,RESOLUTION=320x240,CODECS="avc1.42c01e,mp4a.40.2",AUDIO="audio-grp"
                                video.m3u8
                                """.trimIndent()
                            )
                            "/hls/video.m3u8" -> MockResponse().setBody(
                                """
                                #EXTM3U
                                #EXT-X-TARGETDURATION:4
                                #EXT-X-KEY:METHOD=AES-128,URI="$base/hls/key.bin",IV=0x$ivHex
                                #EXTINF:4.0,
                                seg_v0.ts
                                #EXT-X-ENDLIST
                                """.trimIndent()
                            )
                            "/hls/audio.m3u8" -> MockResponse().setBody(
                                """
                                #EXTM3U
                                #EXT-X-TARGETDURATION:4
                                #EXTINF:4.0,
                                seg_a0.ts
                                #EXT-X-ENDLIST
                                """.trimIndent()
                            )
                            "/hls/key.bin" -> MockResponse().setBody(Buffer().write(aesKey))
                            "/hls/seg_v0.ts" -> MockResponse().setBody(Buffer().write(encVideoTs))
                            "/hls/seg_a0.ts" -> MockResponse().setBody(Buffer().write(rawAudioTs))
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()

                val client = rewriteHostsToMockServer(server)
                val saved = LinkResolver.downloadUrl(
                    "https://www.reddit.com/r/memes/comments/xyz987/cooked_reaction/",
                    tmpDir,
                    client,
                )

                assertEquals("reddit_xyz987.mp4", saved.fileName)
                assertEquals("video/mp4", saved.mimeType)
                val tracks = UbitMp4Muxer.readTracks(saved.file)
                assertEquals("Expected both H.264 video and separate-rendition AAC audio tracks", 2, tracks.size)
                assertEquals("vide", tracks[0].kind)
                assertEquals("soun", tracks[1].kind)
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun tiktokShortlinkUnwrapsViaGetAndForwardsSessionCookiesAndRefererToCdn() {
        val tmpDir = Files.createTempDirectory("ubit-tiktok-test").toFile()
        try {
            val mp4Bytes = buildSyntheticMp4File(tmpDir, "tt.mp4", "vide", "TT_FRAME_1".toByteArray(), "TT_FRAME_2".toByteArray())
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        return when {
                            request.path == "/ZMrShort/" && request.method == "HEAD" ->
                                MockResponse().setResponseCode(405)
                            request.path == "/ZMrShort/" && request.method == "GET" ->
                                MockResponse()
                                    .setResponseCode(302)
                                    .setHeader("Location", "https://www.tiktok.com/@frog/video/7412345678901234567")
                            request.path == "/@frog/video/7412345678901234567" ->
                                MockResponse()
                                    .addHeader("Set-Cookie", "tt_chain_token=tok_abc999; Path=/; HttpOnly")
                                    .setBody(
                                        """
                                        <html><script id="__UNIVERSAL_DATA_FOR_REHYDRATION__" type="application/json">
                                        {"__DEFAULT_SCOPE__":{"webapp.video-detail":{"itemInfo":{"itemStruct":{
                                          "desc":"pepe dancing clip",
                                          "author":{"uniqueId":"frog"},
                                          "video":{"width":576,"height":1024,"duration":8,"playAddr":"https://v16m.tiktokcdn-us.com/cdn/tiktok_play.mp4"}
                                        }}}}}
                                        </script></html>
                                        """.trimIndent()
                                    )
                            request.path == "/cdn/tiktok_play.mp4" -> {
                                val cookie = request.getHeader("Cookie").orEmpty()
                                val referer = request.getHeader("Referer").orEmpty()
                                if (!cookie.contains("tt_chain_token=tok_abc999") || !referer.contains("tiktok.com")) {
                                    MockResponse().setResponseCode(403)
                                } else {
                                    serveRangeAware(request, mp4Bytes, "video/mp4")
                                }
                            }
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()

                val client = rewriteHostsToMockServer(server)
                val saved = LinkResolver.downloadUrl("https://vm.tiktok.com/ZMrShort/", tmpDir, client)
                assertEquals("tiktok_7412345678901234567.mp4", saved.fileName)
                assertArrayEquals(mp4Bytes, saved.file.readBytes())
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun tombstonedTwitterPostFallsBackToVxTwitterAndInfiniteLiveStreamIsRejected() {
        val tmpDir = Files.createTempDirectory("ubit-twitter-live").toFile()
        try {
            val clipBytes = buildSyntheticMp4File(tmpDir, "vx.mp4", "vide", "X_VID_1".toByteArray(), "X_VID_2".toByteArray())
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val base = server.url("").toString().trimEnd('/')
                        val p = request.path.orEmpty()
                        return when {
                            p.startsWith("/tweet-result") -> MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"__typename":"TweetTombstone","tombstone":{"text":{"text":"Age-restricted"}}}""")
                            p == "/Twitter/status/1844000000000000000" -> MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """
                                    {
                                      "text": "nsfw meme reaction",
                                      "user_screen_name": "poster",
                                      "media_extended": [{
                                        "type": "video",
                                        "url": "$base/vx/clip.mp4",
                                        "size": {"width": 720, "height": 720}
                                      }]
                                    }
                                    """.trimIndent()
                                )
                            p == "/vx/clip.mp4" -> serveRangeAware(request, clipBytes, "video/mp4")
                            p == "/live.m3u8" -> MockResponse().setBody(
                                """
                                #EXTM3U
                                #EXT-X-TARGETDURATION:4
                                #EXTINF:4.0,
                                seg1.ts
                                """.trimIndent()
                            )
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()

                val client = rewriteHostsToMockServer(server)
                val saved = LinkResolver.downloadUrl("https://fxtwitter.com/poster/status/1844000000000000000", tmpDir, client)
                assertEquals("tweet_1844000000000000000.mp4", saved.fileName)
                assertArrayEquals(clipBytes, saved.file.readBytes())

                // Live HLS stream without #EXT-X-ENDLIST must be refused cleanly instead of hanging forever
                try {
                    LinkResolver.downloadUrl(server.url("/live.m3u8").toString(), tmpDir, client)
                    throw AssertionError("Infinite live stream should be rejected")
                } catch (expected: IOException) {
                    assertTrue(expected.message.orEmpty().contains("Live streams"))
                }
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun twitchClipGqlKickClipAndDashFragmentedMp4MuxCleanly() {
        val tmpDir = Files.createTempDirectory("ubit-dash-twitch-kick").toFile()
        try {
            // Build a fragmented fMP4 init segment (ftyp + moov with mvex/trex) + media segment (moof/traf/trun + mdat)
            val fragPayload = "FRAGMENTED_DASH_H264_SAMPLE".toByteArray()
            val initSeg = UbitBinBuf(256).apply {
                box("ftyp") { b -> b.fourCc("isom"); b.u32(0x200L); b.fourCc("isom"); b.fourCc("dash") }
                box("moov") { m ->
                    m.box("mvex") { mv ->
                        mv.fullBox("trex", 0, 0) { t ->
                            t.u32(1L); t.u32(1L); t.u32(1000L); t.u32(fragPayload.size.toLong()); t.u32(0L)
                        }
                    }
                    m.box("trak") { tr ->
                        tr.fullBox("tkhd", 0, 3) { b ->
                            b.u32(0L); b.u32(0L); b.u32(1L); b.u32(0L); b.u32(0L)
                            b.zeros(8); b.u16(0); b.u16(0); b.u16(0); b.u16(0)
                            for (v in longArrayOf(0x00010000L, 0L, 0L, 0L, 0x00010000L, 0L, 0L, 0L, 0x40000000L)) b.u32(v)
                            b.u32(640L shl 16); b.u32(360L shl 16)
                        }
                        tr.box("mdia") { md ->
                            md.fullBox("mdhd", 0, 0) { b -> b.u32(0L); b.u32(0L); b.u32(30000L); b.u32(0L); b.u16(0x55c4); b.u16(0) }
                            md.fullBox("hdlr", 0, 0) { b -> b.u32(0L); b.fourCc("vide"); b.zeros(12); b.strBytes("Video"); b.u8(0) }
                            md.box("minf") { mf ->
                                mf.box("stbl") { sb ->
                                    sb.box("stsd") { s ->
                                        s.u8(0); s.u24(0); s.u32(1L)
                                        s.box("avc1") { b ->
                                            b.zeros(6); b.u16(1); b.zeros(16); b.u16(640); b.u16(360)
                                            b.u32(0x00480000L); b.u32(0x00480000L); b.u32(0L); b.u16(1); b.zeros(32); b.u16(0x0018); b.u16(0xffff)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }.toByteArray()

            // moof size: header(8) + traf(header(8) + tfhd(16) + trun(28)) = 60 bytes, mdat header = 8 => data_offset = 68
            val mediaSeg = UbitBinBuf(256).apply {
                box("moof") { mo ->
                    mo.box("traf") { tf ->
                        tf.fullBox("tfhd", 0, 0x020000) { h -> h.u32(1L) } // default-base-is-moof, track_id=1
                        tf.fullBox("trun", 0, 0x000301) { r -> // data-offset + sample-duration + sample-size
                            r.u32(1L)   // sample_count = 1
                            r.i32(68)   // data_offset from moof start -> mdat payload!
                            r.u32(1000L)
                            r.u32(fragPayload.size.toLong())
                        }
                    }
                }
                box("mdat") { md -> md.bytes(fragPayload) }
            }.toByteArray()

            val clipBytes = buildSyntheticMp4File(tmpDir, "clip.mp4", "vide", "CLIP_1".toByteArray(), "CLIP_2".toByteArray())

            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val base = server.url("").toString().trimEnd('/')
                        return when (request.path) {
                            "/gql" -> MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody(
                                    """[{"data":{"clip":{"title":"PogClip","durationSeconds":12,"broadcaster":{"displayName":"Streamer"},"playbackAccessToken":{"signature":"sig1","value":"{\"clip\":\"ok\"}"},"videoQualities":[{"quality":"1080","frameRate":60,"sourceURL":"$base/twitch/clip1080.mp4"}]}}}]"""
                                )
                            "/twitch/clip1080.mp4?sig=sig1&token=%7B%22clip%22%3A%22ok%22%7D" ->
                                serveRangeAware(request, clipBytes, "video/mp4")
                            "/api/v2/clips/clip_99" -> MockResponse()
                                .setHeader("Content-Type", "application/json")
                                .setBody("""{"clip":{"title":"KickW","clip_url":"$base/kick/clip.mp4"}}""")
                            "/kick/clip.mp4" -> serveRangeAware(request, clipBytes, "video/mp4")
                            "/manifest.mpd" -> MockResponse()
                                .setHeader("Content-Type", "application/dash+xml")
                                .setBody(
                                    """
                                    <MPD type="static" mediaPresentationDuration="PT2S">
                                      <Period>
                                        <AdaptationSet mimeType="video/mp4" codecs="avc1.4d401f">
                                          <Representation id="v720" width="1280" height="720" bandwidth="1500000">
                                            <SegmentTemplate initialization="$base/dash/init.mp4" media="$base/dash/seg_${'$'}Number%03d${'$'}.m4s" startNumber="1" duration="2" timescale="1"/>
                                          </Representation>
                                        </AdaptationSet>
                                      </Period>
                                    </MPD>
                                    """.trimIndent()
                                )
                            "/dash/init.mp4" -> MockResponse().setBody(Buffer().write(initSeg))
                            "/dash/seg_001.m4s" -> MockResponse().setBody(Buffer().write(mediaSeg))
                            else -> MockResponse().setResponseCode(404)
                        }
                    }
                }
                server.start()

                val client = rewriteHostsToMockServer(server)

                val twitchSaved = LinkResolver.downloadUrl("https://clips.twitch.tv/FunnySlug123", tmpDir, client)
                assertEquals("twitch_FunnySlug123.mp4", twitchSaved.fileName)
                assertArrayEquals(clipBytes, twitchSaved.file.readBytes())

                val kickSaved = LinkResolver.downloadUrl("https://kick.com/streamer/clips/clip_99", tmpDir, client)
                assertEquals("kick_clip_99.mp4", kickSaved.fileName)
                assertArrayEquals(clipBytes, kickSaved.file.readBytes())

                val dashSaved = LinkResolver.downloadUrl(server.url("/manifest.mpd").toString(), tmpDir, client)
                val dashTracks = UbitMp4Muxer.readTracks(dashSaved.file)
                assertEquals(1, dashTracks.size)
                assertEquals(1, dashTracks[0].samples.size)
                val dashOut = dashSaved.file.readBytes()
                assertArrayEquals(
                    fragPayload,
                    dashOut.copyOfRange(dashTracks[0].samples[0].offset.toInt(), (dashTracks[0].samples[0].offset + dashTracks[0].samples[0].size).toInt())
                )
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    private fun rewriteHostsToMockServer(server: MockWebServer): OkHttpClient {
        val mockBase = server.url("/")
        return OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val orig = chain.request().url
                val redirected = orig.newBuilder()
                    .scheme(mockBase.scheme)
                    .host(mockBase.host)
                    .port(mockBase.port)
                    .build()
                chain.proceed(chain.request().newBuilder().url(redirected).build())
            }
            .build()
    }

    private fun serveRangeAware(request: RecordedRequest, bytes: ByteArray, contentType: String): MockResponse {
        val range = request.getHeader("Range")
        if (range != null && range.startsWith("bytes=")) {
            val parts = range.removePrefix("bytes=").split('-')
            val s = parts[0].toIntOrNull() ?: 0
            val e = parts.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()?.coerceAtMost(bytes.size - 1) ?: (bytes.size - 1)
            if (s in 0 until bytes.size && e >= s) {
                val slice = bytes.copyOfRange(s, e + 1)
                return MockResponse()
                    .setResponseCode(206)
                    .setHeader("Content-Type", contentType)
                    .setHeader("Accept-Ranges", "bytes")
                    .setHeader("Content-Range", "bytes $s-$e/${bytes.size}")
                    .setBody(Buffer().write(slice))
            }
        }
        return MockResponse()
            .setResponseCode(200)
            .setHeader("Content-Type", contentType)
            .setHeader("Content-Length", bytes.size.toString())
            .setBody(Buffer().write(bytes))
    }
}
