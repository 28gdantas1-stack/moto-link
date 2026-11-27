package com.gabriel.motolink;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lê o relatório de erro nativo do Android (tombstone, em protobuf) e devolve um resumo legível:
 * mensagem de abort, a thread que quebrou com a pilha de chamadas e as últimas mensagens do log do sistema
 * daquele processo. Campos de system/core/debuggerd/proto/tombstone.proto:
 *   Tombstone: tid=6, abort_message=14, threads=16 (map<uint32, Thread>), log_buffers=18
 *   Thread: name=2, current_backtrace=4 (BacktraceFrame)
 *   BacktraceFrame: rel_pc=1, function_name=4, function_offset=5, file_name=6
 *   LogBuffer: name=1, logs=2 (LogMessage: timestamp=1, priority=4, tag=5, message=6)
 */
final class Tombstone {

    private Tombstone() {}

    private static final class Leitor {
        final byte[] b;
        int p;
        final int fim;
        Leitor(byte[] b, int ini, int fim) { this.b = b; this.p = ini; this.fim = fim; }
        Leitor(byte[] b) { this(b, 0, b.length); }
        boolean tem() { return p < fim; }
        long varint() {
            long v = 0;
            int s = 0;
            while (p < fim) {
                int x = b[p++] & 0xFF;
                v |= (long) (x & 0x7F) << s;
                if (x < 0x80) break;
                s += 7;
            }
            return v;
        }
        byte[] bytes() {
            int n = (int) varint();
            if (n < 0 || p + n > fim) throw new IllegalStateException("campo fora do limite");
            byte[] r = new byte[n];
            System.arraycopy(b, p, r, 0, n);
            p += n;
            return r;
        }
        String texto() { return new String(bytes(), StandardCharsets.UTF_8); }
        void pular(int tipo) {
            switch (tipo) {
                case 0: varint(); break;
                case 1: p += 8; break;
                case 2: { int n = (int) varint(); p += n; break; }
                case 5: p += 4; break;
                default: throw new IllegalStateException("tipo " + tipo);
            }
        }
    }

    /** Resumo legível, ou null se não deu para ler. */
    static String resumo(byte[] d) {
        try {
            int tid = -1;
            String abort = null;
            Map<Integer, byte[]> threads = new HashMap<>();
            List<byte[]> buffers = new ArrayList<>();
            Leitor r = new Leitor(d);
            while (r.tem()) {
                int tag = (int) r.varint(), campo = tag >>> 3, tipo = tag & 7;
                if (campo == 6 && tipo == 0) tid = (int) r.varint();
                else if (campo == 14 && tipo == 2) abort = r.texto();
                else if (campo == 16 && tipo == 2) {
                    Leitor m = new Leitor(r.bytes());
                    int k = -1;
                    byte[] v = null;
                    while (m.tem()) {
                        int t = (int) m.varint();
                        if ((t >>> 3) == 1 && (t & 7) == 0) k = (int) m.varint();
                        else if ((t >>> 3) == 2 && (t & 7) == 2) v = m.bytes();
                        else m.pular(t & 7);
                    }
                    if (v != null) threads.put(k, v);
                } else if (campo == 18 && tipo == 2) buffers.add(r.bytes());
                else r.pular(tipo);
            }
            StringBuilder sb = new StringBuilder();
            if (abort != null) sb.append("   abort: ").append(abort.length() > 300 ? abort.substring(0, 300) + "…" : abort).append('\n');
            byte[] th = threads.get(tid);
            if (th != null) {
                Leitor t = new Leitor(th);
                String nome = "?";
                List<String> pilha = new ArrayList<>();
                while (t.tem()) {
                    int tag = (int) t.varint(), campo = tag >>> 3, tipo = tag & 7;
                    if (campo == 2 && tipo == 2) nome = t.texto();
                    else if (campo == 4 && tipo == 2) pilha.add(quadro(t.bytes()));
                    else t.pular(tipo);
                }
                sb.append("   thread que quebrou: ").append(nome).append(" (tid ").append(tid).append(")\n");
                for (int i = 0; i < pilha.size() && i < 40; i++) sb.append("     #").append(i).append(' ').append(pilha.get(i)).append('\n');
            } else {
                sb.append("   (não achei a thread ").append(tid).append(" no relatório)\n");
            }
            List<String> linhas = new ArrayList<>();
            for (byte[] buf : buffers) {
                Leitor lb = new Leitor(buf);
                while (lb.tem()) {
                    int tag = (int) lb.varint(), campo = tag >>> 3, tipo = tag & 7;
                    if (campo == 2 && tipo == 2) linhas.add(mensagem(lb.bytes()));
                    else lb.pular(tipo);
                }
            }
            if (!linhas.isEmpty()) {
                sb.append("   últimas mensagens do sistema:\n");
                for (int i = Math.max(0, linhas.size() - 45); i < linhas.size(); i++) sb.append("     ").append(linhas.get(i)).append('\n');
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String quadro(byte[] f) {
        Leitor q = new Leitor(f);
        String fn = "", arq = "";
        long off = 0, pc = 0;
        while (q.tem()) {
            int tag = (int) q.varint(), campo = tag >>> 3, tipo = tag & 7;
            if (campo == 1 && tipo == 0) pc = q.varint();
            else if (campo == 4 && tipo == 2) fn = q.texto();
            else if (campo == 5 && tipo == 0) off = q.varint();
            else if (campo == 6 && tipo == 2) arq = q.texto();
            else q.pular(tipo);
        }
        int barra = arq.lastIndexOf('/');
        String curto = barra >= 0 ? arq.substring(barra + 1) : arq;
        if (fn.length() > 140) fn = fn.substring(0, 140) + "…";
        return (fn.isEmpty() ? String.format("pc %x", pc) : fn + "+" + off) + "  [" + curto + "]";
    }

    private static String mensagem(byte[] m) {
        Leitor q = new Leitor(m);
        String ts = "", tg = "", msg = "";
        int pri = 0;
        while (q.tem()) {
            int tag = (int) q.varint(), campo = tag >>> 3, tipo = tag & 7;
            if (campo == 1 && tipo == 2) ts = q.texto();
            else if (campo == 4 && tipo == 0) pri = (int) q.varint();
            else if (campo == 5 && tipo == 2) tg = q.texto();
            else if (campo == 6 && tipo == 2) msg = q.texto();
            else q.pular(tipo);
        }
        String p = pri >= 0 && pri < 8 ? "??VDIWEF".substring(pri, pri + 1) : "?";
        int esp = ts.indexOf(' ');
        if (esp > 0) ts = ts.substring(esp + 1);
        if (msg.length() > 220) msg = msg.substring(0, 220) + "…";
        return ts + " " + p + " " + tg + ": " + msg.replace('\n', ' ');
    }
}
