package com.liuhong.tower;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 都市盖楼：摆动吊钩 + 单键落块的叠塔游戏。
 * 确认键（或触屏点按）落块；完全对齐触发连击奖励；偏出过多楼塌结束。
 * 支持遥控器 D-pad（只需确认键）与触屏。
 */
public class GameView extends View {

    private static final long FRAME_MS = 15;
    private static final float GRAVITY_RATIO = 2.4f; // 重力加速度（屏高/秒^2 倍数）

    private final Handler handler = new Handler(Looper.getMainLooper());
    private long lastNanos;
    private boolean running;

    private static class Block {
        float left, width, bottom; // bottom 为世界坐标（向上增长）
    }

    private static class Debris {
        float left, width, bottom, vy, rot, rotV;
    }

    private final List<Block> tower = new ArrayList<Block>();
    private final List<Debris> debrisList = new ArrayList<Debris>();
    private final Random random = new Random();

    private int viewW, viewH;
    private float bh;      // 层高
    private float baseW;   // 初始楼宽
    private float groundY; // 地面世界 y（画面底部）
    private float camY;    // 相机偏移：screenY = worldY + camY
    private float gravity;

    private float phase;       // 摆动相位
    private float swingSpeed;  // 摆动角速度 rad/s
    private Block falling;     // 下落中的块
    private float fallVy;

    private int floors, score, combo;
    private float comboFlash;  // 连击提示剩余秒
    private int perfectStreak;
    private boolean gameOver;
    private float crashTimer;  // 楼塌后延迟弹结算

    private ToneGenerator tones;

    private final Paint skyPaint = new Paint();
    private final Paint sunPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint farPaint = new Paint();
    private final Paint nearPaint = new Paint();
    private final Paint groundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockTopPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint winPaint = new Paint();
    private final Paint hookPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hudPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint comboPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint overlayPaint = new Paint();
    private final Paint overlayTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint flashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public GameView(Context context) {
        this(context, null);
    }

    public GameView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusable(true);
        setFocusableInTouchMode(true);
        sunPaint.setColor(0xFFFFF59D);
        farPaint.setColor(0xFF5C6BC0);
        nearPaint.setColor(0xFF283593);
        groundPaint.setColor(0xFF4E342E);
        blockPaint.setColor(0xFFFB8C00);
        blockTopPaint.setColor(0xFFFFD54F);
        winPaint.setColor(0xFF37474F);
        hookPaint.setColor(0xFF37474F);
        hookPaint.setStrokeWidth(5f);
        hudPaint.setColor(Color.WHITE);
        comboPaint.setColor(0xFFFFEB3B);
        overlayPaint.setColor(0xCC000000);
        overlayTextPaint.setColor(Color.WHITE);
        flashPaint.setColor(0x33FFFFFF);
    }

    // ---------- 生命周期 ----------

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            tones = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
        } catch (Exception ignored) {
        }
        running = true;
        lastNanos = System.nanoTime();
        handler.postDelayed(tick, FRAME_MS);
    }

    @Override
    protected void onDetachedFromWindow() {
        running = false;
        handler.removeCallbacks(tick);
        if (tones != null) tones.release();
        super.onDetachedFromWindow();
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            long now = System.nanoTime();
            float dt = Math.min(0.05f, (now - lastNanos) / 1e9f);
            lastNanos = now;
            step(dt);
            invalidate();
            if (running) handler.postDelayed(this, FRAME_MS);
        }
    };

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        viewW = w;
        viewH = h;
        bh = h * 0.062f;
        baseW = w * 0.30f;
        groundY = h * 0.94f;
        gravity = h * GRAVITY_RATIO;
        skyPaint.setShader(new LinearGradient(0, 0, 0, h,
                0xFF0D47A1, 0xFF90CAF9, Shader.TileMode.CLAMP));
        hudPaint.setTextSize(h * 0.045f);
        comboPaint.setTextSize(h * 0.06f);
        overlayTextPaint.setTextSize(h * 0.07f);
        if (tower.isEmpty()) reset();
    }

    private void reset() {
        tower.clear();
        debrisList.clear();
        Block base = new Block();
        base.left = viewW / 2f - baseW / 2f;
        base.width = baseW;
        base.bottom = groundY - bh;
        tower.add(base);
        floors = 1;
        score = 0;
        combo = 0;
        perfectStreak = 0;
        comboFlash = 0f;
        gameOver = false;
        crashTimer = 0f;
        falling = null;
        phase = 0f;
        camY = 0f;
        swingSpeed = 1.5f;
    }

    // ---------- 游戏逻辑 ----------

    private Block topBlock() {
        return tower.get(tower.size() - 1);
    }

    private float topY() {
        return groundY - tower.size() * bh;
    }

    private float swingAmp() {
        float w = topBlock().width;
        return Math.max(viewW * 0.1f, viewW / 2f - w / 2f - viewW * 0.03f);
    }

    private float hangLeft() {
        return viewW / 2f + (float) Math.sin(phase) * swingAmp() - topBlock().width / 2f;
    }

    private void step(float dt) {
        // 坠物始终更新
        for (int i = debrisList.size() - 1; i >= 0; i--) {
            Debris d = debrisList.get(i);
            d.vy += gravity * dt;
            d.bottom += d.vy * dt; // 向下坠落
            d.rot += d.rotV * dt;
            if (d.bottom + camY > viewH + bh * 3) debrisList.remove(i);
        }
        if (comboFlash > 0) comboFlash -= dt;

        if (gameOver) {
            if (crashTimer > 0) crashTimer -= dt;
            return;
        }

        phase += swingSpeed * dt;

        if (falling != null) {
            fallVy += gravity * dt;
            falling.bottom += fallVy * dt; // 世界坐标向下（y 增大）为坠落
            if (falling.bottom >= topY()) {
                falling.bottom = topY();
                land();
            }
        }

        // 相机跟随楼顶：楼顶应保持在屏幕上方 1/3 处
        float camTarget = Math.max(0f, viewH * 0.34f - topY());
        camY += (camTarget - camY) * Math.min(1f, dt * 4f);
    }

    private void land() {
        Block top = topBlock();
        float left = Math.max(falling.left, top.left);
        float right = Math.min(falling.left + falling.width, top.left + top.width);
        float overlap = right - left;

        if (overlap < top.width * 0.08f) {
            // 没接住：楼塌
            Debris d = new Debris();
            d.left = falling.left;
            d.width = falling.width;
            d.bottom = falling.bottom;
            d.vy = Math.min(fallVy, viewH * 0.5f);
            d.rot = 0f;
            d.rotV = (random.nextFloat() - 0.5f) * 6f;
            debrisList.add(d);
            falling = null;
            gameOver = true;
            crashTimer = 0.9f;
            beep(ToneGenerator.TONE_PROP_NACK, 250);
            return;
        }

        float offset = falling.left - top.left;
        float perfectPx = Math.max(3f, top.width * 0.045f);
        Block placed = new Block();
        placed.bottom = topY();

        if (Math.abs(offset) <= perfectPx) {
            // 完美对齐：不切边，连击加成
            placed.left = top.left;
            placed.width = top.width;
            combo++;
            perfectStreak = Math.max(perfectStreak, combo);
            score += 10 + combo * 10;
            comboFlash = 1.2f;
            beep(ToneGenerator.TONE_PROP_ACK, 90);
        } else {
            combo = 0;
            placed.left = left;
            placed.width = overlap;
            score += 10;
            // 切下的部分成为坠物
            if (falling.left < left) {
                Debris d = new Debris();
                d.left = falling.left;
                d.width = left - falling.left;
                d.bottom = falling.bottom;
                d.vy = 0f;
                d.rot = 0f;
                d.rotV = (random.nextFloat() - 0.5f) * 8f;
                debrisList.add(d);
            }
            float fallRight = falling.left + falling.width;
            float placedRight = left + overlap;
            if (fallRight > placedRight) {
                Debris d = new Debris();
                d.left = placedRight;
                d.width = fallRight - placedRight;
                d.bottom = falling.bottom;
                d.vy = 0f;
                d.rot = 0f;
                d.rotV = (random.nextFloat() - 0.5f) * 8f;
                debrisList.add(d);
            }
            beep(ToneGenerator.TONE_PROP_BEEP, 50);
        }

        tower.add(placed);
        floors++;
        falling = null;
        swingSpeed = 1.5f + floors * 0.055f;
    }

    private void dropOrRestart() {
        if (gameOver) {
            if (crashTimer <= 0) reset();
            return;
        }
        if (falling != null) return;
        falling = new Block();
        falling.left = hangLeft();
        falling.width = topBlock().width;
        falling.bottom = topY() - bh * 1.1f;
        fallVy = 0f;
    }

    private void beep(int tone, int ms) {
        try {
            if (tones != null) tones.startTone(tone, ms);
        } catch (Exception ignored) {
        }
    }

    // ---------- 输入 ----------

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
            case KeyEvent.KEYCODE_SPACE:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                dropOrRestart();
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            dropOrRestart();
            return true;
        }
        return true;
    }

    // ---------- 绘制 ----------

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 天空
        canvas.drawRect(0, 0, viewW, viewH, skyPaint);
        // 太阳
        canvas.drawCircle(viewW * 0.85f, viewH * 0.16f, viewH * 0.06f, sunPaint);
        // 远景天际线（视差）
        drawSkyline(canvas, farPaint, viewH * 0.62f, viewW * 0.11f, 0.25f);
        drawSkyline(canvas, nearPaint, viewH * 0.74f, viewW * 0.16f, 0.5f);
        // 地面
        canvas.drawRect(0, groundY + camY, viewW, viewH, groundPaint);

        // 楼体
        for (int i = 0; i < tower.size(); i++) {
            Block b = tower.get(i);
            drawBlock(canvas, b.left, b.bottom + camY, b.width, i == tower.size() - 1);
        }

        // 吊钩与悬挂块 / 下落块
        if (!gameOver) {
            float left = falling != null ? falling.left : hangLeft();
            float w = topBlock().width;
            float bottom = falling != null ? falling.bottom + camY : topY() - bh * 1.1f + camY;
            float cx = left + w / 2f;
            // 吊绳
            canvas.drawLine(cx, 0, cx, bottom + bh, hookPaint);
            drawBlock(canvas, left, bottom, w, true);
        }

        // 坠物
        for (Debris d : debrisList) {
            canvas.save();
            canvas.translate(d.left + d.width / 2f, d.bottom + bh / 2f + camY);
            canvas.rotate(d.rot);
            canvas.translate(-d.width / 2f, -bh / 2f);
            canvas.drawRect(0, 0, d.width, bh, blockPaint);
            canvas.restore();
        }

        // HUD
        canvas.drawText("得分 " + score, viewW * 0.03f, viewH * 0.07f, hudPaint);
        canvas.drawText("层数 " + floors, viewW * 0.72f, viewH * 0.07f, hudPaint);

        // 连击提示
        if (comboFlash > 0 && combo > 0) {
            float a = Math.min(1f, comboFlash);
            comboPaint.setAlpha((int) (255 * a));
            canvas.drawText("完美 x" + combo + "!", viewW / 2f - comboPaint.measureText("完美 x" + combo + "!") / 2f,
                    viewH * 0.22f, comboPaint);
            comboPaint.setAlpha(255);
        }

        // 结算遮罩
        if (gameOver && crashTimer <= 0) {
            canvas.drawRect(0, 0, viewW, viewH, overlayPaint);
            String t1 = "楼塌了！";
            String t2 = "层数 " + floors + " · 得分 " + score;
            String t3 = perfectStreak > 0 ? "最高连击 x" + perfectStreak : "";
            String t4 = "按确认键再来一局";
            drawCenter(canvas, t1, viewH * 0.36f, overlayTextPaint);
            drawCenter(canvas, t2, viewH * 0.48f, hudPaint);
            if (!t3.isEmpty()) drawCenter(canvas, t3, viewH * 0.58f, hudPaint);
            drawCenter(canvas, t4, viewH * 0.72f, overlayTextPaint);
        }
    }

    private void drawCenter(Canvas canvas, String text, float y, Paint p) {
        canvas.drawText(text, viewW / 2f - p.measureText(text) / 2f, y, p);
    }

    private void drawSkyline(Canvas canvas, Paint paint, float base, float bw, float parallax) {
        float off = camY * parallax;
        float y0 = base + off;
        int n = (int) (viewW / bw) + 2;
        for (int i = 0; i < n; i++) {
            float hgt = bh * (3 + ((i * 37) % 7));
            canvas.drawRect(i * bw, y0 - hgt, i * bw + bw * 0.82f, viewH, paint);
        }
    }

    private void drawBlock(Canvas canvas, float left, float bottomScreen, float width, boolean isTop) {
        canvas.drawRect(left, bottomScreen, left + width, bottomScreen + bh, blockPaint);
        canvas.drawRect(left, bottomScreen + bh, left + width, bottomScreen + bh + bh * 0.18f, blockTopPaint);
        // 窗户
        float margin = width * 0.08f;
        float cols = Math.max(1, (int) ((width - margin * 2) / (bh * 0.32f)));
        float winW = (width - margin * 2) / cols;
        for (int c = 0; c < cols; c++) {
            canvas.drawRect(left + margin + c * winW + winW * 0.18f,
                    bottomScreen + bh * 0.28f,
                    left + margin + c * winW + winW * 0.82f,
                    bottomScreen + bh * 0.66f, winPaint);
        }
        if (isTop) {
            canvas.drawRect(left, bottomScreen + bh * 0.18f, left + width, bottomScreen + bh, flashPaint);
        }
    }
}
