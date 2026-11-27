package com.gabriel.motolink;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Leva a imagem do Android Auto até o painel:
 *   H.264 do Android Auto (800x480) -> decodificador -> textura OpenGL -> recorte da faixa útil
 *   (800x384, tirando as margens de cima e de baixo) -> codificador do Moto Link -> painel.
 * Recodificar no celular deixa o Moto Link controlar os quadros-chave do painel (o Android Auto
 * só manda quadro-chave a cada ~1 minuto e não deixa pedir).
 */
final class PonteAA {

    private final int aaW, aaH, margemCima, margemBaixo;
    private final Video destino;
    private final Video.Log log;

    private HandlerThread gl;
    private Handler hg;
    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctxGl = EGL14.EGL_NO_CONTEXT;
    private EGLSurface sup = EGL14.EGL_NO_SURFACE;
    private int prog, tex, aPos, aTex, uSt, uCorte;
    private SurfaceTexture st;
    private Surface saidaDecod;
    private MediaCodec dec;
    private Thread drenador;
    private volatile boolean rodando;
    private final float[] mat = new float[16];
    private long quadrosDecod, quadrosDesenho;
    private EGLConfig cfgGl;
    private EGLSurface supPrevia = EGL14.EGL_NO_SURFACE;
    private int previaW, previaH;
    private boolean temImagem;
    private long ultimoDesenho;

    PonteAA(int larguraAA, int alturaAA, int cima, int baixo, Video destino, Video.Log log) {
        aaW = larguraAA; aaH = alturaAA; margemCima = cima; margemBaixo = baixo;
        this.destino = destino; this.log = log;
    }

    long quadrosDecodificados() { return quadrosDecod; }

    void iniciar() throws Exception {
        gl = new HandlerThread("aa-gl", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        gl.start();
        hg = new Handler(gl.getLooper());
        final Exception[] erro = new Exception[1];
        final Object pronto = new Object();
        synchronized (pronto) {
            hg.post(new Runnable() { @Override public void run() {
                try { prepararGl(); } catch (Exception e) { erro[0] = e; }
                synchronized (pronto) { pronto.notifyAll(); }
            }});
            pronto.wait(3000);
        }
        if (erro[0] != null) throw erro[0];
        if (saidaDecod == null) throw new Exception("OpenGL não ficou pronto");

        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, aaW, aaH);
        if (android.os.Build.VERSION.SDK_INT >= 30) f.setInteger(MediaFormat.KEY_LOW_LATENCY, 1);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);
        dec = Video.novoDecodificador();
        try {
            dec.configure(f, saidaDecod, null, 0);
        } catch (Exception e) {
            f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, aaW, aaH);
            dec.configure(f, saidaDecod, null, 0);
        }
        dec.start();
        rodando = true;
        log.log("Android Auto: decodificador " + dec.getName() + " " + aaW + "x" + aaH
                + ", recorte " + margemCima + " px em cima e " + margemBaixo + " px embaixo.");
        drenador = new Thread(new Runnable() { @Override public void run() { drenar(); } }, "aa-decod");
        drenador.start();
    }

    /** Um pedaço de H.264 do Android Auto (config = SPS/PPS). Espera vaga no decodificador. */
    void entrada(byte[] d, int de, int tam, boolean config) {
        if (!rodando || tam <= 0) return;
        try {
            int i = -1;
            for (int t = 0; t < 50 && rodando; t++) {          // até ~1 s: perder quadro do AA estraga a imagem
                i = dec.dequeueInputBuffer(20_000);
                if (i >= 0) break;
            }
            if (i < 0) { log.log("! Android Auto: decodificador entupido, quadro perdido"); return; }
            ByteBuffer b = dec.getInputBuffer(i);
            b.clear();
            b.put(d, de, tam);
            dec.queueInputBuffer(i, 0, tam, System.nanoTime() / 1000, config ? MediaCodec.BUFFER_FLAG_CODEC_CONFIG : 0);
        } catch (Exception e) {
            if (rodando) log.log("! Android Auto: erro no decodificador: " + e);
        }
    }

    private void drenar() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (rodando) {
            int i;
            try { i = dec.dequeueOutputBuffer(info, 20_000); } catch (Exception e) { break; }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                try { log.log("Android Auto: formato do vídeo " + dec.getOutputFormat()); } catch (Exception ignored) {}
                continue;
            }
            if (i < 0) continue;
            quadrosDecod++;
            try { dec.releaseOutputBuffer(i, true); } catch (Exception ignored) {}
        }
    }

    // ------------------------------------------------------------------ OpenGL (na linha "aa-gl")
    private static final String VS =
            "attribute vec4 aPos; attribute vec2 aTex; uniform mat4 uSt; uniform vec4 uCorte; varying vec2 vTex;\n" +
            "void main() { gl_Position = aPos;\n" +
            "  vec2 t = vec2(uCorte.x + aTex.x * uCorte.z, uCorte.y + aTex.y * uCorte.w);\n" +
            "  vTex = (uSt * vec4(t, 0.0, 1.0)).xy; }\n";
    private static final String FS =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float; varying vec2 vTex; uniform samplerExternalOES sTex;\n" +
            "void main() { gl_FragColor = texture2D(sTex, vTex); }\n";
    private FloatBuffer quad;

    private void prepararGl() throws Exception {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        if (!EGL14.eglInitialize(dpy, v, 0, v, 1)) throw new Exception("eglInitialize falhou");
        int[] atr = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_NONE };
        EGLConfig[] cfg = new EGLConfig[1];
        int[] n = new int[1];
        if (!EGL14.eglChooseConfig(dpy, atr, 0, cfg, 0, 1, n, 0) || n[0] == 0) throw new Exception("sem configuração EGL");
        cfgGl = cfg[0];
        ctxGl = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        sup = EGL14.eglCreateWindowSurface(dpy, cfg[0], destino.superficie(), new int[]{EGL14.EGL_NONE}, 0);
        if (sup == null || sup == EGL14.EGL_NO_SURFACE) throw new Exception("não consegui ligar o OpenGL no codificador");
        EGL14.eglMakeCurrent(dpy, sup, sup, ctxGl);

        prog = programa(VS, FS);
        aPos = GLES20.glGetAttribLocation(prog, "aPos");
        aTex = GLES20.glGetAttribLocation(prog, "aTex");
        uSt = GLES20.glGetUniformLocation(prog, "uSt");
        uCorte = GLES20.glGetUniformLocation(prog, "uCorte");
        float[] q = { -1, -1, 0, 0,   1, -1, 1, 0,   -1, 1, 0, 1,   1, 1, 1, 1 };
        quad = ByteBuffer.allocateDirect(q.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(q).position(0);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        tex = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        st = new SurfaceTexture(tex);
        st.setDefaultBufferSize(aaW, aaH);
        st.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override public void onFrameAvailable(SurfaceTexture s) { desenhar(); }
        }, hg);
        saidaDecod = new Surface(st);
        hg.postDelayed(repetir, 70);
    }

    /**
     * O Android Auto só manda quadro quando a tela dele muda. Para o painel não ficar sem imagem
     * (e receber quadro-chave na hora que precisar), redesenha a última imagem ~15 vezes por segundo.
     */
    private final Runnable repetir = new Runnable() {
        @Override public void run() {
            if (!rodando && quadrosDesenho > 0) return;
            if (temImagem && System.nanoTime() - ultimoDesenho > 60_000_000L) desenhar(false);
            if (hg != null) hg.postDelayed(this, 66);
        }
    };

    /** Mostra o que vai pro painel também numa superfície da tela do app (ou null para tirar). */
    void previa(final Surface s, final int w, final int h) {
        if (hg == null) return;
        hg.post(new Runnable() { @Override public void run() {
            try {
                if (supPrevia != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(dpy, sup, sup, ctxGl);
                    EGL14.eglDestroySurface(dpy, supPrevia);
                    supPrevia = EGL14.EGL_NO_SURFACE;
                }
                if (s != null && s.isValid() && dpy != EGL14.EGL_NO_DISPLAY) {
                    supPrevia = EGL14.eglCreateWindowSurface(dpy, cfgGl, s, new int[]{EGL14.EGL_NONE}, 0);
                    previaW = w; previaH = h;
                    if (temImagem) desenhar(false);
                }
            } catch (Exception e) {
                supPrevia = EGL14.EGL_NO_SURFACE;
                log.log("! prévia: " + e.getMessage());
            }
        }});
    }

    private void desenhar() { desenhar(true); }

    private void desenhar(boolean novo) {
        if (!rodando || st == null) return;
        try {
            if (novo) {
                st.updateTexImage();
                st.getTransformMatrix(mat);
                temImagem = true;
            }
            ultimoDesenho = System.nanoTime();
            pintar(destino.largura(), destino.altura());
            EGLExt.eglPresentationTimeANDROID(dpy, sup, ultimoDesenho);
            EGL14.eglSwapBuffers(dpy, sup);
            quadrosDesenho++;
            if (supPrevia != EGL14.EGL_NO_SURFACE) {           // prévia na tela do app
                EGL14.eglMakeCurrent(dpy, supPrevia, supPrevia, ctxGl);
                EGL14.eglSwapInterval(dpy, 0);
                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                pintar(previaW, previaH);
                EGL14.eglSwapBuffers(dpy, supPrevia);
                EGL14.eglMakeCurrent(dpy, sup, sup, ctxGl);
            }
        } catch (Exception e) {
            if (rodando) log.log("! Android Auto: erro ao desenhar: " + e.getMessage());
        }
    }

    /** Desenha a faixa útil da imagem do Android Auto ocupando a área w x h. */
    private void pintar(int w, int h) {
        {
            GLES20.glViewport(0, 0, w, h);
            GLES20.glUseProgram(prog);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex);
            GLES20.glUniformMatrix4fv(uSt, 1, false, mat, 0);
            // coordenadas de textura: y=0 é a base da imagem. Corta 'margemBaixo' embaixo e 'margemCima' em cima.
            float y0 = margemBaixo / (float) aaH, sy = (aaH - margemCima - margemBaixo) / (float) aaH;
            GLES20.glUniform4f(uCorte, 0f, y0, 1f, sy);
            quad.position(0);
            GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }
    }

    private static int programa(String vs, String fs) throws Exception {
        int v = shader(GLES20.GL_VERTEX_SHADER, vs), f = shader(GLES20.GL_FRAGMENT_SHADER, fs);
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, v);
        GLES20.glAttachShader(p, f);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception("shader: " + GLES20.glGetProgramInfoLog(p));
        return p;
    }

    private static int shader(int tipo, String src) throws Exception {
        int s = GLES20.glCreateShader(tipo);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new Exception("shader: " + GLES20.glGetShaderInfoLog(s));
        return s;
    }

    void parar() {
        rodando = false;
        try { if (dec != null) { dec.stop(); dec.release(); } } catch (Exception ignored) {}
        dec = null;
        if (hg != null) hg.post(new Runnable() { @Override public void run() {
            hg.removeCallbacks(repetir);
            try {
                if (supPrevia != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, supPrevia);
                supPrevia = EGL14.EGL_NO_SURFACE;
            } catch (Exception ignored) {}
            try { if (saidaDecod != null) saidaDecod.release(); } catch (Exception ignored) {}
            try { if (st != null) st.release(); } catch (Exception ignored) {}
            try {
                if (dpy != EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                    if (sup != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, sup);
                    if (ctxGl != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctxGl);
                    EGL14.eglTerminate(dpy);
                }
            } catch (Exception ignored) {}
            gl.quitSafely();
        }});
    }
}
