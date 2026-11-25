package com.gabriel.motolink;

import android.app.Activity;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Android Auto em tela cheia, deitado, do tamanho do celular: para escolher destino e música com
 * facilidade antes de sair. Mostra exatamente o que está no painel e repassa os toques.
 */
public class TelaCheia extends Activity {

    private Sessao sessao;
    private TextureView tela;
    private Surface superficie;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        sessao = Sessao.de(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28)
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        FrameLayout raiz = new FrameLayout(this);
        raiz.setBackgroundColor(0xFF000000);

        // imagem do Android Auto, o maior possível mantendo a proporção do painel
        tela = new TextureView(this) {
            @Override protected void onMeasure(int wm, int hm) {
                int w = View.MeasureSpec.getSize(wm), h = View.MeasureSpec.getSize(hm);
                int aw = Math.max(1, sessao.rede.midia.largura()), ah = Math.max(1, sessao.rede.midia.altura());
                if (w * ah > h * aw) w = h * aw / ah; else h = w * ah / aw;
                setMeasuredDimension(w, h);
            }
        };
        tela.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                superficie = new Surface(st);
                sessao.aa.mostrarPrevia(TelaCheia.this, superficie, w, h);
            }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) {
                if (superficie != null) sessao.aa.mostrarPrevia(TelaCheia.this, superficie, w, h);
            }
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
                sessao.aa.soltarPrevia(TelaCheia.this);
                superficie = null;
                return true;
            }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture st) {}
        });
        tela.setOnTouchListener(MainActivity.Toque.para(sessao));
        raiz.addView(tela, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));

        // botões discretos no canto
        LinearLayout botoes = new LinearLayout(this);
        botoes.setOrientation(LinearLayout.VERTICAL);
        botoes.addView(redondo("✕", new View.OnClickListener() { @Override public void onClick(View v) { finish(); } }));
        botoes.addView(redondo("◀", new View.OnClickListener() { @Override public void onClick(View v) { sessao.aa.tecla(4); } }));
        botoes.addView(redondo("🎤", new View.OnClickListener() { @Override public void onClick(View v) { sessao.aa.tecla(84); } }));
        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL);
        lb.rightMargin = Estilo.dp(this, 10);
        raiz.addView(botoes, lb);

        // não deixa os botões ficarem embaixo do furo da câmera
        if (Build.VERSION.SDK_INT >= 28) {
            raiz.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
                @Override public WindowInsets onApplyWindowInsets(View v, WindowInsets in) {
                    android.view.DisplayCutout c = in.getDisplayCutout();
                    if (c != null) v.setPadding(c.getSafeInsetLeft(), c.getSafeInsetTop(), c.getSafeInsetRight(), c.getSafeInsetBottom());
                    else v.setPadding(0, 0, 0, 0);
                    return in;
                }
            });
        }
        setContentView(raiz);
        esconderBarras();
    }

    private View redondo(String s, View.OnClickListener ao) {
        TextView t = Estilo.texto(this, s, 17, Estilo.TEXTO, true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(Estilo.fundo(0x99202028, 24, this));
        t.setClickable(true);
        t.setOnClickListener(ao);
        int lado = Estilo.dp(this, 48);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(lado, lado);
        lp.topMargin = lp.bottomMargin = Estilo.dp(this, 6);
        t.setLayoutParams(lp);
        return t;
    }

    private void esconderBarras() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    @Override public void onWindowFocusChanged(boolean foco) {
        super.onWindowFocusChanged(foco);
        if (foco) esconderBarras();
    }

    @Override protected void onStart() { super.onStart(); sessao.telaVisivel(true); }
    @Override protected void onStop() { sessao.telaVisivel(false); super.onStop(); }

    @Override protected void onResume() {
        super.onResume();
        if (superficie != null) sessao.aa.mostrarPrevia(this, superficie, tela.getWidth(), tela.getHeight());
    }

    @Override protected void onDestroy() {
        sessao.aa.soltarPrevia(this);
        super.onDestroy();
    }
}
