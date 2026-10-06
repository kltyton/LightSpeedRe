package com.ccr4ft3r.lightspeed.client.renderer;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

public final class LazyBlockEntityRenderer<T extends BlockEntity> implements BlockEntityRenderer<T> {
    private final BlockEntityRendererProvider<T> provider;
    private final BlockEntityRendererProvider.Context context;
    private final String rendererId;
    private volatile BlockEntityRenderer<T> delegate;

    public LazyBlockEntityRenderer(BlockEntityRendererProvider.Context context,
                                   BlockEntityRendererProvider<T> provider, String rendererId) {
        this.context = context;
        this.provider = provider;
        this.rendererId = rendererId;
    }

    @Override
    public void render(T blockEntity, float partialTick, PoseStack pose, MultiBufferSource buffers,
                       int packedLight, int packedOverlay) {
        delegate().render(blockEntity, partialTick, pose, buffers, packedLight, packedOverlay);
    }

    @Override
    public boolean shouldRenderOffScreen(T blockEntity) {
        return delegate().shouldRenderOffScreen(blockEntity);
    }

    @Override
    public int getViewDistance() {
        return delegate().getViewDistance();
    }

    @Override
    public boolean shouldRender(T blockEntity, Vec3 camera) {
        return delegate().shouldRender(blockEntity, camera);
    }

    public static BlockEntityRenderer<?> unwrap(BlockEntityRenderer<?> renderer) {
        while (renderer instanceof LazyBlockEntityRenderer<?> lazy) {
            renderer = lazy.delegate();
        }
        return renderer;
    }

    private BlockEntityRenderer<T> delegate() {
        BlockEntityRenderer<T> current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = delegate;
            if (current == null) {
                try {
                    current = provider.create(context);
                } catch (RuntimeException exception) {
                    LogUtils.getLogger().warn(
                            "Lazy block-entity renderer {} failed during first use; using no-op renderer",
                            rendererId, exception);
                    current = (blockEntity, partialTick, pose, buffers, packedLight, packedOverlay) -> { };
                }
                delegate = current;
            }
            return current;
        }
    }
}
