package com.gabriel.motolink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Mensagens pela rede Wi-Fi (protocolo "PXC" do Carbit, classe sdk_communication/i):
 *   cabeçalho de 16 bytes little-endian: [comando][tamanho total = 16 + dados][comando XOR tamanho][0]
 *   seguido dos dados (normalmente JSON).
 */
final class Pxc {
    private Pxc() {}

    static final int APRESENTACAO = 0x70000010;   // celular -> painel na porta 10930

    static final class Msg {
        final int cmd;
        final byte[] dados;
        Msg(int c, byte[] d) { cmd = c; dados = d; }
        String texto() { return new String(dados, StandardCharsets.UTF_8); }
        @Override public String toString() {
            String t = texto();
            boolean legivel = dados.length > 0 && (dados[0] == '{' || dados[0] == '[');
            return String.format("0x%08X (%d bytes)%s", cmd, dados.length,
                    legivel ? " " + t.replaceAll("\\s+", " ") : dados.length > 0 ? " " + Quadro.hex(corte(dados, 64)) + (dados.length > 64 ? " …" : "") : "");
        }
    }

    static byte[] montar(int cmd, byte[] dados) {
        int total = 16 + (dados == null ? 0 : dados.length);
        ByteBuffer b = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(cmd).putInt(total).putInt(cmd ^ total).putInt(0);
        if (dados != null) b.put(dados);
        return b.array();
    }

    static void enviar(OutputStream o, int cmd, byte[] dados) throws IOException {
        o.write(montar(cmd, dados));
        o.flush();
    }

    /** Lê uma mensagem inteira. Devolve null se o cabeçalho não for PXC (aí 'cru' recebe os 16 bytes). */
    static Msg ler(InputStream in, byte[][] cru) throws IOException {
        byte[] cab = new byte[16];
        lerTudo(in, cab, 16);
        ByteBuffer b = ByteBuffer.wrap(cab).order(ByteOrder.LITTLE_ENDIAN);
        int cmd = b.getInt(0), total = b.getInt(4), magia = b.getInt(8);
        if ((cmd ^ total) != magia || total < 16 || total > 8 * 1024 * 1024) {
            if (cru != null) cru[0] = cab;
            return null;
        }
        byte[] d = new byte[total - 16];
        lerTudo(in, d, d.length);
        return new Msg(cmd, d);
    }

    static void lerTudo(InputStream in, byte[] b, int n) throws IOException {
        int lidos = 0;
        while (lidos < n) {
            int r = in.read(b, lidos, n - lidos);
            if (r <= 0) throw new IOException("conexão fechada");
            lidos += r;
        }
    }

    static byte[] corte(byte[] b, int n) {
        if (b.length <= n) return b;
        byte[] c = new byte[n];
        System.arraycopy(b, 0, c, 0, n);
        return c;
    }
}
