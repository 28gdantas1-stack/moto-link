package com.gabriel.motolink;

/**
 * Qual app está sendo montado.
 *
 * Moto Link: painéis que se anunciam pelo Bluetooth ("EC…") e entram no Wi-Fi criado pelo celular (GK 350 do Gabriel).
 * Moto Link NM: painéis que mostram QR codes e se conectam por Wi-Fi Direct ("ZT5G…"); o celular encontra o painel
 * na lista de aparelhos Wi-Fi Direct e pede para conectar, como o Carbit faz depois de ler o QR.
 *
 * O build.py troca NM para true quando monta a variante (python3 build.py 100 1.0 nm).
 */
final class Variante {
    static final boolean NM = false;
    static final String NOME = NM ? "Moto Link NM" : "Moto Link";
    static final String SIGLA = NM ? "NM" : "ML";

    private Variante() {}
}
