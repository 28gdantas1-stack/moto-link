package com.gabriel.motolink;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * Conversa com o painel da Zontes (protocolo "EasyConnection" usado pelo Carbit) pelo Bluetooth LE.
 * Tudo o que acontece vai para o log, para conferirmos o protocolo na moto.
 */
@SuppressWarnings({"deprecation", "MissingPermission"})
final class Painel extends BluetoothGattCallback {

    interface Ouvinte {
        void log(String linha);
        void estado(String texto, boolean conectado);
        /** O painel respondeu ao pedido de rede (0x50). status 2 = quer que o celular crie a rede. */
        void painelPedeRede(int status, int frequencia, String json);
        /** O painel entrou na rede e informou o IP dele (0x53). */
        void painelNaRede(String ip);
    }

    static final UUID SERVICO = UUID.fromString("0000b360-d6d8-c7ec-bdf0-eab1bfc6bcbc");
    static final UUID[] CARACS = {
            UUID.fromString("0000b362-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000b363-d6d8-c7ec-bdf0-eab1bfc6bcbc"),
            UUID.fromString("0000b364-d6d8-c7ec-bdf0-eab1bfc6bcbc")};
    static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid DADOS_B360 = ParcelUuid.fromString("0000b360-0000-1000-8000-00805f9b34fb");
    static final ParcelUuid UUID_6967 = ParcelUuid.fromString("00006967-0000-1000-8000-00805f9b34fb");

    private final Context ctx;
    private final Ouvinte ouv;
    private final Handler h = new Handler(Looper.getMainLooper());
    private final SharedPreferences prefs;
    private final ArrayDeque<byte[]> fila = new ArrayDeque<>();
    private final Quadro.Montador montador;

    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic car;
    private BluetoothLeScanner scanner;
    private boolean procurando, escrevendo, conectado;
    /** Ligado pelo Conectar: se não achar o painel, continua procurando até achar ou desconectar. */
    private boolean insistir;
    private int rodadas;
    private int mtu = 23;
    private int pedidosRede;

    // o que o painel contou de si
    boolean protocoloNovo;      // respondeu 0x58/0x59 (formato novo de navegação)
    int funcaoHud;              // bits de função (bit 4 = aceita destino na navegação)
    int maxIcone = 53;
    boolean notifNova;          // painel pediu o formato novo de notificação (0x63)

    Painel(Context c, Ouvinte o) {
        ctx = c.getApplicationContext();
        ouv = o;
        prefs = ctx.getSharedPreferences("painel", Context.MODE_PRIVATE);
        montador = new Quadro.Montador(new Quadro.Montador.Saida() {
            @Override public void pacote(int cmd, byte[] dados, boolean ok) { recebido(cmd, dados, ok); }
            @Override public void lixo(byte[] b) { log("? bytes soltos: " + Quadro.hex(b)); }
        });
    }

    boolean conectado() { return conectado && car != null; }

    private void log(String s) { ouv.log(s); }

    // ---------------------------------------------------------------- procura e conexão
    void procurar() {
        rodadas = 0;
        buscar();
    }

    private void buscar() {
        BluetoothManager bm = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter ad = bm == null ? null : bm.getAdapter();
        if (ad == null || !ad.isEnabled()) { log("! Bluetooth desligado."); ouv.estado("Ligue o Bluetooth", false); return; }
        if (gatt != null) desconectar();
        insistir = true;
        scanner = ad.getBluetoothLeScanner();
        if (scanner == null) { log("! Não consegui usar o Bluetooth LE."); return; }
        ScanSettings cfg = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
        procurando = true;
        String ultimo = prefs.getString("mac", "");
        if (rodadas == 0) log("Procurando o painel" + (ultimo.isEmpty() ? "" : " (último: " + ultimo + ")") + "…");
        ouv.estado(rodadas == 0 ? "Procurando o painel… ligue a ignição"
                : "Ainda procurando… a ignição está ligada e o Carbit fechado?", false);
        try {
            scanner.startScan(null, cfg, busca);
        } catch (Exception e) {
            log("! Erro ao procurar: " + e);
            procurando = false;
            return;
        }
        h.postDelayed(new Runnable() {
            @Override public void run() {
                if (procurando) {
                    pararBusca();
                    rodadas++;
                    if (rodadas == 1) log("! Não achei o painel em 25 s. Ignição ligada? O Carbit está fechado? Continuo procurando…");
                    if (insistir && rodadas < 40) {
                        ouv.estado("Ainda procurando… a ignição está ligada e o Carbit fechado?", false);
                        h.postDelayed(new Runnable() { @Override public void run() {
                            if (insistir && !procurando && gatt == null) buscar();
                        }}, 4000);
                    } else {
                        insistir = false;
                        log("! Parei de procurar o painel.");
                        ouv.estado("Painel não encontrado. Toque em Desconectar e tente de novo.", false);
                    }
                }
            }
        }, 25000);
    }

    private void pararBusca() {
        if (!procurando) return;
        procurando = false;
        try { if (scanner != null) scanner.stopScan(busca); } catch (Exception ignored) {}
    }

    private final ScanCallback busca = new ScanCallback() {
        @Override public void onScanResult(int tipo, final ScanResult r) {
            if (!procurando) return;
            ScanRecord rec = r.getScanRecord();
            String nome = rec != null ? rec.getDeviceName() : null;
            boolean temB360 = rec != null && rec.getServiceData(DADOS_B360) != null;
            List<ParcelUuid> uuids = rec != null ? rec.getServiceUuids() : null;
            boolean tem6967 = uuids != null && uuids.contains(UUID_6967);
            String mac = r.getDevice().getAddress();
            boolean ehOUltimo = mac.equals(prefs.getString("mac", "-"));
            if ((nome != null && nome.startsWith("EC")) || temB360 || tem6967 || ehOUltimo) {
                pararBusca();
                log("Achei: " + (nome == null ? "(sem nome)" : nome) + " " + mac + " sinal " + r.getRssi() + " dBm");
                if (rec != null) log("  anúncio: " + Quadro.hex(rec.getBytes()));
                h.post(new Runnable() { @Override public void run() { conectar(r.getDevice()); } });
            }
        }

        @Override public void onScanFailed(int erro) {
            procurando = false;
            log("! A busca falhou (código " + erro + "). Desligue e ligue o Bluetooth.");
        }
    };

    private void conectar(BluetoothDevice d) {
        prefs.edit().putString("mac", d.getAddress()).apply();
        ouv.estado("Conectando…", false);
        log("Conectando em " + d.getAddress() + "…");
        montador.limpar();
        fila.clear();
        escrevendo = false;
        mtu = 23;
        gatt = d.connectGatt(ctx, false, this, BluetoothDevice.TRANSPORT_LE);
    }

    void desconectar() {
        insistir = false;
        pararBusca();
        if (gatt != null) {
            try { gatt.disconnect(); gatt.close(); } catch (Exception ignored) {}
        }
        gatt = null;
        car = null;
        conectado = false;
        fila.clear();
        escrevendo = false;
        ouv.estado("Desconectado", false);
    }

    // ---------------------------------------------------------------- callbacks do Bluetooth
    @Override public void onConnectionStateChange(final BluetoothGatt g, final int status, final int novo) {
        h.post(new Runnable() { @Override public void run() {
            if (g != gatt) return;
            if (novo == BluetoothProfile.STATE_CONNECTED) {
                conectado = true;
                log("Conectado (status " + status + "). Pedindo MTU maior…");
                ouv.estado("Conectado, preparando…", false);
                if (!g.requestMtu(247)) g.discoverServices();
            } else if (novo == BluetoothProfile.STATE_DISCONNECTED) {
                log("Desconectado (status " + status + ").");
                try { g.close(); } catch (Exception ignored) {}
                gatt = null; car = null; conectado = false;
                ouv.estado("Desconectado", false);
            }
        }});
    }

    @Override public void onMtuChanged(final BluetoothGatt g, final int novoMtu, final int status) {
        h.post(new Runnable() { @Override public void run() {
            mtu = status == BluetoothGatt.GATT_SUCCESS ? novoMtu : 23;
            log("MTU = " + mtu + " (status " + status + "). Procurando serviços…");
            g.discoverServices();
        }});
    }

    @Override public void onServicesDiscovered(final BluetoothGatt g, final int status) {
        h.post(new Runnable() { @Override public void run() {
            for (BluetoothGattService s : g.getServices()) log("  serviço " + s.getUuid());
            BluetoothGattService s = g.getService(SERVICO);
            if (s == null) { log("! Serviço b360 não encontrado. Esse não é o painel?"); return; }
            for (UUID u : CARACS) {
                BluetoothGattCharacteristic c = s.getCharacteristic(u);
                if (c != null) { car = c; break; }
            }
            if (car == null) { log("! Característica b362/b363/b364 não encontrada."); return; }
            log("Canal: " + car.getUuid() + " propriedades 0x" + Integer.toHexString(car.getProperties()));
            if (car.getUuid().equals(CARACS[2])) log("! Este canal (b364) usa criptografia, ainda não suportada.");
            g.setCharacteristicNotification(car, true);
            BluetoothGattDescriptor d = car.getDescriptor(CCCD);
            if (d == null) { log("! Sem descritor de notificação."); comecar(); return; }
            d.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            if (!g.writeDescriptor(d)) { log("! Não consegui ligar as notificações."); }
        }});
    }

    @Override public void onDescriptorWrite(BluetoothGatt g, BluetoothGattDescriptor d, final int status) {
        h.post(new Runnable() { @Override public void run() {
            log("Notificações ligadas (status " + status + ").");
            comecar();
        }});
    }

    private void comecar() {
        ouv.estado("Conversando com o painel…", true);
        // o Carbit espera 1 s antes de se apresentar
        h.postDelayed(new Runnable() { @Override public void run() { enviarApresentacao(); } }, 1000);
    }

    @Override public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic c, final int status) {
        h.post(new Runnable() { @Override public void run() {
            if (status != BluetoothGatt.GATT_SUCCESS) log("! Escrita falhou (status " + status + ").");
            escrevendo = false;
            bombear();
        }});
    }

    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c) {
        if (Build.VERSION.SDK_INT < 33) chegou(c.getValue());
    }

    @Override public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic c, byte[] valor) {
        chegou(valor);
    }

    private void chegou(byte[] v) {
        if (v == null) return;
        final byte[] copia = v.clone();
        h.post(new Runnable() { @Override public void run() {
            log("  ← " + Quadro.hex(copia));
            montador.chegou(copia);
        }});
    }

    // ---------------------------------------------------------------- envio
    void enviar(int cmd, byte[] dados, String oque) {
        if (!conectado()) { log("! Não conectado: " + oque + " não enviado."); return; }
        byte[] q = Quadro.montar(cmd, dados);
        log(String.format(Locale.ROOT, "→ %s (0x%02X): %s", oque, cmd, Quadro.hex(q)));
        int max = Math.max(20, mtu - 3);
        for (int i = 0; i < q.length; i += max) {
            int n = Math.min(max, q.length - i);
            byte[] p = new byte[n];
            System.arraycopy(q, i, p, 0, n);
            fila.add(p);
        }
        bombear();
    }

    private void bombear() {
        if (escrevendo || fila.isEmpty() || gatt == null || car == null) return;
        final byte[] p = fila.peek();
        boolean semResposta = (car.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE) == 0;
        car.setWriteType(semResposta ? BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                : BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        car.setValue(p);
        if (gatt.writeCharacteristic(car)) {
            fila.poll();
            escrevendo = true;
        } else {
            h.postDelayed(new Runnable() { @Override public void run() { bombear(); } }, 60);
        }
    }

    // ---------------------------------------------------------------- comandos (celular -> painel)
    void enviarApresentacao() {
        String id = prefs.getString("id", null);
        if (id == null) { id = UUID.randomUUID().toString(); prefs.edit().putString("id", id).apply(); }
        String json = "{\"phoneType\":0,\"phoneID\":\"" + id + "\"}";
        enviar(0x30, json.getBytes(StandardCharsets.UTF_8), "Apresentação do celular");
    }

    void enviarVerificacaoOk() { enviar(0x60, new byte[]{0}, "Verificação OK"); }

    void enviarHora() {
        long agora = System.currentTimeMillis();
        enviar(0x01, Quadro.longLE(agora + TimeZone.getDefault().getOffset(agora)), "Hora");
    }

    void enviarHoraTexto() {
        String s = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss:zzz", Locale.getDefault()).format(new Date());
        enviar(0x55, Quadro.utf8(s, 120), "Hora em texto");
    }

    /** tipo/vento/direção: códigos do painel (ainda a descobrir). */
    void enviarClima(String cidade, int tipo, int temp, int umid, boolean formatoNovo) {
        byte[] nome = Quadro.utf8(cidade, 60);
        byte[] d = new byte[nome.length + 6];
        d[0] = (byte) nome.length;
        System.arraycopy(nome, 0, d, 1, nome.length);
        int k = nome.length;
        d[k + 1] = (byte) tipo;
        d[k + 2] = 2;              // nível do vento
        d[k + 3] = 1;              // direção do vento
        d[k + 4] = (byte) temp;
        d[k + 5] = (byte) umid;
        enviar(formatoNovo ? 0x56 : 0x02, d, "Clima " + cidade + " tipo " + tipo + " " + temp + "°C");
    }

    /** Navegação curva-a-curva: o painel desenha a seta (código) e a distância. */
    void enviarNavegacao(int codigoSeta, int distCurva, String rua, int vel, int restante, int segundos, String destino) {
        byte[] rd = Quadro.utf8(rua, 60);
        byte[] dist = Quadro.intLE(distCurva), velB = Quadro.intLE(vel), rest = Quadro.intLE(restante);
        ByteBuffer b;
        if (protocoloNovo) {
            boolean comDestino = (funcaoHud & 4) == 4;
            byte[] dst = Quadro.utf8(destino, 60);
            String chegada = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
                    .format(new Date(System.currentTimeMillis() + segundos * 1000L));
            byte[] ch = Quadro.utf8(chegada, 19);
            b = ByteBuffer.allocate(rd.length + 36 + (comDestino ? dst.length + 1 : 0));
            b.put((byte) 51).put((byte) codigoSeta).put(dist).put((byte) rd.length).put(rd)
                    .put(velB).put(rest).put(ch);
            int min = segundos / 60;
            b.put((byte) min).put((byte) (min >> 8));
            if (comDestino) b.put((byte) dst.length).put(dst);
        } else {
            Date chegada = new Date(System.currentTimeMillis() + segundos * 1000L);
            String[] hm = new SimpleDateFormat("HH:mm", Locale.ROOT).format(chegada).split(":");
            b = ByteBuffer.allocate(rd.length + 17);
            b.put((byte) 51).put((byte) codigoSeta).put(dist).put((byte) rd.length).put(rd)
                    .put(velB).put(rest).put((byte) Integer.parseInt(hm[0])).put((byte) Integer.parseInt(hm[1]));
        }
        enviar(0x14, b.array(), "Navegação seta " + codigoSeta + " em " + distCurva + " m");
    }

    void enviarFimNavegacao() { enviar(0x14, new byte[]{53}, "Fim da navegação"); }

    void enviarNotificacao(String app, String titulo, String msg) {
        if (notifNova) {
            byte[] a = Quadro.utf8(app, 120), t = Quadro.utf8(titulo, 120), m = Quadro.utf8(msg, 120);
            ByteBuffer b = ByteBuffer.allocate(a.length + t.length + m.length + 3);
            b.put((byte) a.length).put(a).put((byte) t.length).put(t).put((byte) m.length).put(m);
            enviar(0x63, b.array(), "Notificação (formato novo)");
        } else {
            byte[] m = Quadro.utf8(msg, 120);
            byte[] d = new byte[m.length + 2];
            d[0] = 1;   // tipo de mensagem (1 = SMS, a confirmar)
            d[1] = (byte) m.length;
            System.arraycopy(m, 0, d, 2, m.length);
            enviar(0x22, d, "Notificação");
        }
    }

    void enviarAltitude(int metros) { enviar(0x07, Quadro.intLE(metros), "Altitude " + metros + " m"); }

    void enviarGps(double lat, double lon, double alt, String lugar) {
        byte[] l = Quadro.utf8(lugar, 64);
        byte[] d = new byte[l.length + 37];
        copiarTexto(String.valueOf(lon), d, 0);
        copiarTexto(String.valueOf(lat), d, 12);
        copiarTexto(String.valueOf(alt), d, 24);
        d[36] = (byte) l.length;
        System.arraycopy(l, 0, d, 37, l.length);
        enviar(0x57, d, "GPS " + lat + "," + lon);
    }

    private static void copiarTexto(String s, byte[] d, int off) {
        byte[] b = Quadro.utf8(s, 12);
        System.arraycopy(b, 0, d, off, b.length);
    }

    // ---------------------------------------------------------------- respostas (painel -> celular)
    private void recebido(int cmd, byte[] d, boolean ok) {
        String cab = String.format(Locale.ROOT, "← PACOTE 0x%02X (%d) %d bytes%s", cmd, cmd, d.length, ok ? "" : "  [conferência NÃO bateu]");
        log(cab);
        switch (cmd) {
            case 0x58: apertoExterno(d); break;
            case 0x59: apertoInterno(d); break;
            case 0x30: apertoAntigo(d); break;
            case 0x50: respostaRede(d); break;
            case 0x52: log("  painel confirmou os dados do Wi-Fi: " + texto(d)); break;
            case 0x53: redeDoPainel(d); break;
            case 0x01: log("  painel pediu a HORA"); enviarHora(); break;
            case 0x55: log("  painel pediu a HORA (texto)"); enviarHoraTexto(); break;
            case 0x02: log("  painel pediu o CLIMA"); enviarClima("Rio de Janeiro", 1, 27, 70, false); break;
            case 0x56: log("  painel pediu o CLIMA (novo)"); enviarClima("Rio de Janeiro", 1, 27, 70, true); break;
            case 0x03: log("  painel pediu a LOCALIZAÇÃO"); break;
            case 0x57: log("  painel pediu o GPS"); enviarGps(-22.9068, -43.1729, 10, "Rio de Janeiro"); break;
            case 0x07: log("  painel pediu a ALTITUDE"); enviarAltitude(10); break;
            case 0x14: infoHud(d); break;
            case 0x20: log("  config de chamadas: " + Quadro.hex(d)); break;
            case 0x22: log("  config de notificações: " + Quadro.hex(d)); break;
            case 0x63: notifNova = d.length > 0 && d[0] == 1; log("  notificações formato novo: " + notifNova); break;
            case 0x31: log("★ painel avisou que manda dados da moto (VAN): " + Quadro.hex(d)); break;
            case 0x34: log("★ dados da moto (personalizado): " + Quadro.hex(d)); break;
            case 0x32: log("★ dados da moto (tempo real): " + telemetria(d) + "  [" + Quadro.hex(d) + "]"); break;
            case 0x33: log("★ dados da moto: " + telemetria(d) + "  [" + Quadro.hex(d) + "]"); break;
            case 0x35: log("  histórico de consumo: " + Quadro.hex(d)); break;
            case 0x64: log("  criar tela virtual: " + texto(d)); break;
            case 0x65: log("  destruir tela virtual"); break;
            default: log("  (comando ainda desconhecido) " + Quadro.hex(d) + "  |" + texto(d) + "|");
        }
    }

    private void apertoAntigo(byte[] d) {
        String j = texto(d);
        log("  aperto de mão (formato antigo): " + j);
        ouv.estado("Painel respondeu ✓ preparando o Wi-Fi…", true);
        int funcoes = 0;
        try { funcoes = new org.json.JSONObject(j).optInt("supportFunction"); } catch (Exception ignored) {}
        pedidosRede = 0;
        if ((funcoes & 2) == 2) {
            log("  o painel monta rede Wi-Fi (supportFunction=" + funcoes + "): pedindo a rede…");
            h.postDelayed(new Runnable() { @Override public void run() { pedirRede(); } }, 300);
        }
    }

    void pedirRede() { enviar(0x50, new byte[0], "Pedido de rede Wi-Fi"); }

    private void respostaRede(byte[] d) {
        String j = texto(d);
        log("  resposta do pedido de rede: " + j);
        int status = -9, freq = 0;
        try {
            org.json.JSONObject o = new org.json.JSONObject(j);
            status = o.optInt("status", -9);
            freq = o.optInt("phoneApFrequency", 0);
        } catch (Exception e) { log("! Não entendi a resposta de rede: " + e.getMessage()); }
        String[] nomes = {"pronta", "o celular deve entrar na rede do painel", "o celular deve criar a rede"};
        log("  status " + status + (status >= 0 && status <= 2 ? " = " + nomes[status] : status == -1 ? " = aguardando" : status == -2 ? " = falhou" : "")
                + (freq > 0 ? "  frequência " + freq + " MHz" : ""));
        if (status == -1 && pedidosRede < 15) {
            pedidosRede++;
            log("  painel ainda não está pronto, pedindo de novo em 2 s (tentativa " + pedidosRede + ")…");
            h.postDelayed(new Runnable() { @Override public void run() { if (conectado()) pedirRede(); } }, 2000);
            return;
        }
        ouv.painelPedeRede(status, freq, j);
    }

    /** Manda ao painel o nome, a senha e o IP da rede Wi-Fi Direct que o celular criou (0x52). */
    void enviarWifi(String ssid, String senha, String ip) {
        try {
            org.json.JSONObject o = new org.json.JSONObject();
            o.put("ssid", ssid).put("pwd", senha).put("auth", "WPA2").put("mac", "");
            if (ip != null && !ip.isEmpty()) o.put("ip", ip);
            enviar(0x52, o.toString().getBytes(StandardCharsets.UTF_8), "Dados do Wi-Fi");
        } catch (Exception e) { log("! " + e); }
    }

    private void redeDoPainel(byte[] d) {
        String j = texto(d);
        log("  rede do painel: " + j.replaceAll("\\s+", " "));
        try {
            org.json.JSONArray a = new org.json.JSONObject(j).optJSONArray("data");
            String ip = a != null && a.length() > 0 ? a.getJSONObject(0).optString("ip") : "";
            if (!ip.isEmpty()) ouv.painelNaRede(ip);
        } catch (Exception e) { log("! Não entendi a rede do painel: " + e.getMessage()); }
    }

    private static String texto(byte[] d) { return new String(d, StandardCharsets.UTF_8).replace("\u0000", ""); }

    private void apertoExterno(byte[] d) {
        if (d.length < 100) { log("  aperto 0x58 curto demais: " + Quadro.hex(d)); }
        else {
            String key = Quadro.texto(d, 0, 32), uuid = Quadro.texto(d, 32, 32), pwd = Quadro.texto(d, 64, 32);
            int flavor = Quadro.lerIntLE(d, 96);
            log("  chave=" + key + "  uuid=" + uuid + "  senha=" + pwd + "  flavor=" + flavor);
            if (d.length >= 166) {
                log("  marca=" + Quadro.texto(d, 100, 16) + "  modelo=" + Quadro.texto(d, 116, 24) + "  config=" + Quadro.texto(d, 140, 24));
                int m = ((d[164] & 255) << 8) | (d[165] & 255);
                log("  MTU do painel = " + m);
                if (m > 0) mtu = Math.min(mtu, m);
            }
        }
        protocoloNovo = true;
        ouv.estado("Painel respondeu ✓ preparando o Wi-Fi…", true);
        enviarVerificacaoOk();
    }

    private void apertoInterno(byte[] d) {
        if (d.length < 74) { log("  aperto 0x59 curto demais: " + Quadro.hex(d)); return; }
        int canal = Quadro.lerIntLE(d, 0);
        String uuid = Quadro.texto(d, 4, 32), pwd = Quadro.texto(d, 36, 32);
        int funcao = Quadro.lerIntLE(d, 68);
        int blesdk = d[72] & 255, wpn = d[73] & 255;
        log("  canal=" + canal + "  uuid=" + uuid + "  senha=" + pwd + "  funções=0x" + Integer.toHexString(funcao) + "  blesdk=" + blesdk + "  wpn=" + wpn);
        if (d.length >= 142) {
            log("  flavor=" + Quadro.texto(d, 74, 4) + "  marca=" + Quadro.texto(d, 78, 16) + "  modelo=" + Quadro.texto(d, 94, 24) + "  config=" + Quadro.texto(d, 118, 24));
        }
        protocoloNovo = true;
        ouv.estado("Painel respondeu ✓ preparando o Wi-Fi…", true);
        if (blesdk == 1) enviarVerificacaoOk();
    }

    private void infoHud(byte[] d) {
        if (d.length >= 3) {
            int aberto = d[0] & 255;
            maxIcone = (d[1] & 255) | ((d[2] & 255) << 8);
            funcaoHud = d.length > 4 ? (d[3] & 255) | ((d[4] & 255) << 8) : 0;
            log("  HUD: aberto=" + aberto + "  maior código de seta=" + maxIcone + "  funções=0x" + Integer.toHexString(funcaoHud));
        } else {
            log("  HUD: " + Quadro.hex(d));
        }
    }

    /** Telemetria: pares id -> valor. */
    private static String telemetria(byte[] d) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < d.length) {
            int id = d[i] & 255;
            int v;
            switch (id) {
                case 1: case 2: case 3: case 4: case 12: case 13: case 15: case 16: case 17: case 28: case 30: case 32:
                    if (i + 2 >= d.length) return sb + " …";
                    v = (d[i + 1] & 255) | ((d[i + 2] & 255) << 8);
                    sb.append(id).append('=').append(v).append(' ');
                    i += 3; break;
                case 5: case 10: case 11: case 14:
                    if (i + 4 >= d.length) return sb + " …";
                    v = Quadro.lerIntLE(d, i + 1);
                    sb.append(id).append('=').append(v).append(' ');
                    i += 5; break;
                case 6: case 7: case 8: case 9: case 18: case 19: case 20: case 21: case 22: case 23: case 24: case 25: case 31:
                    if (i + 1 >= d.length) return sb + " …";
                    sb.append(id).append('=').append(d[i + 1] & 255).append(' ');
                    i += 2; break;
                default:
                    i += 1;
            }
        }
        return sb.toString().trim();
    }
}
