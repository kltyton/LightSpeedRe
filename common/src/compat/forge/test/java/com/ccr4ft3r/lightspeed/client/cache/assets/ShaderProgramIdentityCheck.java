package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;
import org.lwjgl.opengl.GL40;

import java.util.List;

public final class ShaderProgramIdentityCheck {
    private ShaderProgramIdentityCheck() {
    }

    public static void main(String[] args) {
        System.setProperty("lightspeed.shaderProgramSnapshot", "false");
        String vertex = ShaderSourceIdentity.processedDigest(
                Program.Type.VERTEX, "example", List.of("#version 150\n", "void main() {}"));
        String vertexAgain = ShaderSourceIdentity.processedDigest(
                Program.Type.VERTEX, "example", List.of("#version 150\n", "void main() {}"));
        String differentBoundaries = ShaderSourceIdentity.processedDigest(
                Program.Type.VERTEX, "example", List.of("#version 150\nvoid ", "main() {}"));
        String fragment = ShaderSourceIdentity.processedDigest(
                Program.Type.FRAGMENT, "example", List.of("#version 150\n", "void main() {}"));

        require(vertex.equals(vertexAgain), "identical processed source must have a stable identity");
        require(!vertex.equals(differentBoundaries), "source list boundaries must be part of the identity");
        require(!vertex.equals(fragment), "shader stage must be part of the identity");

        VertexFormat format = format(3);
        String base = ShaderProgramSnapshot.contentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                vertex, fragment, format, true);
        require(base.equals(ShaderProgramSnapshot.contentKey(
                        "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                        vertex, fragment, format, true)),
                "identical shader inputs must have a stable program key");
        require(!base.equals(ShaderProgramSnapshot.contentKey(
                        "vendor", "renderer", "driver-2", "glsl", new int[]{1, 2},
                        vertex, fragment, format, true)),
                "driver identity must invalidate program binaries");
        require(!base.equals(ShaderProgramSnapshot.contentKey(
                        "vendor", "renderer", "driver", "glsl", new int[]{1, 3},
                        vertex, fragment, format, true)),
                "binary format support must invalidate program binaries");
        require(!base.equals(ShaderProgramSnapshot.contentKey(
                        "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                        vertex, fragment, format(4), true)),
                "vertex format must invalidate program binaries");
        require(!base.equals(ShaderProgramSnapshot.contentKey(
                        "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                        vertex, fragment, format, false)),
                "attribute binding mode must invalidate program binaries");

        var vsStage = ShaderProgramSnapshot.StageDigest.fromSource(
                GL20.GL_VERTEX_SHADER, "#version 330 core\nvoid main() {}");
        var fsStage = ShaderProgramSnapshot.StageDigest.fromSource(
                GL20.GL_FRAGMENT_SHADER, "#version 330 core\nout vec4 c; void main() { c = vec4(1.0); }");
        var gsRed = ShaderProgramSnapshot.StageDigest.fromSource(
                GL32.GL_GEOMETRY_SHADER, "#version 330 core\n// red\nvoid main() {}");
        var gsGreen = ShaderProgramSnapshot.StageDigest.fromSource(
                GL32.GL_GEOMETRY_SHADER, "#version 330 core\n// green\nvoid main() {}");
        var tcsStage = ShaderProgramSnapshot.StageDigest.fromSource(
                GL40.GL_TESS_CONTROL_SHADER, "#version 400 core\nvoid main() {}");
        var defaultBindings = List.of(new ShaderProgramSnapshot.AttributeBinding(0, "Position"));
        var irisBindings = List.of(new ShaderProgramSnapshot.AttributeBinding(0, "iris_Position"));

        String redKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, defaultBindings, List.of(vsStage, fsStage, gsRed));
        String redKeyReordered = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, defaultBindings, List.of(gsRed, fsStage, vsStage));
        String greenKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, defaultBindings, List.of(vsStage, fsStage, gsGreen));
        String noGeomKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, defaultBindings, List.of(vsStage, fsStage));
        String tessKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, defaultBindings, List.of(vsStage, fsStage, gsRed, tcsStage));
        String differentClassKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.minecraft.client.renderer.ShaderInstance", false,
                format, true, defaultBindings, List.of(vsStage, fsStage, gsRed));
        String reboundAttrKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", false,
                format, true, irisBindings, List.of(vsStage, fsStage, gsRed));
        String separableKey = ShaderProgramSnapshot.attachedContentKey(
                "vendor", "renderer", "driver", "glsl", new int[]{1, 2},
                "net.irisshaders.iris.pipeline.programs.ExtendedShader", true,
                format, true, defaultBindings, List.of(vsStage, fsStage, gsRed));

        require(redKey.equals(redKeyReordered), "shader attachment order must not alter program key");
        require(!redKey.equals(greenKey), "geometry stage source changes must invalidate program binaries");
        require(!redKey.equals(noGeomKey), "presence of geometry stage must invalidate program binaries");
        require(!redKey.equals(tessKey), "tessellation stages must be included in program binaries");
        require(!redKey.equals(differentClassKey), "shader implementation class must partition program keys");
        require(!redKey.equals(reboundAttrKey), "remapped attribute bindings must invalidate program binaries");
        require(!redKey.equals(separableKey), "program separable flag must invalidate program binaries");

        System.out.println("SHADER_PROGRAM_IDENTITY_OK");
    }

    private static VertexFormat format(int positionComponents) {
        return new VertexFormat(ImmutableMap.of(
                "Position", new VertexFormatElement(
                        0, VertexFormatElement.Type.FLOAT, VertexFormatElement.Usage.POSITION,
                        positionComponents)));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
