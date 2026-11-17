package org.neutrino.afdo.workload;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicInteger;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * gpu: an OpenGL ES 3 scene drawn continuously: a fragment-heavy procedural
 * background, a few thousand instanced, animated quads blended over it, and a
 * 512x512 texture re-uploaded every frame (texture streaming, as video and
 * camera previews do).  Preparation draws a bounded warmup (WARMUP frames,
 * on request) and then leaves the GL thread idle; it renders continuously
 * only between RUN and DONE.  Evidence: frames drawn by the GL thread itself
 * (a SurfaceView producer is not in the window's frame metrics) and the
 * renderer string.
 */
final class Gpu extends Case {
    private GLSurfaceView view;
    private final AtomicInteger drawn = new AtomicInteger(), uploads = new AtomicInteger();
    private static final int WARMUP = 10;
    private volatile String renderer = "", glError = "";
    private volatile boolean counting;

    Gpu(Run a, Params p) { super(a, p); }

    @Override
    void prepare() {
        view = new GLSurfaceView(activity);
        view.setEGLContextClientVersion(3);
        view.setRenderer(new Scene());
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        activity.setContentView(view);
    }

    @Override
    void run() {
        counting = true;
        view.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        main.postDelayed(this::done, p.seconds * 1000L);
    }

    /** Back to drawing on request only, and wait until the GL thread is past its last frame. */
    @Override
    boolean stop() {
        counting = false;
        if (view == null) return true;
        view.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        java.util.concurrent.CountDownLatch idle = new java.util.concurrent.CountDownLatch(1);
        view.queueEvent(idle::countDown);
        try {
            return idle.await(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    void validate() {
        double s = activeSeconds();
        int n = drawn.get();
        put(completed, "frames", n);
        put(completed, "texture_uploads", uploads.get());
        put(evidence, "fps", s > 0 ? n / s : 0);
        put(evidence, "renderer", renderer);
        need(glError.isEmpty(), "GL: " + glError);
        need(!renderer.isEmpty(), "no GL renderer");
        need(n >= 10 * s, "too few frames: " + n + " in " + s + " s");
    }

    @Override
    void cleanup() {
        if (view != null) view.onPause();
    }

    private final class Scene implements GLSurfaceView.Renderer {
        private static final int QUADS = 3000;
        private int bgProg, quadProg, tex, frame;
        private int bgTime, bgRes, quadTime;
        private FloatBuffer quad, offsets;
        private final ByteBuffer pixels = ByteBuffer.allocateDirect(512 * 512 * 4);
        private int warm;

        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            renderer = GLES30.glGetString(GLES30.GL_RENDERER) + " / " + GLES30.glGetString(GLES30.GL_VERSION);
            bgProg = program(
                    "#version 300 es\nin vec2 p; out vec2 uv; void main(){ uv = p * 0.5 + 0.5; gl_Position = vec4(p, 0.0, 1.0); }",
                    "#version 300 es\nprecision highp float; in vec2 uv; uniform float t; uniform vec2 res; out vec4 o;\n"
                    + "float h(vec2 p){ return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }\n"
                    + "float n(vec2 p){ vec2 i = floor(p), f = fract(p); f = f*f*(3.0-2.0*f);\n"
                    + "  return mix(mix(h(i), h(i+vec2(1,0)), f.x), mix(h(i+vec2(0,1)), h(i+vec2(1,1)), f.x), f.y); }\n"
                    + "void main(){ vec2 p = uv * res / 180.0; float v = 0.0, a = 0.5;\n"
                    + "  for (int k = 0; k < 7; k++) { v += a * n(p + t * 0.3); p *= 2.03; a *= 0.5; }\n"
                    + "  o = vec4(0.2 + 0.6 * v, 0.3 + 0.4 * v * v, 0.5 + 0.5 * sin(v * 6.0 + t), 1.0); }");
            quadProg = program(
                    "#version 300 es\nin vec2 p; in vec3 off; uniform float t; out vec2 uv;\n"
                    + "void main(){ float a = t * (0.5 + off.z) + off.z * 6.28; mat2 r = mat2(cos(a), -sin(a), sin(a), cos(a));\n"
                    + "  uv = p * 0.5 + 0.5; vec2 q = r * p * 0.035 + off.xy + 0.05 * vec2(sin(t + off.z * 9.0), cos(t * 1.3 + off.z * 7.0));\n"
                    + "  gl_Position = vec4(q, 0.0, 1.0); }",
                    "#version 300 es\nprecision mediump float; in vec2 uv; uniform sampler2D s; out vec4 o;\n"
                    + "void main(){ vec4 c = texture(s, uv); o = vec4(c.rgb, 0.6); }");
            bgTime = GLES30.glGetUniformLocation(bgProg, "t");
            bgRes = GLES30.glGetUniformLocation(bgProg, "res");
            quadTime = GLES30.glGetUniformLocation(quadProg, "t");
            quad = floats(new float[]{-1, -1, 1, -1, -1, 1, 1, 1});
            float[] off = new float[QUADS * 3];
            java.util.Random r = new java.util.Random(p.seed);
            for (int i = 0; i < off.length; i += 3) {
                off[i] = r.nextFloat() * 2 - 1; off[i + 1] = r.nextFloat() * 2 - 1; off[i + 2] = r.nextFloat();
            }
            offsets = floats(off);
            int[] t = new int[1];
            GLES30.glGenTextures(1, t, 0);
            tex = t[0];
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 512, 512, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
            GLES30.glEnable(GLES30.GL_BLEND);
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA);
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int w, int h) {
            GLES30.glViewport(0, 0, w, h);
            GLES30.glUseProgram(bgProg);
            GLES30.glUniform2f(bgRes, w, h);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            float t = frame++ / 60f;
            // texture streaming: new contents every frame
            pixels.clear();
            int shade = frame & 0xff;
            for (int i = 0; i < 512 * 512; i += 61) pixels.putInt(i * 4, 0xff000000 | (shade << 16) | ((i >> 9) & 0xff) << 8 | (i & 0xff));
            pixels.position(0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 512, 512, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels);
            GLES30.glUseProgram(bgProg);
            GLES30.glUniform1f(bgTime, t);
            attrib(bgProg, "p", quad, 2, 0);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
            GLES30.glUseProgram(quadProg);
            GLES30.glUniform1f(quadTime, t);
            attrib(quadProg, "p", quad, 2, 0);
            attrib(quadProg, "off", offsets, 3, 1);
            GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLE_STRIP, 0, 4, QUADS);
            int e = GLES30.glGetError();
            if (e != GLES30.GL_NO_ERROR && glError.isEmpty()) glError = "0x" + Integer.toHexString(e);
            if (counting && runEndNs == 0) { drawn.incrementAndGet(); uploads.incrementAndGet(); }
            else if (warm < WARMUP && ++warm == WARMUP) main.post(Gpu.this::ready);
            else if (warm < WARMUP) view.requestRender();
        }

        private void attrib(int prog, String name, FloatBuffer b, int size, int divisor) {
            int loc = GLES30.glGetAttribLocation(prog, name);
            b.position(0);
            GLES30.glEnableVertexAttribArray(loc);
            GLES30.glVertexAttribPointer(loc, size, GLES30.GL_FLOAT, false, 0, b);
            GLES30.glVertexAttribDivisor(loc, divisor);
        }

        private FloatBuffer floats(float[] f) {
            FloatBuffer b = ByteBuffer.allocateDirect(f.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            b.put(f).position(0);
            return b;
        }

        private int program(String vs, String fs) {
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) glError = "link: " + GLES30.glGetProgramInfoLog(p);
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) glError = "compile: " + GLES30.glGetShaderInfoLog(s);
            return s;
        }
    }
}
