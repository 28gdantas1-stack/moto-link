package com.gabriel.motolink;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * A conexão com o painel e o log moram aqui, fora da tela. Assim, se o Android fechar ou recriar a
 * tela do app (o que às vezes acontece ao voltar para ele), a moto continua conectada e o log continua.
 */
final class Sessao implements Painel.Ouvinte {

    static final String VERSAO = "1.1.3";

    interface Tela {
        void novaLinha(String linha);
        void novoEstado(String texto, boolean ok);
        void tudoDeNovo();
    }

    private static Sessao unica;

    static synchronized Sessao de(Context c) {
        if (unica == null) unica = new Sessao(c.getApplicationContext());
        return unica;
    }

    final Painel painel;
    final Rede rede;
    final Bolha bolha;
    final AndroidAuto aa;
    private volatile boolean ativa;

    /** O usuário mandou conectar e ainda não desconectou. */
    boolean ativa() { return ativa; }

    private final Context ctx;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final StringBuilder logTudo = new StringBuilder();
    private final List<String> logTela = new ArrayList<>();
    private final SimpleDateFormat hora = new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT);
    private volatile Tela tela;
    private String estadoTexto = "Desconectado";
    private boolean estadoOk;
    private int freqPedida;

    private Sessao(Context app) {
        ctx = app;
        prepararCaixaPreta();
        painel = new Painel(app, this);
        rede = new Rede(app, this);
        bolha = new Bolha(app, this);
        aa = new AndroidAuto(app, rede.midia, new AndroidAuto.Avisos() {
            @Override public void log(String s) { Sessao.this.log(s); }
            @Override public void estado(String s, boolean ok) { Sessao.this.estado(s, ok); }
        });
        rede.aoComecarVideo = new Runnable() { @Override public void run() {
            if (!aa.ligado()) aa.conectar();          // painel pronto: tenta pôr o Android Auto nele
        }};
        log(Variante.NOME + " v" + VERSAO + " · Android " + Build.VERSION.RELEASE + " · " + Build.MODEL);
        contarFechamentoAnterior();
    }

    // ---------------------------------------------------------------- caixa-preta: por que o app fechou?
    /*
     * O log fica só na memória; se o app fecha sozinho, ele se perde. Então cada linha também vai para um arquivo,
     * e um erro não tratado é gravado antes do app morrer. Na próxima abertura, o log mostra o motivo do último
     * fechamento (o Android 11+ conta até travamentos nativos) e as últimas linhas da vez anterior.
     */
    private java.io.Writer arquivoLog;
    private String logAnterior;

    private void prepararCaixaPreta() {
        try {
            java.io.File atual = new java.io.File(ctx.getFilesDir(), "log-atual.txt");
            java.io.File anterior = new java.io.File(ctx.getFilesDir(), "log-anterior.txt");
            if (atual.exists()) {
                anterior.delete();
                atual.renameTo(anterior);
            }
            if (anterior.exists()) {
                byte[] b = new byte[(int) Math.min(anterior.length(), 64 * 1024)];
                java.io.RandomAccessFile f = new java.io.RandomAccessFile(anterior, "r");
                f.seek(Math.max(0, anterior.length() - b.length));
                f.readFully(b);
                f.close();
                logAnterior = new String(b, java.nio.charset.StandardCharsets.UTF_8);
            }
            arquivoLog = new java.io.OutputStreamWriter(new java.io.FileOutputStream(atual), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
        final Thread.UncaughtExceptionHandler antes = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override public void uncaughtException(Thread t, Throwable e) {
                try {
                    java.io.StringWriter sw = new java.io.StringWriter();
                    e.printStackTrace(new java.io.PrintWriter(sw));
                    log("✖ ERRO que fechou o app (thread " + t.getName() + "):\n" + sw);
                } catch (Throwable ignored) {}
                if (antes != null) antes.uncaughtException(t, e);
            }
        });
    }

    private void contarFechamentoAnterior() {
        String motivo = null;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
                List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 1);
                if (!l.isEmpty()) {
                    android.app.ApplicationExitInfo i = l.get(0);
                    motivo = motivoDeSaida(i.getReason()) + (i.getDescription() != null ? " (" + i.getDescription() + ")" : "")
                            + " em " + new SimpleDateFormat("dd/MM HH:mm:ss", Locale.ROOT).format(new Date(i.getTimestamp()));
                    int r = i.getReason();
                    boolean grave = r != 1 && r != 10 && r != 11 && r != 16;   // tudo, menos saída normal, usuário e atualização
                    if (!grave) { logAnterior = null; }
                }
            } catch (Exception ignored) {}
        }
        if (motivo != null) log("Último fechamento do app: " + motivo);
        if (Build.VERSION.SDK_INT >= 31 && motivo != null && motivo.startsWith("ERRO nativo")) detalharErroNativo();
        boolean teveErro = logAnterior != null && logAnterior.contains("✖ ERRO que fechou o app");
        if (logAnterior != null && (teveErro || motivo != null)) {
            String[] linhas = logAnterior.split("\n");
            int de = Math.max(0, linhas.length - (teveErro ? 120 : 40));
            StringBuilder sb = new StringBuilder();
            for (int i = de; i < linhas.length; i++) sb.append("   | ").append(linhas[i]).append('\n');
            log("★ Últimas linhas da vez anterior (antes de fechar):\n" + sb);
        }
        logAnterior = null;
    }

    /** Erro nativo: o Android guarda um relatório (tombstone). Mostra as partes legíveis: bibliotecas, funções, threads. */
    private void detalharErroNativo() {
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<android.app.ApplicationExitInfo> l = am.getHistoricalProcessExitReasons(ctx.getPackageName(), 0, 1);
            if (l.isEmpty()) return;
            java.io.InputStream in = l.get(0).getTraceInputStream();
            if (in == null) { log("  (o Android não guardou detalhes do erro nativo)"); return; }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] b = new byte[16384];
            int r;
            while ((r = in.read(b)) > 0 && bo.size() < 400_000) bo.write(b, 0, r);
            in.close();
            byte[] d = bo.toByteArray();
            java.util.LinkedHashSet<String> partes = new java.util.LinkedHashSet<>();
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i <= d.length; i++) {
                int c = i < d.length ? d[i] & 0xFF : 0;
                if (c >= 0x20 && c < 0x7F) { cur.append((char) c); continue; }
                if (cur.length() >= 6) {
                    String t = cur.toString().trim();
                    if (t.contains(".so") || t.contains("::") || t.contains("(") || t.contains("abort") || t.contains("Abort")
                            || t.contains("SIG") || t.contains("signal") || t.contains("Cause") || t.contains("video")
                            || t.contains("Thread") || t.contains("Codec") || t.contains("codec") || t.contains("aa-") || t.contains("Render"))
                        partes.add(t);
                }
                cur.setLength(0);
            }
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (String p : partes) { if (n++ >= 70) break; sb.append("   # ").append(p).append('\n'); }
            log("★ Detalhes do erro nativo (" + d.length + " bytes):\n" + sb);
        } catch (Throwable e) {
            log("  (não consegui ler os detalhes do erro nativo: " + e + ")");
        }
    }

    private static String motivoDeSaida(int r) {
        switch (r) {
            case 1: return "o próprio app saiu";
            case 2: return "o sistema encerrou (sinal)";
            case 3: return "o sistema fechou por falta de memória";
            case 4: return "ERRO no app";
            case 5: return "ERRO nativo (do sistema ou do vídeo)";
            case 6: return "o app travou (ANR)";
            case 7: return "falha ao iniciar";
            case 8: return "permissão mudou";
            case 9: return "uso excessivo de recursos";
            case 10: return "o usuário pediu para fechar";
            case 11: return "o usuário parou o app";
            case 12: return "dependência fechou";
            case 14: return "congelado pelo sistema";
            case 15: return "pacote mudou";
            case 16: return "o app foi atualizado";
            default: return "outro motivo (" + r + ")";
        }
    }

    // ---------------------------------------------------------------- telas visíveis e bolinha
    private int telasVisiveis;

    /** Cada tela do app avisa quando aparece/some. Sem nenhuma tela visível, a bolinha segura o app vivo. */
    void telaVisivel(boolean sim) {
        telasVisiveis = Math.max(0, telasVisiveis + (sim ? 1 : -1));
        h.removeCallbacks(checarBolha);
        h.postDelayed(checarBolha, sim ? 0 : 350);           // espera a próxima tela abrir (troca entre telas)
    }

    private final Runnable checarBolha = new Runnable() {
        @Override public void run() {
            if (telasVisiveis > 0) { bolha.esconder(); return; }
            if ((ativa || rede.midia.transmitindo() || aa.ligado()) && Bolha.podeAparecer(ctx)) bolha.mostrar();
        }
    };

    // ---------------------------------------------------------------- tela
    void mostrarEm(Tela t) {
        tela = t;
        if (t != null) t.tudoDeNovo();
    }

    void soltar(Tela t) { if (tela == t) tela = null; }

    synchronized String linhasNaTela() {
        StringBuilder sb = new StringBuilder();
        for (String s : logTela) sb.append(s).append('\n');
        return sb.toString();
    }

    synchronized String logCompleto() { return logTudo.toString(); }

    synchronized void limparLog() {
        logTudo.setLength(0);
        logTela.clear();
    }

    String estadoTexto() { return estadoTexto; }
    boolean estadoOk() { return estadoOk; }

    // ---------------------------------------------------------------- ações
    void conectar() {
        ativa = true;
        Servico.ligar(ctx);
        situacaoDaBateria();
        vigiar();
        if (Variante.NM) {
            if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
                log("! Falta a permissão de dispositivos próximos (Wi-Fi). Abra o app e aceite.");
                estado("Falta a permissão de dispositivos próximos", false);
                return;
            }
            android.content.SharedPreferences q = ctx.getSharedPreferences("painel_nm", Context.MODE_PRIVATE);
            rede.entrarNoPainel(q.getString("nome", null), q.getString("mac", null));
        } else {
            painel.procurar();
        }
    }

    // ---------------------------------------------------------------- QR code do painel (Moto Link NM)
    /** Lê o link do QR do painel (http://www.carbit.com.cn/...?name=ZT5Gf541&mac=...) e guarda nome e endereço. */
    String guardarQr(String link) {
        if (link == null) return null;
        link = link.trim();
        int q = link.indexOf('?');
        if (!link.contains("carbit.com.cn") || q < 0) return null;
        String nome = null, mac = null;
        for (String par : link.substring(q + 1).split("&")) {
            int e = par.indexOf('=');
            if (e <= 0) continue;
            String k = par.substring(0, e), v;
            try { v = java.net.URLDecoder.decode(par.substring(e + 1), "UTF-8"); } catch (Exception x) { v = par.substring(e + 1); }
            if (k.equals("name")) nome = v;
            else if (k.equals("mac")) mac = v;
        }
        if (nome == null && mac == null) return null;
        ctx.getSharedPreferences("painel_nm", Context.MODE_PRIVATE).edit().putString("nome", nome).putString("mac", mac).apply();
        log("QR do painel guardado: nome " + nome + ", endereço " + mac);
        return nome != null ? nome : mac;
    }

    /** Nome do painel guardado pelo QR (ou o último achado sozinho), para mostrar na tela. */
    String painelGuardado() {
        android.content.SharedPreferences q = ctx.getSharedPreferences("painel_nm", Context.MODE_PRIVATE);
        String n = q.getString("nome", null);
        return n != null ? n : q.getString("ultimoNome", null);
    }

    boolean qrGuardado() {
        return ctx.getSharedPreferences("painel_nm", Context.MODE_PRIVATE).getString("nome", null) != null;
    }

    /** Diz como o Android está tratando o app (economia de bateria, restrições, grupo de espera). */
    void situacaoDaBateria() {
        StringBuilder sb = new StringBuilder("Bateria: ");
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            boolean livre = pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
            sb.append(livre ? "sem restrição ✓" : "OTIMIZADA (o Android pode congelar o app)");
        } catch (Exception e) { sb.append("?"); }
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 28 && am.isBackgroundRestricted()) sb.append(" · RESTRITO em segundo plano");
        } catch (Exception ignored) {}
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.app.usage.UsageStatsManager u = (android.app.usage.UsageStatsManager) ctx.getSystemService(Context.USAGE_STATS_SERVICE);
                sb.append(" · grupo ").append(u.getAppStandbyBucket());
            }
        } catch (Exception ignored) {}
        log(sb.toString());
    }

    boolean bateriaLivre() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
            return pm.isIgnoringBatteryOptimizations(ctx.getPackageName());
        } catch (Exception e) { return true; }
    }

    private Thread vigia;

    /** Uma linha que acorda a cada 100 ms: se ela demorar muito, foi o Android que congelou o app inteiro. */
    private synchronized void vigiar() {
        if (vigia != null) return;
        vigia = new Thread(new Runnable() { @Override public void run() {
            long antes = android.os.SystemClock.elapsedRealtime();
            int avisos = 0;
            while (true) {
                try { Thread.sleep(100); } catch (InterruptedException e) { return; }
                long agora = android.os.SystemClock.elapsedRealtime();
                long d = agora - antes - 100;
                if (d > 400 && ativa && avisos++ < 60) log("  ⚠ o Android congelou o app inteiro por " + d + " ms");
                antes = agora;
            }
        }}, "vigia");
        vigia.setDaemon(true);
        vigia.start();
    }

    void desconectar() {
        ativa = false;
        bolha.esconder();
        aa.parar();
        painel.desconectar();
        rede.parar();
        Servico.desligar(ctx);
        estado("Desconectado", false);
        log("Desconectado pelo botão.");
    }

    void criarRede() { criarRede(freqPedida); }

    private void criarRede(int frequencia) {
        if (Build.VERSION.SDK_INT >= 33 && ctx.checkSelfPermission("android.permission.NEARBY_WIFI_DEVICES") != PackageManager.PERMISSION_GRANTED) {
            log("! Falta a permissão de dispositivos próximos (Wi-Fi). Abra o app e aceite.");
            estado("Falta a permissão de dispositivos próximos", false);
            return;
        }
        rede.criar(frequencia, new Rede.Pronta() {
            @Override public void rede(String ssid, String senha, String ip) {
                painel.enviarWifi(ssid, senha, ip);
                estado("Esperando o painel entrar no Wi-Fi…", true);
            }
        });
    }

    // ---------------------------------------------------------------- Painel.Ouvinte
    @Override public void log(String linha) {
        final String l = hora.format(new Date()) + "  " + linha;
        synchronized (this) {
            logTudo.append(l).append('\n');
            if (logTudo.length() > 4_000_000) logTudo.delete(0, 1_000_000);
            logTela.add(l);
            if (logTela.size() > 500) logTela.subList(0, logTela.size() - 400).clear();
            if (arquivoLog != null) {
                try { arquivoLog.write(l); arquivoLog.write('\n'); arquivoLog.flush(); } catch (Exception ignored) {}
            }
        }
        h.post(new Runnable() { @Override public void run() {
            Tela t = tela;
            if (t != null) t.novaLinha(l);
        }});
    }

    @Override public void estado(final String texto, final boolean ok) {
        estadoTexto = texto;
        estadoOk = ok;
        h.post(new Runnable() { @Override public void run() {
            Tela t = tela;
            if (t != null) t.novoEstado(texto, ok);
        }});
    }

    @Override public void painelPedeRede(int status, int frequencia, String json) {
        freqPedida = frequencia;
        if (status == 2) criarRede(frequencia);
        else if (status == 1) { log("O painel quer que o celular entre na rede DELE (ainda não implementado): " + json); estado("Este painel ainda não é compatível (envie o log)", false); }
        else if (status == 0) log("O painel diz que a rede já está pronta.");
    }

    @Override public void painelNaRede(String ip) {
        if (ip == null || ip.isEmpty() || ip.startsWith("0.") || ip.startsWith("127.")) {
            log("O painel ainda está pegando IP (" + ip + "), espero o próximo aviso.");
            return;
        }
        log("O painel está na rede com IP " + ip + ". Apresentando o celular…");
        rede.apresentar(ip);
    }
}
