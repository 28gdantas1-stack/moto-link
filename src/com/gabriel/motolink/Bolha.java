package com.gabriel.motolink;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Build;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

/**
 * Bolinha flutuante (precisa da permissão "Aparecer sobre outros apps"): redonda, pequena e
 * 75% transparente. Fica na tela enquanto o painel recebe imagem e o app está fechado, para a
 * Samsung não congelar o Moto Link. Toque abre o app; arrastar muda de lugar.
 */
final class Bolha {

    private static final float ALFA = 0.25f;          // 75% transparente
    private static final int TAMANHO_DP = 48;

    private final Context ctx;
    private final Sessao sessao;
    private final WindowManager wm;
    private Vista vista;
    private WindowManager.LayoutParams lp;

    Bolha(Context app, Sessao s) {
        ctx = app;
        sessao = s;
        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    static boolean podeAparecer(Context c) {
        return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
    }

    boolean visivel() { return vista != null; }

    void mostrar() {
        if (vista != null || !podeAparecer(ctx)) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        int lado = Math.round(TAMANHO_DP * d);
        lp = new WindowManager.LayoutParams(lado, lado,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        android.content.SharedPreferences sp = ctx.getSharedPreferences("bolha", Context.MODE_PRIVATE);
        lp.x = sp.getInt("x2", Math.round(72 * d));          // longe da borda (gesto de voltar)
        lp.y = sp.getInt("y2", Math.round(220 * d));
        // a janela fica 100% opaca (o Android pode ignorar toques em janelas sobrepostas transparentes);
        // a transparência de 75% vai no desenho da bolinha
        prender();
        vista = new Vista(ctx);
        vista.setAlpha(ALFA);
        vista.setClickable(true);
        try {
            wm.addView(vista, lp);
            vista.comecar();
            sessao.log("Bolinha flutuante na tela (o app continua visível).");
        } catch (Exception e) {
            vista = null;
            sessao.log("! Não consegui mostrar a bolinha: " + e.getMessage());
        }
    }

    void esconder() {
        esconderAlvo();
        if (vista == null) return;
        vista.parar();
        try { wm.removeView(vista); } catch (Exception ignored) {}
        vista = null;
    }

    // ---------------------------------------------------------------- "X" lá embaixo para fechar
    private Alvo alvo;
    private boolean emCimaDoX;

    private void mostrarAlvo() {
        if (alvo != null) return;
        float d = ctx.getResources().getDisplayMetrics().density;
        int lado = Math.round(64 * d);
        WindowManager.LayoutParams a = new WindowManager.LayoutParams(lado, lado,
                Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        a.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        a.y = Math.round(72 * d);
        alvo = new Alvo(ctx);
        try { wm.addView(alvo, a); } catch (Exception e) { alvo = null; }
    }

    private void esconderAlvo() {
        if (alvo == null) return;
        try { wm.removeView(alvo); } catch (Exception ignored) {}
        alvo = null;
        emCimaDoX = false;
    }

    /** O dedo está em cima do X? (compara a posição do toque com o centro do X na tela) */
    private boolean sobreOX(float xTela, float yTela) {
        if (alvo == null || alvo.getWidth() == 0) return false;
        int[] pos = new int[2];
        alvo.getLocationOnScreen(pos);
        float cx = pos[0] + alvo.getWidth() / 2f, cy = pos[1] + alvo.getHeight() / 2f;
        float raio = alvo.getWidth() * 1.1f;
        float dx = xTela - cx, dy = yTela - cy;
        return dx * dx + dy * dy < raio * raio;
    }

    /** Mantém a bolinha inteira dentro da tela (com uma folguinha nas bordas). */
    private void prender() {
        int sw, sh;
        if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Rect r = wm.getCurrentWindowMetrics().getBounds();
            sw = r.width(); sh = r.height();
        } else {
            android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            sw = dm.widthPixels; sh = dm.heightPixels;
        }
        float d = ctx.getResources().getDisplayMetrics().density;
        int folga = Math.round(4 * d), lado = lp.width;
        int topo = Math.round(28 * d), baixo = Math.round(56 * d);   // barra de status / barra de navegação
        lp.x = Math.max(folga, Math.min(lp.x, sw - lado - folga));
        lp.y = Math.max(topo, Math.min(lp.y, sh - lado - baixo));
    }

    private void fecharPeloX() {
        esconder();
        sessao.log("Bolinha arrastada pro X: desconectando do painel.");
        sessao.desconectar();
    }

    private final class Alvo extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Alvo(Context c) { super(c); }
        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            float raio = emCimaDoX ? r : r * 0.8f;
            p.setStyle(Paint.Style.FILL);
            p.setColor(emCimaDoX ? 0xE6D23C3C : 0xB3202026);
            c.drawCircle(r, r, raio, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeWidth(r * 0.12f);
            p.setColor(0xFFFFFFFF);
            float b = raio * 0.38f;
            c.drawLine(r - b, r - b, r + b, r + b, p);
            c.drawLine(r + b, r - b, r - b, r + b, p);
        }
    }

    private int toques;

    private Intent intencao() {
        return new Intent(ctx, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
    }

    private void abrirApp() {
        sessao.log("Bolinha: abrindo o app…");
        try {
            ctx.startActivity(intencao());
        } catch (Exception e) {
            sessao.log("! Abrir direto falhou: " + e.getMessage());
        }
        // se o Android barrar a abertura em segundo plano, tenta de novo por um PendingIntent autorizado
        if (vista != null) vista.postDelayed(new Runnable() { @Override public void run() {
            if (MainActivity.naFrente) return;
            sessao.log("  o Android não deixou abrir direto; tentando pelo caminho autorizado…");
            try {
                android.app.PendingIntent pi = android.app.PendingIntent.getActivity(ctx, 7, intencao(),
                        android.app.PendingIntent.FLAG_IMMUTABLE | android.app.PendingIntent.FLAG_UPDATE_CURRENT);
                android.os.Bundle op = null;
                if (Build.VERSION.SDK_INT >= 34) {
                    android.app.ActivityOptions ao = android.app.ActivityOptions.makeBasic();
                    try {
                        ao.getClass().getMethod("setPendingIntentBackgroundActivityStartMode", int.class).invoke(ao, 1);
                    } catch (Exception ignored) {}
                    op = ao.toBundle();
                }
                pi.send(ctx, 0, null, null, null, null, op);
            } catch (Exception e) {
                sessao.log("! Também não abriu: " + e.getMessage());
            }
        }}, 1200);
    }

    private final class Vista extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean ligada;
        private float toqueX, toqueY;
        private int origemX, origemY;
        private boolean arrastou;
        private final Runnable tique = new Runnable() { @Override public void run() { if (ligada) { invalidate(); postDelayed(this, 250); } } };

        Vista(Context c) { super(c); }
        void comecar() { ligada = true; post(tique); }
        void parar() { ligada = false; removeCallbacks(tique); }

        @Override protected void onDraw(Canvas c) {
            float r = getWidth() / 2f;
            boolean ok = sessao.rede.midia.transmitindo();
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF0B0B0F);
            c.drawCircle(r, r, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(r * 0.16f);
            // anel verde "respirando" enquanto a imagem vai pro painel; cinza se parou
            int a = ok ? (int) (170 + 85 * Math.sin(System.currentTimeMillis() / 300.0)) : 255;
            p.setColor(ok ? (a << 24) | 0x2BD47E : 0xFF6A6A74);
            c.drawCircle(r, r, r * 0.84f, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFFFFFFFF);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextAlign(Paint.Align.CENTER);
            p.setTextSize(r * 0.62f);
            c.drawText("ML", r, r + r * 0.22f, p);
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (toques++ < 3) sessao.log("Bolinha: toque recebido ✓");
                    toqueX = e.getRawX(); toqueY = e.getRawY();
                    origemX = lp.x; origemY = lp.y;
                    arrastou = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dx = e.getRawX() - toqueX, dy = e.getRawY() - toqueY;
                    if (Math.abs(dx) + Math.abs(dy) > 12) arrastou = true;
                    if (arrastou) {
                        mostrarAlvo();
                        lp.x = origemX + (int) dx;
                        lp.y = origemY + (int) dy;
                        prender();
                        try { wm.updateViewLayout(this, lp); } catch (Exception ignored) {}
                        boolean sobre = sobreOX(e.getRawX(), e.getRawY());
                        if (sobre != emCimaDoX) {
                            emCimaDoX = sobre;
                            if (sobre) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                            if (alvo != null) alvo.invalidate();
                        }
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    if (arrastou) {
                        boolean fechar = sobreOX(e.getRawX(), e.getRawY());
                        esconderAlvo();
                        if (fechar) { fecharPeloX(); return true; }
                        ctx.getSharedPreferences("bolha", Context.MODE_PRIVATE).edit().putInt("x2", lp.x).putInt("y2", lp.y).apply();
                    } else {
                        abrirApp();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    esconderAlvo();
                    return true;
            }
            return super.onTouchEvent(e);
        }
    }
}
