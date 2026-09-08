package com.skypatrol.groundstation.ui

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.TextureView
import android.widget.FrameLayout
import dji.sdk.keyvalue.value.common.ComponentIndexType
import dji.v5.manager.datacenter.MediaDataCenter
import dji.v5.manager.interfaces.ICameraStreamManager
import kotlin.math.max

/**
 * Texture-backed MSDK camera preview.
 *
 * Multiple preview instances share one receive-stream listener, but only an explicitly resumed
 * instance owns a decoder surface. This lets callers hand the feed between a main view and a PiP
 * without accumulating SDK listeners or retaining a surface after its Activity is paused.
 */
class DjiVideoPreviewView(context: Context) : FrameLayout(context), TextureView.SurfaceTextureListener {
    enum class VideoScaleMode {
        /** Preserves the complete frame; aspect-ratio mismatch may produce letterboxing. */
        FIT,

        /** Fills the view while preserving geometry; frame edges may be cropped. */
        FILL,
    }

    private val textureView = TextureView(context)
    private var decoderSurface: Surface? = null
    private var streamStateListener: ((Boolean) -> Unit)? = null
    private var requestedScaleMode = VideoScaleMode.FIT
    @Volatile private var active = false
    private var surfaceBound = false
    private var activeSince = 0L
    private var lastRenderedFrameAt = 0L
    private var lastRecoveryAt = 0L
    @Volatile private var lastDispatchedAvailability = false
    @Volatile private var lastStreamPacketAt = 0L

    init {
        addView(textureView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        textureView.surfaceTextureListener = this
    }

    override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (active) bind(ensureSurface(surfaceTexture), width, height)
    }

    override fun onSurfaceTextureSizeChanged(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
        if (active) bind(ensureSurface(surfaceTexture), width, height)
    }

    override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture): Boolean {
        releaseDecoderSurface()
        return true
    }

    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
        if (active) lastRenderedFrameAt = SystemClock.elapsedRealtime()
    }

    fun setStreamStateListener(listener: (Boolean) -> Unit) {
        streamStateListener = listener
    }

    fun videoScaleMode(): VideoScaleMode = requestedScaleMode

    /**
     * Changes only how the existing decoded frame is fitted into this view.
     *
     * When a decoder surface is active, MSDK receives the same Surface again with the new scale
     * type. The Surface, shared stream listener, and freshness timestamps remain untouched.
     * Otherwise the requested mode is retained and applied by the next normal bind.
     */
    fun setVideoScaleMode(mode: VideoScaleMode) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setVideoScaleMode(mode) }
            return
        }
        if (requestedScaleMode == mode) return

        val previous = requestedScaleMode
        requestedScaleMode = mode
        val surface = decoderSurface
        if (
            active &&
            surfaceBound &&
            surface != null &&
            surface.isValid &&
            textureView.width > 0 &&
            textureView.height > 0
        ) {
            reapplyScaleToExistingSurface(
                surface = surface,
                surfaceWidth = textureView.width,
                surfaceHeight = textureView.height,
                previousMode = previous,
            )
        } else {
            Log.i(
                LOG_TAG,
                "videoScaleMode=$previous->$mode deferred active=$active bound=$surfaceBound",
            )
        }
    }

    fun hasReceivedStream(): Boolean = lastStreamPacketAt > 0L

    fun isStreamFresh(maxAgeMillis: Long = 3_500L): Boolean =
        lastStreamPacketAt > 0L && SystemClock.elapsedRealtime() - lastStreamPacketAt <= maxAgeMillis

    /**
     * A point-in-time health reading. Packet freshness describes the aircraft stream while frame
     * freshness confirms that this TextureView is actually presenting new buffers.
     */
    fun streamHealth(now: Long = SystemClock.elapsedRealtime()): StreamHealth {
        val packetAt = lastStreamPacketAt.takeIf { it >= activeSince && activeSince > 0L }
        val renderedAt = lastRenderedFrameAt.takeIf { it >= activeSince && activeSince > 0L }
        return StreamHealth(
            isActive = active,
            isSurfaceBound = surfaceBound,
            activeForMillis = if (activeSince > 0L) max(0L, now - activeSince) else 0L,
            packetAgeMillis = packetAt?.let { max(0L, now - it) },
            frameAgeMillis = renderedAt?.let { max(0L, now - it) },
        )
    }

    fun resume() {
        if (!active) {
            active = true
            activeSince = SystemClock.elapsedRealtime()
            lastDispatchedAvailability = false
        }
        SharedStreamObserver.subscribe(this)
        val surfaceTexture = textureView.surfaceTexture ?: return
        bind(ensureSurface(surfaceTexture), textureView.width, textureView.height)
    }

    fun release() {
        if (active) {
            active = false
            activeSince = 0L
            dispatchStreamAvailability(false)
        }
        SharedStreamObserver.unsubscribe(this)
        releaseDecoderSurface()
    }

    /** Invalidates old-frame freshness before the camera changes lens or stream source. */
    fun markStreamTransition() {
        if (!active) return
        activeSince = SystemClock.elapsedRealtime()
        lastStreamPacketAt = 0L
        lastRenderedFrameAt = 0L
        dispatchStreamAvailability(false)
    }

    /**
     * Rebuilds only this view's decoder surface. Calls are throttled so a stalled link cannot cause
     * a one-second remove/put loop from the HUD health poller.
     */
    fun attemptRecovery(minIntervalMillis: Long = 4_000L): Boolean {
        if (!active) return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryAt < minIntervalMillis) return false
        val surfaceTexture = textureView.surfaceTexture ?: return false
        if (textureView.width <= 0 || textureView.height <= 0) return false
        lastRecoveryAt = now
        SharedStreamObserver.subscribe(this)
        releaseDecoderSurface()
        return bind(ensureSurface(surfaceTexture), textureView.width, textureView.height)
    }

    override fun onDetachedFromWindow() {
        release()
        streamStateListener = null
        super.onDetachedFromWindow()
    }

    private fun ensureSurface(surfaceTexture: SurfaceTexture): Surface {
        decoderSurface?.takeIf { it.isValid }?.let { return it }
        return Surface(surfaceTexture).also { decoderSurface = it }
    }

    private fun bind(surface: Surface, surfaceWidth: Int, surfaceHeight: Int): Boolean {
        if (!active || !surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return false
        val result = runCatching {
            val manager = MediaDataCenter.getInstance().cameraStreamManager
            // This Mini 3 cockpit owns one camera surface; release decoding when it is closed.
            manager.setKeepAliveDecoding(false)
            manager.putCameraStreamSurface(
                ComponentIndexType.LEFT_OR_MAIN,
                surface,
                surfaceWidth,
                surfaceHeight,
                requestedScaleMode.toDjiScaleType(),
            )
            manager.enableStream(ComponentIndexType.LEFT_OR_MAIN, true)
        }
        val bound = result.isSuccess
        result.exceptionOrNull()?.let {
            Log.w(LOG_TAG, "Unable to bind decoder surface scale=$requestedScaleMode", it)
        }
        surfaceBound = bound
        return bound
    }

    private fun reapplyScaleToExistingSurface(
        surface: Surface,
        surfaceWidth: Int,
        surfaceHeight: Int,
        previousMode: VideoScaleMode,
    ) {
        val result = runCatching {
            MediaDataCenter.getInstance().cameraStreamManager.putCameraStreamSurface(
                ComponentIndexType.LEFT_OR_MAIN,
                surface,
                surfaceWidth,
                surfaceHeight,
                requestedScaleMode.toDjiScaleType(),
            )
        }
        result.fold(
            onSuccess = {
                Log.i(
                    LOG_TAG,
                    "videoScaleMode=$previousMode->$requestedScaleMode " +
                        "applied=${requestedScaleMode.toDjiScaleType()} " +
                        "surface=${surfaceWidth}x$surfaceHeight",
                )
            },
            onFailure = {
                Log.w(
                    LOG_TAG,
                    "Unable to apply videoScaleMode=$requestedScaleMode to existing surface",
                    it,
                )
            },
        )
    }

    private fun VideoScaleMode.toDjiScaleType(): ICameraStreamManager.ScaleType = when (this) {
        VideoScaleMode.FIT -> ICameraStreamManager.ScaleType.CENTER_INSIDE
        VideoScaleMode.FILL -> ICameraStreamManager.ScaleType.CENTER_CROP
    }

    private fun releaseDecoderSurface() {
        decoderSurface?.let { surface ->
            runCatching { MediaDataCenter.getInstance().cameraStreamManager.removeCameraStreamSurface(surface) }
            surface.release()
        }
        decoderSurface = null
        surfaceBound = false
    }

    private fun onStreamPacket(receivedAt: Long) {
        if (!active) return
        lastStreamPacketAt = receivedAt
        if (!lastDispatchedAvailability) dispatchStreamAvailability(true)
    }

    private fun dispatchStreamAvailability(available: Boolean) {
        if (lastDispatchedAvailability == available) return
        lastDispatchedAvailability = available
        post {
            if (lastDispatchedAvailability == available) streamStateListener?.invoke(available)
        }
    }

    data class StreamHealth(
        val isActive: Boolean,
        val isSurfaceBound: Boolean,
        val activeForMillis: Long,
        val packetAgeMillis: Long?,
        val frameAgeMillis: Long?,
    ) {
        fun hasFreshPackets(maxAgeMillis: Long = STREAM_FRESH_WINDOW_MS): Boolean =
            packetAgeMillis != null && packetAgeMillis <= maxAgeMillis

        fun hasFreshFrames(maxAgeMillis: Long = FRAME_FRESH_WINDOW_MS): Boolean =
            frameAgeMillis != null && frameAgeMillis <= maxAgeMillis
    }

    private companion object SharedStreamObserver {
        private const val STREAM_FRESH_WINDOW_MS = 3_500L
        private const val FRAME_FRESH_WINDOW_MS = 2_500L
        private const val LOG_TAG = "SkyPatrolVideo"
        private val lock = Any()
        private val subscribers = LinkedHashSet<DjiVideoPreviewView>()
        private var listenerRegistered = false
        private val receiveStreamListener =
            ICameraStreamManager.ReceiveStreamListener { _, _, length, _ ->
                if (length <= 0) return@ReceiveStreamListener
                val receivedAt = SystemClock.elapsedRealtime()
                synchronized(lock) {
                    // Packet callbacks are frequent; update the tiny subscriber set in-place to
                    // avoid allocating a snapshot list for every encoded chunk.
                    subscribers.forEach { it.onStreamPacket(receivedAt) }
                }
            }

        fun subscribe(view: DjiVideoPreviewView) {
            val shouldRegister = synchronized(lock) {
                subscribers += view
                val registerNow = !listenerRegistered
                if (registerNow) {
                    // Reserve registration under the lock, then call the SDK outside it. Some SDK
                    // implementations wait for an in-flight callback during add/remove.
                    listenerRegistered = true
                }
                registerNow
            }
            if (!shouldRegister) return
            val result = runCatching {
                MediaDataCenter.getInstance().cameraStreamManager.addReceiveStreamListener(
                    ComponentIndexType.LEFT_OR_MAIN,
                    receiveStreamListener,
                )
            }
            if (result.isFailure) {
                synchronized(lock) { listenerRegistered = false }
                result.exceptionOrNull()?.let {
                    Log.w(LOG_TAG, "Unable to register stream listener", it)
                }
            }
        }

        fun unsubscribe(view: DjiVideoPreviewView) {
            val shouldUnregister = synchronized(lock) {
                subscribers -= view
                val unregisterNow = subscribers.isEmpty() && listenerRegistered
                if (unregisterNow) {
                    listenerRegistered = false
                }
                unregisterNow
            }
            if (!shouldUnregister) return
            val removed = runCatching {
                MediaDataCenter.getInstance().cameraStreamManager
                    .removeReceiveStreamListener(receiveStreamListener)
            }.isSuccess
            if (!removed) {
                // Assume the SDK may still own the listener. Keeping the flag prevents a duplicate
                // registration; the empty subscriber set still holds no Activity or View.
                synchronized(lock) { listenerRegistered = true }
            }
        }
    }
}
