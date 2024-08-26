package com.moulberry.flashback.exporting;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.glGetError;
import static org.lwjgl.opengl.GL45.*;

public class FramebufferDownloadStream implements AutoCloseable {
    private final int downloadStream;
    private final long downloadPtr;
    private final int framebufferSizeBytes;
    private final RenderTarget flippedBuffer;
    private final ShaderInstance flippedShader;

    private final int width;
    private final int height;

    private final int maxFramesInflight;
    private int start;
    private int end;

    private final ArrayDeque<FrameInflight> inflight = new ArrayDeque<>();

    public record CompletedFrame(NativeImageBuffer image, @Nullable FloatBuffer audioBuffer) {

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
        this.framebufferSizeBytes = width*height*4;//4 bytes per pixel
        this.maxFramesInflight = maxFramesInflight;
        this.flippedBuffer = new TextureTarget(width, height, false, false);

        long size = maxFramesInflight*(long)this.framebufferSizeBytes;
        this.downloadStream = glCreateBuffers();
        glNamedBufferStorage(this.downloadStream, size, GL_CLIENT_STORAGE_BIT|GL_MAP_PERSISTENT_BIT|GL_MAP_READ_BIT);
        this.downloadPtr = nglMapNamedBufferRange(this.downloadStream, 0, size, GL_MAP_READ_BIT|GL_MAP_PERSISTENT_BIT);

        try {
            this.flippedShader = new ShaderInstance(Minecraft.getInstance().getResourceManager(), "blit_screen_flip", DefaultVertexFormat.BLIT_SCREEN);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }


    public void download(RenderTarget frame, @Nullable FloatBuffer audioBuffer) {
        int idx = this.start++; this.start %= this.maxFramesInflight;
        if (((idx+1)%this.maxFramesInflight)==this.end) {
            throw new IllegalStateException("No downstream space available!");
        }


        GlStateManager._colorMask(true, true, true, false);
        GlStateManager._disableDepthTest();
        GlStateManager._depthMask(false);
        GlStateManager._viewport(0, 0, frame.width, frame.height);
        GlStateManager._disableBlend();
        RenderSystem.disableCull();

        this.flippedBuffer.bindWrite(true);
        this.flippedShader.setSampler("DiffuseSampler", frame.colorTextureId);
        this.flippedShader.apply();
        BufferBuilder bufferBuilder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN);
        bufferBuilder.addVertex(0.0F, 1.0F, 0.0F);
        bufferBuilder.addVertex(1.0F, 1.0F, 0.0F);
        bufferBuilder.addVertex(1.0F, 0.0F, 0.0F);
        bufferBuilder.addVertex(0.0F, 0.0F, 0.0F);
        BufferUploader.draw(bufferBuilder.buildOrThrow());
        this.flippedShader.clear();

        GlStateManager._depthMask(true);
        GlStateManager._colorMask(true, true, true, true);
        RenderSystem.enableCull();


        long downOffset = idx*(long)this.framebufferSizeBytes;

        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, this.downloadStream);
        GL30C.glReadPixels(0, 0, this.width, this.height, GL30C.GL_RGBA, GL30C.GL_UNSIGNED_BYTE, downOffset);
        GL30C.glBindBuffer(GL30C.GL_PIXEL_PACK_BUFFER, 0);
        this.flippedBuffer.unbindWrite();

        this.inflight.add(new FrameInflight(downOffset + this.downloadPtr, audioBuffer));
    }

    public List<CompletedFrame> poll(boolean drain) {
        List<CompletedFrame> frames = new ArrayList<>();

        if (!this.inflight.isEmpty() && ((this.start+1)%this.maxFramesInflight)==this.end) {
            //Stops No downstream space available!
            this.inflight.peek().waitFence();
        }

        while ((!this.inflight.isEmpty())&&(drain||this.inflight.peek().isReady())) {
            var frame = this.inflight.poll();
            if (drain) {
                frame.waitFence();
            }

            frame.free();

            NativeImageBuffer nativeImage = new NativeImageBuffer(frame.offset, this.width, this.height, this.framebufferSizeBytes);//NativeImage.Format.RGBA
            //MemoryUtil.memCopy(frame.offset, nativeImage.pixels, nativeImage.size);

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
        this.flippedBuffer.destroyBuffers();
        this.flippedShader.close();
    }
}
