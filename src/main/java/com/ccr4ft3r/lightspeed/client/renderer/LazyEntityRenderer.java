package com.ccr4ft3r.lightspeed.client.renderer;

import com.ccr4ft3r.lightspeed.mixin.client.EntityRendererAccessor;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.NoopRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

public final class LazyEntityRenderer<T extends Entity> extends EntityRenderer<T> {
    private final EntityRendererProvider<T> provider;
    private final EntityRendererProvider.Context context;
    private final String rendererId;
    private volatile EntityRenderer<T> delegate;

    public LazyEntityRenderer(EntityRendererProvider.Context context, EntityRendererProvider<T> provider,
                              String rendererId) {
        super(context);
        this.context = context;
        this.provider = provider;
        this.rendererId = rendererId;
    }

    @Override
    public boolean shouldRender(T entity, Frustum frustum, double x, double y, double z) {
        return delegate().shouldRender(entity, frustum, x, y, z);
    }

    @Override
    public Vec3 getRenderOffset(T entity, float partialTick) {
        return delegate().getRenderOffset(entity, partialTick);
    }

    @Override
    public void render(T entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        delegate().render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        return delegate().getTextureLocation(entity);
    }

    @Override
    public Font getFont() {
        return delegate().getFont();
    }

    @Override
    protected int getSkyLightLevel(T entity, BlockPos position) {
        return ((EntityRendererAccessor) (Object) delegate()).lightspeed$getSkyLightLevel(entity, position);
    }

    @Override
    protected int getBlockLightLevel(T entity, BlockPos position) {
        return ((EntityRendererAccessor) (Object) delegate()).lightspeed$getBlockLightLevel(entity, position);
    }

    public EntityRenderer<T> resolveDelegate() {
        return delegate();
    }

    @SuppressWarnings("unchecked")
    private EntityRenderer<T> delegate() {
        EntityRenderer<T> current = delegate;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = delegate;
            if (current == null) {
                try {
                    current = (EntityRenderer<T>) provider.create(context);
                } catch (RuntimeException exception) {
                    LogUtils.getLogger().warn("Lazy renderer {} failed during first use; using no-op renderer",
                            rendererId, exception);
                    current = (EntityRenderer<T>) new NoopRenderer<Entity>(context);
                }
                EntityRendererAccessor access = (EntityRendererAccessor) (Object) current;
                this.shadowRadius = access.lightspeed$shadowRadius();
                this.shadowStrength = access.lightspeed$shadowStrength();
                delegate = current;
            }
            return current;
        }
    }
}
