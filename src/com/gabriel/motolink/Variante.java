package com.gabriel.motolink;

/**
 * Qual app está sendo montado (o build.py troca TIPO e VERSAO num arquivo gerado).
 *
 * 0 · Moto Link: painéis que se anunciam pelo Bluetooth ("EC…") e entram no Wi-Fi Direct criado pelo celular
 *     (GK 350 do Gabriel).
 * 1 · Moto Link NM: painéis que mostram QR code e se conectam por Wi-Fi Direct ("ZT5G…"); o celular acha o painel
 *     na lista de aparelhos Wi-Fi Direct e pede para conectar, como o Carbit faz depois de ler o QR.
 * 2 · Moto Link Hotspot: painéis que pedem o hotspot do celular ("Please open Android hotspot"); o painel entra
 *     no hotspot e o app o encontra na rede. Usa o mesmo código de conexão do NM e começa no modo de vídeo
 *     compatível (codificador por software + desenho por OpenGL), que foi o que funcionou no POCO X5 Pro.
 *
 * Uso: python3 build.py <versionCode> <versionName> [nm|hotspot]
 */
final class Variante {
    static final int TIPO = 0;
    static final String VERSAO = "dev";

    /** NM e Hotspot usam a conexão por Wi-Fi (Direct ou hotspot), sem Bluetooth. */
    static final boolean NM = TIPO != 0;
    static final boolean HOTSPOT = TIPO == 2;
    static final String NOME = TIPO == 2 ? "Moto Link Hotspot" : TIPO == 1 ? "Moto Link NM" : "Moto Link";
    static final String SIGLA = TIPO == 2 ? "HS" : TIPO == 1 ? "NM" : "ML";

    private Variante() {}
}
