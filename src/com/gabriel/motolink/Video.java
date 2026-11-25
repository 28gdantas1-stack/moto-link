package com.gabriel.motolink;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Gera a imagem que vai para o painel: desenha uma tela de teste num Surface, codifica em H.264
 * (MediaCodec por hardware) e guarda os quadros numa fila. O painel puxa um quadro por vez.
 * Cada quadro-chave sai com SPS/PPS na frente, para o painel conseguir começar a decodificar.
 */
final class Video {

    interface Log { void log(String s); }

    private final int w, h;
    private final Log log;
    private MediaCodec codec;
    private Surface entrada;
    private HandlerThread desenho;
    private Handler hd;
    private Thread saida;
    private volatile boolean rodando;
    private byte[] cabecalho;                      // SPS + PPS
    private final ArrayDeque<byte[]> fila = new ArrayDeque<>();
    private boolean esperandoChave = true;
    private long quadros, enviados;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final SimpleDateFormat relogio = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

    private final int taxa;
    private final boolean padrao;       // true = tela de teste desenhada aqui; false = imagem vem de fora (Android Auto)

    Video(int largura, int altura, int bits, Log l) { this(largura, altura, bits, l, true); }

    Video(int largura, int altura, int bits, Log l, boolean telaDeTeste) {
        w = largura & ~15;
        h = altura & ~15;
        taxa = bits;
        log = l;
        padrao = telaDeTeste;
    }

    boolean padrao() { return padrao; }

    /** Superfície de entrada do codificador (para quem vai desenhar nela por fora, via OpenGL). */
    Surface superficie() { return entrada; }

    byte[] cabecalho() { return cabecalho; }

    int largura() { return w; }
    int altura() { return h; }
    boolean rodando() { return rodando; }

    synchronized void iniciar() throws Exception {
        if (rodando) return;
        MediaFormat f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, taxa);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        f.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
        f.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
        f.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        f.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000);
        f.setInteger(MediaFormat.KEY_PRIORITY, 0);            // tempo real
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        } catch (Exception e) {
            // alguns codificadores não aceitam perfil/nível: tenta sem
            log.log("  (codificador recusou o perfil, tentando o padrão)");
            f = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h);
            f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            f.setInteger(MediaFormat.KEY_BIT_RATE, taxa);
            f.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        }
        entrada = codec.createInputSurface();
        codec.start();
        rodando = true;
        log.log("Vídeo: codificador " + codec.getName() + " " + w + "x" + h + " a " + (taxa / 1000) + " kbit/s iniciado.");

        saida = new Thread(new Runnable() { @Override public void run() { drenar(); } }, "video-saida");
        saida.start();
        if (!padrao) return;
        desenho = new HandlerThread("video-desenho", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        desenho.start();
        hd = new Handler(desenho.getLooper());
        proximoDesenho = SystemClock.uptimeMillis();
        hd.post(quadro);
    }

    private long proximoDesenho, ultimoDesenho, ultimaSaida;
    private int avisos;
    int descartes;

    private void aviso(String s) { if (avisos++ < 40) log.log(s); }

    /** Desenha a tela de teste 25 vezes por segundo, em ritmo fixo. */
    private final Runnable quadro = new Runnable() {
        @Override public void run() {
            if (!rodando) return;
            long agora = SystemClock.uptimeMillis();
            if (ultimoDesenho > 0 && agora - ultimoDesenho > 300)
                aviso("  ⚠ celular: o desenho da imagem atrasou " + (agora - ultimoDesenho) + " ms");
            ultimoDesenho = agora;
            try {
                Canvas c = entrada.lockHardwareCanvas();
                try { desenhar(c); } finally { entrada.unlockCanvasAndPost(c); }
            } catch (Exception e) {
                aviso("! desenho: " + e.getMessage());
            }
            proximoDesenho += 40;
            if (proximoDesenho < agora) proximoDesenho = agora + 40;
            hd.postAtTime(this, proximoDesenho);
        }
    };

    private void desenhar(Canvas c) { desenharTela(c, w, h, p, relogio, enviados); }

    /** Aviso discreto no painel enquanto o Android Auto não chega (vazio = tela preta). */
    static volatile String mensagemEspera = "";

    /** Tela de espera: preta. Só mostra um aviso pequeno se houver algo para o usuário fazer. */
    static void desenharTela(Canvas c, int w, int h, Paint p, SimpleDateFormat relogio, long enviados) {
        c.drawColor(0xFF000000);
        String m = mensagemEspera;
        if (m == null || m.isEmpty()) return;
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setColor(0xFF2BD47E);
        p.setTextSize(h * 0.09f);
        c.drawText("Moto Link", w / 2f, h * 0.46f, p);
        p.setTypeface(Typeface.DEFAULT);
        p.setColor(0xFF9A9AA6);
        p.setTextSize(h * 0.06f);
        c.drawText(m, w / 2f, h * 0.60f, p);
    }

    long enviados() { return enviados; }

    /** Lê o que o codificador produz e guarda na fila. */
    private void drenar() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (rodando) {
            int i;
            try { i = codec.dequeueOutputBuffer(info, 20_000); } catch (Exception e) { if (rodando) aviso("! codificador parou: " + e); break; }
            if (i < 0) continue;
            long agora = SystemClock.uptimeMillis();
            if (ultimaSaida > 0 && agora - ultimaSaida > 300)
                aviso("  ⚠ celular: o codificador ficou " + (agora - ultimaSaida) + " ms sem entregar imagem");
            ultimaSaida = agora;
            ByteBuffer b = codec.getOutputBuffer(i);
            byte[] d = new byte[info.size];
            if (b != null) { b.position(info.offset); b.get(d, 0, info.size); }
            codec.releaseOutputBuffer(i, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                cabecalho = d;
                log.log("Vídeo: SPS/PPS prontos (" + d.length + " bytes).");
                continue;
            }
            boolean chave = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
            if (chave && cabecalho != null) {
                byte[] j = new byte[cabecalho.length + d.length];
                System.arraycopy(cabecalho, 0, j, 0, cabecalho.length);
                System.arraycopy(d, 0, j, cabecalho.length, d.length);
                d = j;
            }
            quadros++;
            synchronized (fila) {
                if (esperandoChave) {
                    if (!chave) continue;              // o painel só pode começar num quadro-chave
                    esperandoChave = false;
                }
                fila.add(d);
                if (fila.size() > 8) {                 // painel atrasou mais de ~1/3 s: joga fora o atraso
                    fila.clear();                      // e recomeça num quadro-chave (imagem volta ao vivo)
                    esperandoChave = true;
                    descartes++;
                    pedirChave();
                }
                fila.notifyAll();
            }
        }
    }

    /** O painel começou a puxar: recomeça a fila num quadro-chave. */
    void reiniciarFila() {
        synchronized (fila) {
            fila.clear();
            esperandoChave = true;
        }
        pedirChave();
    }

    void pedirChave() {
        try {
            Bundle b = new Bundle();
            b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(b);
        } catch (Exception ignored) {}
    }

    /** Próximo quadro para o painel (espera até 'ms'); null se não tiver. */
    byte[] proximo(long ms) {
        synchronized (fila) {
            if (fila.isEmpty()) {
                try { fila.wait(ms); } catch (InterruptedException ignored) {}
            }
            byte[] d = fila.poll();
            if (d != null) enviados++;
            return d;
        }
    }

    synchronized void parar() {
        rodando = false;
        try { if (desenho != null) desenho.quitSafely(); } catch (Exception ignored) {}
        try { if (codec != null) { codec.stop(); codec.release(); } } catch (Exception ignored) {}
        try { if (entrada != null) entrada.release(); } catch (Exception ignored) {}
        codec = null;
        synchronized (fila) { fila.clear(); fila.notifyAll(); }
    }
}
