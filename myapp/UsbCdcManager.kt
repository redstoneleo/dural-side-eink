package com.example.myapp

import android.hardware.usb.*
import android.util.Log
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class UsbCdcManager(
    private val usbManager: UsbManager,
    private val onDataReceived: (ByteArray) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val TAG = "UsbCdcManager"
        private const val USB_CLASS_COMM = 0x02
        private const val USB_CLASS_CDC_DATA = 0x0A
        private const val CDC_SET_CONTROL_LINE_STATE = 0x22
        private const val CDC_REQTYPE_HOST2DEVICE = 0x21
        private const val READ_BUFFER_SIZE = 16384
        private const val USB_TIMEOUT = 1000
    }

    private var connection: UsbDeviceConnection? = null
    private var controlInterface: UsbInterface? = null
    private var dataInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private var readThread: Thread? = null
    private val isRunning = AtomicBoolean(false)
    private val ackSemaphore = java.util.concurrent.Semaphore(0)

    fun open(device: UsbDevice): Boolean {
        Log.d(TAG, "Opening: ${device.deviceName} VID=${device.vendorId} PID=${device.productId}")

        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.d(TAG, "  Interface $i: class=0x${iface.interfaceClass.toString(16)} subclass=${iface.interfaceSubclass} endpoints=${iface.endpointCount}")
            when (iface.interfaceClass) {
                USB_CLASS_COMM -> {
                    controlInterface = iface
                    Log.d(TAG, "  → CDC Communication")
                }
                USB_CLASS_CDC_DATA -> {
                    dataInterface = iface
                    for (j in 0 until iface.endpointCount) {
                        val ep = iface.getEndpoint(j)
                        if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                            if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                            else bulkOut = ep
                        }
                    }
                    Log.d(TAG, "  → CDC Data, BulkIn=${bulkIn?.address} BulkOut=${bulkOut?.address}")
                }
            }
        }

        if (dataInterface == null || bulkIn == null || bulkOut == null) {
            onError("CDC interfaces not found")
            return false
        }

        connection = usbManager.openDevice(device)
        if (connection == null) {
            onError("openDevice() returned null")
            return false
        }

        // Claim Control interface
        controlInterface?.let { ctrl ->
            val ok = connection!!.claimInterface(ctrl, true)
            Log.d(TAG, "Claim control(${ctrl.id}): $ok")
        }

        if (!connection!!.claimInterface(dataInterface!!, true)) {
            onError("Failed to claim data interface")
            close()
            return false
        }

        // DTR to Control interface
        controlInterface?.let { ctrl ->
            val r1 = connection!!.controlTransfer(
                CDC_REQTYPE_HOST2DEVICE, CDC_SET_CONTROL_LINE_STATE,
                0x01, ctrl.id, null, 0, USB_TIMEOUT
            )
            Log.d(TAG, "DTR result: $r1")
            val r2 = connection!!.controlTransfer(
                CDC_REQTYPE_HOST2DEVICE, CDC_SET_CONTROL_LINE_STATE,
                0x03, ctrl.id, null, 0, USB_TIMEOUT
            )
            Log.d(TAG, "DTR+RTS result: $r2")
        }

        isRunning.set(true)
        readThread = Thread({
            Log.d(TAG, "Read thread started")
            val buf = ByteArray(READ_BUFFER_SIZE)
            while (isRunning.get()) {
                try {
                    val len = connection?.bulkTransfer(bulkIn!!, buf, buf.size, 200) ?: -1
                    if (len > 0) {
                        val data = buf.copyOf(len)
                        // Check for ACK ('A')
                        var ackCount = 0
                        for (b in data) {
                            if (b == 'A'.code.toByte()) {
                                ackSemaphore.release()
                                ackCount++
                            }
                        }
                        if (ackCount > 0) Log.d(TAG, "Received $ackCount ACKs")
                        onDataReceived(data)
                    }
                } catch (e: Exception) {
                    if (isRunning.get()) Log.e(TAG, "Read error: ${e.message}")
                }
            }
            Log.d(TAG, "Read thread exited")
        }, "UsbCdcRead").apply { isDaemon = true; start() }

        Log.d(TAG, "USB CDC opened OK")
        return true
    }

    fun write(data: ByteArray): Boolean {
        val conn = connection ?: return false
        val ep = bulkOut ?: return false
        var offset = 0
        while (offset < data.size) {
            val chunk = minOf(data.size - offset, 16384)
            val sent = conn.bulkTransfer(ep, data, offset, chunk, 2000)
            if (sent < 0) {
                Log.e(TAG, "Bulk transfer failed at offset $offset")
                return false
            }
            offset += sent
        }
        return true
    }

    /**
     * Optimized write method for chunk-ack-v4 protocol
     * 1. Write 12-byte header first
     * 2. For each chunkSize bytes, wait for one 'A' (ACK)
     */
    fun writeWithAck(data: ByteArray, chunkSize: Int = 4096, timeoutMs: Long = 1000): Boolean {
        val conn = connection ?: return false
        val ep = bulkOut ?: return false
        if (data.size < 12) return write(data)

        ackSemaphore.drainPermits() // Clear old ACKs

        // 1. Send 12-byte header (EIMG + Header)
        var sent = conn.bulkTransfer(ep, data, 0, 12, USB_TIMEOUT)
        if (sent < 12) {
            Log.e(TAG, "Failed to send header: $sent")
            return false
        }
        Log.d(TAG, "Header sent OK")

        // 2. Send data in chunks and wait for ACK
        var offset = 12
        var chunkIdx = 0
        while (offset < data.size) {
            val remaining = data.size - offset
            val toSend = minOf(remaining, chunkSize)
            
            sent = conn.bulkTransfer(ep, data, offset, toSend, USB_TIMEOUT)
            if (sent < 0) {
                Log.e(TAG, "Bulk transfer failed at chunk $chunkIdx, offset $offset")
                return false
            }
            offset += sent

            // Wait for ACK
            try {
                // Use provided timeout to accommodate slow E-Ink refresh
                if (!ackSemaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS)) {
                    Log.e(TAG, "Wait ACK timeout at chunk $chunkIdx, offset $offset (sent $sent bytes). Board might be busy drawing.")
                    return false
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
            chunkIdx++
        }
        Log.d(TAG, "Frame sent OK ($offset bytes)")
        return true
    }

    fun close() {
        isRunning.set(false)
        readThread?.interrupt()
        try { readThread?.join(500) } catch (_: Exception) {}
        readThread = null
        connection?.releaseInterface(controlInterface)
        connection?.releaseInterface(dataInterface)
        connection?.close()
        connection = null
        controlInterface = null
        dataInterface = null
        bulkIn = null
        bulkOut = null
        Log.d(TAG, "USB CDC closed")
    }

    val isOpen: Boolean get() = connection != null && isRunning.get()
}

