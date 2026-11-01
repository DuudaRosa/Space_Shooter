import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/** Space Shooter: menu inicial e HUD futurista. WASD/setas: mover; ESPAÇO: atirar; P: pausar. */
public class Main extends Canvas implements Runnable, KeyListener, MouseListener, MouseMotionListener {
    private static final long serialVersionUID = 1L;
    private static final int W0 = 800, H0 = 600, FPS_ALVO = 60;
    private static final double PLAYER_SPEED_FACTOR = 0.90;
    private static final double PLAYER_ACCELERATION = 2200 * PLAYER_SPEED_FACTOR;
    private static final double PLAYER_MAX_SPEED = 700 * PLAYER_SPEED_FACTOR;
    private static final double COAST_DISTANCE_LIMIT = 10.0;
    private static final double PLAYER_RENDER_SCALE = 1.15;
    private static final double HUD_SCALE_REDUCTION = 0.90;
    private static final double HUD_OPACITY_FACTOR = 0.90;
    private static final int MAX_LIVES = 3;
    private static final double INVULNERABILITY_SECONDS = 1.35;
    private static final double DAMAGE_SHAKE_SECONDS = 0.20;
    private static final double DAMAGE_SHAKE_PIXELS = 3.5;
    private static final double ENEMY_BASE_MIN_SPEED = 80.0, ENEMY_BASE_MAX_SPEED = 160.0;
    private static final int[] PHASE_ENEMY_COUNTS = {3, 5, 7, 9, 12};
    private static final double[] PHASE_SPEED_MULTIPLIERS = {0.80, 0.90, 1.00, 1.10, 1.20};
    private static final String PLAYER_ASSET_PATH = "assets/images/player.png";
    private static final String[] ENEMY_ASSET_PATHS = {
            "assets/images/enemies/enemy_1.png",
            "assets/images/enemies/enemy_2.png",
            "assets/images/enemies/enemy_3.png"
    };
    private static final BufferedImage PLAYER_ASSET = load64pxAsset(PLAYER_ASSET_PATH);
    private static final BufferedImage[] ENEMY_ASSETS = {
            load64pxAsset(ENEMY_ASSET_PATHS[0]), load64pxAsset(ENEMY_ASSET_PATHS[1]),
            load64pxAsset(ENEMY_ASSET_PATHS[2])
    };
    private static final String TITULO = "SPACE SHOOTER // COMBAT SYSTEM";
    private static final Color CYAN = new Color(83, 225, 255);
    private static final Color HUD_MAIN = new Color(255, 182, 217);
    private static final Color HUD_LINE = new Color(255, 200, 229);
    private static final Color HUD_ACCENT = new Color(255, 143, 199);
    private static final Color HUD_TEXT = new Color(255, 239, 248);
    private static final Color HUD_MUTED = new Color(222, 177, 202);
    private static final Color HUD_PANEL = new Color(0x16, 0x08, 0x17,
            (int)Math.round(218 * HUD_OPACITY_FACTOR));

    private enum GameState { MENU, PLAYING, PAUSED, VICTORY, GAME_OVER }
    private volatile boolean running;
    private volatile GameState gameState = GameState.MENU;
    private boolean left, right, up, down, firing;
    private boolean movementKeysActive;
    private double coastDistanceRemaining;
    private boolean radarOn = true, statsOn;
    private int fps, hovered = -1, hoveredMenuButton = -1, pressedMenuButton = -1;
    private int hoveredEndButton = -1, pressedEndButton = -1;
    private transient Thread gameThread;
    private transient JFrame hostFrame;
    private final Random rng = new Random();
    private final ArrayList<Enemy> enemies = new ArrayList<>();
    private final ArrayList<Bullet> bullets = new ArrayList<>();
    private final ArrayList<Explosion> explosions = new ArrayList<>();
    private final ArrayList<Trail> trail = new ArrayList<>();
    private final double[] starX = new double[110], starY = new double[110], starSpeed = new double[110];
    private transient Player player;
    private double trailTimer, elapsed, phaseSpawnTimer;
    private double worldW = W0, worldH = H0;
    private int currentPhase = 1;
    private int phaseEnemiesPendingSpawn, phaseEnemiesSpawned;
    private static final double PHASE_SPAWN_INTERVAL = 1.5;
    private int lives = MAX_LIVES;
    private double invulnerabilityRemaining;
    private long damageShakeStartedNanos;

    @SuppressWarnings("this-escape")
    public Main() {
        setPreferredSize(new Dimension(W0, H0));
        setMinimumSize(new Dimension(640, 480));
        setBackground(new Color(5, 14, 28));
        setFocusable(true);
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        player = new Player(W0 / 2.0 - 35, H0 - 135);
        for (int i = 0; i < starX.length; i++) {
            starX[i] = rng.nextDouble() * W0;
            starY[i] = rng.nextDouble() * H0;
            starSpeed[i] = 24 + rng.nextDouble() * 74;
        }
    }

    private void startNewGame() {
        if (gameState != GameState.MENU && gameState != GameState.VICTORY
                && gameState != GameState.GAME_OVER) return;
        clearHeldInput();
        hoveredMenuButton = -1;
        pressedMenuButton = -1;
        hoveredEndButton = -1;
        pressedEndButton = -1;
        bullets.clear(); enemies.clear(); explosions.clear(); trail.clear();
        elapsed = 0; trailTimer = 0; phaseSpawnTimer = 0;
        currentPhase = 1;
        lives = MAX_LIVES;
        invulnerabilityRemaining = 0;
        damageShakeStartedNanos = 0;
        player.x = worldW / 2.0 - player.w / 2.0;
        player.y = worldH - 135;
        player.vx = 0; player.vy = 0; player.cooldown = 0;
        player.visualTilt = 0; player.visualTime = 0;
        movementKeysActive = false;
        coastDistanceRemaining = 0;
        gameState = GameState.PLAYING;
        beginPhase(1);
    }

    private void clearHeldInput() {
        boolean wasMovementActive = movementKeysActive;
        left = right = up = down = firing = false;
        movementKeysActive = false;
        if (wasMovementActive) {
            coastDistanceRemaining = Math.hypot(player.vx, player.vy) > 1e-9
                    ? COAST_DISTANCE_LIMIT : 0;
        } else if (coastDistanceRemaining <= 0 && Math.hypot(player.vx, player.vy) > 1e-9) {
            coastDistanceRemaining = COAST_DISTANCE_LIMIT;
        }
    }

    private boolean hasMovementInput() {
        return left || right || up || down;
    }

    private static BufferedImage load64pxAsset(String path) {
        try {
            BufferedImage image = null;
            File file = new File(path);
            if (file.isFile()) image = ImageIO.read(file);
            else {
                URL resource = Main.class.getClassLoader().getResource(path);
                if (resource != null) image = ImageIO.read(resource);
            }
            if (image == null) {
                System.out.println("[assets] ausente: " + path + " (será usado o sprite vetorial original)");
                return null;
            }
            if (image.getWidth() != 64 || image.getHeight() != 64) {
                System.err.println("[assets] ignorado: " + path + " precisa ter exatamente 64x64 px");
                return null;
            }
            return image;
        } catch (IOException ex) {
            System.err.println("[assets] falha ao ler " + path + ": " + ex.getMessage());
            return null;
        }
    }

    private void beginPhase(int phase) {
        if (phase < 1 || phase > PHASE_ENEMY_COUNTS.length) return;
        currentPhase = phase;
        enemies.clear();
        phaseEnemiesPendingSpawn = PHASE_ENEMY_COUNTS[phase - 1];
        phaseEnemiesSpawned = 0;
        phaseSpawnTimer = 0;
    }

    private void spawnNextPhaseEnemy() {
        double maxX = Math.max(1, worldW - 60);
        double x = 20 + rng.nextDouble() * Math.max(1, maxX - 20);
        double baseSpeed = ENEMY_BASE_MIN_SPEED
                + rng.nextDouble() * (ENEMY_BASE_MAX_SPEED - ENEMY_BASE_MIN_SPEED);
        double speed = baseSpeed * PHASE_SPEED_MULTIPLIERS[currentPhase - 1];
        int hp = rng.nextBoolean() ? 1 : 2;
        int type = phaseEnemiesSpawned % ENEMY_ASSETS.length;
        enemies.add(new Enemy(x, 135, speed, hp, type));
        phaseEnemiesSpawned++;
        phaseEnemiesPendingSpawn--;
        phaseSpawnTimer = PHASE_SPAWN_INTERVAL;
    }

    public synchronized void startGame() {
        if (running) return;
        running = true;
        gameThread = new Thread(this, "space-shooter-loop");
        gameThread.start();
    }
    public synchronized void stopGame() {
        running = false;
        if (gameThread != null) {
            try { gameThread.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public void run() {
        final long frameNs = 1_000_000_000L / FPS_ALVO;
        long previous = System.nanoTime(), fpsStart = System.currentTimeMillis();
        int frameCount = 0;
        while (running) {
            long now = System.nanoTime();
            double dt = Math.min(0.05, (now - previous) / 1_000_000_000.0);
            previous = now;
            if (gameState == GameState.PLAYING) update(dt);
            else if (gameState == GameState.MENU) updateMenuBackground(dt);
            render();
            frameCount++;
            if (System.currentTimeMillis() - fpsStart >= 1000) {
                fps = frameCount; frameCount = 0; fpsStart = System.currentTimeMillis();
            }
            long wait = frameNs - (System.nanoTime() - now);
            if (wait > 0) try { Thread.sleep(wait / 1_000_000L); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private void update(double dt) {
        worldW = Math.max(1, getWidth());
        worldH = Math.max(1, getHeight());
        elapsed += dt;
        invulnerabilityRemaining = Math.max(0, invulnerabilityRemaining - dt);
        double accel = PLAYER_ACCELERATION, drag = Math.pow(0.98, dt * 60);
        if (left) player.vx -= accel * dt;
        if (right) player.vx += accel * dt;
        if (up) player.vy -= accel * dt;
        if (down) player.vy += accel * dt;
        player.vx *= drag; player.vy *= drag;
        double speed = Math.hypot(player.vx, player.vy);
        if (speed > PLAYER_MAX_SPEED) {
            double factor = PLAYER_MAX_SPEED / speed;
            player.vx *= factor;
            player.vy *= factor;
        }
        boolean hasMovementInput = hasMovementInput();
        double moveX = player.vx * dt, moveY = player.vy * dt;
        double requestedDistance = Math.hypot(moveX, moveY);
        if (!hasMovementInput) {
            if (coastDistanceRemaining <= 1e-9) {
                player.vx = 0;
                player.vy = 0;
                moveX = 0;
                moveY = 0;
            } else if (requestedDistance > coastDistanceRemaining) {
                double factor = coastDistanceRemaining / requestedDistance;
                moveX *= factor;
                moveY *= factor;
            }
        }
        double oldX = player.x, oldY = player.y;
        player.x = clamp(player.x + moveX, 0, worldW - player.w);
        player.y = clamp(player.y + moveY, 0, worldH - player.h);
        double actualDistance = Math.hypot(player.x - oldX, player.y - oldY);
        if (!hasMovementInput && coastDistanceRemaining > 0) {
            coastDistanceRemaining = Math.max(0, coastDistanceRemaining - actualDistance);
            if (coastDistanceRemaining <= 1e-9 || (requestedDistance > 1e-9 && actualDistance <= 1e-9)) {
                coastDistanceRemaining = 0;
                player.vx = 0;
                player.vy = 0;
            }
        }
        player.cooldown = Math.max(0, player.cooldown - dt);
        player.updateVisualMotion(dt);
        if (firing && player.cooldown <= 0) {
            bullets.add(new Bullet(player.x + player.w / 2.0 - 2, player.y - 12));
            player.cooldown = 0.16;
        }
        trailTimer += dt;
        if (trailTimer >= 0.045) {
            trailTimer = 0; trail.add(new Trail(player.x, player.y));
            if (trail.size() > 9) trail.remove(0);
        }
        for (Bullet b : bullets) { b.y -= b.speed * dt; if (b.y + b.h < 0) b.alive = false; }
        bullets.removeIf(b -> !b.alive);
        for (Enemy e : enemies) {
            e.age += dt; e.phase += 2 * dt; e.y += e.speed * dt;
            e.x = clamp(e.startX + Math.sin(e.phase) * e.amplitude, 0, worldW - e.w);
            if (e.y > worldH + 50) {
                e.y = 135;
                e.startX = 20 + rng.nextDouble() * Math.max(1, worldW - e.w - 40);
            }
        }
        if (invulnerabilityRemaining <= 0) {
            for (Enemy e : enemies) {
                if (e.alive && intersects(player.x, player.y, player.w, player.h,
                        e.x, e.y, e.w, e.h)) {
                    applyPlayerDamage();
                    break;
                }
            }
            if (gameState == GameState.GAME_OVER) return;
        }
        for (Bullet b : bullets) for (Enemy e : enemies) {
            if (b.alive && e.alive && intersects(b.x, b.y, b.w, b.h, e.x, e.y, e.w, e.h)) {
                b.alive = false;
                if (--e.hp <= 0) {
                    e.alive = false;
                    explosions.add(new Explosion(e.x + e.w / 2, e.y + e.h / 2, e.hpMax == 2 ? 82 : 58));
                }
            }
        }
        bullets.removeIf(b -> !b.alive);
        enemies.removeIf(e -> !e.alive);
        phaseSpawnTimer -= dt;
        if (phaseEnemiesPendingSpawn > 0 && phaseSpawnTimer <= 0) spawnNextPhaseEnemy();
        if (enemies.isEmpty() && phaseEnemiesPendingSpawn == 0) {
            if (currentPhase < PHASE_ENEMY_COUNTS.length) beginPhase(currentPhase + 1);
            else gameState = GameState.VICTORY;
        }
        for (Explosion ex : explosions) ex.age += dt;
        explosions.removeIf(ex -> ex.age > 0.52);
        updateStars(dt);
    }

    private void applyPlayerDamage() {
        if (gameState != GameState.PLAYING || invulnerabilityRemaining > 0 || lives <= 0) return;
        lives--;
        damageShakeStartedNanos = System.nanoTime();
        if (lives == 0) {
            clearHeldInput();
            player.vx = 0;
            player.vy = 0;
            coastDistanceRemaining = 0;
            gameState = GameState.GAME_OVER;
            return;
        }

        invulnerabilityRemaining = INVULNERABILITY_SECONDS;
        player.x = clamp(worldW / 2.0 - player.w / 2.0, 0, worldW - player.w);
        player.y = clamp(worldH - 135, 0, worldH - player.h);
        player.vx = 0;
        player.vy = 0;
        player.visualTilt = 0;
        coastDistanceRemaining = 0;
        movementKeysActive = hasMovementInput();
        trail.clear();
    }

    private double[] damageShakeOffset() {
        if (damageShakeStartedNanos == 0) return new double[]{0, 0};
        double elapsedSeconds = (System.nanoTime() - damageShakeStartedNanos) / 1_000_000_000.0;
        double progress = elapsedSeconds / DAMAGE_SHAKE_SECONDS;
        if (progress >= 1) return new double[]{0, 0};
        double envelope = (1 - progress) * (1 - progress);
        double phase = elapsedSeconds * 82;
        double x = (Math.sin(phase) * .7 + Math.sin(phase * 1.71 + 1.3) * .3)
                * DAMAGE_SHAKE_PIXELS * envelope;
        double y = (Math.cos(phase * 1.13) * .65 + Math.sin(phase * 1.93 + .7) * .35)
                * DAMAGE_SHAKE_PIXELS * envelope;
        return new double[]{x, y};
    }

    private void updateMenuBackground(double dt) {
        worldW = Math.max(1, getWidth());
        worldH = Math.max(1, getHeight());
        updateStars(dt);
    }

    private void updateStars(double dt) {
        for (int i = 0; i < starY.length; i++) {
            starY[i] += starSpeed[i] * dt;
            if (starY[i] > worldH) { starY[i] = 0; starX[i] = rng.nextDouble() * worldW; }
        }
    }

    private void render() {
        BufferStrategy bs = getBufferStrategy();
        if (bs == null) { createBufferStrategy(3); return; }
        do {
            do {
                Graphics2D g = (Graphics2D) bs.getDrawGraphics();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    if (gameState == GameState.MENU) {
                        drawBackground(g);
                        drawMainMenu(g);
                    } else {
                        drawWorld(g);
                        drawHud(g);
                    }
                } finally { g.dispose(); }
            } while (bs.contentsRestored());
            bs.show();
        } while (bs.contentsLost());
    }

    private void drawWorld(Graphics2D g) {
        Graphics2D world = (Graphics2D)g.create();
        double[] shake = damageShakeOffset();
        drawBackground(world);
        world.translate(shake[0], shake[1]);
        for (Bullet b : bullets) { world.setColor(CYAN); world.fillRoundRect((int)b.x, (int)b.y, b.w, b.h, 4, 4); }
        for (Enemy e : enemies) e.draw(world);
        for (int i = 0; i < trail.size(); i++) {
            Trail t = trail.get(i); float alpha = (i + 1f) / trail.size() * .20f;
            Composite old = world.getComposite(); world.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            player.drawAt(world, t.x, t.y); world.setComposite(old);
        }
        if (invulnerabilityRemaining <= 0 || (((int)(invulnerabilityRemaining * 12) & 1) == 0)) {
            player.draw(world);
        }
        for (Explosion ex : explosions) ex.draw(world);
        world.dispose();
    }

    private void drawBackground(Graphics2D g) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        g.setPaint(new GradientPaint(0, 0, new Color(8, 20, 39), 0, h, new Color(11, 30, 48)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(156, 220, 255, 175));
        for (int i = 0; i < starX.length; i++) {
            int d = starSpeed[i] > 65 ? 3 : 2;
            g.fillOval((int) starX[i], (int) starY[i], d, d);
        }
    }

    private Rectangle menuButtonBounds(int index) {
        int width = Math.min(300, Math.max(220, getWidth() - 80));
        int height = 54;
        int x = (getWidth() - width) / 2;
        int y = getHeight() / 2 + 12 + index * 70;
        return new Rectangle(x, y, width, height);
    }

    private void drawMainMenu(Graphics2D g) {
        int w = Math.max(1, getWidth()), h = Math.max(1, getHeight());
        Graphics2D menu = (Graphics2D) g.create();
        menu.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        menu.setColor(new Color(3, 12, 25, 152));
        menu.fillRect(0, 0, w, h);
        menu.setStroke(new BasicStroke(1f));
        menu.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), 70));
        int inset = Math.max(18, Math.min(54, w / 14));
        menu.drawLine(inset, 42, inset + 110, 42);
        menu.drawLine(w - inset - 110, h - 42, w - inset, h - 42);
        menu.drawLine(inset, 42, inset, 88);
        menu.drawLine(w - inset, h - 88, w - inset, h - 42);

        centered(menu, "SPACE SHOOTER", w / 2, h / 2 - 92, 27, HUD_MAIN);
        centered(menu, "COMBAT SYSTEM  /  5 FASES", w / 2, h / 2 - 64, 10, HUD_LINE);
        centered(menu, "WASD / SETAS: MOVER     •     ESPAÇO: DISPARAR", w / 2, h / 2 - 42, 9, HUD_MUTED);
        for (int i = 0; i < 2; i++) {
            Rectangle r = menuButtonBounds(i);
            boolean hot = hoveredMenuButton == i;
            boolean pressed = pressedMenuButton == i;
            int fillAlpha = hot ? 210 : 172;
            menu.setColor(pressed ? new Color(74, 31, 59, 222)
                    : hot ? new Color(71, 29, 55, fillAlpha) : new Color(27, 12, 29, fillAlpha));
            menu.fillRoundRect(r.x, r.y, r.width, r.height, 9, 9);
            menu.setColor(hot ? HUD_MAIN : new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), 150));
            menu.setStroke(new BasicStroke(hot ? 2f : 1.2f));
            menu.drawRoundRect(r.x, r.y, r.width, r.height, 9, 9);
            centered(menu, i == 0 ? "INICIAR" : "SAIR", w / 2, r.y + 34,
                    15, hot ? Color.WHITE : HUD_LINE);
            if (hot) {
                menu.setColor(HUD_ACCENT);
                menu.drawLine(r.x + 18, r.y + r.height / 2, r.x + 38, r.y + r.height / 2);
                menu.drawLine(r.x + r.width - 38, r.y + r.height / 2,
                        r.x + r.width - 18, r.y + r.height / 2);
            }
        }
        centered(menu, "ENTER: INICIAR    •    ESC: SAIR", w / 2, h - 28, 9, HUD_MUTED);
        menu.dispose();
    }

    private void drawHud(Graphics2D g) {
        double scale = hudScale();
        Graphics2D hud = (Graphics2D) g.create();
        hud.scale(scale, scale);
        int w = (int) Math.ceil(getWidth() / scale), h = (int) Math.ceil(getHeight() / scale);
        drawTopPanels(hud, w);
        drawActionGrid(hud, w, h);
        drawContextPanel(hud, w, h);
        if (radarOn) drawRadar(hud, w, h);
        if (statsOn) drawStats(hud, w, h);
        if (gameState == GameState.VICTORY) drawVictoryOverlay(hud, w, h);
        else if (gameState == GameState.GAME_OVER) drawGameOverOverlay(hud, w, h);
        else if (gameState == GameState.PAUSED) drawPauseOverlay(hud, w, h);
        hud.dispose();
    }

    private double hudScale() {
        return clamp(Math.min(getWidth() / (double) W0, getHeight() / (double) H0), .85, 1.35)
                * HUD_SCALE_REDUCTION;
    }

    private void drawTopPanels(Graphics2D g, int w) {
        int pw = Math.min(222, Math.max(174, w / 3 - 20));
        panel(g, 16, 16, pw, 82, true);
        label(g, "PROPULSÃO / VELOCIDADE", 29, 35, 10, HUD_MUTED);
        double speed = Math.hypot(player.vx, player.vy);
        value(g, String.format(Locale.US, "%03d", Math.round(speed)), 29, 64, 24, HUD_MAIN);
        label(g, "u/s", 82, 63, 10, HUD_LINE);
        segmentedBar(g, 29, 76, pw - 54, 8, speed / PLAYER_MAX_SPEED, HUD_MAIN, 14);
        label(g, "VIDAS", 16 + pw - 68, 35, 8, HUD_MUTED);
        for (int i = 0; i < MAX_LIVES; i++) drawHeart(g, 16 + pw - 68 + i * 18, 44, 13, i < lives);
        int rx = w - pw - 16;
        panel(g, rx, 16, pw, 82, true);
        label(g, "CONTATOS DETECTADOS", rx + 14, 35, 10, HUD_MUTED);
        value(g, String.format(Locale.US, "%02d", enemies.size()), rx + 14, 64, 24, HUD_MAIN);
        label(g, "ALVOS", rx + 58, 63, 10, HUD_LINE);
        label(g, "SISTEMA", rx + pw - 67, 35, 9, HUD_MUTED);
        value(g, String.format(Locale.US, "%02d FPS", fps), rx + pw - 83, 61, 12, HUD_TEXT);
        int cw = Math.min(194, Math.max(166, w / 4));
        int cx = w / 2 - cw / 2;
        panel(g, cx, 10, cw, 100, true);
        g.setColor(new Color(HUD_MAIN.getRed(), HUD_MAIN.getGreen(), HUD_MAIN.getBlue(), hudAlpha(255)));
        g.setStroke(new BasicStroke(1.4f));
        Polygon p = new Polygon(new int[]{cx + 10, cx + cw - 10, cx + cw - 20, cx + cw - 20, cx + 12},
                new int[]{14, 14, 25, 92, 92}, 5); g.drawPolygon(p);
        centered(g, "LEVEL", w / 2, 34, 9, HUD_LINE);
        centered(g, String.format(Locale.US, "%02d", currentPhase), w / 2, 64, 23, HUD_MAIN);
        String status = gameState == GameState.VICTORY ? "VITÓRIA"
                : gameState == GameState.GAME_OVER ? "GAME OVER" : gameState == GameState.PAUSED ? "PAUSADO"
                : String.format(Locale.US, "ALVOS %02d / %02d", enemies.size() + phaseEnemiesPendingSpawn,
                        PHASE_ENEMY_COUNTS[currentPhase - 1]);
        Color statusColor = gameState == GameState.GAME_OVER ? HUD_ACCENT
                : gameState == GameState.PAUSED ? HUD_MAIN
                : gameState == GameState.VICTORY ? HUD_LINE : HUD_MUTED;
        centered(g, status, w / 2, 87, 8, statusColor);
    }

    private void drawHeart(Graphics2D g, int x, int y, int size, boolean active) {
        Path2D heart = new Path2D.Double();
        heart.moveTo(x + size * .5, y + size * .94);
        heart.curveTo(x + size * .39, y + size * .80, x, y + size * .52, x, y + size * .31);
        heart.curveTo(x, y + size * .03, x + size * .36, y - size * .04, x + size * .5, y + size * .21);
        heart.curveTo(x + size * .64, y - size * .04, x + size, y + size * .03, x + size, y + size * .31);
        heart.curveTo(x + size, y + size * .52, x + size * .61, y + size * .80, x + size * .5, y + size * .94);
        heart.closePath();
        if (active) {
            g.setColor(HUD_ACCENT);
            g.fill(heart);
            g.setColor(HUD_LINE);
        } else {
            g.setColor(new Color(HUD_ACCENT.getRed(), HUD_ACCENT.getGreen(), HUD_ACCENT.getBlue(), hudAlpha(40)));
            g.fill(heart);
            g.setColor(HUD_MUTED);
        }
        g.setStroke(new BasicStroke(1f));
        g.draw(heart);
    }

    private void drawActionGrid(Graphics2D g, int w, int h) {
        int x = 16, y = h - 58, bw = 82, bh = 40, gap = 7;
        String[] names = {gameState == GameState.PAUSED ? "RETOMAR" : "PAUSAR", radarOn ? "RADAR ON" : "RADAR OFF", statsOn ? "DADOS ON" : "DADOS"};
        String[] icons = {gameState == GameState.PAUSED ? ">" : "II", "◎", "▤"};
        String[] tips = {"P", "R", "I"};
        for (int i = 0; i < 3; i++) {
            int bx = x + i * (bw + gap), by = y;
            boolean hot = hovered == i;
            g.setColor(hot ? new Color(71, 29, 55, hudAlpha(235)) : HUD_PANEL); g.fillRoundRect(bx, by, bw, bh, 7, 7);
            g.setColor(hot ? HUD_MAIN : new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(125)));
            g.setStroke(new BasicStroke(hot ? 1.6f : 1f));
            g.drawRoundRect(bx, by, bw, bh, 7, 7);
            text(g, icons[i], bx + 8, by + 24, 15, hot ? HUD_MAIN : HUD_LINE);
            text(g, names[i], bx + 27, by + 23, 8, HUD_TEXT);
            text(g, tips[i], bx + bw - 11, by + 12, 8, HUD_MUTED);
        }
        label(g, "ATALHOS", x + 2, y - 7, 8, HUD_MUTED);
    }

    private void drawContextPanel(Graphics2D g, int w, int h) {
        int bw = Math.min(276, Math.max(210, w - 420));
        int x = (w - bw) / 2, y = h - 78;
        panel(g, x, y, bw, 62, true);
        label(g, "SISTEMA DE ARMAS", x + 13, y + 18, 8, HUD_MUTED);
        text(g, "CANHÃO DE ENERGIA  /  AUTOMÁTICO", x + 13, y + 36, 10, HUD_MAIN);
        text(g, "MUNIÇÃO ∞   •   ESPAÇO: DISPARAR", x + 13, y + 51, 9, HUD_LINE);
        g.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(90)));
        g.drawLine(x + bw - 46, y + 15, x + bw - 18, y + 15);
        g.drawLine(x + bw - 30, y + 15, x + bw - 30, y + 30);
    }

    private void drawRadar(Graphics2D g, int w, int h) {
        int rw = Math.min(184, Math.max(160, w / 4)), rh = 112, x = w - rw - 16, y = h - rh - 17;
        panel(g, x, y, rw, rh, false);
        label(g, "RADAR TÁTICO", x + 12, y + 16, 8, HUD_MUTED);
        int px = x + 10, py = y + 24, pw = rw - 20, ph = rh - 35;
        g.setColor(new Color(0x16, 0x08, 0x17, hudAlpha(180))); g.fillRoundRect(px, py, pw, ph, 8, 8);
        g.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(75)));
        g.drawRoundRect(px, py, pw, ph, 8, 8);
        g.drawLine(px + pw/2, py + 4, px + pw/2, py + ph - 4);
        g.drawLine(px + 4, py + ph/2, px + pw - 4, py + ph/2);
        g.setColor(HUD_MAIN); g.fillOval(px + (int)(clamp(player.x / worldW, 0, 1) * pw) - 3,
                py + (int)(clamp(player.y / worldH, 0, 1) * ph) - 3, 7, 7);
        g.setColor(HUD_ACCENT);
        for (Enemy e : enemies) {
            int ex = px + (int)(clamp(e.x / worldW, 0, 1) * pw);
            int ey = py + (int)(clamp(e.y / worldH, 0, 1) * ph);
            g.fillOval(ex - 2, ey - 2, 5, 5);
        }
    }

    private void drawStats(Graphics2D g, int w, int h) {
        int x = 16, y = h - 247;
        panel(g, x, y, 190, 112, false);
        label(g, "TELEMETRIA", x + 12, y + 18, 9, HUD_MAIN);
        text(g, "PROJÉTEIS ATIVOS  " + bullets.size(), x + 12, y + 39, 10, HUD_TEXT);
        text(g, "ALVOS NA TELA    " + enemies.size(), x + 12, y + 57, 10, HUD_TEXT);
        text(g, "VELOCIDADE   " + Math.round(Math.hypot(player.vx, player.vy)) + " u/s", x + 12, y + 75, 10, HUD_TEXT);
        text(g, "TEMPO DE VOO  " + (int)elapsed + " s", x + 12, y + 93, 10, HUD_MUTED);
    }

    private void drawPauseOverlay(Graphics2D g, int w, int h) {
        g.setColor(new Color(0x16, 0x08, 0x17, hudAlpha(112))); g.fillRect(0, 0, w, h);
        int bw = 230, bh = 78, x = w/2 - bw/2, y = h/2 - bh/2;
        panel(g, x, y, bw, bh, true);
        centered(g, "JOGO PAUSADO", w/2, y + 32, 17, HUD_MAIN);
        centered(g, "P ou clique em RETOMAR", w/2, y + 54, 10, HUD_LINE);
    }

    private void drawVictoryOverlay(Graphics2D g, int w, int h) {
        g.setColor(new Color(0x16, 0x08, 0x17, hudAlpha(150))); g.fillRect(0, 0, w, h);
        int bw = 330, bh = 112, x = w/2 - bw/2, y = h/2 - bh/2;
        panel(g, x, y, bw, bh, true);
        centered(g, "VITÓRIA", w/2, y + 39, 22, HUD_MAIN);
        centered(g, "TODAS AS 5 FASES CONCLUÍDAS", w/2, y + 65, 10, HUD_LINE);
        centered(g, "COMBATE ENCERRADO", w/2, y + 88, 9, HUD_MUTED);
    }

    private Rectangle gameOverButtonBounds(int index, int w, int h) {
        int buttonWidth = 146, buttonHeight = 44, gap = 12;
        int totalWidth = buttonWidth * 2 + gap;
        int x = w / 2 - totalWidth / 2 + index * (buttonWidth + gap);
        int y = h / 2 + 24;
        return new Rectangle(x, y, buttonWidth, buttonHeight);
    }

    private void drawGameOverOverlay(Graphics2D g, int w, int h) {
        g.setColor(new Color(10, 3, 12, hudAlpha(220)));
        g.fillRect(0, 0, w, h);
        int panelWidth = 370, panelHeight = 170;
        int x = w / 2 - panelWidth / 2, y = h / 2 - panelHeight / 2;
        panel(g, x, y, panelWidth, panelHeight, true);
        centered(g, "GAME OVER", w / 2, y + 40, 23, HUD_ACCENT);
        centered(g, "VOCÊ PERDEU TODAS AS VIDAS", w / 2, y + 67, 10, HUD_LINE);
        centered(g, "FASE " + currentPhase, w / 2, y + 87, 9, HUD_MUTED);
        for (int i = 0; i < 2; i++) {
            Rectangle button = gameOverButtonBounds(i, w, h);
            boolean hot = hoveredEndButton == i;
            boolean pressed = pressedEndButton == i;
            g.setColor(pressed ? new Color(74, 31, 59, 222)
                    : hot ? new Color(71, 29, 55, hudAlpha(210)) : new Color(27, 12, 29, hudAlpha(172)));
            g.fillRoundRect(button.x, button.y, button.width, button.height, 9, 9);
            g.setColor(hot ? HUD_MAIN : new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(150)));
            g.setStroke(new BasicStroke(hot ? 1.8f : 1f));
            g.drawRoundRect(button.x, button.y, button.width, button.height, 9, 9);
            centered(g, i == 0 ? "REINICIAR" : "MENU INICIAL", button.x + button.width / 2,
                    button.y + 27, 9, hot ? Color.WHITE : HUD_LINE);
        }
    }

    // ---------- Ferramentas visuais compartilhadas pela HUD ----------
    private static void panel(Graphics2D g, int x, int y, int w, int h, boolean angular) {
        g.setColor(HUD_PANEL); g.fillRoundRect(x, y, w, h, 10, 10);
        g.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(135)));
        g.setStroke(new BasicStroke(1f));
        if (angular) {
            Polygon p = new Polygon(new int[]{x+1,x+w-14,x+w-1,x+w-1,x+14,x+1},
                    new int[]{y+1,y+1,y+14,y+h-1,y+h-1,y+h-14}, 6);
            g.drawPolygon(p);
            g.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(75)));
            g.drawLine(x+10,y+h-8,x+30,y+h-8); g.drawLine(x+w-30,y+8,x+w-10,y+8);
        } else g.drawRoundRect(x, y, w, h, 10, 10);
    }
    private static void segmentedBar(Graphics2D g, int x, int y, int w, int h, double amount, Color color, int n) {
        double f = clamp(amount, 0, 1); int gap = 2, sw = (w - (n-1)*gap) / n;
        for (int i=0; i<n; i++) {
            int sx=x+i*(sw+gap); g.setColor(new Color(HUD_LINE.getRed(), HUD_LINE.getGreen(), HUD_LINE.getBlue(), hudAlpha(35))); g.fillRect(sx,y,sw,h);
            if (f >= (i+1)/(double)n) { g.setColor(color); g.fillRect(sx,y,sw,h); }
            else if (f > i/(double)n) { g.setColor(color); g.fillRect(sx,y,(int)(sw*(f*n-i)),h); }
        }
    }
    private static void label(Graphics2D g, String s, int x, int y, int size, Color c) {
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size)); g.setColor(c); g.drawString(s,x,y);
    }
    private static void value(Graphics2D g, String s, int x, int y, int size, Color c) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size)); g.setColor(c); g.drawString(s,x,y);
    }
    private static void text(Graphics2D g, String s, int x, int y, int size, Color c) {
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size)); g.setColor(c); g.drawString(s,x,y);
    }
    private static void centered(Graphics2D g, String s, int cx, int y, int size, Color c) {
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, size)); g.setColor(c);
        FontMetrics fm=g.getFontMetrics(); g.drawString(s,cx-fm.stringWidth(s)/2,y);
    }
    private static double clamp(double v, double min, double max) { return Math.max(min,Math.min(max,v)); }
    private static int hudAlpha(int originalAlpha) {
        return (int)Math.round(originalAlpha * HUD_OPACITY_FACTOR);
    }
    private static boolean intersects(double ax,double ay,double aw,double ah,double bx,double by,double bw,double bh) {
        return ax < bx+bw && ax+aw > bx && ay < by+bh && ay+ah > by;
    }

    // ---------- Entidades do jogo ----------
    private static final class Player {
        double x,y,vx,vy,cooldown,visualTilt,visualTime; final int w=70,h=86;
        final BufferedImage sprite = makeShip();
        Player(double x,double y) { this.x=x; this.y=y; }
        void updateVisualMotion(double dt) {
            visualTime += dt;
            double targetTilt = clamp(vx / PLAYER_MAX_SPEED, -1, 1) * Math.toRadians(12);
            double smoothing = 1 - Math.exp(-dt / 0.10);
            visualTilt += (targetTilt - visualTilt) * smoothing;
        }
        void draw(Graphics2D g) { drawAt(g,x,y); }
        void drawAt(Graphics2D g,double dx,double dy) {
            Graphics2D ship = (Graphics2D)g.create();
            double bob = Math.hypot(vx, vy) > 12 ? Math.sin(visualTime * 8) * 1.1 : 0;
            ship.translate(dx + w / 2.0, dy + h / 2.0 + bob);
            ship.rotate(visualTilt);
            if (PLAYER_ASSET != null) {
                int size = (int)Math.round(64 * PLAYER_RENDER_SCALE);
                ship.drawImage(PLAYER_ASSET, -size / 2, -size / 2, size, size, null);
            } else {
                int drawW = (int)Math.round(w * PLAYER_RENDER_SCALE);
                int drawH = (int)Math.round(h * PLAYER_RENDER_SCALE);
                ship.drawImage(sprite, -drawW / 2, -drawH / 2, drawW, drawH, null);
            }
            ship.dispose();
        }
        private static BufferedImage makeShip() {
            BufferedImage im=new BufferedImage(70,86,BufferedImage.TYPE_INT_ARGB);
            Graphics2D g=im.createGraphics(); g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(42,65,82)); g.fillRoundRect(5,35,60,16,8,8);
            g.fillOval(19,17,32,52); g.fillOval(25,6,20,31);
            g.setColor(new Color(25,34,44)); g.fillRoundRect(9,61,13,17,5,5); g.fillRoundRect(48,61,13,17,5,5);
            g.setColor(new Color(255,105,24,220)); g.fillOval(11,70,9,16); g.fillOval(50,70,9,16);
            g.setColor(new Color(123,211,231)); g.fillRect(33,20,3,38); g.fillOval(28,15,14,18);
            g.setColor(new Color(210,246,255)); g.fillOval(30,13,10,15); g.dispose(); return im;
        }
    }
    private static final class Enemy {
        double x,y,startX,speed,phase,amplitude,age; int hp,hpMax; final int type; final int w=40,h=34; boolean alive=true;
        Enemy(double x,double y,double speed,int hp,int type) {
            this.x=x;this.y=y;this.startX=x;this.speed=speed;this.hp=hp;this.hpMax=hp;this.type=type;
            amplitude=25+Math.random()*45;phase=Math.random()*Math.PI*2;
        }
        void draw(Graphics2D g) {
            int hover=(int)(Math.sin(phase*1.5)*2); int yy=(int)y+hover;
            BufferedImage asset = ENEMY_ASSETS[type];
            if (asset != null) {
                g.drawImage(asset, (int)(x + (w - 64) / 2.0), yy + (h - 64) / 2, 64, 64, null);
            } else {
                Color body = type == 0 ? new Color(0,107,187)
                        : type == 1 ? new Color(184,58,62) : new Color(125,77,184);
                g.setColor(body); g.fillOval((int)x,yy,w,h);
                g.setColor(type == 2 ? new Color(180,255,126) : new Color(255,222,102));
                g.fillOval((int)x+7,yy+9,10,10); g.fillOval((int)x+23,yy+9,10,10);
                g.setColor(new Color(20,28,37));
                g.fillOval((int)x+10,yy+12,5,5); g.fillOval((int)x+26,yy+12,5,5);
            }
            if (hpMax > 1) {
                int barX = (int)(x + (w - 64) / 2.0), barY = yy + (h - 64) / 2 - 5;
                g.setColor(new Color(4,15,27,190)); g.fillRect(barX,barY,64,3);
                g.setColor(CYAN); g.fillRect(barX,barY,64*hp/hpMax,3);
            }
        }
    }
    private static final class Bullet {
        double x,y; final int w=4,h=12; final double speed=440; boolean alive=true;
        Bullet(double x,double y){this.x=x;this.y=y;}
    }
    private static final class Trail { final double x,y; Trail(double x,double y){this.x=x;this.y=y;} }
    private static final class Explosion {
        final double x,y; final int size; double age;
        Explosion(double x,double y,int size){this.x=x;this.y=y;this.size=size;}
        void draw(Graphics2D g){
            float p=(float)Math.min(1,age/.52); float alpha=1-p; int r=(int)(size*(.18+.42*p));
            Composite old=g.getComposite();g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,alpha));
            g.setColor(new Color(255,126,29));g.fillOval((int)x-r,(int)y-r,2*r,2*r);
            g.setColor(new Color(255,238,165));g.fillOval((int)x-r/2,(int)y-r/2,r,r);
            g.setColor(new Color(255,203,80));g.setStroke(new BasicStroke(2));
            for(int i=0;i<8;i++){double a=i*Math.PI/4;g.drawLine((int)x,(int)y,(int)(x+Math.cos(a)*r*1.35),(int)(y+Math.sin(a)*r*1.35));}
            g.setComposite(old);
        }
    }

    // ---------- Entrada e botões clicáveis ----------
    private void togglePause() {
        if (gameState == GameState.PLAYING) {
            gameState = GameState.PAUSED;
            clearHeldInput();
        } else if (gameState == GameState.PAUSED) gameState = GameState.PLAYING;
    }

    private int menuButtonAt(int mx, int my) {
        for (int i = 0; i < 2; i++) if (menuButtonBounds(i).contains(mx, my)) return i;
        return -1;
    }

    private void activateMenuButton(int button) {
        if (button == 0) startNewGame();
        else if (button == 1) exitGame();
    }

    private void exitGame() {
        if (hostFrame != null) {
            hostFrame.dispatchEvent(new WindowEvent(hostFrame, WindowEvent.WINDOW_CLOSING));
        } else stopGame();
    }

    private void returnToMenu() {
        clearHeldInput();
        hoveredEndButton = -1;
        pressedEndButton = -1;
        bullets.clear(); enemies.clear(); explosions.clear(); trail.clear();
        phaseEnemiesPendingSpawn = 0;
        invulnerabilityRemaining = 0;
        coastDistanceRemaining = 0;
        player.vx = 0; player.vy = 0;
        gameState = GameState.MENU;
    }

    private int gameOverButtonAt(int mx, int my) {
        double scale = hudScale();
        int logicalWidth = (int)(getWidth() / scale), logicalHeight = (int)(getHeight() / scale);
        mx = (int)(mx / scale); my = (int)(my / scale);
        for (int i = 0; i < 2; i++) {
            if (gameOverButtonBounds(i, logicalWidth, logicalHeight).contains(mx, my)) return i;
        }
        return -1;
    }

    private void activateGameOverButton(int button) {
        if (button == 0) startNewGame();
        else if (button == 1) returnToMenu();
    }

    private void activateAt(int mx,int my) {
        if (gameState != GameState.PLAYING && gameState != GameState.PAUSED) return;
        double scale=hudScale(); mx=(int)(mx/scale); my=(int)(my/scale);
        int logicalHeight=(int)(getHeight()/scale);
        int x=16,y=logicalHeight-58,bw=82,bh=40,gap=7;
        for(int i=0;i<3;i++){
            int bx=x+i*(bw+gap),by=y;
            if(mx>=bx&&mx<=bx+bw&&my>=by&&my<=by+bh){
                if(i==0)togglePause(); else if(i==1)radarOn=!radarOn;
                else statsOn=!statsOn;
                return;
            }
        }
    }
    @Override public void keyPressed(KeyEvent e) {
        int k=e.getKeyCode();
        if (gameState == GameState.MENU) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_SPACE) startNewGame();
            else if (k == KeyEvent.VK_ESCAPE || k == KeyEvent.VK_Q) exitGame();
            return;
        }
        if (gameState == GameState.VICTORY) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_R) startNewGame();
            else if (k == KeyEvent.VK_ESCAPE) gameState = GameState.MENU;
            return;
        }
        if (gameState == GameState.GAME_OVER) {
            if (k == KeyEvent.VK_ENTER || k == KeyEvent.VK_R) startNewGame();
            else if (k == KeyEvent.VK_ESCAPE) returnToMenu();
            return;
        }
        if(k==KeyEvent.VK_P){togglePause();return;}
        if(gameState != GameState.PLAYING)return;
        if(k==KeyEvent.VK_LEFT||k==KeyEvent.VK_A){left=true;markMovementKeyPressed();}
        if(k==KeyEvent.VK_RIGHT||k==KeyEvent.VK_D){right=true;markMovementKeyPressed();}
        if(k==KeyEvent.VK_UP||k==KeyEvent.VK_W){up=true;markMovementKeyPressed();}
        if(k==KeyEvent.VK_DOWN||k==KeyEvent.VK_S){down=true;markMovementKeyPressed();}
        if(k==KeyEvent.VK_SPACE)firing=true;
    }
    @Override public void keyReleased(KeyEvent e) {
        int k=e.getKeyCode();
        if(k==KeyEvent.VK_LEFT||k==KeyEvent.VK_A)left=false;
        if(k==KeyEvent.VK_RIGHT||k==KeyEvent.VK_D)right=false;
        if(k==KeyEvent.VK_UP||k==KeyEvent.VK_W)up=false;
        if(k==KeyEvent.VK_DOWN||k==KeyEvent.VK_S)down=false;
        if(k==KeyEvent.VK_SPACE)firing=false;
        if (movementKeysActive && !hasMovementInput()) {
            movementKeysActive = false;
            coastDistanceRemaining = Math.hypot(player.vx, player.vy) > 1e-9
                    ? COAST_DISTANCE_LIMIT : 0;
        }
    }
    private void markMovementKeyPressed() {
        movementKeysActive = true;
        coastDistanceRemaining = 0;
    }
    @Override public void keyTyped(KeyEvent e) { }
    @Override public void mouseClicked(MouseEvent e) {
        if (gameState != GameState.MENU) activateAt(e.getX(),e.getY());
        requestFocusInWindow();
    }
    @Override public void mouseMoved(MouseEvent e) {
        if (gameState == GameState.MENU) {
            hoveredMenuButton = menuButtonAt(e.getX(), e.getY());
            hoveredEndButton = -1;
            hovered = -1;
            return;
        }
        if (gameState == GameState.GAME_OVER) {
            hoveredEndButton = gameOverButtonAt(e.getX(), e.getY());
            hovered = -1; hoveredMenuButton = -1;
            return;
        }
        double scale=hudScale(); int mx=(int)(e.getX()/scale), my=(int)(e.getY()/scale);
        int x=16,y=(int)(getHeight()/scale)-58,bw=82,bh=40,gap=7; hovered=-1;
        for(int i=0;i<3;i++){int bx=x+i*(bw+gap),by=y;
            if(mx>=bx&&mx<=bx+bw&&my>=by&&my<=by+bh)hovered=i;}
        hoveredMenuButton = -1;
    }
    @Override public void mouseDragged(MouseEvent e) { mouseMoved(e); }
    @Override public void mousePressed(MouseEvent e) {
        if (gameState == GameState.MENU) {
            pressedMenuButton = menuButtonAt(e.getX(), e.getY());
            hoveredMenuButton = pressedMenuButton;
        } else if (gameState == GameState.GAME_OVER) {
            pressedEndButton = gameOverButtonAt(e.getX(), e.getY());
            hoveredEndButton = pressedEndButton;
        }
    }
    @Override public void mouseReleased(MouseEvent e) {
        if (gameState == GameState.MENU) {
            int releasedOn = menuButtonAt(e.getX(), e.getY());
            int action = pressedMenuButton;
            pressedMenuButton = -1;
            if (action >= 0 && action == releasedOn) activateMenuButton(action);
        } else if (gameState == GameState.GAME_OVER) {
            int releasedOn = gameOverButtonAt(e.getX(), e.getY());
            int action = pressedEndButton;
            pressedEndButton = -1;
            if (action >= 0 && action == releasedOn) activateGameOverButton(action);
        }
    }
    @Override public void mouseEntered(MouseEvent e) { }
    @Override public void mouseExited(MouseEvent e) { hovered=-1; hoveredMenuButton=-1; hoveredEndButton=-1; }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame=new JFrame(TITULO);
            Main game=new Main();
            game.hostFrame = frame;
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.add(game); frame.pack(); frame.setMinimumSize(new Dimension(640,480));
            frame.setLocationRelativeTo(null); frame.setVisible(true); game.requestFocusInWindow(); game.startGame();
            frame.addWindowListener(new WindowAdapter(){@Override public void windowClosing(WindowEvent e){game.stopGame();}});
        });
    }
}
