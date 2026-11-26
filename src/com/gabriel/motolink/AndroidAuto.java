package com.gabriel.motolink;

import android.content.Context;
import android.os.Build;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

/**
 * O Moto Link fingindo ser a central de um carro (uma moto, no caso) para o Android Auto do próprio
 * celular. Liga no "servidor da unidade principal" do Android Auto (127.0.0.1:5277, ativado nas opções de
 * desenvolvedor dele), faz o aperto de mão + TLS, diz o que a "central" tem (tela 800x480 com a faixa
 * útil de 800x384) e recebe o vídeo, que segue para o painel pela PonteAA.
 *
 * Moldura de cada mensagem: [canal][flags][tamanho 2 bytes][(total 4 bytes, só no 1º pedaço de
 * vários)][dados]. Dados = [tipo 2 bytes][protobuf], cifrados com TLS depois do aperto de mão.
 */
final class AndroidAuto {

    interface Avisos { void log(String s); void estado(String s, boolean ok); }

    static final int PORTA = 5277;
    private static final int CH_CONTROLE = 0, CH_SENSOR = 1, CH_VIDEO = 2, CH_ENTRADA = 3;
    // Tamanho pedido ao Android Auto e o recorte para o painel. No GK 350 (800x384): AA em 800x480
    // com margem de 96 na altura. Painéis mais largos que 1000 px pedem 1280x720 ao AA.
    private int AA_W = 800, AA_H = 480, RES = 1, DENSIDADE = 140;
    /** Margem pedida ao AA: altura do AA - altura que aparece no painel. */
    private int MARGEM = 96;

    private final Context ctx;
    private final Midia midia;
    private final Avisos av;
    private volatile Thread linha;
    private volatile Socket sock;
    private volatile boolean ligado;
    private SSLEngine tls;
    private OutputStream out;
    private DataInputStream in;
    private ByteBuffer appIn, netOut;
    private final ByteArrayOutputStream[] montagem = new ByteArrayOutputStream[32];
    private PonteAA ponte;
    private Video videoAA;
    private int sessaoVideo;
    private long quadrosAA, ultimoAviso;
    private int desconhecidos;

    AndroidAuto(Context c, Midia m, Avisos a) { ctx = c.getApplicationContext(); midia = m; av = a; }

    private void log(String s) { av.log(s); }

    boolean ligado() { return ligado; }

    // ---------------------------------------------------------------- servidor desligado: abrir o AA e esperar
    private long ultimaAbertura;
    private volatile boolean esperando, veioDaEspera;
    private final android.os.Handler hp = new android.os.Handler(android.os.Looper.getMainLooper());

    /** Abre a tela de configurações do Android Auto (no máximo 1 vez por minuto). */
    void abrirAndroidAuto() {
        long agora = System.currentTimeMillis();
        if (agora - ultimaAbertura < 60_000) return;
        ultimaAbertura = agora;
        hp.post(new Runnable() { @Override public void run() {
            if (abrirConfigDoAA(ctx)) dica(ctx);
            else log("! Não consegui abrir o Android Auto. Abra: Configurações → Dispositivos conectados → Android Auto.");
        }});
    }

    static final String PACOTE_AA = "com.google.android.projection.gearhead";

    /** Abre as configurações do Android Auto (onde fica o modo desenvolvedor e o servidor). */
    static boolean abrirConfigDoAA(Context c) {
        String[] telas = {
                "com.google.android.projection.gearhead.companion.settings.DefaultSettingsActivity",
                "com.google.android.apps.auto.carservice.settings.SettingsActivity",
                "com.google.android.projection.gearhead.companion.settings.SettingsActivity",
        };
        for (String t : telas) {
            try {
                c.startActivity(new android.content.Intent().setClassName(PACOTE_AA, t).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Exception ignored) {}
        }
        try {
            c.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:" + PACOTE_AA)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception ignored) {}
        return false;
    }

    /** Lembrete por cima do Android Auto: onde tocar para ligar o servidor. */
    static void dica(final Context c) {
        final android.os.Handler h = new android.os.Handler(android.os.Looper.getMainLooper());
        final String t = Variante.NOME + ": toque em ⋮ (no alto, à direita) → \"Iniciar servidor da unidade principal\". Depois é só voltar.";
        h.postDelayed(new Runnable() { @Override public void run() {
            try { android.widget.Toast.makeText(c, t, android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }}, 900);
        h.postDelayed(new Runnable() { @Override public void run() {
            try { android.widget.Toast.makeText(c, t, android.widget.Toast.LENGTH_LONG).show(); } catch (Exception ignored) {}
        }}, 4800);
    }

    /** Tenta de novo a cada 3 s por até 3 minutos: quando o servidor ligar, conecta sozinho. */
    private void esperarServidor() {
        if (esperando) return;
        esperando = true;
        final long fim = System.currentTimeMillis() + 180_000;
        new Thread(new Runnable() { @Override public void run() {
            try {
                while (System.currentTimeMillis() < fim) {
                    try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                    if (ligado) { esperando = false; return; }
                    Thread l = linha;
                    if (l != null && l.isAlive()) continue;
                    veioDaEspera = true;
                    conectar();                               // se o servidor ainda estiver desligado, falha rápido e calado
                    try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
                    if (ligado) { log("Android Auto: servidor ligado, conectado ✓"); esperando = false; return; }
                }
                veioDaEspera = false;
                log("Android Auto: o servidor não foi ligado em 3 minutos. Toque em Ligar quando quiser.");
            } finally { esperando = false; }
        }}, "aa-espera").start();
    }

    private void voltarProApp() {
        hp.post(new Runnable() { @Override public void run() {
            try {
                ctx.startActivity(new android.content.Intent(ctx, MainActivity.class)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
            } catch (Exception ignored) {}
        }});
    }

    private volatile android.view.Surface previa;
    private volatile int previaW, previaH;
    private volatile Object donoPrevia;

    /** Uma tela do app quer mostrar o que vai pro painel. A última que pedir fica com a prévia. */
    synchronized void mostrarPrevia(Object dono, android.view.Surface s, int w, int h) {
        donoPrevia = dono; previa = s; previaW = w; previaH = h;
        PonteAA p = ponte;
        if (p != null) p.previa(s, w, h);
    }

    /** A tela 'dono' saiu: tira a prévia, se ainda for dela. */
    synchronized void soltarPrevia(Object dono) {
        if (donoPrevia != dono) return;
        donoPrevia = null; previa = null;
        PonteAA p = ponte;
        if (p != null) p.previa(null, 0, 0);
    }

    private volatile boolean servidorDesligado;
    /** A última tentativa achou o servidor da unidade principal do Android Auto desligado. */
    boolean servidorDesligado() { return servidorDesligado; }

    boolean temVideo() { return ponte != null; }

    // ---------------------------------------------------------------- controlar o Android Auto pelo celular
    private final java.util.concurrent.ExecutorService fila = java.util.concurrent.Executors.newSingleThreadExecutor();

    /**
     * Toque na prévia: fx/fy de 0 a 1 dentro da faixa que aparece no painel (800x384).
     * ação: 0 = encostou, 1 = soltou, 2 = arrastou, 3 = cancelou.
     */
    void tocar(final int acao, float fx, float fy) {
        if (!ligado) return;
        // O Android Auto lê o toque no espaço da interface dele (800x384), sem somar a margem.
        final int x = Math.max(0, Math.min(AA_W - 1, Math.round(fx * AA_W)));
        final int y = Math.max(0, Math.min(AA_H - MARGEM - 1, Math.round(fy * (AA_H - MARGEM))));
        fila.execute(new Runnable() { @Override public void run() {
            Proto.W toque = new Proto.W()
                    .m(1, new Proto.W().n(1, x).n(2, y).n(3, 0))
                    .n(2, 0)
                    .n(3, acao);
            enviar(CH_ENTRADA, 0x8001, new Proto.W().n(1, android.os.SystemClock.elapsedRealtimeNanos()).m(3, toque));
        }});
    }

    /** Aperta e solta uma tecla (códigos do Android: 84 = assistente de voz, 19-23 = direcionais/OK, 4 = voltar). */
    void tecla(final int codigo) {
        if (!ligado) return;
        fila.execute(new Runnable() { @Override public void run() {
            for (boolean baixo : new boolean[]{true, false}) {
                Proto.W k = new Proto.W().m(1, new Proto.W().n(1, codigo).b(2, baixo).n(3, 0).b(4, false));
                enviar(CH_ENTRADA, 0x8001, new Proto.W().n(1, android.os.SystemClock.elapsedRealtimeNanos()).m(4, k));
                try { Thread.sleep(60); } catch (InterruptedException ignored) {}
            }
        }});
    }

    synchronized void conectar() {
        if (linha != null && linha.isAlive()) { log("Android Auto: já estou conectando/conectado."); return; }
        linha = new Thread(new Runnable() { @Override public void run() { sessao(); } }, "android-auto");
        linha.start();
    }

    void parar() {
        ligado = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
    }

    // ================================================================== sessão
    private void sessao() {
        try {
            if (!esperando) log("Android Auto: ligando no servidor da unidade principal (127.0.0.1:" + PORTA + ")…");
            Socket s = new Socket();
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(1 << 20);
            try {
                s.connect(new InetSocketAddress("127.0.0.1", PORTA), 3000);
            } catch (IOException e) {
                servidorDesligado = true;
                Video.mensagemEspera = "Ligue o servidor do Android Auto no celular";
                if (esperando) return;                       // já estamos esperando o usuário ligar
                log("! O servidor da unidade principal do Android Auto não está ligado.");
                log("  Abrindo o Android Auto: toque em ⋮ → \"Iniciar servidor da unidade principal\". O Moto Link conecta sozinho em seguida.");
                av.estado("Ligue o servidor do Android Auto", false);
                abrirAndroidAuto();
                esperarServidor();
                return;
            }
            sock = s;
            servidorDesligado = false;
            Video.mensagemEspera = "";
            out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            in = new DataInputStream(new BufferedInputStream(s.getInputStream(), 256 * 1024));
            ligado = true;

            // 1) versão
            enviarCru(CH_CONTROLE, 0x03, 1, new byte[]{0, 1, 0, 2});
            Quadro v = lerQuadro();
            if (v.tipo() != 2) throw new IOException("resposta de versão inesperada (tipo " + v.tipo() + ")");
            int maj = ((v.d[2] & 0xFF) << 8) | (v.d[3] & 0xFF), min = ((v.d[4] & 0xFF) << 8) | (v.d[5] & 0xFF);
            int st = (short) (((v.d[6] & 0xFF) << 8) | (v.d[7] & 0xFF));
            log("Android Auto: protocolo " + maj + "." + min + " (status " + st + ")");
            if (st == -1) throw new IOException("versão incompatível");

            // 2) TLS
            prepararTls();
            apertoDeMaoTls();
            log("Android Auto: TLS ok (" + tls.getSession().getProtocol() + ").");
            enviarCru(CH_CONTROLE, 0x03, 4, new byte[]{8, 0});        // autenticação concluída
            av.estado("Android Auto conectado ✓", true);
            ctx.getSharedPreferences("guia", Context.MODE_PRIVATE).edit().putBoolean("aaOk", true).apply();
            if (veioDaEspera) { veioDaEspera = false; voltarProApp(); }

            // 3) mensagens
            Thread pings = new Thread(new Runnable() { @Override public void run() { pingar(); } }, "aa-ping");
            pings.setDaemon(true);
            pings.start();
            while (ligado) {
                Quadro q = lerQuadro();
                byte[] dados = (q.flags & 0x08) != 0 ? decifrar(q.d) : q.d;
                montar(q.canal, q.flags, dados);
            }
        } catch (Exception e) {
            if (ligado) log("Android Auto: conexão terminou: " + e.getMessage());
        } finally {
            ligado = false;
            try { if (sock != null) sock.close(); } catch (Exception ignored) {}
            sock = null;
            desligarVideo();
            av.estado("Android Auto desconectado", false);
        }
    }

    // ================================================================== moldura
    private static final class Quadro {
        int canal, flags;
        byte[] d;
        int tipo() { return d.length >= 2 ? ((d[0] & 0xFF) << 8) | (d[1] & 0xFF) : -1; }
    }

    private Quadro lerQuadro() throws IOException {
        Quadro q = new Quadro();
        q.canal = in.readUnsignedByte();
        q.flags = in.readUnsignedByte();
        int len = in.readUnsignedShort();
        if ((q.flags & 0x01) != 0 && (q.flags & 0x02) == 0) in.readInt();   // 1º de vários: tamanho total
        q.d = new byte[len];
        in.readFully(q.d);
        return q;
    }

    private void enviarCru(int canal, int flags, int tipo, byte[] dados) throws IOException {
        byte[] f = new byte[6 + dados.length];
        f[0] = (byte) canal; f[1] = (byte) flags;
        f[2] = (byte) ((dados.length + 2) >> 8); f[3] = (byte) (dados.length + 2);
        f[4] = (byte) (tipo >> 8); f[5] = (byte) tipo;
        System.arraycopy(dados, 0, f, 6, dados.length);
        synchronized (this) { out.write(f); out.flush(); }
    }

    /** Envia uma mensagem cifrada. Tipos de controle (1..26) fora do canal 0 levam a marca 0x04. */
    private void enviar(int canal, int tipo, byte[] proto) {
        try {
            byte[] p = new byte[2 + proto.length];
            p[0] = (byte) (tipo >> 8); p[1] = (byte) tipo;
            System.arraycopy(proto, 0, p, 2, proto.length);
            int flags = (canal != CH_CONTROLE && tipo >= 1 && tipo <= 26) ? 0x0f : 0x0b;
            synchronized (this) {
                byte[] c = cifrar(p);
                out.write(new byte[]{(byte) canal, (byte) flags, (byte) (c.length >> 8), (byte) c.length});
                out.write(c);
                out.flush();
            }
        } catch (Exception e) {
            if (ligado) log("! Android Auto: falha ao enviar tipo " + tipo + ": " + e.getMessage());
        }
    }

    private void enviar(int canal, int tipo, Proto.W w) { enviar(canal, tipo, w.bytes()); }

    /** Junta os pedaços de uma mensagem grande (vídeo) e entrega quando estiver completa. */
    private void montar(int canal, int flags, byte[] d) {
        boolean primeiro = (flags & 0x01) != 0, ultimo = (flags & 0x02) != 0;
        if (primeiro && ultimo) { tratar(canal, d, d.length); return; }
        ByteArrayOutputStream m = montagem[canal & 31];
        if (primeiro) { m = new ByteArrayOutputStream(Math.max(d.length * 4, 64 * 1024)); montagem[canal & 31] = m; }
        if (m == null) return;                                        // pedaço órfão
        m.write(d, 0, d.length);
        if (ultimo) {
            montagem[canal & 31] = null;
            byte[] tudo = m.toByteArray();
            tratar(canal, tudo, tudo.length);
        }
    }

    // ================================================================== TLS
    private void prepararTls() throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        final X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(
                ("-----BEGIN CERTIFICATE-----\n" + CertificadoAA.CERT + "\n-----END CERTIFICATE-----\n").getBytes()));
        final PrivateKey chave = KeyFactory.getInstance("RSA").generatePrivate(
                new PKCS8EncodedKeySpec(android.util.Base64.decode(CertificadoAA.CHAVE, android.util.Base64.DEFAULT)));
        KeyManager km = new X509ExtendedKeyManager() {
            @Override public String[] getClientAliases(String t, Principal[] i) { return new String[]{"central"}; }
            @Override public String chooseClientAlias(String[] t, Principal[] i, Socket s) { return "central"; }
            @Override public String[] getServerAliases(String t, Principal[] i) { return null; }
            @Override public String chooseServerAlias(String t, Principal[] i, Socket s) { return null; }
            @Override public X509Certificate[] getCertificateChain(String a) { return new X509Certificate[]{cert}; }
            @Override public PrivateKey getPrivateKey(String a) { return chave; }
            @Override public String chooseEngineClientAlias(String[] t, Principal[] i, SSLEngine e) { return "central"; }
        };
        TrustManager tm = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] c, String a) {}
            @Override public void checkServerTrusted(X509Certificate[] c, String a) {}
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(new KeyManager[]{km}, new TrustManager[]{tm}, null);
        tls = sc.createSSLEngine("android-auto", PORTA);
        tls.setUseClientMode(true);
        appIn = ByteBuffer.allocate(tls.getSession().getApplicationBufferSize() + 1024);
        netOut = ByteBuffer.allocate(tls.getSession().getPacketBufferSize() + 1024);
    }

    private void apertoDeMaoTls() throws Exception {
        tls.beginHandshake();
        byte[] pend = new byte[0];
        SSLEngineResult.HandshakeStatus hs = tls.getHandshakeStatus();
        long limite = System.currentTimeMillis() + 15000;
        while (hs != SSLEngineResult.HandshakeStatus.FINISHED && hs != SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING) {
            if (System.currentTimeMillis() > limite) throw new IOException("TLS demorou demais");
            switch (hs) {
                case NEED_WRAP: {
                    netOut.clear();
                    SSLEngineResult r = tls.wrap(ByteBuffer.allocate(0), netOut);
                    netOut.flip();
                    if (netOut.hasRemaining()) {
                        byte[] b = new byte[netOut.remaining()];
                        netOut.get(b);
                        enviarCru(CH_CONTROLE, 0x03, 3, b);
                    }
                    hs = r.getHandshakeStatus();
                    break;
                }
                case NEED_UNWRAP: {
                    if (pend.length == 0) pend = lerTls();
                    ByteBuffer src = ByteBuffer.wrap(pend);
                    appIn.clear();
                    SSLEngineResult r = tls.unwrap(src, appIn);
                    if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
                        byte[] mais = lerTls();
                        byte[] j = new byte[pend.length + mais.length];
                        System.arraycopy(pend, 0, j, 0, pend.length);
                        System.arraycopy(mais, 0, j, pend.length, mais.length);
                        pend = j;
                        hs = tls.getHandshakeStatus();
                        break;
                    }
                    byte[] resto = new byte[src.remaining()];
                    src.get(resto);
                    pend = resto;
                    hs = r.getHandshakeStatus();
                    break;
                }
                case NEED_TASK: {
                    Runnable t;
                    while ((t = tls.getDelegatedTask()) != null) t.run();
                    hs = tls.getHandshakeStatus();
                    break;
                }
                default:
                    hs = tls.getHandshakeStatus();
                    if (hs != SSLEngineResult.HandshakeStatus.NEED_UNWRAP && hs != SSLEngineResult.HandshakeStatus.NEED_WRAP
                            && hs != SSLEngineResult.HandshakeStatus.NEED_TASK) return;
            }
        }
    }

    /** Lê o próximo pedaço do aperto de mão TLS (tipo 3 no canal 0); ignora versões repetidas. */
    private byte[] lerTls() throws IOException {
        while (true) {
            Quadro q = lerQuadro();
            if (q.tipo() == 2) continue;
            if (q.canal != 0 || q.tipo() != 3) throw new IOException("esperava TLS e veio tipo " + q.tipo());
            byte[] b = new byte[q.d.length - 2];
            System.arraycopy(q.d, 2, b, 0, b.length);
            return b;
        }
    }

    private byte[] decifrar(byte[] net) throws Exception {
        ByteBuffer src = ByteBuffer.wrap(net);
        ByteArrayOutputStream o = new ByteArrayOutputStream(net.length);
        while (src.hasRemaining()) {
            appIn.clear();
            SSLEngineResult r = tls.unwrap(src, appIn);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                appIn = ByteBuffer.allocate(appIn.capacity() * 2);
                continue;
            }
            if (r.getStatus() == SSLEngineResult.Status.CLOSED) throw new IOException("TLS fechado pelo Android Auto");
            appIn.flip();
            o.write(appIn.array(), appIn.arrayOffset() + appIn.position(), appIn.remaining());
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) break;
            if (r.bytesConsumed() == 0 && r.bytesProduced() == 0) break;
        }
        return o.toByteArray();
    }

    private byte[] cifrar(byte[] plano) throws Exception {
        ByteBuffer src = ByteBuffer.wrap(plano);
        ByteArrayOutputStream o = new ByteArrayOutputStream(plano.length + 64);
        while (src.hasRemaining()) {
            netOut.clear();
            SSLEngineResult r = tls.wrap(src, netOut);
            if (r.getStatus() == SSLEngineResult.Status.BUFFER_OVERFLOW) {
                netOut = ByteBuffer.allocate(netOut.capacity() * 2);
                continue;
            }
            netOut.flip();
            o.write(netOut.array(), netOut.arrayOffset() + netOut.position(), netOut.remaining());
        }
        return o.toByteArray();
    }

    // ================================================================== o que o Android Auto manda
    private void tratar(int canal, byte[] d, int n) {
        if (n < 2) return;
        int tipo = ((d[0] & 0xFF) << 8) | (d[1] & 0xFF);
        if (tipo == 7) { abrirCanal(canal, d, n); return; }                  // pedido de abrir canal (qualquer canal)
        switch (canal) {
            case CH_CONTROLE: controle(tipo, d, n); return;
            case CH_VIDEO: video(tipo, d, n); return;
            case CH_SENSOR: sensor(tipo, d, n); return;
            case CH_ENTRADA: entrada(tipo, d, n); return;
        }
        desconhecido(canal, tipo, n);
    }

    private void desconhecido(int canal, int tipo, int n) {
        if (desconhecidos++ < 25) log(String.format("  [AA] canal %d tipo 0x%04X (%d bytes) — ignorado", canal, tipo, n));
    }

    private void controle(int tipo, byte[] d, int n) {
        switch (tipo) {
            case 5:                                                          // quem é a central?
                log("Android Auto: celular " + Proto.texto(d, 2, n, 5) + " " + Proto.texto(d, 2, n, 4) + " pediu a descrição da central.");
                enviar(CH_CONTROLE, 6, descricaoDaCentral());
                log("Android Auto: descrição enviada (moto, tela 800x384).");
                return;
            case 11:                                                         // ping
                enviar(CH_CONTROLE, 12, new Proto.W().n(1, Proto.num(d, 2, n, 1, 0)));
                return;
            case 12: return;                                                 // resposta ao nosso ping
            case 13:                                                         // foco de navegação
                enviar(CH_CONTROLE, 14, new Proto.W().n(1, 2));
                return;
            case 15:                                                         // tchau
                log("Android Auto: o celular encerrou a projeção.");
                enviar(CH_CONTROLE, 16, new byte[0]);
                parar();
                return;
            case 17:
                log("Android Auto: comando de voz " + (Proto.num(d, 2, n, 1, 0) == 1 ? "começou" : "terminou"));
                return;
            case 18: {                                                       // foco de áudio: sempre concede
                long pedido = Proto.num(d, 2, n, 1, 0);
                int estado = pedido == 4 ? 3 : pedido == 1 ? 1 : 2;           // RELEASE->LOSS, GAIN->GAIN, resto->transitório
                enviar(CH_CONTROLE, 19, new Proto.W().n(1, estado));
                return;
            }
            case 9:
                log("Android Auto: canal " + Proto.num(d, 2, n, 1, -1) + " fechado.");
                return;
        }
        desconhecido(CH_CONTROLE, tipo, n);
    }

    private void abrirCanal(int canal, byte[] d, int n) {
        log("Android Auto: abriu o canal " + canal + nomeCanal(canal));
        enviar(canal, 8, new Proto.W().n(1, 0));
        if (canal == CH_SENSOR) enviarSensor(13);
    }

    private static String nomeCanal(int c) {
        switch (c) { case 1: return " (sensores)"; case 2: return " (vídeo)"; case 3: return " (botões)"; default: return ""; }
    }

    private void video(int tipo, byte[] d, int n) {
        switch (tipo) {
            case 0x8000:                                                     // configurar vídeo
                log("Android Auto: preparando o vídeo…");
                ligarVideo();
                enviar(CH_VIDEO, 0x8003, new Proto.W().n(1, 2).n(2, 1).n(3, 0));
                enviar(CH_VIDEO, 0x8008, new Proto.W().n(1, 1).b(2, false));   // foco de vídeo: projetado
                return;
            case 0x8001:                                                     // começar
                sessaoVideo = (int) Proto.num(d, 2, n, 1, 0);
                log("Android Auto: vídeo começou (sessão " + sessaoVideo + ") ✓");
                av.estado("Android Auto no painel ✓", true);
                return;
            case 0x8002:
                log("Android Auto: vídeo parou.");
                return;
            case 0x8007: {                                                   // o celular pede foco de vídeo
                long modo = Proto.num(d, 2, n, 2, 1);
                log("Android Auto: pedido de foco de vídeo (modo " + modo + ")");
                enviar(CH_VIDEO, 0x8008, new Proto.W().n(1, modo == 2 ? 2 : 1).b(2, false));
                return;
            }
            case 1:                                                          // SPS/PPS
                if (ponte != null) ponte.entrada(d, 2, n - 2, true);
                log("Android Auto: configuração do vídeo recebida (" + (n - 2) + " bytes).");
                return;
            case 0: {                                                        // quadro
                int ini = inicioH264(d, n);
                if (ponte != null && ini > 0) ponte.entrada(d, ini, n - ini, false);
                enviar(CH_VIDEO, 0x8004, new Proto.W().n(1, sessaoVideo).n(2, 1));
                quadrosAA++;
                long agora = System.currentTimeMillis();
                if (agora - ultimoAviso > 10000) {
                    ultimoAviso = agora;
                    log("  [AA] " + quadrosAA + " quadros recebidos do Android Auto" + (ponte != null ? ", " + ponte.quadrosDecodificados() + " decodificados" : ""));
                }
                return;
            }
        }
        desconhecido(CH_VIDEO, tipo, n);
    }

    /** Quadro de vídeo: [tipo 2][carimbo 8][H.264] ou [tipo 2][H.264]. Acha onde começa o H.264. */
    private static int inicioH264(byte[] d, int n) {
        if (n > 14 && d[10] == 0 && d[11] == 0 && (d[12] == 1 || (d[12] == 0 && d[13] == 1))) return 10;
        if (n > 6 && d[2] == 0 && d[3] == 0 && (d[4] == 1 || (d[4] == 0 && d[5] == 1))) return 2;
        return 10;
    }

    private void sensor(int tipo, byte[] d, int n) {
        if (tipo == 0x8001) {                                               // pedido de sensor
            int qual = (int) Proto.num(d, 2, n, 1, 0);
            log("Android Auto: pediu o sensor " + qual + (qual == 13 ? " (situação de direção)" : qual == 10 ? " (modo noturno)" : ""));
            enviar(CH_SENSOR, 0x8002, new Proto.W().n(1, 0));
            enviarSensor(qual);
            return;
        }
        desconhecido(CH_SENSOR, tipo, n);
    }

    private void enviarSensor(int qual) {
        if (qual == 13) enviar(CH_SENSOR, 0x8003, new Proto.W().m(13, new Proto.W().n(1, 0)));
        else if (qual == 10) {
            int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            enviar(CH_SENSOR, 0x8003, new Proto.W().m(10, new Proto.W().b(1, h >= 18 || h < 6)));
        }
    }

    private void entrada(int tipo, byte[] d, int n) {
        if (tipo == 0x8002) {                                               // quais botões a central tem
            enviar(CH_ENTRADA, 0x8003, new Proto.W().n(1, 0));
            return;
        }
        desconhecido(CH_ENTRADA, tipo, n);
    }

    private void pingar() {
        while (ligado) {
            try { Thread.sleep(1500); } catch (InterruptedException e) { return; }
            if (!ligado) return;
            enviar(CH_CONTROLE, 11, new Proto.W().n(1, System.nanoTime() / 1000));
        }
    }

    // ================================================================== descrição da "central"
    private byte[] descricaoDaCentral() {
        String id = ctx.getSharedPreferences("painel", Context.MODE_PRIVATE).getString("id", "motolink");
        int pw = midia.largura(), ph = midia.altura();
        if (pw > 1000) { AA_W = 1280; AA_H = 720; RES = 2; DENSIDADE = 200; }
        else { AA_W = 800; AA_H = 480; RES = 1; DENSIDADE = 140; }
        int util = pw > 0 && ph > 0 ? Math.round(AA_W * (float) ph / pw) : AA_H - 96;   // 800x384 -> 384
        MARGEM = Math.max(0, AA_H - Math.min(AA_H, util));
        log("Android Auto: painel " + pw + "x" + ph + " → AA " + AA_W + "x" + AA_H + ", área útil " + AA_W + "x" + (AA_H - MARGEM) + " (margem " + MARGEM + ")");
        Proto.W sensores = new Proto.W()
                .m(1, new Proto.W().n(1, 13))       // situação de direção
                .m(1, new Proto.W().n(1, 10));      // modo noturno
        Proto.W video = new Proto.W()
                .n(1, 3)                            // H.264
                .m(4, new Proto.W().n(1, RES).n(2, 2).n(3, 0).n(4, MARGEM).n(5, DENSIDADE)
                        .n(8, 10000).n(10, 3))      // resolução, 30 q/s, margem na altura, pixel quadrado, H.264
                .b(5, true);
        Proto.W botoes = new Proto.W().m(2, new Proto.W().n(1, AA_W).n(2, AA_H));   // tela de toque (o painel não tem, mas o AA espera)
        for (int k : new int[]{4, 19, 20, 21, 22, 23, 84, 85, 87, 88}) botoes.n(1, k);
        Proto.W info = new Proto.W()
                .s(1, "Zontes").s(2, "GK 350").s(3, "2025").s(4, "motolink-" + id.substring(0, Math.min(8, id.length())))
                .s(5, "Moto Link").s(6, "Moto Link").s(7, "1").s(8, Sessao.VERSAO)
                .n(9, 3);                           // tipo de veículo: moto
        return new Proto.W()
                .m(1, new Proto.W().n(1, CH_SENSOR).m(2, sensores))
                .m(1, new Proto.W().n(1, CH_VIDEO).m(3, video))
                .m(1, new Proto.W().n(1, CH_ENTRADA).m(4, botoes))
                .s(2, "Zontes").s(3, "GK 350").s(4, "2025").s(5, "motolink-" + id.substring(0, Math.min(8, id.length())))
                .n(6, 0)
                .s(7, "Moto Link").s(8, "Moto Link").s(9, "1").s(10, Sessao.VERSAO)
                .b(11, false).b(12, false)
                .s(14, "Moto Link")
                .m(17, info)
                .bytes();
    }

    // ================================================================== vídeo -> painel
    private synchronized void ligarVideo() {
        if (ponte != null) return;
        try {
            Video v = new Video(midia.largura(), midia.altura(), midia.taxa(), new Video.Log() {
                @Override public void log(String s) { AndroidAuto.this.log(s); }
            }, false);
            v.iniciar();
            int corte = MARGEM / 2;
            PonteAA p = new PonteAA(AA_W, AA_H, corte, MARGEM - corte, v, new Video.Log() {
                @Override public void log(String s) { AndroidAuto.this.log(s); }
            });
            p.iniciar();
            videoAA = v;
            ponte = p;
            midia.usarFonte(v);
            if (previa != null) p.previa(previa, previaW, previaH);
        } catch (Exception e) {
            log("! Android Auto: não consegui montar o caminho do vídeo: " + e);
        }
    }

    private synchronized void desligarVideo() {
        if (ponte != null) ponte.parar();
        ponte = null;
        if (videoAA != null) midia.voltarTelaDeTeste();
        videoAA = null;
    }
}
