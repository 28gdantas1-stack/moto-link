package com.gabriel.motolink;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Canais de imagem do painel (classes common/base/l0 e q0 do Carbit).
 * Cabeçalho de 8 bytes little-endian: [comando 2][tamanho do corpo 2][token 4], depois o corpo.
 *
 * Porta 10921 (controle): painel manda 0x10 com o tamanho da tela -> celular responde 0x11;
 *   0x60 configuração extra -> 0x61; 0x70 começar vídeo -> 0x71; 0x40 batimento -> 0x41; 0x20 toque.
 * Porta 10920 (dados): painel pede 0x72 a cada quadro; celular responde [tamanho 4][H.264]
 *   (no "modo filme" o tamanho inclui +8 e vai um carimbo de tempo de 8 bytes no fim). Sem quadro: 4 zeros.
 */
final class Midia {

    interface Avisos {
        void log(String s);
        void estado(String s, boolean ok);
        void videoComecou(int w, int h);
    }

    private final Avisos av;
    private volatile Video video;
    private volatile boolean modoFilme;
    private volatile int telaW = 800, telaH = 384, taxa = 2_500_000;
    private volatile long pedidos, ultimoPedidoGeral;


    /** O painel está puxando imagem agora (pediu quadro nos últimos 3 s). */
    boolean transmitindo() { return video != null && android.os.SystemClock.uptimeMillis() - ultimoPedidoGeral < 3000; }
    long enviados() { Video v = video; return v == null ? 0 : v.enviados(); }

    Midia(Avisos a) { av = a; }

    private void log(String s) { av.log(s); }

    // ------------------------------------------------------------------ porta 10921
    void controle(Socket s) {
        int n = 0;
        try {
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[] cab = new byte[8];
            while (true) {
                Pxc.lerTudo(in, cab, 8);
                ByteBuffer b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN);
                int cmd = b.getShort(0) & 0xFFFF, len = b.getShort(2) & 0xFFFF;
                byte[] corpo = new byte[len];
                if (len > 0) Pxc.lerTudo(in, corpo, len);
                n++;
                boolean barulho = cmd == 0x40 || cmd == 0x20;
                if (!barulho || n < 12) log(String.format("  [controle] ← 0x%04X (%d bytes)%s", cmd, len, len > 0 && !barulho ? " " + Quadro.hex(Pxc.corte(corpo, 48)) : ""));
                switch (cmd) {
                    case 0x10: configurar(out, corpo); break;
                    case 0x30: {                                  // versão
                        ByteBuffer r = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                        r.putInt(5002).putInt(1);
                        enviar(out, 0x31, r.array());
                        break;
                    }
                    case 0x40: enviar(out, 0x41, null); break;    // batimento
                    case 0x60: extra(out, corpo); break;
                    case 0x70: comecar(out); break;
                    case 0x20: if (n < 40) log("  [controle] toque: " + Quadro.hex(corpo)); break;
                    default:
                        log(String.format("  [controle] comando 0x%04X não conhecido, respondo 'não suportado'", cmd));
                        enviar(out, 0x7F02, null);
                }
            }
        } catch (Exception e) {
            log("  [controle] conexão terminou: " + e.getMessage() + " (" + n + " comandos)");
            Video v = video;                           // sem controle não há imagem: desliga a tela de teste
            if (v != null && v.padrao()) parar();      // (a do Android Auto continua, o painel pode voltar)
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void configurar(OutputStream out, byte[] c) throws Exception {
        ByteBuffer b = ByteBuffer.wrap(c).order(ByteOrder.LITTLE_ENDIAN);
        int w = b.getShort() & 0xFFFF, h = b.getShort() & 0xFFFF;
        int fps = b.getInt(), enc = b.getInt(), codecs = b.getInt();
        int qMin = b.getShort() & 0xFFFF, qMax = b.getShort() & 0xFFFF, bits = b.getInt();
        int modoCap = 0, toque = 0, orient = 0, tela = 0, tipo = 0, ext = 0;
        if (b.remaining() >= 8) {
            modoCap = b.get(); toque = b.get(); orient = b.get(); tela = b.get(); tipo = b.get(); ext = b.get();
            b.get(); b.get();
        }
        String huid = b.remaining() > 0 ? new String(c, 32, c.length - 32, StandardCharsets.UTF_8) : "";
        log("★ Painel pediu imagem: tela " + w + "x" + h + ", fps " + fps + ", codificador " + enc + " (2 = H.264), codecs " + codecs
                + ", qualidade " + qMin + "-" + qMax + ", taxa " + bits + ", modo " + modoCap + ", toque " + toque
                + ", orientação " + orient + ", tela nº " + tela + ", tipo " + tipo + ", ext " + ext + (huid.isEmpty() ? "" : ", HUID " + huid));
        telaW = w > 0 ? w : 800;
        telaH = h > 0 ? h : 384;
        if (bits > 200_000 && bits < 20_000_000) taxa = bits;
        modoFilme = false;

        ByteBuffer r = ByteBuffer.allocate(ext != 0 ? 9 : 8).order(ByteOrder.LITTLE_ENDIAN);
        r.putInt(2).putShort((short) (telaW & ~15)).putShort((short) (telaH & ~15));
        if (ext != 0) r.put((byte) ext);
        enviar(out, 0x11, r.array());
        log("  [controle] → 0x0011 H.264 " + (telaW & ~15) + "x" + (telaH & ~15));
        prepararVideo();
    }

    private void extra(OutputStream out, byte[] c) throws Exception {
        String t = new String(c, StandardCharsets.UTF_8);
        log("  [controle] configuração extra: " + t.replaceAll("\\s+", " "));
        org.json.JSONObject r = new org.json.JSONObject();
        int sf = 0;
        try {
            org.json.JSONObject j = new org.json.JSONObject(t);
            if (j.optJSONObject("viewAreaConfig") != null) {
                org.json.JSONObject v = new org.json.JSONObject();
                v.put("state", 0);
                r.put("viewAreaConfig", v);
            }
            sf = j.optInt("supportFunction");
        } catch (Exception ignored) {}
        r.put("supportFunction", sf);
        modoFilme = (sf & 1) == 1;
        enviar(out, 0x61, r.toString().getBytes(StandardCharsets.UTF_8));
        log("  [controle] → 0x0061 " + r + (modoFilme ? " (com carimbo de tempo)" : ""));
    }

    private void comecar(OutputStream out) throws Exception {
        prepararVideo();
        enviar(out, 0x71, null);
        log("★ Painel mandou começar o vídeo → 0x0071");
        Video v = video;
        if (v != null) {
            v.reiniciarFila();
            av.videoComecou(v.largura(), v.altura());
        }
    }

    private synchronized void prepararVideo() {
        Video v = video;
        if (v != null && v.rodando() && (!v.padrao() || (v.largura() == (telaW & ~15) && v.altura() == (telaH & ~15)))) return;
        if (v != null) v.parar();
        Video novo = new Video(telaW, telaH, taxa, new Video.Log() { @Override public void log(String s) { Midia.this.log(s); } });
        try {
            novo.iniciar();
            video = novo;
        } catch (Exception e) {
            log("! Não consegui ligar o codificador de vídeo: " + e);
        }
    }

    // ------------------------------------------------------------------ porta 10920
    void dados(Socket s) {
        long n = 0, vazios = 0, bytes = 0;
        long t0 = System.currentTimeMillis(), ultimoAviso = t0;
        try {
            s.setTcpNoDelay(true);
            s.setSendBufferSize(512 * 1024);
            InputStream in = s.getInputStream();
            OutputStream out = new java.io.BufferedOutputStream(s.getOutputStream(), 256 * 1024);
            byte[] cab = new byte[8];
            byte[] ext = new byte[64];
            boolean primeiro = true;
            long ultimoPedido = 0;
            int avisos = 0;
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY);
            Video v0 = video;
            if (v0 != null) v0.reiniciarFila();
            while (true) {
                Pxc.lerTudo(in, cab, 8);
                ByteBuffer b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN);
                int cmd = b.getShort(0) & 0xFFFF, len = b.getShort(2) & 0xFFFF;
                if (len > 0) {
                    if (len > ext.length) ext = new byte[len];
                    Pxc.lerTudo(in, ext, len);
                }
                if (n < 3) log(String.format("  [vídeo] ← pedido 0x%04X%s", cmd, len > 0 ? " extra " + Quadro.hex(Pxc.corte(java.util.Arrays.copyOf(ext, len), 16)) : ""));
                if (cmd != 0x72) {
                    log(String.format("  [vídeo] pedido 0x%04X desconhecido → não suportado", cmd));
                    ByteBuffer r = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
                    r.putShort((short) 0x7F02).putShort((short) 0).putInt(0);
                    out.write(r.array());
                    out.flush();
                    continue;
                }
                n++;
                pedidos++;
                long chegou = android.os.SystemClock.uptimeMillis();
                if (ultimoPedido > 0 && chegou - ultimoPedido > 300 && avisos++ < 40)
                    log("  ⚠ rede/painel: o painel ficou " + (chegou - ultimoPedido) + " ms sem pedir quadro");
                ultimoPedido = chegou;
                ultimoPedidoGeral = chegou;
                Video v = video;
                if (v == null) { prepararVideo(); v = video; }
                byte[] q = v == null ? null : v.proximo(primeiro ? 600 : 120);
                if (q == null) {
                    out.write(Protocol0.ZERO);
                    vazios++;
                } else {
                    if (primeiro && v.cabecalho() != null) {
                        blob(out, v.cabecalho());          // SPS/PPS separados antes do primeiro quadro, como o Carbit
                        log("  [vídeo] → SPS/PPS (" + v.cabecalho().length + " bytes) + primeiro quadro (" + q.length + " bytes)");
                    }
                    primeiro = false;
                    blob(out, q);
                    bytes += q.length;
                }
                out.flush();
                long agora = System.currentTimeMillis();
                if (agora - ultimoAviso > 5000) {
                    double seg = (agora - t0) / 1000.0;
                    log(String.format("  [vídeo] %d pedidos, %d vazios, %d descartes, %.0f quadros/s, %.0f kbit/s", n, vazios, v == null ? 0 : v.descartes, (n - vazios) / seg, bytes * 8 / seg / 1000));
                    if (n - vazios > 0) av.estado("Imagem indo para o painel ✓", true);
                    ultimoAviso = agora;
                }
            }
        } catch (Exception e) {
            log("  [vídeo] conexão terminou: " + e.getMessage() + " (" + n + " pedidos, " + vazios + " sem quadro)");
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private void blob(OutputStream out, byte[] d) throws Exception {
        int tam = d.length + (modoFilme ? 8 : 0);
        out.write(new byte[]{(byte) tam, (byte) (tam >>> 8), (byte) (tam >>> 16), (byte) (tam >>> 24)});
        out.write(d);
        if (modoFilme) {
            ByteBuffer t = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
            t.putLong(android.os.SystemClock.elapsedRealtime());
            out.write(t.array());
        }
    }

    private void enviar(OutputStream out, int cmd, byte[] corpo) throws Exception {
        int len = corpo == null ? 0 : corpo.length;
        ByteBuffer b = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) cmd).putShort((short) len).putInt(0);
        if (corpo != null) b.put(corpo);
        synchronized (out) {
            out.write(b.array());
            out.flush();
        }
    }

    /** Troca a fonte da imagem (ex.: Android Auto). O painel passa a puxar quadros dela. */
    synchronized void usarFonte(Video nova) {
        Video velha = video;
        video = nova;
        if (velha != null && velha != nova) velha.parar();
        nova.reiniciarFila();
        log("Imagem do painel agora vem de: " + (nova.padrao() ? "tela de teste" : "Android Auto"));
    }

    /** Volta para a tela de teste (ex.: Android Auto desconectou). */
    synchronized void voltarTelaDeTeste() {
        Video v = video;
        if (v != null && v.padrao()) return;
        video = null;                                  // o próximo pedido do painel recria a tela de teste
        if (v != null) v.parar();
        log("Imagem do painel volta para a tela de teste.");
    }

    int taxa() { return taxa; }

    int largura() { return telaW & ~15; }
    int altura() { return telaH & ~15; }

    void parar() {
        Video v = video;
        video = null;
        if (v != null) v.parar();
    }

    private static final class Protocol0 { static final byte[] ZERO = new byte[4]; }
}
