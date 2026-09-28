package com.ccr4ft3r.lightspeed.client.cache.assets;

import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.shaders.Program;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;

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
