package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL45.*;

public class FramebufferDownloadStream implements AutoCloseable {
    private final MainTarget framebuffer;
    private final int downloadStream;
    private final long downloadPtr;
    private final int framebufferSizeBytes;

    private final int width;
    private final int height;

    private final int maxFramesInflight;
    private int start;
    private int end;

    private final ArrayDeque<FrameInflight> inflight = new ArrayDeque<>();

    public record CompletedFrame(NativeImage image, @Nullable FloatBuffer audioBuffer) {

    }

    private record FrameInflight(long fence, long offset, @Nullable FloatBuffer audioBuffer) {
        public FrameInflight(long offset, @Nullable FloatBuffer audioBuffer) {
            this(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0), offset, audioBuffer);
        }

        boolean isReady() {
            int ret = glClientWaitSync(this.fence, 0, 0);
            if (ret == GL_ALREADY_SIGNALED || ret == GL_CONDITION_SATISFIED) {
                return true;
            } else if (ret != GL_TIMEOUT_EXPIRED) {
                throw new IllegalStateException("Poll for fence failed, glError: " + glGetError());
            }
            return false;
        }

        void free() {
            glDeleteSync(this.fence);
        }

        public void waitFence() {
            glClientWaitSync(this.fence, GL_SYNC_FLUSH_COMMANDS_BIT, Long.MAX_VALUE>>3);
        }
    }
    public FramebufferDownloadStream(int width, int height, int maxFramesInflight) {
        this.width = width;
        this.height = height;
        this.framebuffer = new MainTarget(width, height);
        this.framebufferSizeBytes = width*height*4;//4 bytes per pixel
        this.maxFramesInflight = maxFramesInflight;

        long size = maxFramesInflight*(long)this.framebufferSizeBytes;
        this.downloadStream = glCreateBuffers();
        glNamedBufferStorage(this.downloadStream, size, GL_CLIENT_STORAGE_BIT|GL_MAP_PERSISTENT_BIT|GL_MAP_READ_BIT|GL_MAP_COHERENT_BIT);
        this.downloadPtr = nglMapNamedBufferRange(this.downloadStream, 0, size, GL_MAP_READ_BIT|GL_MAP_PERSISTENT_BIT);
    }


    public void download(RenderTarget frame, @Nullable FloatBuffer audioBuffer) {
        int idx = this.start++; this.start %= this.maxFramesInflight;
        if (((idx+1)%this.maxFramesInflight)==this.end) {
            throw new IllegalStateException("No downstream space available!");
        }

        long downOffset = idx*(long)this.framebufferSizeBytes;

        frame.bindWrite(true);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.downloadStream);
        GL30C.glReadPixels(0, 0, this.width, this.height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, downOffset);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        frame.unbindWrite();

        this.inflight.add(new FrameInflight(downOffset + this.downloadPtr, audioBuffer));
    }

    public List<CompletedFrame> poll(boolean drain) {
        List<CompletedFrame> frames = new ArrayList<>();
        while ((!this.inflight.isEmpty())&&(drain||this.inflight.peek().isReady())) {
            var frame = this.inflight.poll();
            if (drain) {
                frame.waitFence();
            }

            frame.free();

            NativeImage nativeImage = new NativeImage(NativeImage.Format.RGBA, this.width, this.height, false);
            MemoryUtil.memCopy(frame.offset, nativeImage.pixels, nativeImage.size);
            nativeImage.flipY();

            //nativeImage
            frames.add(new CompletedFrame(nativeImage, frame.audioBuffer));

            this.end = (this.end+1)%this.maxFramesInflight;
        }
        return frames;
    }

    @Override
    public void close() {
        glFinish();
        while (!this.inflight.isEmpty()) {
            this.inflight.poll().free();
        }
        glUnmapNamedBuffer(this.downloadStream);
        glDeleteBuffers(this.downloadStream);
        this.framebuffer.destroyBuffers();
    }
}
