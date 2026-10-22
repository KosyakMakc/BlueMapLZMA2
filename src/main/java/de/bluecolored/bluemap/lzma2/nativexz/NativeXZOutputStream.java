package de.bluecolored.bluemap.lzma2.nativexz;

import java.io.IOException;
import java.io.OutputStream;

public class NativeXZOutputStream extends OutputStream {

    private final OutputStream out;
    private final NativeEncoderPool pool;
    private final long handle;
    private final byte[] inBuffer = new byte[128 * 1024];
    private int inPos = 0;
    private boolean finished = false;
    private boolean closed = false;

    public NativeXZOutputStream(OutputStream out, NativeEncoderPool pool) throws IOException {
        this.out = out;
        this.pool = pool;
        this.handle = pool.claim();
    }

    public NativeXZOutputStream(OutputStream out, int preset, int threads) throws IOException {
        this.out = out;
        this.pool = null;
        this.handle = NativeLZMA2.createEncoder(preset, threads);
        if (this.handle == 0) {
            throw new IOException("Failed to create native LZMA2 encoder");
        }
    }

    @Override
    public void write(int b) throws IOException {
        if (closed) throw new IOException("Stream closed");
        inBuffer[inPos++] = (byte) b;
        if (inPos == inBuffer.length) {
            flushBuffer(inBuffer, 0, inPos, 0);
        }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (finished) throw new IOException("Stream finished");

        if (inPos > 0) {
            int toCopy = Math.min(len, inBuffer.length - inPos);
            System.arraycopy(b, off, inBuffer, inPos, toCopy);
            inPos += toCopy;
            off += toCopy;
            len -= toCopy;

            if (inPos == inBuffer.length) {
                flushBuffer(inBuffer, 0, inPos, 0);
            }
        }

        if (len >= inBuffer.length) {
            flushBuffer(b, off, len, 0);
        } else if (len > 0) {
            System.arraycopy(b, off, inBuffer, 0, len);
            inPos = len;
        }
    }

    private void flushBuffer(byte[] buffer, int offset, int length, int action) throws IOException {
        NativeXZResources resources = NativeXZResources.get();
        byte[] outBuffer = resources.outBuffer;

        int inputOff = offset;
        int inputEnd = offset + length;
        
        while (inputOff < inputEnd || action == 3) {
            long result = NativeLZMA2.process(handle, buffer, inputOff, inputEnd - inputOff, outBuffer, 0, outBuffer.length, action);
            int status = (int) (result >> 56) & 0xFF;
            int consumed = (int) (result >> 28) & 0x0FFFFFFF;
            int produced = (int) result & 0x0FFFFFFF;

            if (status < 0 || status > 10) throw new IOException("Native LZMA2 error: " + status);

            if (produced > 0) {
                out.write(outBuffer, 0, produced);
            }

            inputOff += consumed;

            if (action == 3 && status == 1) { // LZMA_STREAM_END
                break;
            }
            
            if (action != 3 && consumed == 0 && inputOff >= inputEnd) break;
        }
        inPos = 0;
    }

    public void finish() throws IOException {
        if (closed || finished) return;
        flushBuffer(inBuffer, 0, inPos, 3);
        finished = true;
    }

    @Override
    public void flush() throws IOException {
        if (closed) throw new IOException("Stream closed");
        if (inPos > 0) {
            flushBuffer(inBuffer, 0, inPos, 0);
        }
        out.flush();
    }

    @Override
    public void close() throws IOException {
        if (closed) return;
        try {
            try {
                finish();
            } finally {
                if (pool != null) {
                    pool.release(handle);
                } else {
                    NativeLZMA2.free(handle);
                }
                closed = true;
            }
        } finally {
            // Must not be skipped if finish()/release() throws, otherwise the
            // underlying file/stream (and its buffers) would never be closed.
            out.close();
        }
    }

}
