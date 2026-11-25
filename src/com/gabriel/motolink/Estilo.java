package com.gabriel.motolink;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Cores e peças de tela do Moto Link (tudo feito em código, sem XML). */
final class Estilo {
    private Estilo() {}

    static final int FUNDO = 0xFF0A0A0D, CARTAO = 0xFF15151B, CARTAO2 = 0xFF1E1E26, LINHA = 0xFF26262F;
    static final int VERDE = 0xFF2BD47E, VERMELHO = 0xFFFF5A5F, AMARELO = 0xFFFFC145;
    static final int TEXTO = 0xFFF4F4F6, TEXTO2 = 0xFF9A9AA6, TEXTO3 = 0xFF62626E;

    static int dp(Context c, float v) { return Math.round(v * c.getResources().getDisplayMetrics().density); }

    static GradientDrawable fundo(int cor, float raioDp, Context c) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(cor);
        g.setCornerRadius(dp(c, raioDp));
        return g;
    }

    static View cartao(Context c, View v) {
        v.setBackground(fundo(CARTAO, 22, c));
        v.setPadding(dp(c, 18), dp(c, 16), dp(c, 18), dp(c, 16));
        return v;
    }

    static TextView texto(Context c, String s, float sp, int cor, boolean negrito) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(cor);
        if (negrito) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    /** Botão arredondado. principal = verde cheio; senão, cinza escuro. */
    static TextView botao(Context c, String s, boolean principal, View.OnClickListener ao) {
        TextView b = texto(c, s, principal ? 17 : 15, principal ? Color.BLACK : TEXTO, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, principal ? 56 : 46));
        b.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        GradientDrawable f = fundo(principal ? VERDE : CARTAO2, principal ? 18 : 14, c);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), f, null));
        b.setClickable(true);
        b.setOnClickListener(ao);
        return b;
    }

    static void corDoBotao(TextView b, int fundoCor, int textoCor) {
        b.setTextColor(textoCor);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), fundo(fundoCor, 18, b.getContext()), null));
    }

    /** Botão pequeno em formato de pílula (para os passos da preparação). */
    static TextView pilula(Context c, String s, View.OnClickListener ao) {
        TextView b = texto(c, s, 14, Color.BLACK, true);
        b.setGravity(Gravity.CENTER);
        b.setMinHeight(dp(c, 36));
        b.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33000000), fundo(VERDE, 18, c), null));
        b.setClickable(true);
        b.setOnClickListener(ao);
        return b;
    }

    /** Texto clicável discreto (link). */
    static TextView link(Context c, String s, View.OnClickListener ao) {
        TextView t = texto(c, s, 14, VERDE, true);
        t.setPadding(0, dp(c, 8), 0, dp(c, 8));
        t.setClickable(true);
        t.setOnClickListener(ao);
        return t;
    }

    /** Parágrafo de texto corrido, com espaço entre linhas confortável. */
    static TextView paragrafo(Context c, String s, int cor) {
        TextView t = texto(c, s, 14, cor, false);
        t.setLineSpacing(dp(c, 3), 1f);
        return t;
    }

    /**
     * Um passo da preparação: número (ou ✓) numa bolinha, título, explicação e um botão à direita
     * que some quando o passo está feito.
     */
    static final class Passo extends LinearLayout {
        final TextView numero, titulo, explicacao, acao;
        private final String textoNumero;
        Passo(Context c, int n, String nome, String sobre, String botao, View.OnClickListener ao) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(c, 10), 0, dp(c, 10));
            textoNumero = String.valueOf(n);
            numero = texto(c, textoNumero, 14, TEXTO, true);
            numero.setGravity(Gravity.CENTER);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(CARTAO2);
            numero.setBackground(g);
            LayoutParams lpN = new LayoutParams(dp(c, 30), dp(c, 30));
            lpN.rightMargin = dp(c, 14);
            addView(numero, lpN);
            LinearLayout meio = new LinearLayout(c);
            meio.setOrientation(VERTICAL);
            titulo = texto(c, nome, 15, TEXTO, true);
            explicacao = texto(c, sobre, 13, TEXTO2, false);
            explicacao.setPadding(0, dp(c, 2), 0, 0);
            meio.addView(titulo);
            meio.addView(explicacao);
            addView(meio, new LayoutParams(0, -2, 1));
            acao = pilula(c, botao, ao);
            LayoutParams lpA = new LayoutParams(-2, -2);
            lpA.leftMargin = dp(c, 10);
            addView(acao, lpA);
        }
        /** Marca como feito (✓ verde, sem botão) ou pendente (número, com botão). */
        void feito(boolean ok) {
            numero.setText(ok ? "✓" : textoNumero);
            numero.setTextColor(ok ? Color.BLACK : TEXTO);
            ((GradientDrawable) numero.getBackground()).setColor(ok ? VERDE : CARTAO2);
            acao.setVisibility(ok ? GONE : VISIBLE);
            titulo.setTextColor(ok ? TEXTO2 : TEXTO);
        }
    }

    /** Cabeçalho de seção que abre e fecha o conteúdo logo abaixo. */
    static final class Secao extends LinearLayout {
        final View conteudo;
        private final TextView seta;
        private boolean aberta;
        private Runnable aoAbrir;
        Secao(Context c, String nome, View conteudo) {
            super(c);
            this.conteudo = conteudo;
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(dp(c, 4), dp(c, 14), dp(c, 4), dp(c, 14));
            addView(texto(c, nome, 16, TEXTO, true), new LayoutParams(0, -2, 1));
            seta = texto(c, "▾", 18, TEXTO3, false);
            addView(seta);
            setClickable(true);
            setOnClickListener(new OnClickListener() {
                @Override public void onClick(View v) { abrir(!aberta); }
            });
            conteudo.setVisibility(GONE);
        }
        void aoAbrir(Runnable r) { aoAbrir = r; }
        boolean aberta() { return aberta; }
        void abrir(boolean sim) {
            aberta = sim;
            conteudo.setVisibility(sim ? VISIBLE : GONE);
            seta.setText(sim ? "▴" : "▾");
            if (sim && aoAbrir != null) aoAbrir.run();
        }
    }

    /** Linha de status: bolinha colorida, nome e valor à direita. */
    static final class LinhaStatus extends LinearLayout {
        final View ponto;
        final TextView valor;
        LinhaStatus(Context c, String nome) {
            super(c);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(0, dp(c, 7), 0, dp(c, 7));
            ponto = new View(c);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(TEXTO3);
            ponto.setBackground(g);
            LayoutParams lp = new LayoutParams(dp(c, 10), dp(c, 10));
            lp.rightMargin = dp(c, 12);
            addView(ponto, lp);
            addView(texto(c, nome, 15, TEXTO2, false), new LayoutParams(0, -2, 1));
            valor = texto(c, "", 15, TEXTO, true);
            addView(valor);
        }
        void mostrar(String v, int cor) {
            valor.setText(v);
            ((GradientDrawable) ponto.getBackground()).setColor(cor);
        }
    }
}
