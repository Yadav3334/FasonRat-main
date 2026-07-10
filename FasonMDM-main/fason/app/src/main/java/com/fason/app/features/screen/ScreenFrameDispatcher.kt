package com.fason.app.features.screen

import android.os.Process
import com.fason.app.core.Protocol
import com.fason.app.core.network.SocketClient
import org.json.JSONObject
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Sends video on its own bounded pipeline. If the network is slower than the
 * encoder, only the latest pending frame is retained so latency cannot grow
 * without limit.
 */
class ScreenFrameDispatcher {
    private val running = AtomicBoolean(false)
    private val latestFrame = AtomicReference<ByteArray?>(null)
    private val available = Semaphore(0)
    private val sequence = AtomicLong(0)
    private var worker: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        worker = Thread({ dispatchLoop() }, "remote-desktop-socket").apply { start() }
    }

    fun offer(frame: ByteArray) {
        if (!running.get()) return
        if (latestFrame.getAndSet(frame) == null) {
            available.release()
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        latestFrame.set(null)
        available.release()
        worker?.interrupt()
        worker = null
    }

    private fun dispatchLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        while (running.get()) {
            try {
                available.acquire()
                val frame = latestFrame.getAndSet(null) ?: continue
                val payload = JSONObject().apply {
                    put(Protocol.KEY_TYPE, "frame")
                    // Socket.IO transports ByteArray as a binary attachment. This avoids
                    // Base64's CPU cost and ~33% bandwidth overhead.
                    put("frame", frame)
                    put("sequence", sequence.incrementAndGet())
                }
                SocketClient.getInstance().socket?.emit(Protocol.SCREEN, payload)
            } catch (_: InterruptedException) {
                break
            }
        }
    }
}
