package com.gabriel.motolink;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Tela principal: preparação do celular (uma vez só), status, conectar, prévia do Android Auto,
 * como usar e (escondido) o diagnóstico.
 */
public class MainActivity extends Activity implements Sessao.Tela {

    static volatile boolean naFrente;

    private static final String[] PACOTES_CARBIT = {"net.easyconn.easyride.wws", "net.easyconn.motofun"};

    private Sessao sessao;
    private SharedPreferences guia;
    private final Handler h = new Handler(Looper.getMainLooper());

    private ScrollView rolar;
    private LinearLayout cartaoGuia, avisoCarbit;
    private TextView tituloGuia, subGuia, esconderGuia;
    private Estilo.Passo pPermissoes, pBateria, pBolha, pAA, pDev;
    private boolean guiaAberto, aaInstalado, carbitInstalado;

    private Estilo.LinhaStatus stMoto, stAA;
    private TextView detalhe, btConectar, dicaAA;
    private LinearLayout cartaoAA, cartaoSemAA;
    private TextureView telaAA;
    private Surface superficieAA;

    private Estilo.Secao secComoUsar, secDiag;
    private TextView logView;
    private ScrollView logRolagem;
    private int linhasNaTela;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().setStatusBarColor(Estilo.FUNDO);
        getWindow().setNavigationBarColor(Estilo.FUNDO);
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Estilo.FUNDO));
        sessao = Sessao.de(this);
        guia = getSharedPreferences("guia", MODE_PRIVATE);
        montarTela();
        respeitarBarras();
        sessao.mostrarEm(this);
    }

    private int dp(float v) { return Estilo.dp(this, v); }

    /**
     * Android 15+: a tela vai de ponta a ponta (por baixo da barra de status e da de navegação).
     * Afasta o conteúdo das barras para nada ficar escondido atrás delas.
     */
    private void respeitarBarras() {
        if (Build.VERSION.SDK_INT < 35) return;
        rolar.setClipToPadding(false);
        rolar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets in) {
                android.graphics.Insets b = in.getInsets(android.view.WindowInsets.Type.systemBars()
                        | android.view.WindowInsets.Type.displayCutout());
                v.setPadding(b.left, b.top, b.right, b.bottom);
                return in;
            }
        });
        rolar.requestApplyInsets();
    }

    // ================================================================== tela
    private void montarTela() {
        rolar = new ScrollView(this);
        rolar.setBackgroundColor(Estilo.FUNDO);
        rolar.setFillViewport(true);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(36), dp(20), dp(28));
        rolar.addView(col);

        // ---- cabeçalho: nome, frase e o botão de ajuda
        LinearLayout cab = new LinearLayout(this);
        cab.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout nomes = new LinearLayout(this);
        nomes.setOrientation(LinearLayout.VERTICAL);
        TextView titulo = Estilo.texto(this, "Moto Link", 32, Estilo.TEXTO, true);
        titulo.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        nomes.addView(titulo);
        TextView sub = Estilo.texto(this, "Android Auto no painel da sua Zontes", 14, Estilo.TEXTO2, false);
        sub.setPadding(0, dp(2), 0, 0);
        nomes.addView(sub);
        cab.addView(nomes, new LinearLayout.LayoutParams(0, -2, 1));
        TextView ajuda = Estilo.texto(this, "?", 18, Estilo.TEXTO, true);
        ajuda.setGravity(Gravity.CENTER);
        ajuda.setBackground(Estilo.fundo(Estilo.CARTAO2, 20, this));
        ajuda.setClickable(true);
        ajuda.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { abrirAjuda(); }
        });
        cab.addView(ajuda, new LinearLayout.LayoutParams(dp(40), dp(40)));
        col.addView(cab, margem(0, 20));

        // ---- preparação (uma vez só)
        montarGuia();
        col.addView(cartaoGuia, margem(0, 14));

        // ---- status
        LinearLayout cs = new LinearLayout(this);
        cs.setOrientation(LinearLayout.VERTICAL);
        Estilo.cartao(this, cs);
        stMoto = new Estilo.LinhaStatus(this, "Painel da moto");
        stAA = new Estilo.LinhaStatus(this, "Android Auto");
        cs.addView(stMoto);
        View linha = new View(this);
        linha.setBackgroundColor(Estilo.LINHA);
        cs.addView(linha, new LinearLayout.LayoutParams(-1, 1));
        cs.addView(stAA);
        detalhe = Estilo.texto(this, "", 13, Estilo.TEXTO2, false);
        detalhe.setPadding(dp(22), dp(4), 0, dp(2));
        detalhe.setVisibility(View.GONE);
        cs.addView(detalhe);
        col.addView(cs, margem(0, 14));

        // ---- conectar / desconectar
        btConectar = Estilo.botao(this, "Conectar à moto", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (sessao.ativa()) sessao.desconectar();
                else if (pedirPermissoes()) {
                    liberarBateriaUmaVez();
                    pedirBolhaUmaVez();
                    sessao.conectar();
                }
                atualizar();
            }
        });
        col.addView(btConectar, margem(0, 18));

        // ---- Android Auto no painel: prévia que dá para tocar
        cartaoAA = new LinearLayout(this);
        cartaoAA.setOrientation(LinearLayout.VERTICAL);
        Estilo.cartao(this, cartaoAA);
        TextView rotulo = Estilo.texto(this, "No painel agora", 13, Estilo.TEXTO2, true);
        rotulo.setPadding(0, 0, 0, dp(10));
        cartaoAA.addView(rotulo);
        telaAA = new TextureView(this) {
            @Override protected void onMeasure(int wm, int hm) {
                int w = View.MeasureSpec.getSize(wm);
                int pw = Math.max(1, sessao.rede.midia.largura());
                setMeasuredDimension(w, w * sessao.rede.midia.altura() / pw);
            }
        };
        telaAA.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(12)); }
        });
        telaAA.setClipToOutline(true);
        telaAA.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int hh) {
                superficieAA = new Surface(st);
                sessao.aa.mostrarPrevia(MainActivity.this, superficieAA, w, hh);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int hh) {
                if (superficieAA != null) sessao.aa.mostrarPrevia(MainActivity.this, superficieAA, w, hh);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                sessao.aa.soltarPrevia(MainActivity.this);
                superficieAA = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        telaAA.setOnTouchListener(Toque.para(sessao));
        cartaoAA.addView(telaAA);
        TextView dica = Estilo.texto(this, "Toque na imagem para mexer no Android Auto", 13, Estilo.TEXTO3, false);
        dica.setPadding(0, dp(10), 0, dp(12));
        cartaoAA.addView(dica);
        LinearLayout acoes = new LinearLayout(this);
        acoes.addView(Estilo.botao(this, "⤢  Tela cheia", false, new View.OnClickListener() {
            @Override public void onClick(View v) { startActivity(new Intent(MainActivity.this, TelaCheia.class)); }
        }), peso(1.4f, 0));
        acoes.addView(Estilo.botao(this, "🎤", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sessao.aa.tecla(84); }
        }), peso(0.6f, 8));
        acoes.addView(Estilo.botao(this, "◀", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sessao.aa.tecla(4); }
        }), peso(0.6f, 8));
        cartaoAA.addView(acoes);
        cartaoAA.setVisibility(View.GONE);
        col.addView(cartaoAA, margem(0, 18));

        // ---- painel conectado, mas sem Android Auto
        cartaoSemAA = new LinearLayout(this);
        cartaoSemAA.setOrientation(LinearLayout.VERTICAL);
        Estilo.cartao(this, cartaoSemAA);
        cartaoSemAA.addView(Estilo.texto(this, "Falta ligar o Android Auto", 17, Estilo.TEXTO, true));
        dicaAA = Estilo.paragrafo(this, "", Estilo.TEXTO2);
        dicaAA.setPadding(0, dp(6), 0, dp(14));
        cartaoSemAA.addView(dicaAA);
        LinearLayout a2 = new LinearLayout(this);
        a2.addView(Estilo.botao(this, "Abrir Android Auto", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AndroidAuto.abrirConfigDoAA(MainActivity.this)) AndroidAuto.dica(getApplicationContext());
            }
        }), peso(1.4f, 0));
        a2.addView(Estilo.botao(this, "Tentar de novo", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sessao.aa.conectar(); }
        }), peso(1, 8));
        cartaoSemAA.addView(a2);
        cartaoSemAA.setVisibility(View.GONE);
        col.addView(cartaoSemAA, margem(0, 18));

        // ---- como usar
        LinearLayout como = montarComoUsar();
        secComoUsar = new Estilo.Secao(this, "Como usar", como);
        col.addView(secComoUsar);
        col.addView(como, margem(0, 6));

        // ---- diagnóstico
        LinearLayout diag = new LinearLayout(this);
        diag.setOrientation(LinearLayout.VERTICAL);
        diag.addView(Estilo.paragrafo(this,
                "Se algo não funcionar, toque em Enviar log e mande o arquivo para quem te passou o app.",
                Estilo.TEXTO3), margem(0, 10));
        LinearLayout a3 = new LinearLayout(this);
        a3.addView(Estilo.botao(this, "Enviar log", false, new View.OnClickListener() {
            @Override public void onClick(View v) { salvarLog(); }
        }), peso(1, 0));
        a3.addView(Estilo.botao(this, "Limpar", false, new View.OnClickListener() {
            @Override public void onClick(View v) { sessao.limparLog(); tudoDeNovo(); }
        }), peso(1, 8));
        diag.addView(a3, margem(0, 10));
        logRolagem = new ScrollView(this);
        logRolagem.setBackground(Estilo.fundo(0xFF050507, 16, this));
        logRolagem.setPadding(dp(12), dp(10), dp(12), dp(10));
        logView = new TextView(this);
        logView.setTextColor(0xFFB8B8C2);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        logView.setTextIsSelectable(true);
        logRolagem.addView(logView);
        diag.addView(logRolagem, new LinearLayout.LayoutParams(-1, dp(360)));
        secDiag = new Estilo.Secao(this, "Diagnóstico", diag);
        secDiag.aoAbrir(new Runnable() { @Override public void run() { tudoDeNovo(); } });
        col.addView(secDiag);
        col.addView(diag);

        TextView rodape = Estilo.texto(this, "Moto Link v" + Sessao.VERSAO, 12, Estilo.TEXTO3, false);
        rodape.setGravity(Gravity.CENTER);
        rodape.setPadding(0, dp(24), 0, 0);
        col.addView(rodape);

        setContentView(rolar);
    }

    /** Cartão "Prepare o celular": os passos que se faz uma vez só. */
    private void montarGuia() {
        cartaoGuia = new LinearLayout(this);
        cartaoGuia.setOrientation(LinearLayout.VERTICAL);
        Estilo.cartao(this, cartaoGuia);
        tituloGuia = Estilo.texto(this, "Prepare o celular", 18, Estilo.TEXTO, true);
        cartaoGuia.addView(tituloGuia);
        subGuia = Estilo.texto(this, "Só na primeira vez. Depois é só ligar a moto e tocar em Conectar.", 13, Estilo.TEXTO2, false);
        subGuia.setPadding(0, dp(4), 0, dp(6));
        cartaoGuia.addView(subGuia);

        pPermissoes = new Estilo.Passo(this, 1, "Bluetooth e Wi-Fi",
                "Para achar o painel e criar a conexão com ele.", "Permitir", new View.OnClickListener() {
            @Override public void onClick(View v) { resolverConexao(); }
        });
        pBateria = new Estilo.Passo(this, 2, "Rodar em segundo plano",
                "Para a imagem não cair com a tela apagada.", "Liberar", new View.OnClickListener() {
            @Override public void onClick(View v) { liberarBateria(); }
        });
        pBolha = new Estilo.Passo(this, 3, "Bolinha flutuante",
                "Deixa o celular no bolso sem travar a imagem.", "Liberar", new View.OnClickListener() {
            @Override public void onClick(View v) { abrirPermissaoBolha(); }
        });
        pAA = new Estilo.Passo(this, 4, "Android Auto instalado",
                "O app do Google que desenha o mapa e a música.", "Instalar", new View.OnClickListener() {
            @Override public void onClick(View v) { instalarAA(); }
        });
        pDev = new Estilo.Passo(this, 5, "Modo desenvolvedor do Android Auto",
                "Abra, role até o fim e toque 10 vezes em \"Versão\". Confirme o aviso.", "Abrir", new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (AndroidAuto.abrirConfigDoAA(MainActivity.this))
                    toastLongo("Role até o fim e toque 10 vezes em \"Versão\". Depois volte para o Moto Link.");
                else toastLongo("Não consegui abrir o Android Auto. Ele está instalado?");
            }
        });
        for (View p : new View[]{pPermissoes, pBateria, pBolha, pAA, pDev}) cartaoGuia.addView(p);
        TextView jaFiz = Estilo.link(this, "Já fiz o passo 5", new View.OnClickListener() {
            @Override public void onClick(View v) { guia.edit().putBoolean("devOk", true).apply(); atualizarGuia(); }
        });
        jaFiz.setPadding(dp(44), 0, 0, dp(4));
        jaFiz.setTag("jaFiz");
        cartaoGuia.addView(jaFiz);

        avisoCarbit = new LinearLayout(this);
        avisoCarbit.setOrientation(LinearLayout.VERTICAL);
        avisoCarbit.setBackground(Estilo.fundo(0x1FFFC145, 14, this));
        avisoCarbit.setPadding(dp(14), dp(12), dp(14), dp(6));
        avisoCarbit.addView(Estilo.paragrafo(this,
                "⚠  O app Carbit está instalado. Feche ele (Forçar parada) antes de conectar: os dois disputam o painel.",
                Estilo.AMARELO));
        avisoCarbit.addView(Estilo.link(this, "Fechar o Carbit", new View.OnClickListener() {
            @Override public void onClick(View v) { abrirDetalhesDoCarbit(); }
        }));
        LinearLayout.LayoutParams lpC = margem(8, 4);
        cartaoGuia.addView(avisoCarbit, lpC);

        esconderGuia = Estilo.link(this, "Esconder", new View.OnClickListener() {
            @Override public void onClick(View v) { guiaAberto = false; atualizarGuia(); }
        });
        esconderGuia.setGravity(Gravity.END);
        cartaoGuia.addView(esconderGuia, new LinearLayout.LayoutParams(-1, -2));
    }

    /** Texto de "Como usar": o dia a dia e os problemas mais comuns. */
    private LinearLayout montarComoUsar() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        Estilo.cartao(this, c);
        bloco(c, "Toda vez que for andar",
                "1.  Ligue a ignição da moto.\n"
                + "2.  Abra o Moto Link e toque em Conectar à moto.\n"
                + "3.  Em uns 15 segundos o Android Auto aparece no painel.\n"
                + "4.  Pode guardar o celular. A bolinha flutuante mantém tudo funcionando.");
        bloco(c, "Depois de reiniciar o celular",
                "O Android Auto desliga o servidor dele quando o celular reinicia ou o app atualiza. "
                + "O Moto Link percebe e abre a tela certa: toque em ⋮ (no alto, à direita) → "
                + "\"Iniciar servidor da unidade principal\". Ele conecta sozinho em seguida.");
        bloco(c, "Para mexer no Android Auto",
                "O painel da moto não tem toque. Use a imagem aqui no app (ou Tela cheia) como se fosse o painel. "
                + "O 🎤 chama o Google Assistente e o ◀ volta.\n"
                + "O som e a voz da navegação saem pelo celular ou pelo intercomunicador do capacete.");
        bloco(c, "Se algo não funcionar",
                "•  Não acha o painel: confira se a ignição e o Bluetooth estão ligados e se o Carbit está fechado.\n"
                + "•  \"Ligue o Wi-Fi\": o Wi-Fi do celular precisa estar ligado, mas não precisa estar em rede nenhuma.\n"
                + "•  A imagem trava com o celular no bolso: faça os passos 2 e 3 da preparação.\n"
                + "•  O botão MOD do guidão tira o Android Auto do painel. Se acontecer, toque em Desconectar e Conectar de novo.\n"
                + "•  Continuou com problema: abra Diagnóstico, toque em Enviar log e mande o arquivo.");
        TextView rever = Estilo.link(this, "Ver a preparação do celular de novo", new View.OnClickListener() {
            @Override public void onClick(View v) { guiaAberto = true; atualizarGuia(); rolar.smoothScrollTo(0, 0); }
        });
        c.addView(rever);
        return c;
    }

    private void bloco(LinearLayout c, String titulo, String texto) {
        TextView t = Estilo.texto(this, titulo, 15, Estilo.TEXTO, true);
        t.setPadding(0, c.getChildCount() == 0 ? 0 : dp(14), 0, dp(4));
        c.addView(t);
        c.addView(Estilo.paragrafo(this, texto, Estilo.TEXTO2));
    }

    private void abrirAjuda() {
        guiaAberto = true;
        atualizarGuia();
        secComoUsar.abrir(true);
        rolar.post(new Runnable() { @Override public void run() { rolar.smoothScrollTo(0, secComoUsar.getTop() - dp(10)); } });
    }

    private LinearLayout.LayoutParams margem(int cima, int baixo) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(cima);
        lp.bottomMargin = dp(baixo);
        return lp;
    }

    private LinearLayout.LayoutParams peso(float p, int esquerda) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, p);
        lp.leftMargin = dp(esquerda);
        return lp;
    }

    // ================================================================== preparação: o que falta?
    private boolean temPermissoes() {
        for (String p : permissoesNecessarias())
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) return false;
        return true;
    }

    private boolean bluetoothLigado() {
        BluetoothAdapter ad = BluetoothAdapter.getDefaultAdapter();
        return ad != null && ad.isEnabled();
    }

    private boolean wifiLigado() {
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        return wm == null || wm.isWifiEnabled();
    }

    private boolean instalado(String pacote) {
        try { getPackageManager().getPackageInfo(pacote, 0); return true; }
        catch (Exception e) { return false; }
    }

    private void verPacotes() {
        aaInstalado = instalado(AndroidAuto.PACOTE_AA);
        carbitInstalado = false;
        for (String p : PACOTES_CARBIT) carbitInstalado |= instalado(p);
    }

    private boolean devFeito() { return guia.getBoolean("devOk", false) || guia.getBoolean("aaOk", false); }

    private void atualizarGuia() {
        boolean perm = temPermissoes(), bt = bluetoothLigado(), wifi = wifiLigado();
        boolean conexao = perm && bt && wifi;
        pPermissoes.feito(conexao);
        pPermissoes.explicacao.setText(!perm ? "Para achar o painel e criar a conexão com ele."
                : !bt ? "Falta ligar o Bluetooth." : !wifi ? "Falta ligar o Wi-Fi (não precisa entrar em rede nenhuma)."
                : "Tudo certo.");
        pPermissoes.acao.setText(!perm ? "Permitir" : "Ligar");
        boolean bateria = sessao.bateriaLivre();
        pBateria.feito(bateria);
        boolean bolha = Bolha.podeAparecer(this);
        pBolha.feito(bolha);
        pAA.feito(aaInstalado);
        boolean dev = devFeito();
        pDev.feito(dev);
        View jaFiz = cartaoGuia.findViewWithTag("jaFiz");
        if (jaFiz != null) jaFiz.setVisibility(dev ? View.GONE : View.VISIBLE);
        avisoCarbit.setVisibility(carbitInstalado ? View.VISIBLE : View.GONE);

        boolean pronto = conexao && bateria && bolha && aaInstalado && dev;
        tituloGuia.setText(pronto ? "Celular pronto ✓" : "Prepare o celular");
        subGuia.setText(pronto ? "Está tudo liberado. É só ligar a moto e tocar em Conectar."
                : "Só na primeira vez. Depois é só ligar a moto e tocar em Conectar.");
        esconderGuia.setVisibility(pronto ? View.VISIBLE : View.GONE);
        boolean emUso = sessao.rede.midia.transmitindo();
        cartaoGuia.setVisibility(guiaAberto || (!pronto && !emUso) ? View.VISIBLE : View.GONE);
    }

    private void resolverConexao() {
        if (!temPermissoes()) { pedirPermissoes(); return; }
        if (!bluetoothLigado()) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {}
            return;
        }
        if (!wifiLigado()) {
            try {
                startActivity(new Intent(Build.VERSION.SDK_INT >= 29 ? Settings.Panel.ACTION_WIFI : Settings.ACTION_WIFI_SETTINGS));
            } catch (Exception e) {
                try { startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)); } catch (Exception ignored) {}
            }
        }
    }

    private void instalarAA() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + AndroidAuto.PACOTE_AA)));
        } catch (Exception e) {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=" + AndroidAuto.PACOTE_AA)));
            } catch (Exception ignored) {}
        }
    }

    private void abrirDetalhesDoCarbit() {
        for (String p : PACOTES_CARBIT) {
            if (!instalado(p)) continue;
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + p)));
                toastLongo("Toque em \"Forçar parada\" e volte para o Moto Link.");
                return;
            } catch (Exception ignored) {}
        }
    }

    private void toastLongo(String t) { Toast.makeText(this, t, Toast.LENGTH_LONG).show(); }

    // ================================================================== atualização periódica
    private int tiques;
    private final Runnable tique = new Runnable() {
        @Override public void run() {
            atualizar();
            if (++tiques % 4 == 0) atualizarGuia();
            h.postDelayed(this, 700);
        }
    };

    private void atualizar() {
        boolean ativa = sessao.ativa(), imagem = sessao.rede.midia.transmitindo();
        AndroidAuto aa = sessao.aa;

        if (imagem) stMoto.mostrar("Conectado", Estilo.VERDE);
        else if (ativa) stMoto.mostrar("Conectando…", Estilo.AMARELO);
        else stMoto.mostrar("Desconectado", Estilo.TEXTO3);

        if (aa.temVideo()) stAA.mostrar("No painel", Estilo.VERDE);
        else if (aa.ligado()) stAA.mostrar("Iniciando…", Estilo.AMARELO);
        else if (aa.servidorDesligado()) stAA.mostrar("Servidor desligado", Estilo.VERMELHO);
        else stAA.mostrar("Desligado", Estilo.TEXTO3);

        String d = ativa && !imagem ? sessao.estadoTexto() : "";
        detalhe.setText(d);
        detalhe.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);

        btConectar.setText(ativa ? "Desconectar" : "Conectar à moto");
        if (ativa) Estilo.corDoBotao(btConectar, Estilo.CARTAO2, Estilo.VERMELHO);
        else Estilo.corDoBotao(btConectar, Estilo.VERDE, 0xFF000000);

        cartaoAA.setVisibility(aa.temVideo() ? View.VISIBLE : View.GONE);
        boolean semAA = imagem && !aa.temVideo() && !aa.ligado();
        cartaoSemAA.setVisibility(semAA ? View.VISIBLE : View.GONE);
        dicaAA.setText(aa.servidorDesligado()
                ? "O servidor do Android Auto está desligado (acontece depois de reiniciar o celular). "
                  + "Toque em Abrir Android Auto → ⋮ → \"Iniciar servidor da unidade principal\". O Moto Link conecta sozinho logo depois."
                : "O painel está pronto. Toque em Tentar de novo para mandar o Android Auto.");
    }

    // ================================================================== Sessao.Tela (log)
    @Override public void novaLinha(String linha) {
        if (secDiag == null || !secDiag.aberta() || logView == null) return;
        logView.append(linha + "\n");
        if (++linhasNaTela > 600) tudoDeNovo();
        rolarLog();
    }

    @Override public void novoEstado(String texto, boolean ok) { atualizar(); }

    @Override public void tudoDeNovo() {
        if (logView == null) return;
        String t = secDiag != null && secDiag.aberta() ? sessao.linhasNaTela() : "";
        logView.setText(t);
        linhasNaTela = 0;
        for (int i = 0; i < t.length(); i++) if (t.charAt(i) == '\n') linhasNaTela++;
        rolarLog();
        atualizar();
    }

    private void rolarLog() {
        logRolagem.post(new Runnable() { @Override public void run() { logRolagem.fullScroll(View.FOCUS_DOWN); } });
    }

    private void log(String s) { sessao.log(s); }

    // ================================================================== ciclo de vida
    @Override protected void onStart() { super.onStart(); sessao.telaVisivel(true); }
    @Override protected void onStop() { sessao.telaVisivel(false); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        naFrente = true;
        verPacotes();
        atualizarGuia();
        if (superficieAA != null) sessao.aa.mostrarPrevia(this, superficieAA, telaAA.getWidth(), telaAA.getHeight());
        h.post(tique);
    }

    @Override protected void onPause() {
        naFrente = false;
        h.removeCallbacks(tique);
        super.onPause();
    }

    /** Fechar a tela não desconecta: a conexão fica na Sessao (e o serviço mantém o app vivo). */
    @Override protected void onDestroy() {
        sessao.aa.soltarPrevia(this);
        sessao.soltar(this);
        super.onDestroy();
    }

    // ================================================================== permissões e ajustes
    private void pedirBolhaUmaVez() {
        if (Bolha.podeAparecer(this)) return;
        if (guia.getBoolean("pediuBolha", false) || getSharedPreferences("bolha", MODE_PRIVATE).getBoolean("pediu", false)) return;
        guia.edit().putBoolean("pediuBolha", true).apply();
        abrirPermissaoBolha();
    }

    private void abrirPermissaoBolha() {
        log("Libere \"Aparecer sobre outros apps\" para o Moto Link (é a bolinha flutuante).");
        try {
            startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
            toastLongo("Ative \"Aparecer sobre outros apps\" para o Moto Link e volte.");
        } catch (Exception e) {
            log("! Não consegui abrir a tela da permissão: " + e.getMessage());
        }
    }

    private void liberarBateriaUmaVez() {
        if (sessao.bateriaLivre() || guia.getBoolean("pediuBateria", false)) return;
        guia.edit().putBoolean("pediuBateria", true).apply();
        liberarBateria();
    }

    /** Pede ao Android para não economizar bateria no Moto Link. */
    private void liberarBateria() {
        if (sessao.bateriaLivre()) return;
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            try { startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)); } catch (Exception ignored) {}
        }
    }

    private void salvarLog() {
        String nome = "motolink-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".txt";
        try {
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.MediaColumns.DISPLAY_NAME, nome);
            cv.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
            cv.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/MotoLink");
            ContentResolver cr = getContentResolver();
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new Exception("sem acesso a Downloads");
            OutputStream o = cr.openOutputStream(uri);
            o.write(sessao.logCompleto().getBytes(StandardCharsets.UTF_8));
            o.close();
            log("Log salvo em Downloads/MotoLink/" + nome);
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_STREAM, uri);
            i.putExtra(Intent.EXTRA_SUBJECT, nome);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Enviar o log"));
        } catch (Exception e) {
            log("! Não consegui salvar o log: " + e.getMessage());
        }
    }

    private String[] permissoesNecessarias() {
        return Build.VERSION.SDK_INT >= 31
                ? (Build.VERSION.SDK_INT >= 33
                    ? new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.NEARBY_WIFI_DEVICES}
                    : new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.ACCESS_FINE_LOCATION})
                : new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean pedirPermissoes() {
        List<String> faltam = new ArrayList<>();
        for (String p : permissoesNecessarias()) if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltam.add(p);
        if (!faltam.isEmpty()) {
            if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                faltam.add("android.permission.POST_NOTIFICATIONS");
            requestPermissions(faltam.toArray(new String[0]), 1);
            return false;
        }
        if (!bluetoothLigado()) {
            try { startActivity(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); } catch (Exception ignored) {}
            return false;
        }
        return true;
    }

    @Override public void onRequestPermissionsResult(int req, String[] p, int[] r) {
        boolean ok = true;
        for (int i = 0; i < r.length && i < p.length; i++)
            if (!p[i].endsWith("POST_NOTIFICATIONS")) ok &= r[i] == PackageManager.PERMISSION_GRANTED;
        log(ok ? "Permissões de Bluetooth liberadas." : "! Sem permissão de Bluetooth o app não acha o painel.");
        if (!ok) toastLongo("Sem essa permissão o Moto Link não consegue achar o painel.");
        atualizarGuia();
    }

    /** Toque numa prévia -> toque no Android Auto (compartilhado com a tela cheia). */
    static final class Toque {
        static View.OnTouchListener para(final Sessao s) {
            return new View.OnTouchListener() {
                @Override public boolean onTouch(View v, MotionEvent e) {
                    int acao;
                    switch (e.getActionMasked()) {
                        case MotionEvent.ACTION_DOWN:
                            acao = 0;
                            if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                            break;
                        case MotionEvent.ACTION_UP: acao = 1; break;
                        case MotionEvent.ACTION_MOVE: acao = 2; break;
                        case MotionEvent.ACTION_CANCEL: acao = 3; break;
                        default: return true;
                    }
                    s.aa.tocar(acao, e.getX() / Math.max(1, v.getWidth()), e.getY() / Math.max(1, v.getHeight()));
                    return true;
                }
            };
        }
    }
}
