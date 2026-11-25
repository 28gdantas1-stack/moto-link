package com.gabriel.motolink;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

/** Protobuf mínimo (só o que o protocolo do Android Auto usa): escrever e ler campos. */
final class Proto {
    private Proto() {}

    static final class W {
        private final ByteArrayOutputStream o = new ByteArrayOutputStream();

        private void raw(long v) {
            while ((v & ~0x7FL) != 0) { o.write((int) ((v & 0x7F) | 0x80)); v >>>= 7; }
            o.write((int) v);
        }
        private void tag(int campo, int tipo) { raw(((long) campo << 3) | tipo); }

        W n(int campo, long valor) { tag(campo, 0); raw(valor); return this; }
        W b(int campo, boolean valor) { return n(campo, valor ? 1 : 0); }
        W s(int campo, String valor) { return m(campo, valor.getBytes(StandardCharsets.UTF_8)); }
        W m(int campo, byte[] valor) { tag(campo, 2); raw(valor.length); o.write(valor, 0, valor.length); return this; }
        W m(int campo, W sub) { return m(campo, sub.bytes()); }
        byte[] bytes() { return o.toByteArray(); }
    }

    /** Leitor: chame proximo() até dar false; depois veja campo/tipo/num/dados. */
    static final class R {
        private final byte[] d;
        private int p;
        private final int fim;
        int campo, tipo;
        long num;
        int ini, tam;           // para tipo 2: onde estão os bytes do campo

        R(byte[] dados, int de, int ate) { d = dados; p = de; fim = ate; }

        private long varint() {
            long v = 0; int s = 0;
            while (p < fim) {
                int b = d[p++] & 0xFF;
                v |= (long) (b & 0x7F) << s;
                if ((b & 0x80) == 0) break;
                s += 7;
            }
            return v;
        }

        boolean proximo() {
            if (p >= fim) return false;
            long t = varint();
            campo = (int) (t >>> 3);
            tipo = (int) (t & 7);
            switch (tipo) {
                case 0: num = varint(); break;
                case 1: num = 0; for (int i = 0; i < 8 && p < fim; i++) num |= (long) (d[p++] & 0xFF) << (8 * i); break;
                case 2: tam = (int) varint(); ini = p; p += tam; break;
                case 5: num = 0; for (int i = 0; i < 4 && p < fim; i++) num |= (long) (d[p++] & 0xFF) << (8 * i); break;
                default: p = fim; return false;
            }
            return true;
        }

        String texto() { return new String(d, ini, tam, StandardCharsets.UTF_8); }
        R sub() { return new R(d, ini, ini + tam); }
    }

    /** Primeiro valor numérico de um campo (ou 'padrao'). */
    static long num(byte[] d, int de, int ate, int campo, long padrao) {
        R r = new R(d, de, ate);
        while (r.proximo()) if (r.campo == campo && r.tipo != 2) return r.num;
        return padrao;
    }

    static String texto(byte[] d, int de, int ate, int campo) {
        R r = new R(d, de, ate);
        while (r.proximo()) if (r.campo == campo && r.tipo == 2) return r.texto();
        return "";
    }
}
