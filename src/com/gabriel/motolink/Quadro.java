package com.gabriel.motolink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Formato dos pacotes do painel:
 *   0x24 '$' | comando | tamanho | dados... | xor | 0x0A
 *   tamanho = dados + 4 ; total = tamanho + 1
 *   xor = 0x24 ^ comando ^ tamanho ^ (cada byte dos dados)
 */
final class Quadro {
    private Quadro() {}

    static byte[] montar(int cmd, byte[] dados) {
        int len = dados.length + 4;
        byte[] q = new byte[len + 1];
        q[0] = 0x24;
        q[1] = (byte) cmd;
        q[2] = (byte) len;
        System.arraycopy(dados, 0, q, 3, dados.length);
        q[len - 1] = xor(cmd, len, dados);
        q[len] = 0x0A;
        return q;
    }

    static byte xor(int cmd, int len, byte[] dados) {
        int x = 0x24 ^ (cmd & 255) ^ (len & 255);
        for (byte b : dados) x ^= (b & 255);
        return (byte) x;
    }

    static String hex(byte[] b) {
        if (b == null) return "";
        StringBuilder sb = new StringBuilder(b.length * 3);
        for (int i = 0; i < b.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(String.format("%02X", b[i] & 255));
        }
        return sb.toString();
    }

    static byte[] intLE(int v) {
        return new byte[]{(byte) v, (byte) (v >> 8), (byte) (v >> 16), (byte) (v >>> 24)};
    }

    static byte[] longLE(long v) {
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) b[i] = (byte) (v >>> (8 * i));
        return b;
    }

    static int lerIntLE(byte[] b, int off) {
        return (b[off] & 255) | ((b[off + 1] & 255) << 8) | ((b[off + 2] & 255) << 16) | ((b[off + 3] & 255) << 24);
    }

    /** UTF-8 cortado em no máximo n bytes. */
    static byte[] utf8(String s, int n) {
        if (s == null) return new byte[0];
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= n) return b;
        byte[] c = new byte[n];
        System.arraycopy(b, 0, c, 0, n);
        return c;
    }

    static String texto(byte[] b, int off, int n) {
        if (off >= b.length) return "";
        n = Math.min(n, b.length - off);
        return new String(b, off, n, StandardCharsets.UTF_8).replace("\u0000", "").trim();
    }

    /** Junta os pedaços que chegam pelo Bluetooth até formar um pacote inteiro. */
    static final class Montador {
        interface Saida { void pacote(int cmd, byte[] dados, boolean xorOk); void lixo(byte[] b); }

        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        private final Saida saida;

        Montador(Saida s) { saida = s; }

        void limpar() { buf.reset(); }

        void chegou(byte[] pedaco) {
            if (pedaco.length > 0 && pedaco[0] == 0x24 && buf.size() > 0) {
                saida.lixo(buf.toByteArray());   // pacote anterior ficou incompleto
                buf.reset();
            }
            buf.write(pedaco, 0, pedaco.length);
            processar();
        }

        private void processar() {
            while (true) {
                byte[] b = buf.toByteArray();
                if (b.length == 0) return;
                if (b[0] != 0x24) {
                    int i = 1;
                    while (i < b.length && b[i] != 0x24) i++;
                    byte[] l = new byte[i];
                    System.arraycopy(b, 0, l, 0, i);
                    saida.lixo(l);
                    resto(b, i);
                    continue;
                }
                if (b.length < 3) return;
                int len = b[2] & 255;
                if (len < 4) { resto(b, 1); continue; }
                if (b.length < len + 1) return;          // ainda falta chegar
                int cmd = b[1] & 255;
                byte[] dados = new byte[len - 4];
                System.arraycopy(b, 3, dados, 0, dados.length);
                boolean ok = b[len - 1] == xor(cmd, len, dados) && b[len] == 0x0A;
                saida.pacote(cmd, dados, ok);
                resto(b, len + 1);
            }
        }

        private void resto(byte[] b, int de) {
            buf.reset();
            if (de < b.length) buf.write(b, de, b.length - de);
        }
    }
}
