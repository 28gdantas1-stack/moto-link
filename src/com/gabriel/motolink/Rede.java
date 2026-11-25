package com.gabriel.motolink;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * Rede Wi-Fi Direct criada pelo celular (o celular é o "dono do grupo", IP 192.168.49.1) e
 * as portas TCP onde o painel se conecta (10920 vídeo, 10921 controle de mídia, 10922 comandos, 10923 internet).
 * 10920/10921 são tratadas pela classe Midia (imagem); 10922 é o protocolo PXC de comandos.
 */
@SuppressWarnings({"MissingPermission", "deprecation"})
final class Rede {

    interface Pronta { void rede(String ssid, String senha, String ip); }

    static final int[] PORTAS = {10920, 10921, 10922, 10923};
    private static final String[] NOMES = {"vídeo", "controle", "comandos", "internet"};

    private final Context ctx;
    private final Painel.Ouvinte ouv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final WifiP2pManager p2p;
    private final WifiP2pManager.Channel canal;
    private final List<ServerSocket> servidores = new ArrayList<>();
    private boolean ouvindo, receptor;
    private int clientesAntes = -1;
    final Midia midia;
    /** Chamado quando o painel começa a puxar imagem (a Sessao usa para ligar o Android Auto). */
    volatile Runnable aoComecarVideo;

    Rede(Context c, Painel.Ouvinte o) {
        ctx = c.getApplicationContext();
        ouv = o;
        midia = new Midia(new Midia.Avisos() {
            @Override public void log(String s) { ouv.log(s); }
            @Override public void estado(String s, boolean ok) { ouv.estado(s, ok); }
            @Override public void videoComecou(int w, int h) {
                avisarAppNaFrente(w, h);
                Runnable r = aoComecarVideo;
                if (r != null) r.run();
            }
        });
        p2p = (WifiP2pManager) ctx.getSystemService(Context.WIFI_P2P_SERVICE);
        canal = p2p == null ? null : p2p.initialize(ctx, Looper.getMainLooper(), null);
    }

    private void log(String s) { ouv.log(s); }

    // ---------------------------------------------------------------- grupo Wi-Fi Direct
    void criar(final int frequencia, final Pronta pronta) {
        if (p2p == null || canal == null) { log("! Este celular não tem Wi-Fi Direct."); ouv.estado("Este celular não tem Wi-Fi Direct", false); return; }
        WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
        if (wm != null && !wm.isWifiEnabled()) { log("! Ligue o Wi-Fi do celular (não precisa estar conectado em rede nenhuma)."); ouv.estado("Ligue o Wi-Fi do celular", false); return; }
        abrirPortas();
        registrar();
        log("Criando a rede Wi-Fi Direct" + (frequencia > 0 ? " em " + frequencia + " MHz" : " (5 GHz)") + "…");
        p2p.removeGroup(canal, new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() { h.postDelayed(new Runnable() { @Override public void run() { criarComNome(frequencia, pronta); } }, 600); }
            @Override public void onFailure(int r) { criarComNome(frequencia, pronta); }
        });
    }

    private void criarComNome(int frequencia, final Pronta pronta) {
        WifiP2pConfig cfg = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                WifiP2pConfig.Builder b = new WifiP2pConfig.Builder()
                        .setNetworkName("DIRECT-ML-MotoLink")
                        .setPassphrase(senhaNova());
                if (frequencia > 0) b.setGroupOperatingFrequency(frequencia);
                else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ);
                cfg = b.build();
            } catch (Exception e) { log("  (config própria falhou: " + e.getMessage() + ")"); }
        }
        WifiP2pManager.ActionListener fim = new WifiP2pManager.ActionListener() {
            @Override public void onSuccess() {
                log("Rede criada. Lendo nome e senha…");
                h.postDelayed(new Runnable() { @Override public void run() { lerGrupo(pronta, 0); } }, 1500);
            }
            @Override public void onFailure(int r) {
                log("! Não consegui criar a rede (código " + r + ": " + motivo(r) + ").");
                ouv.estado("Não consegui criar o Wi-Fi para o painel", false);
            }
        };
        if (cfg != null) {
            final WifiP2pManager.ActionListener semConfig = fim;
            p2p.createGroup(canal, cfg, new WifiP2pManager.ActionListener() {
                @Override public void onSuccess() { semConfig.onSuccess(); }
                @Override public void onFailure(int r) {
                    log("  rede com nome próprio falhou (" + motivo(r) + "). Tentando com nome automático…");
                    p2p.createGroup(canal, semConfig);
                }
            });
        } else {
            p2p.createGroup(canal, fim);
        }
    }

    private void lerGrupo(final Pronta pronta, final int tentativa) {
        p2p.requestGroupInfo(canal, new WifiP2pManager.GroupInfoListener() {
            @Override public void onGroupInfoAvailable(final WifiP2pGroup g) {
                if (g == null || g.getNetworkName() == null) {
                    if (tentativa < 5) h.postDelayed(new Runnable() { @Override public void run() { lerGrupo(pronta, tentativa + 1); } }, 1000);
                    else log("! A rede foi criada mas não consegui ler o nome dela.");
                    return;
                }
                p2p.requestConnectionInfo(canal, new WifiP2pManager.ConnectionInfoListener() {
                    @Override public void onConnectionInfoAvailable(WifiP2pInfo info) {
                        String ip = info != null && info.groupOwnerAddress != null ? info.groupOwnerAddress.getHostAddress() : "192.168.49.1";
                        String freq = Build.VERSION.SDK_INT >= 29 ? "  frequência " + g.getFrequency() + " MHz" : "";
                        log("Rede pronta: " + g.getNetworkName() + "  senha " + g.getPassphrase() + "  IP " + ip + freq + "  interface " + g.getInterface());
                        pronta.rede(g.getNetworkName(), g.getPassphrase(), ip);
                    }
                });
            }
        });
    }

    private static String senhaNova() {
        String abc = "abcdefghijkmnpqrstuvwxyz23456789";
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 12; i++) sb.append(abc.charAt(r.nextInt(abc.length())));
        return sb.toString();
    }

    private static String motivo(int r) {
        switch (r) {
            case WifiP2pManager.P2P_UNSUPPORTED: return "Wi-Fi Direct não suportado";
            case WifiP2pManager.BUSY: return "Wi-Fi ocupado";
            case WifiP2pManager.ERROR: return "erro interno";
            default: return "desconhecido";
        }
    }

    // ---------------------------------------------------------------- quem entrou na rede
    private void registrar() {
        if (receptor) return;
        receptor = true;
        IntentFilter f = new IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        BroadcastReceiver r = new BroadcastReceiver() {
            @Override public void onReceive(Context c, Intent i) {
                WifiP2pGroup g = i.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_GROUP);
                if (g == null) return;
                int n = g.getClientList().size();
                if (n == clientesAntes) return;
                clientesAntes = n;
                StringBuilder sb = new StringBuilder();
                for (WifiP2pDevice d : g.getClientList()) sb.append(' ').append(d.deviceName).append(" (").append(d.deviceAddress).append(')');
                log(n == 0 ? "Wi-Fi Direct: ninguém conectado na rede." : "Wi-Fi Direct: " + n + " conectado(s):" + sb);
                if (n > 0) ouv.estado("Painel entrou no Wi-Fi ✓", true);
            }
        };
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(r, f);
    }

    // ---------------------------------------------------------------- portas TCP
    private void abrirPortas() {
        if (ouvindo) return;
        ouvindo = true;
        for (int i = 0; i < PORTAS.length; i++) {
            final int porta = PORTAS[i];
            final String nome = NOMES[i];
            new Thread(new Runnable() { @Override public void run() { escutar(porta, nome); } }, "porta-" + porta).start();
        }
    }

    private void escutar(int porta, String nome) {
        try {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(porta));
            synchronized (servidores) { servidores.add(ss); }
            log("Porta " + porta + " (" + nome + ") aberta.");
            while (!ss.isClosed()) {
                final Socket s = ss.accept();
                log("★ Painel conectou na porta " + porta + " (" + nome + ") de " + s.getInetAddress().getHostAddress() + ":" + s.getPort());
                final int p = porta;
                new Thread(new Runnable() { @Override public void run() {
                    if (p == 10920) midia.dados(s);
                    else if (p == 10921) midia.controle(s);
                    else lerPxc(s, nome);
                } }, "ler-" + porta).start();
            }
        } catch (Exception e) {
            if (ouvindo) log("! Porta " + porta + ": " + e.getMessage());
        }
    }

    private void ler(Socket s, String nome) {
        int total = 0, mostrados = 0;
        try {
            InputStream in = s.getInputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (mostrados < 40) {
                    mostrados++;
                    byte[] p = new byte[Math.min(n, 96)];
                    System.arraycopy(buf, 0, p, 0, p.length);
                    log("  [" + nome + "] ← " + n + " bytes: " + Quadro.hex(p) + (n > 96 ? " …" : "") + "  |" + legivel(p) + "|");
                }
            }
            log("  [" + nome + "] painel fechou a conexão (" + total + " bytes no total).");
        } catch (Exception e) {
            log("  [" + nome + "] conexão caiu: " + e.getMessage() + " (" + total + " bytes)");
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** Comandos que o Carbit não responde (classe sdk_communication/v0.f). */
    private static boolean semResposta(int c) {
        return c == 0x40010 || c == 0x30030 || c == 0x30020 || c == 0x30040 || c == 0x102B0 || (c & 0xFF000000) == 0x60000000;
    }

    private volatile OutputStream saidaP2C;
    private volatile long ultimaRespostaP2C;
    private volatile int batidas;

    /**
     * O celular também precisa mandar batimento no canal P2C (classe sdk_communication/x0 do Carbit):
     * a cada 2 s sem outro comando manda 0x70000000 e espera 0x70000001; na 1ª e a cada 7 manda
     * o comando 0 (o painel responde 0x80000010 = "não suportado"). Sem isso o painel desiste em ~7 s.
     */
    private void batimentoP2C(final OutputStream o) {
        new Thread(new Runnable() { @Override public void run() {
            int i = 0;
            boolean avisado = false;
            while (saidaP2C == o) {
                try {
                    int cmd = i % 7 == 0 ? 0 : 0x70000000;
                    synchronized (o) { Pxc.enviar(o, cmd, null); }
                    if (i < 3) log("  [P2C] → " + (cmd == 0 ? "teste 0x00000000" : "batimento 0x70000000"));
                    i++;
                    Thread.sleep(2000);
                    long sem = System.currentTimeMillis() - ultimaRespostaP2C;
                    if (sem > 6500 && !avisado) { log("! [P2C] o painel não responde há " + sem / 1000 + " s"); avisado = true; }
                    if (sem < 3000) avisado = false;
                } catch (Exception e) {
                    if (saidaP2C == o) log("  [P2C] batimento parou: " + e.getMessage());
                    return;
                }
            }
        }}, "p2c-batimento").start();
    }
    private volatile String infoPainel = "";

    /**
     * Portas de comandos (protocolo PXC). A primeira mensagem diz o tipo da conexão:
     * 0x00010000 = canal painel->celular (C2P), 0x00020000 = canal celular->painel (P2C).
     * O celular responde com o mesmo código + 1. Depois, no C2P, responde cada comando com código + 1.
     */
    private void lerPxc(Socket s, String nome) {
        int n = 0, batidasAqui = 0;
        String canal = nome;
        try {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            byte[][] cru = new byte[1][];
            while (true) {
                Pxc.Msg m = Pxc.ler(in, cru);
                if (m == null) {
                    log("  [" + canal + "] ← não é PXC: " + Quadro.hex(cru[0]) + "  |" + legivel(cru[0]) + "| (passo a ler cru)");
                    ler(s, canal);
                    return;
                }
                n++;
                boolean bat = m.cmd == 0x70000000 || m.cmd == 0x70000001 || m.cmd == 0x80000010;
                if (bat) batidasAqui++;
                if ("P2C".equals(canal)) ultimaRespostaP2C = System.currentTimeMillis();
                if (!bat || batidasAqui <= 6) log("  [" + canal + "] ← " + m);
                if (n == 1 && (m.cmd == 0x00010000 || m.cmd == 0x00020000)) {
                    canal = m.cmd == 0x00010000 ? "C2P" : "P2C";
                    Pxc.enviar(out, m.cmd + 1, null);
                    log("  [" + canal + "] → ok da conexão (0x" + Integer.toHexString(m.cmd + 1) + ")");
                    if (m.cmd == 0x00020000) {
                        saidaP2C = out;
                        ultimaRespostaP2C = System.currentTimeMillis();
                        ouv.estado("Painel conectado ✓", true);
                        batimentoP2C(out);
                    }
                    continue;
                }
                if ("P2C".equals(canal)) continue;          // aqui chegam só respostas do painel
                if (m.cmd % 2 != 0 || semResposta(m.cmd)) continue;
                byte[] resp = responder(m);
                synchronized (out) { Pxc.enviar(out, m.cmd + 1, resp); }
                if (!bat || batidasAqui <= 6)
                    log("  [" + canal + "] → 0x" + Integer.toHexString(m.cmd + 1) + (resp == null ? " (vazio)" : " " + new String(resp, java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log("  [" + canal + "] conexão terminou: " + e.getMessage() + " (" + n + " mensagens)");
        } finally {
            if ("P2C".equals(canal)) saidaP2C = null;
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    /** Resposta a cada comando do painel. Por enquanto só o CLIENT_INFO tem conteúdo. */
    private byte[] responder(Pxc.Msg m) {
        if (m.cmd == 0x00010450) {                         // painel pede a hora (km/n0 do Carbit)
            try {
                java.util.TimeZone tz = java.util.TimeZone.getDefault();
                long agora = System.currentTimeMillis();
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("time", agora);
                j.put("currentTime", agora + tz.getOffset(agora));
                j.put("currentTimeZone", tz.getID());
                return j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
        }
        if (m.cmd == 0x00010010) {
            infoPainel = m.texto();
            log("★ Informações do painel: " + infoPainel.replaceAll("\\s+", " "));
            try {
                org.json.JSONObject car = new org.json.JSONObject(infoPainel);
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("pxcVersion", "1.0.2");
                j.put("phoneUUID", ctx.getSharedPreferences("painel", Context.MODE_PRIVATE).getString("id", "motolink"));
                j.put("phoneBrand", Build.BRAND);
                j.put("phoneModel", Build.MODEL);
                j.put("phoneOsVersion", Integer.toString(Build.VERSION.SDK_INT));
                j.put("phoneOs", "Android");
                j.put("package", "net.easyconn.easyride.wws");
                j.put("versionCode", "47");
                j.put("token", 0);
                j.put("bluetoothName", "Moto Link");
                j.put("supportH264IFrame", true);
                if (car.has("supportFunction")) j.put("supportFunction", car.opt("supportFunction"));
                if (car.has("supportFunctionEx")) j.put("supportFunctionEx", car.opt("supportFunctionEx"));
                return j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                log("! Não entendi as informações do painel: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Avisa o painel pelo canal P2C que o app está na frente (ECP_P2C_APPSTATUS 0x00020020, classe mm/c do Carbit),
     * com o tamanho da imagem. O Carbit manda isso quando começa a projetar.
     */
    private void avisarAppNaFrente(final int w, final int h) {
        new Thread(new Runnable() { @Override public void run() {
            OutputStream o = saidaP2C;
            if (o == null) { log("  [P2C] canal não está aberto; não avisei o estado do app."); return; }
            try {
                org.json.JSONObject j = new org.json.JSONObject();
                j.put("displayRotation", 0);
                j.put("width", w);
                j.put("height", h);
                j.put("enableAccessibility", false);
                j.put("enableAOAHid", false);
                j.put("mode", 1);
                synchronized (o) { Pxc.enviar(o, 0x00020020, j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
                log("  [P2C] → 0x00020020 app na frente " + j);
            } catch (Exception e) {
                log("  [P2C] falhou ao avisar: " + e.getMessage());
            }
        }}, "p2c-estado").start();
    }

    // ---------------------------------------------------------------- apresentação na porta 10930 do painel
    /** O celular liga para o painel (porta 10930) e se apresenta; só depois o painel liga de volta. */
    private final java.util.concurrent.atomic.AtomicBoolean apresentando = new java.util.concurrent.atomic.AtomicBoolean();

    void apresentar(final String ip) {
        abrirPortas();
        if (!apresentando.compareAndSet(false, true)) { log("  (já estou me apresentando ao painel)"); return; }
        new Thread(new Runnable() { @Override public void run() {
            try {
                for (int t = 1; t <= 6; t++) {
                    if (tentarApresentar(ip, t)) return;
                    try { Thread.sleep(700); } catch (InterruptedException ignored) {}
                }
                log("! O painel não aceitou a apresentação na porta 10930 depois de 6 tentativas.");
            } finally {
                apresentando.set(false);
            }
        }}, "apresentar").start();
    }

    private boolean tentarApresentar(String ip, int tentativa) {
        Socket s = new Socket();
        try {
            log("Ligando para o painel " + ip + ":10930 (tentativa " + tentativa + ")…");
            s.connect(new InetSocketAddress(ip, 10930), 3000);
            s.setSoTimeout(4000);
            org.json.JSONObject j = new org.json.JSONObject();
            j.put("phoneType", "Android");
            j.put("packageName", "net.easyconn.easyride.wws");
            byte[] dados = j.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            OutputStream o = s.getOutputStream();
            byte[] q = Pxc.montar(Pxc.APRESENTACAO, dados);
            log("  [10930] → " + Quadro.hex(q));
            o.write(q);
            o.flush();
            byte[][] cru = new byte[1][];
            Pxc.Msg m = Pxc.ler(s.getInputStream(), cru);
            if (m == null) { log("  [10930] ← resposta estranha: " + Quadro.hex(cru[0])); return false; }
            log("  [10930] ← " + m);
            boolean ok = m.cmd == Pxc.APRESENTACAO + 1 && m.texto().contains("true");
            if (ok) {
                log("★ Painel aceitou a apresentação. Agora ele deve ligar de volta nas portas do celular.");
                ouv.estado("Quase lá…", true);
            }
            return ok;
        } catch (Exception e) {
            log("  [10930] " + e.getMessage());
            return false;
        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }

    private static String legivel(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(x >= 32 && x < 127 ? (char) x : '.');
        return sb.toString();
    }

    void parar() {
        ouvindo = false;
        midia.parar();
        synchronized (servidores) {
            for (ServerSocket s : servidores) { try { s.close(); } catch (Exception ignored) {} }
            servidores.clear();
        }
        if (p2p != null && canal != null) {
            try { p2p.removeGroup(canal, null); } catch (Exception ignored) {}
        }
    }
}
