package app.dak.ui.conversation

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.UUID
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Shrinks video and audio that are over the MMS size limit, with platform codecs only (MediaExtractor → MediaCodec →
 * MediaMuxer; no Media3 dependency). Output is MP4: H.264 (Baseline requested) with AAC-LC audio for video, AAC-LC
 * alone for audio (3GPP TS 26.140 media types). [MmsTranscodePlan] picks the size, bitrate and frame rate.
 *
 * Video frames go decoder → SurfaceTexture → GLES draw → the encoder's input surface, which scales on the GPU. Frames
 * are dropped down to the plan's frame rate. Audio is decoded to 16-bit PCM and re-encoded at a low bitrate.
 *
 * Every call is blocking and must run off the main thread. It never throws: any codec, container or GL failure,
 * a timeout, or output that still does not fit gives null, and the caller refuses the attachment as before.
 */
internal class MmsMediaTranscoder(private val context: Context) {

    data class Result(val mimeType: String, val bytes: ByteArray)

    fun transcode(uri: Uri, mimeType: String, budgetBytes: Int): Result? {
        val deadline = SystemClock.elapsedRealtime() + DEADLINE_MS
        return try {
            when {
                mimeType.startsWith("video/") -> transcodeVideo(uri, budgetBytes, deadline)?.let { Result("video/mp4", it) }
                mimeType.startsWith("audio/") -> transcodeAudio(uri, budgetBytes, deadline)?.let { Result("audio/mp4", it) }
                else -> null
            }
        } catch (t: Throwable) {
            if (t is VirtualMachineError) throw t
            Log.w(TAG, "transcode failed: ${t.javaClass.simpleName}")
            null
        } finally {
            // Leftovers from a process killed mid-transcode; this call's own files are deleted as it goes.
            val stale = System.currentTimeMillis() - STALE_MS
            workDir().listFiles()?.filter { it.lastModified() < stale }?.forEach { runCatching { it.delete() } }
        }
    }

    // ---- probing ----

    private class Probe(
        val videoTrack: Int,
        val width: Int,
        val height: Int,
        val rotation: Int,
        val audioTrack: Int,
        val audioChannels: Int,
        val durationUs: Long,
    )

    private fun probe(uri: Uri): Probe? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var video = -1
            var audio = -1
            var videoFormat: MediaFormat? = null
            var audioFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (video < 0 && mime.startsWith("video/")) { video = i; videoFormat = format }
                if (audio < 0 && mime.startsWith("audio/")) { audio = i; audioFormat = format }
            }
            val duration = listOfNotNull(videoFormat, audioFormat)
                .mapNotNull { f -> if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else null }
                .maxOrNull()?.takeIf { it > 0 }
                ?: retrieverDurationUs(uri)
                ?: return null
            val rotation = videoFormat?.let { f ->
                if (f.containsKey(MediaFormat.KEY_ROTATION)) f.getInteger(MediaFormat.KEY_ROTATION) else retrieverRotation(uri)
            } ?: 0
            return Probe(
                videoTrack = video,
                width = videoFormat?.intOr(MediaFormat.KEY_WIDTH) ?: 0,
                height = videoFormat?.intOr(MediaFormat.KEY_HEIGHT) ?: 0,
                rotation = ((rotation % 360) + 360) % 360,
                audioTrack = audio,
                audioChannels = audioFormat?.intOr(MediaFormat.KEY_CHANNEL_COUNT) ?: 0,
                durationUs = duration,
            )
        } finally {
            extractor.release()
        }
    }

    private fun MediaFormat.intOr(key: String): Int = if (containsKey(key)) getInteger(key) else 0

    private fun retrieverDurationUs(uri: Uri): Long? = withRetriever(uri) {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.times(1000)
    }

    private fun retrieverRotation(uri: Uri): Int? = withRetriever(uri) {
        it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()
    }

    private fun <T> withRetriever(uri: Uri, block: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            block(retriever)
        } catch (e: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ---- audio ----

    private class EncodedAudio(val format: MediaFormat, val samples: List<Sample>) {
        val bytes: Long get() = samples.sumOf { it.data.size.toLong() }
    }

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    private class OverBudget : Exception()

    private class Unsupported(message: String) : Exception(message)

    private fun transcodeAudio(uri: Uri, budgetBytes: Int, deadline: Long): ByteArray? {
        val probe = probe(uri) ?: return null
        if (probe.audioTrack < 0) return null
        for (attempt in 0 until MmsTranscodePlan.MAX_ATTEMPTS) {
            val plan = MmsTranscodePlan.audio(budgetBytes, probe.durationUs, probe.audioChannels, attempt) ?: return null
            val audio = try {
                encodeAudio(uri, probe.audioTrack, plan.bitrate, budgetBytes.toLong(), deadline)
            } catch (e: OverBudget) {
                continue
            }
            val out = tempFile()
            try {
                val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                try {
                    val track = muxer.addTrack(audio.format)
                    muxer.start()
                    writeAudio(muxer, track, audio.samples, 0, Long.MAX_VALUE)
                    muxer.stop()
                } finally {
                    runCatching { muxer.release() }
                }
                if (out.length() in 1..budgetBytes.toLong()) return out.readBytes()
            } finally {
                out.delete()
            }
        }
        return null
    }

    /**
     * Decodes [track] to PCM and re-encodes it as AAC-LC at [bitrate]. Throws [OverBudget] once the encoded stream
     * passes [maxBytes], and [Unsupported] for PCM this code cannot feed (float, more than two channels).
     */
    private fun encodeAudio(uri: Uri, track: Int, bitrate: Int, maxBytes: Long, deadline: Long): EncodedAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(track)
            val inFormat = extractor.getTrackFormat(track)
            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME) ?: throw Unsupported("no mime"))
            decoder = dec
            dec.configure(inFormat, null, null, 0)
            dec.start()

            val info = MediaCodec.BufferInfo()
            val samples = ArrayList<Sample>()
            var outFormat: MediaFormat? = null
            var total = 0L
            var extractorDone = false
            var encoderDone = false
            var pending: ByteBuffer? = null
            var pendingIndex = -1
            var pendingPtsUs = 0L
            var pendingConsumed = 0L
            var pendingEos = false
            var bytesPerSecond = 0L

            while (!encoderDone) {
                checkDeadline(deadline)
                if (!extractorDone) extractorDone = feed(extractor, dec)

                if (pending == null && !pendingEos) {
                    val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && encoder == null) {
                        encoder = audioEncoder(dec.outputFormat, bitrate)
                        bytesPerSecond = pcmBytesPerSecond(dec.outputFormat)
                    } else if (o >= 0) {
                        if (encoder == null) {
                            encoder = audioEncoder(dec.outputFormat, bitrate)
                            bytesPerSecond = pcmBytesPerSecond(dec.outputFormat)
                        }
                        val buffer = dec.getOutputBuffer(o) ?: throw Unsupported("no output buffer")
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        pending = buffer
                        pendingIndex = o
                        pendingPtsUs = info.presentationTimeUs
                        pendingConsumed = 0
                        pendingEos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    }
                }

                val enc = encoder
                val src = pending
                if (enc != null && src != null) {
                    val i = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val dst = enc.getInputBuffer(i) ?: throw Unsupported("no input buffer")
                        dst.clear()
                        val n = minOf(dst.remaining(), src.remaining())
                        val slice = src.duplicate()
                        slice.limit(slice.position() + n)
                        dst.put(slice)
                        src.position(src.position() + n)
                        val ptsUs = pendingPtsUs + if (bytesPerSecond > 0) pendingConsumed * 1_000_000 / bytesPerSecond else 0
                        pendingConsumed += n
                        val last = !src.hasRemaining()
                        val flags = if (last && pendingEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        enc.queueInputBuffer(i, 0, n, ptsUs, flags)
                        if (last) {
                            dec.releaseOutputBuffer(pendingIndex, false)
                            pending = null
                        }
                    }
                } else if (enc == null && pendingEos) {
                    throw Unsupported("no decoded audio")
                }

                if (enc != null) {
                    val o = enc.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        outFormat = enc.outputFormat
                    } else if (o >= 0) {
                        val buffer = enc.getOutputBuffer(o)
                        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && !isConfig && info.size > 0) {
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(data, 0, info.size)
                            samples += Sample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME)
                            total += info.size
                        }
                        enc.releaseOutputBuffer(o, false)
                        if (total > maxBytes) throw OverBudget()
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
            val format = outFormat ?: throw Unsupported("no encoder format")
            if (samples.isEmpty()) throw Unsupported("no audio samples")
            return EncodedAudio(format, samples)
        } finally {
            releaseCodec(decoder)
            releaseCodec(encoder)
            extractor.release()
        }
    }

    private fun audioEncoder(decoded: MediaFormat, bitrate: Int): MediaCodec {
        if (decoded.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
            decoded.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT
        ) {
            throw Unsupported("pcm encoding")
        }
        val channels = decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val sampleRate = decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        if (channels !in 1..2) throw Unsupported("channels")
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: RuntimeException) {
            releaseCodec(encoder)
            throw e
        }
        return encoder
    }

    private fun pcmBytesPerSecond(decoded: MediaFormat): Long =
        decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE).toLong() * decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) * 2

    /** Writes audio samples from [from] with pts ≤ [untilUs]; returns the index of the first sample not written. */
    private fun writeAudio(muxer: MediaMuxer, track: Int, samples: List<Sample>, from: Int, untilUs: Long): Int {
        var i = from
        val info = MediaCodec.BufferInfo()
        while (i < samples.size && samples[i].ptsUs <= untilUs) {
            val s = samples[i]
            info.set(0, s.data.size, s.ptsUs, s.flags)
            muxer.writeSampleData(track, ByteBuffer.wrap(s.data), info)
            i++
        }
        return i
    }

    // ---- video ----

    private fun transcodeVideo(uri: Uri, budgetBytes: Int, deadline: Long): ByteArray? {
        val probe = probe(uri) ?: return null
        if (probe.videoTrack < 0) return null
        for (attempt in 0 until MmsTranscodePlan.MAX_ATTEMPTS) {
            val plan = MmsTranscodePlan.video(budgetBytes, probe.durationUs, probe.width, probe.height, probe.audioChannels, attempt)
                ?: return null
            // Sound is worth keeping but not required: a clip whose audio cannot be re-encoded goes out silent.
            val audio = if (plan.audioBitrate > 0 && probe.audioTrack >= 0) {
                try {
                    encodeAudio(uri, probe.audioTrack, plan.audioBitrate, budgetBytes.toLong() / 2, deadline)
                } catch (e: Exception) {
                    if (e is DeadlineExceeded) throw e
                    null
                }
            } else {
                null
            }
            val out = tempFile()
            try {
                val fits = encodeVideo(uri, probe, plan, audio, out, budgetBytes.toLong(), deadline)
                if (fits && out.length() in 1..budgetBytes.toLong()) return out.readBytes()
            } finally {
                out.delete()
            }
        }
        return null
    }

    /** Returns false when the output went over [maxBytes] (the caller retries at a lower bitrate). */
    private fun encodeVideo(
        uri: Uri,
        probe: Probe,
        plan: MmsTranscodePlan.Video,
        audio: EncodedAudio?,
        out: File,
        maxBytes: Long,
        deadline: Long,
    ): Boolean {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var gl: GlPipeline? = null
        var muxer: MediaMuxer? = null
        var muxStarted = false
        try {
            extractor.setDataSource(context, uri, null)
            extractor.selectTrack(probe.videoTrack)
            val inFormat = extractor.getTrackFormat(probe.videoTrack)
            // Decode unrotated frames; the rotation goes into the MP4 as an orientation hint instead.
            inFormat.setInteger(MediaFormat.KEY_ROTATION, 0)

            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            encoder = enc
            configureVideoEncoder(enc, plan)
            val pipeline = GlPipeline(enc.createInputSurface(), plan.width, plan.height)
            gl = pipeline
            enc.start()

            val dec = MediaCodec.createDecoderByType(inFormat.getString(MediaFormat.KEY_MIME) ?: return false)
            decoder = dec
            dec.configure(inFormat, pipeline.decoderSurface, null, 0)
            dec.start()

            val mux = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val info = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var videoTrack = -1
            var audioTrack = -1
            var audioNext = 0
            var written = 0L
            var nextKeepUs = Long.MIN_VALUE
            val audioBytes = audio?.bytes ?: 0L

            while (!encoderDone) {
                checkDeadline(deadline)
                if (!extractorDone) extractorDone = feed(extractor, dec)

                if (!decoderDone) {
                    val o = dec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o >= 0) {
                        val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        val render = info.size > 0 && info.presentationTimeUs >= nextKeepUs
                        dec.releaseOutputBuffer(o, render)
                        if (render) {
                            pipeline.awaitFrame()
                            pipeline.draw(info.presentationTimeUs * 1000)
                            nextKeepUs = MmsTranscodePlan.nextFrameUs(info.presentationTimeUs, plan.frameRate)
                        }
                        if (eos) {
                            enc.signalEndOfInputStream()
                            decoderDone = true
                        }
                    }
                }

                // Drain everything the encoder has ready: an undrained encoder stops taking input frames, and
                // eglSwapBuffers would then block this thread.
                while (!encoderDone) {
                    val e = enc.dequeueOutputBuffer(encInfo, if (decoderDone) TIMEOUT_US else 0L)
                    if (e == MediaCodec.INFO_TRY_AGAIN_LATER) {
                        break
                    } else if (e == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxStarted) throw IllegalStateException("format changed twice")
                        videoTrack = mux.addTrack(enc.outputFormat)
                        if (audio != null) audioTrack = mux.addTrack(audio.format)
                        mux.setOrientationHint(probe.rotation.takeIf { it in setOf(0, 90, 180, 270) } ?: 0)
                        mux.start()
                        muxStarted = true
                    } else if (e >= 0) {
                        val buffer = enc.getOutputBuffer(e)
                        val isConfig = encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && !isConfig && encInfo.size > 0) {
                            if (!muxStarted) throw IllegalStateException("sample before format")
                            if (audio != null) audioNext = writeAudio(mux, audioTrack, audio.samples, audioNext, encInfo.presentationTimeUs)
                            buffer.position(encInfo.offset)
                            buffer.limit(encInfo.offset + encInfo.size)
                            mux.writeSampleData(videoTrack, buffer, encInfo)
                            written += encInfo.size
                        }
                        enc.releaseOutputBuffer(e, false)
                        if (written + audioBytes > maxBytes) return false
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) encoderDone = true
                    }
                }
            }
            if (!muxStarted) return false
            if (audio != null) writeAudio(mux, audioTrack, audio.samples, audioNext, Long.MAX_VALUE)
            mux.stop()
            muxStarted = false
            return true
        } finally {
            releaseCodec(decoder)
            releaseCodec(encoder)
            gl?.release()
            extractor.release()
            muxer?.let { m ->
                if (muxStarted) runCatching { m.stop() }
                runCatching { m.release() }
            }
        }
    }

    private fun configureVideoEncoder(encoder: MediaCodec, plan: MmsTranscodePlan.Video) {
        fun format(baseline: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, plan.width, plan.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, plan.videoBitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, plan.frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, plan.iFrameIntervalSec)
            if (baseline) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel3)
            }
        }
        try {
            encoder.configure(format(baseline = true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: RuntimeException) {
            // Some encoders reject an explicit profile/level; their default for these sizes is Baseline anyway.
            encoder.reset()
            encoder.configure(format(baseline = false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
    }

    // ---- shared helpers ----

    /** Queues one compressed sample into [decoder]; returns true once the end of stream was queued. */
    private fun feed(extractor: MediaExtractor, decoder: MediaCodec): Boolean {
        val i = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (i < 0) return false
        val buffer = decoder.getInputBuffer(i) ?: return false
        val n = extractor.readSampleData(buffer, 0)
        return if (n < 0) {
            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            true
        } else {
            decoder.queueInputBuffer(i, 0, n, extractor.sampleTime.coerceAtLeast(0), 0)
            extractor.advance()
            false
        }
    }

    private class DeadlineExceeded : Exception()

    private fun checkDeadline(deadline: Long) {
        if (SystemClock.elapsedRealtime() > deadline || Thread.currentThread().isInterrupted) throw DeadlineExceeded()
    }

    private fun releaseCodec(codec: MediaCodec?) {
        codec ?: return
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }

    private fun workDir(): File = File(context.cacheDir, WORK_DIR).apply { mkdirs() }

    private fun tempFile(): File = File(workDir(), UUID.randomUUID().toString() + ".mp4")

    /**
     * EGL context bound to the encoder's input surface, plus the SurfaceTexture the decoder renders into. Created,
     * used and released on the calling thread (GL contexts are thread-bound). Frame-available callbacks arrive on
     * a private HandlerThread.
     */
    private class GlPipeline(private val encoderSurface: Surface, private val width: Int, private val height: Int) {
        private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private val callbackThread = HandlerThread("dak-mms-transcode").apply { start() }
        private val frames = Semaphore(0)
        private val texture: Int
        private val surfaceTexture: SurfaceTexture
        val decoderSurface: Surface
        private val program: Int
        private val stMatrix = FloatArray(16)
        private val quad: FloatBuffer = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(QUAD).position(0) }

        init {
            // Assigned after the try so the compiler sees each val set exactly once; a failure cleans up and rethrows.
            val setup = try {
                setUpEgl()
                val builtProgram = buildProgram()
                val tex = IntArray(1)
                GLES20.glGenTextures(1, tex, 0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex[0])
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
                checkGl("texture")
                val st = SurfaceTexture(tex[0])
                st.setOnFrameAvailableListener({ frames.release() }, Handler(callbackThread.looper))
                Setup(builtProgram, tex[0], st, Surface(st))
            } catch (t: Throwable) {
                releaseEgl()
                callbackThread.quitSafely()
                encoderSurface.release()
                throw t
            }
            program = setup.program
            texture = setup.texture
            surfaceTexture = setup.surfaceTexture
            decoderSurface = setup.surface
        }

        private class Setup(val program: Int, val texture: Int, val surfaceTexture: SurfaceTexture, val surface: Surface)

        /** Waits for the frame the decoder just released to reach the SurfaceTexture. */
        fun awaitFrame() {
            if (!frames.tryAcquire(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)) throw IllegalStateException("frame timeout")
        }

        /** Draws the latest decoded frame scaled to the encoder size and submits it with [ptsNs]. */
        fun draw(ptsNs: Long) {
            surfaceTexture.updateTexImage()
            surfaceTexture.getTransformMatrix(stMatrix)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
            val aPosition = GLES20.glGetAttribLocation(program, "aPosition")
            val aTexCoord = GLES20.glGetAttribLocation(program, "aTexCoord")
            val uSt = GLES20.glGetUniformLocation(program, "uSTMatrix")
            quad.position(0)
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aPosition)
            quad.position(2)
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 16, quad)
            GLES20.glEnableVertexAttribArray(aTexCoord)
            GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            checkGl("draw")
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, ptsNs)
            if (!EGL14.eglSwapBuffers(display, eglSurface)) throw IllegalStateException("eglSwapBuffers")
        }

        fun release() {
            runCatching { decoderSurface.release() }
            runCatching { surfaceTexture.release() }
            runCatching { GLES20.glDeleteProgram(program) }
            runCatching { GLES20.glDeleteTextures(1, intArrayOf(texture), 0) }
            releaseEgl()
            callbackThread.quitSafely()
            runCatching { encoderSurface.release() }
        }

        private fun setUpEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == EGL14.EGL_NO_DISPLAY) throw IllegalStateException("no EGL display")
            val version = IntArray(2)
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw IllegalStateException("eglInitialize")
            val attribs = intArrayOf(
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGLExt.EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE,
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
                throw IllegalStateException("eglChooseConfig")
            }
            val config = configs[0] ?: throw IllegalStateException("no EGL config")
            eglContext = EGL14.eglCreateContext(
                display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            if (eglContext == EGL14.EGL_NO_CONTEXT) throw IllegalStateException("eglCreateContext")
            eglSurface = EGL14.eglCreateWindowSurface(display, config, encoderSurface, intArrayOf(EGL14.EGL_NONE), 0)
            if (eglSurface == EGL14.EGL_NO_SURFACE) throw IllegalStateException("eglCreateWindowSurface")
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) throw IllegalStateException("eglMakeCurrent")
        }

        private fun releaseEgl() {
            if (display == EGL14.EGL_NO_DISPLAY) return
            runCatching {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, eglContext)
                EGL14.eglReleaseThread()
                EGL14.eglTerminate(display)
            }
            display = EGL14.EGL_NO_DISPLAY
            eglContext = EGL14.EGL_NO_CONTEXT
            eglSurface = EGL14.EGL_NO_SURFACE
        }

        private fun buildProgram(): Int {
            val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fs = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, vs)
            GLES20.glAttachShader(p, fs)
            GLES20.glLinkProgram(p)
            val status = IntArray(1)
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
            GLES20.glDeleteShader(vs)
            GLES20.glDeleteShader(fs)
            if (status[0] != GLES20.GL_TRUE) {
                GLES20.glDeleteProgram(p)
                throw IllegalStateException("link")
            }
            return p
        }

        private fun compile(type: Int, source: String): Int {
            val shader = GLES20.glCreateShader(type)
            GLES20.glShaderSource(shader, source)
            GLES20.glCompileShader(shader)
            val status = IntArray(1)
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                GLES20.glDeleteShader(shader)
                throw IllegalStateException("compile")
            }
            return shader
        }

        private fun checkGl(op: String) {
            val error = GLES20.glGetError()
            if (error != GLES20.GL_NO_ERROR) throw IllegalStateException("$op: GL error $error")
        }

        private companion object {
            // x, y, s, t for a full-screen triangle strip.
            val QUAD = floatArrayOf(
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f,
            )
            const val VERTEX_SHADER = """
                uniform mat4 uSTMatrix;
                attribute vec4 aPosition;
                attribute vec4 aTexCoord;
                varying vec2 vTexCoord;
                void main() {
                    gl_Position = aPosition;
                    vTexCoord = (uSTMatrix * aTexCoord).xy;
                }
            """
            const val FRAGMENT_SHADER = """
                #extension GL_OES_EGL_image_external : require
                precision mediump float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES sTexture;
                void main() {
                    gl_FragColor = texture2D(sTexture, vTexCoord);
                }
            """
        }
    }

    private companion object {
        const val TAG = "MmsTranscoder"
        const val WORK_DIR = "mms-transcode"
        const val TIMEOUT_US = 10_000L
        const val FRAME_TIMEOUT_MS = 2_500L
        /** A send that takes longer than this is abandoned (the attachment is refused as too large). */
        const val DEADLINE_MS = 120_000L
        const val STALE_MS = 60 * 60 * 1000L
    }
}
