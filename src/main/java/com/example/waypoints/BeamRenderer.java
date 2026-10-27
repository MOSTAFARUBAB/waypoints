package com.example.waypoints;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * Draws a translucent vertical beam at every waypoint, visible through walls.
 * Based on the Fabric docs "Rendering in the World" custom pipeline example.
 *
 * If the build ever fails because of this file, delete it and remove the
 * "BeamRenderer.init();" line from WaypointsMod.java - the HUD labels still work.
 */
final class BeamRenderer {
    private static final float BEAM_HALF_WIDTH = 0.12f;
    private static final float BEAM_HEIGHT = 320f;
    private static final float BEAM_ALPHA = 0.95f;

    private static final RenderPipeline BEAM_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath(WaypointsMod.MOD_ID, "pipeline/beam_through_walls"))
                    .withDepthStencilState(Optional.empty())
                    .build());

    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static final StagedVertexBuffer STAGED_BUFFER =
            new StagedVertexBuffer(() -> "Waypoint Beams Buffer", RenderType.SMALL_BUFFER_SIZE);

    private static volatile List<BeamState> beams = List.of();

    private BeamRenderer() {
    }

    static void init() {
        LevelExtractionEvents.END_EXTRACTION.register(BeamRenderer::extract);
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(BeamRenderer::renderAndDraw);
    }

    // ---- extraction phase: copy what we need into immutable render states ----
    private static void extract(LevelExtractionContext context) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            beams = List.of();
            return;
        }
        String dim = WaypointsMod.currentDimension();
        List<BeamState> out = new ArrayList<>();
        for (Waypoint w : WaypointsMod.waypoints()) {
            if (!w.dimension().equals(dim)) continue;
            int c = WaypointsMod.resolveColor(w);
            out.add(new BeamState(
                    w.x() + 0.5, w.y(), w.z() + 0.5,
                    ((c >> 16) & 0xFF) / 255f, ((c >> 8) & 0xFF) / 255f, (c & 0xFF) / 255f,
                    BEAM_ALPHA));
        }
        beams = List.copyOf(out);
    }

    // ---- drawing phase ----
    private static void renderAndDraw(LevelRenderContext context) {
        List<BeamState> states = beams;
        if (states.isEmpty()) return;

        RenderPipeline pipeline = BEAM_PIPELINE;
        VertexFormat formatBinding = pipeline.getVertexFormatBinding(0);
        assert formatBinding != null;

        PrimitiveTopology primitive = pipeline.getPrimitiveTopology();
        StagedVertexBuffer.Draw draw = STAGED_BUFFER.appendDraw(
                formatBinding, primitive,
                primitive == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);

        PoseStack matrices = context.poseStack();
        Vec3 camera = context.levelState().cameraRenderState.pos;

        matrices.pushPose();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        VertexConsumer builder = STAGED_BUFFER.getVertexBuilder(draw);
        for (BeamState b : states) {
            double dx = b.x() - camera.x;
            double dz = b.z() - camera.z;
            if (dx * dx + dz * dz < 1.0) continue; // don't draw when standing inside the beam
            renderFilledBox(matrices.last().pose(), builder,
                    (float) b.x() - BEAM_HALF_WIDTH, (float) b.y(), (float) b.z() - BEAM_HALF_WIDTH,
                    (float) b.x() + BEAM_HALF_WIDTH, (float) b.y() + BEAM_HEIGHT, (float) b.z() + BEAM_HALF_WIDTH,
                    b.r(), b.g(), b.b(), b.a());
        }

        matrices.popPose();

        STAGED_BUFFER.upload();
        StagedVertexBuffer.ExecuteInfo info = STAGED_BUFFER.getExecuteInfo(draw);
        if (info != null) {
            draw(Minecraft.getInstance(), info, pipeline);
        }
        STAGED_BUFFER.endFrame();
    }

    private static void renderFilledBox(Matrix4fc m, VertexConsumer buf,
                                        float minX, float minY, float minZ,
                                        float maxX, float maxY, float maxZ,
                                        float r, float g, float b, float a) {
        // Front
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        // Back
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        // Left
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        // Right
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        // Top
        buf.addVertex(m, minX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, maxY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, maxY, minZ).setColor(r, g, b, a);
        // Bottom
        buf.addVertex(m, minX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, minZ).setColor(r, g, b, a);
        buf.addVertex(m, maxX, minY, maxZ).setColor(r, g, b, a);
        buf.addVertex(m, minX, minY, maxZ).setColor(r, g, b, a);
    }

    private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info, RenderPipeline pipeline) {
        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
                .writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

        RenderTarget mainTarget = client.gameRenderer.mainRenderTarget();
        GpuTextureView colorTexture = mainTarget.getColorTextureView();
        assert colorTexture != null;

        try (RenderPass pass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(() -> WaypointsMod.MOD_ID + " beam rendering", colorTexture,
                        Optional.empty(), mainTarget.getDepthTextureView(), OptionalDouble.empty())) {
            pass.setPipeline(pipeline);
            RenderSystem.bindDefaultUniforms(pass);
            pass.setUniform("DynamicTransforms", dynamicTransforms);
            pass.setVertexBuffer(0, info.vertexBuffer().slice());
            pass.setIndexBuffer(info.indexBuffer(), info.indexType());
            pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
        }
    }

    private record BeamState(double x, double y, double z, float r, float g, float b, float a) {
    }
}
