# Moto Link

Android Auto (Maps, Waze, Spotify…) no painel TFT das motos **Zontes**, sem o app Carbit.

O Moto Link conecta o celular direto no painel da moto (o mesmo painel que usa o Carbit / EasyConn) e manda para ele a imagem do Android Auto. O celular pode ficar no bolso, com a tela apagada.

> Projeto independente, feito por hobby. Não tem ligação com a Zontes, com a Carbit/EasyConn nem com o Google.
> Use por sua conta e risco, e não mexa no celular pilotando.

## Status

- Testado em uma **Zontes GK 350 (2024/25)** com um celular Samsung Galaxy (Android 17).
- Outras Zontes com o mesmo painel Carbit devem funcionar, mas não foram testadas. Se não funcionar na sua, mande o log (veja [Problemas](#se-algo-não-funcionar)).

## Download

Baixe o APK mais recente (`MotoLink-v1.0.1.apk`) na página de [Releases](../../releases).

## Requisitos

- **Celular autorizado:** o app está registrado no Google em distribuição limitada. Peça para o autor autorizar o seu aparelho antes de instalar; sem isso, o Android bloqueia a instalação.
- Android 8 ou mais novo, com Bluetooth e Wi-Fi.
- App **Android Auto** instalado.
- O app Carbit **fechado** na hora de conectar (os dois disputam o painel).

## Instalação

1. Baixe o APK e abra. O celular vai pedir para permitir instalar apps dessa fonte: permita.
2. Se o Play Protect pedir, toque em **Verificar app**. O aviso aparece porque o app não está na Play Store.
3. Abra o Moto Link e siga a tela **Prepare o celular**. São 5 passos, só na primeira vez:
   1. Permitir Bluetooth e Wi-Fi.
   2. Liberar o app para rodar em segundo plano (bateria sem restrição).
   3. Liberar a bolinha flutuante (aparecer sobre outros apps). Ela evita que o Android congele o app com o celular no bolso.
   4. Ter o Android Auto instalado.
   5. Ativar o **modo desenvolvedor do Android Auto**: nas configurações do Android Auto, role até o fim e toque 10 vezes em **Versão**.

## Uso no dia a dia

1. Ligue a ignição da moto.
2. Abra o Moto Link e toque em **Conectar à moto**.
3. Em uns 15 segundos o Android Auto aparece no painel. Pode guardar o celular.

**Depois de reiniciar o celular** (ou quando o Android Auto atualiza), o servidor do Android Auto desliga. O Moto Link percebe, abre a tela certa e mostra onde tocar: **⋮ → Iniciar servidor da unidade principal**. Ele conecta sozinho logo depois.

Dica: deixe a bateria do Android Auto em "Sem restrição" (e, na Samsung, em "Apps nunca suspensos") para o servidor durar mais.

## Limitações conhecidas

- **Servidor do Android Auto:** desde a versão 17.4, o Android Auto só aceita esse tipo de conexão com o "servidor da unidade principal" ligado à mão, nas opções de desenvolvedor. Sem root não há como ligar sozinho.
- **Faixa de baixo do painel:** a área com as luzes e informações da moto é do próprio painel e não dá para cobrir. O Android Auto ocupa a área de 800x384 acima dela.
- **Toque:** o painel não tem tela de toque. Para mexer no Android Auto, use a prévia no app (ou a Tela cheia), o 🎤 (Google Assistente) e o ◀ (voltar).
- **Botão MOD do guidão:** tira o Android Auto do painel. Se acontecer, toque em Desconectar e Conectar de novo.
- **Som:** sai pelo celular ou pelo intercomunicador do capacete, não pelo painel.
- **Dados da moto** (giro, marcha, gasolina): o painel da GK 350 não manda esses dados para o celular.

## Se algo não funcionar

- **Não acha o painel:** confira se a ignição e o Bluetooth estão ligados e se o Carbit está fechado. O app continua procurando por uns 20 minutos.
- **"Ligue o Wi-Fi":** o Wi-Fi precisa estar ligado, mas não precisa estar conectado em nenhuma rede.
- **A imagem trava com o celular no bolso:** faça os passos 2 e 3 da preparação.
- **Continua com problema:** no app, abra **Diagnóstico → Enviar log** e mande o arquivo numa [issue](../../issues).

## Como funciona (resumo técnico)

1. **Bluetooth LE:** o app acha o painel (nome começando com `EC…`), faz o aperto de mão do protocolo do painel e pede a rede.
2. **Wi-Fi Direct:** o celular cria uma rede Wi-Fi Direct (5 GHz) e manda o nome e a senha para o painel, que entra nela.
3. **Protocolo do painel:** o painel conecta de volta nas portas 10920–10923 do celular. Por elas passam comandos, batimentos e o vídeo H.264 que o painel pede quadro a quadro.
4. **Android Auto:** o Moto Link se apresenta como a central de uma moto para o "servidor da unidade principal" do Android Auto (127.0.0.1:5277), recebe o vídeo, recorta a faixa útil e codifica de novo em 800x384 para o painel.

## Compilar

O projeto não usa Gradle. Precisa de Java 17+, Python 3, OpenSSL e uma pasta `ferramentas/` (ou a variável `MOTOLINK_FERRAMENTAS`) com:

- `android-33.jar` (plataforma Android 33)
- `aapt2_64` (aapt2 do build-tools)
- `d8.jar` e `apksigner.jar` (build-tools)

```bash
python3 build.py 100 1.0      # versionCode, versionName
# saída: out/MotoLink.apk
```

Na primeira compilação o script cria uma chave de assinatura em `assinatura/`. Guarde essa pasta e **não publique**: as próximas versões precisam ser assinadas com a mesma chave para instalar por cima.

## Créditos e licença

- A parte do Android Auto se baseia no [Open Headunit](https://github.com/andreknieriem/open-headunit) (antigo Headunit Revived), de André Rinas, que continua o [headunit](https://github.com/mikereidis/headunit) original de Mike Reid. O certificado de central de referência vem desses projetos.
- Licença: [GNU AGPL v3](LICENSE), a mesma do Open Headunit.
