package com.example.game.engine

import android.opengl.GLES30
import android.util.Log

class WorldShaderProgram {
    val programId: Int

    val uMVPLoc: Int
    val uModelLoc: Int
    val uCameraPosLoc: Int
    val uFlashPosLoc: Int
    val uFlashDirLoc: Int
    val uFlashPowerLoc: Int
    val uNumLightsLoc: Int
    val uLightPosLoc: Int
    val uLightColorLoc: Int
    val uLightRadiusLoc: Int
    val uDiffuseMapLoc: Int
    val uNormalMapLoc: Int
    val uEmissiveLoc: Int
    val uTintLoc: Int
    val uFogColorLoc: Int
    val uFogDensityLoc: Int

    init {
        programId = ShaderCompiler.createProgram(VERTEX_SRC, FRAGMENT_SRC)
        uMVPLoc = GLES30.glGetUniformLocation(programId, "uMVP")
        uModelLoc = GLES30.glGetUniformLocation(programId, "uModel")
        uCameraPosLoc = GLES30.glGetUniformLocation(programId, "uCameraPos")
        uFlashPosLoc = GLES30.glGetUniformLocation(programId, "uFlashPos")
        uFlashDirLoc = GLES30.glGetUniformLocation(programId, "uFlashDir")
        uFlashPowerLoc = GLES30.glGetUniformLocation(programId, "uFlashPower")
        uNumLightsLoc = GLES30.glGetUniformLocation(programId, "uNumLights")
        uLightPosLoc = GLES30.glGetUniformLocation(programId, "uLightPos")
        uLightColorLoc = GLES30.glGetUniformLocation(programId, "uLightColor")
        uLightRadiusLoc = GLES30.glGetUniformLocation(programId, "uLightRadius")
        uDiffuseMapLoc = GLES30.glGetUniformLocation(programId, "uDiffuseMap")
        uNormalMapLoc = GLES30.glGetUniformLocation(programId, "uNormalMap")
        uEmissiveLoc = GLES30.glGetUniformLocation(programId, "uEmissive")
        uTintLoc = GLES30.glGetUniformLocation(programId, "uTint")
        uFogColorLoc = GLES30.glGetUniformLocation(programId, "uFogColor")
        uFogDensityLoc = GLES30.glGetUniformLocation(programId, "uFogDensity")
    }

    companion object {
        private const val VERTEX_SRC = """#version 300 es
            layout(location = 0) in vec3 aPos;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec3 aTangent;
            layout(location = 3) in vec2 aUV;
            layout(location = 4) in float aAO;

            uniform mat4 uMVP;
            uniform mat4 uModel;

            out vec3 vWorldPos;
            out vec3 vNormal;
            out vec3 vTangent;
            out vec3 vBitangent;
            out vec2 vUV;
            out float vAO;

            void main() {
                vec4 world = uModel * vec4(aPos, 1.0);
                vWorldPos = world.xyz;
                mat3 normalMat = mat3(uModel);
                vec3 N = normalize(normalMat * aNormal);
                vec3 T = normalize(normalMat * aTangent);
                // Re-orthogonalize T against N
                T = normalize(T - dot(T, N) * N);
                vec3 B = cross(N, T);
                vNormal = N;
                vTangent = T;
                vBitangent = B;
                vUV = aUV;
                vAO = aAO;
                gl_Position = uMVP * vec4(aPos, 1.0);
            }
        """

        private const val FRAGMENT_SRC = """#version 300 es
            precision highp float;

            in vec3 vWorldPos;
            in vec3 vNormal;
            in vec3 vTangent;
            in vec3 vBitangent;
            in vec2 vUV;
            in float vAO;

            uniform vec3 uCameraPos;
            uniform vec3 uFlashPos;
            uniform vec3 uFlashDir;
            uniform float uFlashPower;

            uniform int uNumLights;
            uniform vec3 uLightPos[8];
            uniform vec3 uLightColor[8];
            uniform float uLightRadius[8];

            uniform sampler2D uDiffuseMap;
            uniform sampler2D uNormalMap;
            uniform vec3 uEmissive;
            uniform vec3 uTint;
            uniform vec3 uFogColor;
            uniform float uFogDensity;

            out vec4 fragColor;

            vec3 evalLight(vec3 L, float dist, float radius, vec3 lightCol, vec3 N, vec3 V, vec3 albedo, float rough) {
                float NdotL = max(dot(N, L), 0.0);
                if (NdotL <= 0.0) return vec3(0.0);

                float normDist = clamp(dist / radius, 0.0, 1.0);
                float smoothWin = (1.0 - normDist * normDist);
                float atten = (smoothWin * smoothWin) / (1.0 + 0.32 * dist * dist);

                // Specular (Blinn-Phong normalized + Fresnel grazing sheen)
                vec3 H = normalize(L + V);
                float shininess = mix(96.0, 6.0, rough * rough);
                float specNorm = (shininess + 8.0) / 25.1327;
                float spec = specNorm * pow(max(dot(N, H), 0.0), shininess) * mix(0.45, 0.04, rough);
                float fresnel = pow(1.0 - max(dot(N, V), 0.0), 4.0) * (1.0 - rough) * 0.25;

                vec3 diff = albedo * NdotL;
                return (diff + vec3(spec + fresnel)) * lightCol * atten;
            }

            void main() {
                vec4 diffSample = texture(uDiffuseMap, vUV);
                vec3 albedo = pow(diffSample.rgb * uTint, vec3(2.2));

                vec4 normSample = texture(uNormalMap, vUV);
                vec3 tNorm = normSample.rgb * 2.0 - 1.0;
                float roughness = clamp(normSample.a, 0.08, 0.98);

                vec3 N = normalize(
                    vTangent * tNorm.x +
                    vBitangent * tNorm.y +
                    vNormal * tNorm.z
                );
                vec3 V = normalize(uCameraPos - vWorldPos);

                // Subtle cold midnight ambient + baked vertex AO
                vec3 totalLight = albedo * vec3(0.032, 0.038, 0.052) * vAO;

                // Player Flashlight (warm-white realistic LED cone with soft halo)
                if (uFlashPower > 0.01) {
                    vec3 toFrag = vWorldPos - uFlashPos;
                    float fDist = length(toFrag);
                    vec3 fDir = toFrag / max(fDist, 0.0001);
                    float theta = dot(fDir, normalize(uFlashDir));
                    float innerCos = 0.968; // ~14.5 deg
                    float outerCos = 0.875; // ~29 deg
                    float spot = clamp((theta - outerCos) / (innerCos - outerCos), 0.0, 1.0);
                    // Subtle secondary wide spill halo
                    float halo = clamp((theta - 0.72) / 0.28, 0.0, 1.0) * 0.14;
                    float beam = max(spot * spot, halo);
                    if (beam > 0.001 && fDist < 19.0) {
                        vec3 flashCol = vec3(1.0, 0.94, 0.82) * (3.1 * uFlashPower * beam);
                        totalLight += evalLight(-fDir, fDist, 19.0, flashCol, N, V, albedo, roughness);
                    }
                }

                // Dynamic World Point Lights (sconces, fireplace, moonlight)
                for (int i = 0; i < 8; i++) {
                    if (i >= uNumLights) break;
                    vec3 toLight = uLightPos[i] - vWorldPos;
                    float dist = length(toLight);
                    if (dist < uLightRadius[i]) {
                        vec3 L = toLight / max(dist, 0.0001);
                        totalLight += evalLight(L, dist, uLightRadius[i], uLightColor[i], N, V, albedo, roughness) * (0.45 + 0.55 * vAO);
                    }
                }

                // Emissive accents (windows, embers, Grandma eyes, glint)
                totalLight += uEmissive;

                // Exponential atmospheric horror fog
                float camDist = length(uCameraPos - vWorldPos);
                float fogFactor = 1.0 - exp(-pow(camDist * uFogDensity, 1.6));
                fogFactor = clamp(fogFactor, 0.0, 0.97);
                vec3 fogLin = pow(uFogColor, vec3(2.2));
                vec3 col = mix(totalLight, fogLin, fogFactor);

                // ACES Filmic Tone Mapping
                col = (col * (2.51 * col + 0.03)) / (col * (2.43 * col + 0.59) + 0.14);
                col = clamp(col, 0.0, 1.0);

                // Gamma correction
                col = pow(col, vec3(1.0 / 2.2));
                fragColor = vec4(col, 1.0);
            }
        """
    }
}

class ParticleShaderProgram {
    val programId: Int
    val uMVPLoc: Int
    val uColorLoc: Int
    val uPointSizeLoc: Int

    init {
        programId = ShaderCompiler.createProgram(VERTEX_SRC, FRAGMENT_SRC)
        uMVPLoc = GLES30.glGetUniformLocation(programId, "uMVP")
        uColorLoc = GLES30.glGetUniformLocation(programId, "uColor")
        uPointSizeLoc = GLES30.glGetUniformLocation(programId, "uPointSize")
    }

    companion object {
        private const val VERTEX_SRC = """#version 300 es
            layout(location = 0) in vec3 aPos;
            uniform mat4 uMVP;
            uniform float uPointSize;
            void main() {
                vec4 clip = uMVP * vec4(aPos, 1.0);
                gl_Position = clip;
                gl_PointSize = clamp(uPointSize / max(clip.w, 0.5), 1.5, 10.0);
            }
        """

        private const val FRAGMENT_SRC = """#version 300 es
            precision mediump float;
            uniform vec4 uColor;
            out vec4 fragColor;
            void main() {
                vec2 c = gl_PointCoord - vec2(0.5);
                float r2 = dot(c, c);
                if (r2 > 0.25) discard;
                float alpha = (1.0 - r2 * 4.0) * uColor.a;
                fragColor = vec4(uColor.rgb, alpha);
            }
        """
    }
}

object ShaderCompiler {
    private const val TAG = "ShaderCompiler"

    fun createProgram(vertSrc: String, fragSrc: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, vertSrc)
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, fragSrc)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)

        val status = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Program link error: " + GLES30.glGetProgramInfoLog(prog))
        }
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return prog
    }

    private fun compile(type: Int, src: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, src)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            Log.e(TAG, "Shader compile error: " + GLES30.glGetShaderInfoLog(shader))
        }
        return shader
    }
}
