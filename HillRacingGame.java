import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Random;
import javax.sound.sampled.*;

public class HillRacingGame extends JPanel implements ActionListener, KeyListener {

    // ══════════════════════════════════════════════════════════════
    //  CONSTANTS
    // ══════════════════════════════════════════════════════════════
    static final int W = 960, H = 620, FPS = 60;
    static final int TSTEP = 22, TPTS = 700;
    static final double GRAV = 0.52;

    // ══════════════════════════════════════════════════════════════
    //  GAME SCREENS:  0=name  1=vehicle  2=playing  3=over  4=win
    // ══════════════════════════════════════════════════════════════
    int screen = 0;
    String playerName = "";
    boolean nameCursorBlink = true;
    int     blinkTick = 0;

    // vehicle: 0=Car  1=Scooty  2=Cycle
    int vehicle = 0;

    // ── physics config per vehicle ─────────────────────────────
    static final double[] ENG   = {0.38, 0.30, 0.22};
    static final double[] BRK   = {0.32, 0.28, 0.22};
    static final double[] FRIC  = {0.013, 0.016, 0.020};
    static final double[] MXSPD = {13.0, 10.5, 8.0};
    static final double[] WRAD  = {20.0, 15.0, 13.0};
    static final double[] JUMP  = {-11.0, -9.5, -8.0};
    static final int[]    FUEL_DRAIN_INV = {200, 270, 400}; // ticks per 0.1 drain

    // ── game state ─────────────────────────────────────────────
    boolean gasOn, brakeOn, jumpReq;
    boolean onGround;
    double  camX;
    double  score, fuel = 100;
    int     coins, fuelTick;

    // ── vehicle physics ────────────────────────────────────────
    double vx, vy, vAngle;
    double vX, vY;
    double fwX, fwY, bwX, bwY;
    double fwA, bwA;
    int    jumpCooldown = 0;

    // ── terrain ────────────────────────────────────────────────
    int[] tX = new int[TPTS], tY = new int[TPTS];
    Random rng = new Random(7);

    // ── objects ────────────────────────────────────────────────
    ArrayList<double[]> coinList  = new ArrayList<>();
    ArrayList<double[]> fuelCans  = new ArrayList<>();
    ArrayList<Particle> particles = new ArrayList<>();
    ArrayList<StarBG>   stars     = new ArrayList<>();

    // ── animation ──────────────────────────────────────────────
    Timer timer;
    int   tick = 0;
    float vehicleSelectAnim = 0;

    // ── HUD shake ──────────────────────────────────────────────
    int shakeFrames = 0;
    int shakeX = 0, shakeY = 0;

    // ── sounds ─────────────────────────────────────────────────
    Clip engineClip, coinClip, jumpClip, crashClip, winClip, bgClip;
    boolean enginePlaying = false;

    // ══════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════
    public HillRacingGame() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(this);
        generateTerrain();
        for (int i = 0; i < 120; i++)
            stars.add(new StarBG(rng.nextInt(W), rng.nextInt(H), rng.nextFloat()));
        initSounds();
        timer = new Timer(1000 / FPS, this);
        timer.start();
    }

    // ══════════════════════════════════════════════════════════════
    //  SOUND — synthesised tones (no external files needed)
    // ══════════════════════════════════════════════════════════════
    void initSounds() {
        try { engineClip = buildTone(180, 0.18f, 2.0f, true);  } catch(Exception e){}
        try { coinClip   = buildTone(880, 0.25f, 0.12f,false); } catch(Exception e){}
        try { jumpClip   = buildTone(320, 0.20f, 0.18f,false); } catch(Exception e){}
        try { crashClip  = buildNoise(0.35f, 0.5f);            } catch(Exception e){}
        try { winClip    = buildJingle();                       } catch(Exception e){}
    }

    Clip buildTone(int hz, float amp, float secs, boolean loop) throws Exception {
        int sr = 44100;
        int n  = (int)(sr * secs);
        byte[] buf = new byte[n * 2];
        for (int i = 0; i < n; i++) {
            double t = i / (double)sr;
            double s = amp * Math.sin(2 * Math.PI * hz * t);
            // subtle wobble for engine
            if (loop) s += (amp*0.3) * Math.sin(2*Math.PI*(hz*1.5)*t);
            short v = (short)(s * Short.MAX_VALUE);
            buf[i*2]   = (byte)(v & 0xFF);
            buf[i*2+1] = (byte)((v >> 8) & 0xFF);
        }
        AudioFormat af = new AudioFormat(sr, 16, 1, true, false);
        DataLine.Info info = new DataLine.Info(Clip.class, af);
        Clip c = (Clip) AudioSystem.getLine(info);
        c.open(af, buf, 0, buf.length);
        if (loop) c.loop(Clip.LOOP_CONTINUOUSLY);
        return c;
    }

    Clip buildNoise(float amp, float secs) throws Exception {
        int sr = 44100; int n = (int)(sr*secs);
        byte[] buf = new byte[n*2];
        Random r = new Random();
        for (int i = 0; i < n; i++) {
            float decay = 1f - (float)i/n;
            short v = (short)((r.nextFloat()-0.5f)*2*amp*decay*Short.MAX_VALUE);
            buf[i*2]=(byte)(v&0xFF); buf[i*2+1]=(byte)((v>>8)&0xFF);
        }
        AudioFormat af = new AudioFormat(sr,16,1,true,false);
        DataLine.Info info = new DataLine.Info(Clip.class,af);
        Clip c=(Clip)AudioSystem.getLine(info); c.open(af,buf,0,buf.length);
        return c;
    }

    Clip buildJingle() throws Exception {
        int sr=44100; int[] freqs={523,659,784,1047}; int noteSamps=sr/4;
        byte[] buf=new byte[freqs.length*noteSamps*2];
        int bi=0;
        for(int f:freqs) for(int i=0;i<noteSamps;i++){
            float decay=1f-(float)i/noteSamps;
            short v=(short)(0.3f*decay*Math.sin(2*Math.PI*f*i/(double)sr)*Short.MAX_VALUE);
            buf[bi++]=(byte)(v&0xFF); buf[bi++]=(byte)((v>>8)&0xFF);
        }
        AudioFormat af=new AudioFormat(sr,16,1,true,false);
        DataLine.Info info=new DataLine.Info(Clip.class,af);
        Clip c=(Clip)AudioSystem.getLine(info); c.open(af,buf,0,buf.length);
        return c;
    }

    void playOnce(Clip c){ if(c==null)return; c.stop(); c.setFramePosition(0); c.start(); }
    void stopClip(Clip c){ if(c!=null)c.stop(); }

    // ══════════════════════════════════════════════════════════════
    //  TERRAIN
    // ══════════════════════════════════════════════════════════════
    void generateTerrain() {
        float[] h = new float[TPTS];
        h[0]=H*0.55f; h[TPTS-1]=H*0.55f;
        displace(h,0,TPTS-1,H*0.42f);
        for(int i=0;i<TPTS;i++){ tX[i]=i*TSTEP; tY[i]=Math.min((int)h[i],H-40); }
    }
    void displace(float[] h,int lo,int hi,float amp){
        if(hi-lo<=1)return; int mid=(lo+hi)/2;
        h[mid]=(h[lo]+h[hi])/2f+(rng.nextFloat()-0.5f)*amp;
        h[mid]=Math.max(H*0.15f,Math.min(H*0.82f,h[mid]));
        displace(h,lo,mid,amp*0.56f); displace(h,mid,hi,amp*0.56f);
    }
    double getTY(double x){
        int i=(int)(x/TSTEP); i=Math.max(0,Math.min(TPTS-2,i));
        double t=(x-tX[i])/(double)TSTEP;
        return tY[i]+t*(tY[i+1]-tY[i]);
    }
    double getTA(double x){
        int i=(int)(x/TSTEP); i=Math.max(0,Math.min(TPTS-2,i));
        return Math.atan2(tY[i+1]-tY[i],(double)TSTEP);
    }

    // ══════════════════════════════════════════════════════════════
    //  RESET / SPAWN
    // ══════════════════════════════════════════════════════════════
    void startGame(){
        vX=120; vY=getTY(120)-55;
        vx=vy=vAngle=fwA=bwA=0; camX=0;
        score=0; fuel=100; coins=0; fuelTick=0;
        shakeFrames=0; jumpCooldown=0; onGround=false;
        particles.clear();
        spawnPickups();
        screen=2;
        if(engineClip!=null){ engineClip.setFramePosition(0); engineClip.start(); enginePlaying=true; }
    }

    void spawnPickups(){
        coinList.clear(); fuelCans.clear();
        for(int i=6;i<TPTS-6;i+=7){
            double cx=i*TSTEP, cy=getTY(cx)-45-rng.nextInt(35);
            coinList.add(new double[]{cx,cy,1});
        }
        for(int i=20;i<TPTS-20;i+=40){
            double fx=i*TSTEP, fy=getTY(fx)-40;
            fuelCans.add(new double[]{fx,fy,1});
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  UPDATE LOOP
    // ══════════════════════════════════════════════════════════════
    @Override public void actionPerformed(ActionEvent e){ tick++; update(); repaint(); }

    void update(){
        blinkTick++;
        if(blinkTick%30==0) nameCursorBlink=!nameCursorBlink;
        vehicleSelectAnim += 0.04f;
        if(shakeFrames>0){ shakeFrames--; shakeX=(int)((rng.nextDouble()-0.5)*8); shakeY=(int)((rng.nextDouble()-0.5)*8); }
        else{ shakeX=shakeY=0; }
        if(screen!=2) return;

        double eng=ENG[vehicle],brk=BRK[vehicle],fri=FRIC[vehicle],mxs=MXSPD[vehicle];
        double wr=WRAD[vehicle];

        // fuel drain
        if(gasOn && fuel>0){ fuelTick++; if(fuelTick>=FUEL_DRAIN_INV[vehicle]){fuel-=0.1;fuelTick=0;} }
        if(fuel<0)fuel=0;

        // engine sound pitch
        if(engineClip!=null){
            if(gasOn && fuel>0 && !enginePlaying){ engineClip.start(); enginePlaying=true; }
            if((!gasOn || fuel<=0) && enginePlaying){ stopClip(engineClip); enginePlaying=false; }
        }

        // accelerate
        if(gasOn && fuel>0){ vx+=eng*Math.cos(vAngle); vy+=eng*Math.sin(vAngle); }
        if(brakeOn){ vx-=brk*Math.signum(vx); if(Math.abs(vx)<brk)vx=0; }

        // jump
        if(jumpReq && onGround && jumpCooldown<=0){
            vy=JUMP[vehicle]; onGround=false; jumpCooldown=25;
            playOnce(jumpClip);
            for(int i=0;i<10;i++)
                particles.add(new Particle(vX,vY+20,(rng.nextDouble()-0.5)*4,-rng.nextDouble()*3,new Color(180,220,255,200),20+rng.nextInt(15)));
        }
        jumpReq=false;
        if(jumpCooldown>0)jumpCooldown--;

        // cap speed
        double sp=Math.hypot(vx,vy);
        if(sp>mxs){vx=vx/sp*mxs;vy=vy/sp*mxs;}

        // gravity + friction
        vy+=GRAV; vx*=(1-fri);

        // move
        vX+=vx; vY+=vy;

        // terrain collision
        double ty=getTY(vX), ta=getTA(vX);
        if(vY>ty-16){
            vY=ty-16; onGround=true;
            double nx=-Math.sin(ta),ny=Math.cos(ta);
            double dot=vx*nx+vy*ny;
            if(dot<0){ vx-=dot*nx*1.1; vy-=dot*ny*1.1; }
            vy*=0.45; vAngle=ta;
            // exhaust
            if(gasOn && rng.nextInt(3)==0){
                double ex=vX-Math.cos(vAngle)*50,ey=vY+Math.sin(vAngle)*50+8;
                particles.add(new Particle(ex,ey,-vx*0.25+(rng.nextDouble()-0.5)*1.5,-1-rng.nextDouble()*1.5,new Color(150,150,150,160),18+rng.nextInt(18)));
            }
        } else { onGround=false; if(Math.abs(vx)>0.5) vAngle+=(Math.atan2(vy,vx)-vAngle)*0.07; }

        // wheel positions
        double spread = (vehicle==2)?28:(vehicle==1)?26:34;
        fwX=vX+Math.cos(vAngle)*spread; fwY=vY+Math.sin(vAngle)*spread+14;
        bwX=vX-Math.cos(vAngle)*spread; bwY=vY-Math.sin(vAngle)*spread+14;
        snapWheel(fwX,fwY); snapWheel(bwX,bwY);
        double spin=vx*0.06; fwA+=spin; bwA+=spin;

        // camera
        camX=vX-W*0.34;

        // score
        score=Math.max(score,vX/55.0);

        // pickup collection
        for(double[] c:coinList) if(c[2]==1&&dist2(c[0],c[1],vX,vY-10)<32*32){
            c[2]=0; coins++; playOnce(coinClip);
            for(int i=0;i<8;i++) particles.add(new Particle(c[0],c[1],(rng.nextDouble()-0.5)*5,-rng.nextDouble()*4,new Color(255,215,0,220),22+rng.nextInt(12)));
        }
        for(double[] f:fuelCans) if(f[2]==1&&dist2(f[0],f[1],vX,vY-10)<35*35){
            f[2]=0; fuel=Math.min(100,fuel+30);
            shakeFrames=8;
            for(int i=0;i<12;i++) particles.add(new Particle(f[0],f[1],(rng.nextDouble()-0.5)*5,-rng.nextDouble()*4,new Color(80,200,255,220),22+rng.nextInt(12)));
        }

        // crash
        double norm=((vAngle%(Math.PI*2))+Math.PI*2)%(Math.PI*2);
        if(norm>Math.PI*0.58&&norm<Math.PI*1.42){ screen=3; stopClip(engineClip); enginePlaying=false; playOnce(crashClip); shakeFrames=30; }
        if(vY>H+150){ screen=3; stopClip(engineClip); enginePlaying=false; }

        // win
        if(vX>(TPTS-25)*TSTEP){ screen=4; stopClip(engineClip); enginePlaying=false; playOnce(winClip); }

        // particles
        for(int i=particles.size()-1;i>=0;i--){ Particle p=particles.get(i); p.update(); if(p.life<=0)particles.remove(i); }
    }

    void snapWheel(double wx,double wy){
        double ty=getTY(wx);
        if(wy+WRAD[vehicle]>ty){ double d=(wy+WRAD[vehicle])-ty; vY-=d*0.5; vy-=d*0.28; if(vy>0)vy=0; }
    }
    double dist2(double ax,double ay,double bx,double by){ double dx=ax-bx,dy=ay-by; return dx*dx+dy*dy; }

    // ══════════════════════════════════════════════════════════════
    //  PAINT DISPATCHER
    // ══════════════════════════════════════════════════════════════
    @Override protected void paintComponent(Graphics g){
        super.paintComponent(g);
        Graphics2D g2=(Graphics2D)g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        switch(screen){
            case 0: drawNameScreen(g2); break;
            case 1: drawVehicleScreen(g2); break;
            case 2: drawGame(g2); break;
            case 3: drawGame(g2); drawOverlay(g2,"💥 CRASHED!","Press R to retry",new Color(180,20,20,220)); break;
            case 4: drawGame(g2); drawOverlay(g2,"🏆 YOU WIN!","Press R to play again",new Color(20,150,60,220)); break;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SCREEN 0 — NAME ENTRY
    // ══════════════════════════════════════════════════════════════
    void drawNameScreen(Graphics2D g2){
        // animated gradient bg
        float t=(float)(tick%360)/360f;
        Color c1=new Color((int)(20+30*Math.sin(t*Math.PI*2)),20,60);
        Color c2=new Color(10,(int)(30+20*Math.sin(t*Math.PI*2+1)),80);
        g2.setPaint(new GradientPaint(0,0,c1,W,H,c2));
        g2.fillRect(0,0,W,H);

        // stars
        for(StarBG s:stars) s.draw(g2,0);

        // hills silhouette
        drawHillSilhouette(g2);

        // glowing title
        String title="🏔  HILL CLIMBER  🏔";
        g2.setFont(new Font("Arial Black",Font.BOLD,54));
        FontMetrics fm=g2.getFontMetrics();
        int tx=(W-fm.stringWidth(title))/2;
        // glow
        for(int r=12;r>0;r-=3){
            g2.setColor(new Color(255,180,0,(int)(15*(13-r))));
            g2.setFont(new Font("Arial Black",Font.BOLD,54));
            g2.drawString(title,tx-r/2,160+r/2);
        }
        g2.setPaint(new GradientPaint(tx,110,new Color(255,230,60),tx+fm.stringWidth(title),110,new Color(255,100,20)));
        g2.drawString(title,tx,160);

        // subtitle
        g2.setColor(new Color(200,230,255,200));
        g2.setFont(new Font("Segoe UI",Font.ITALIC,20));
        String sub="Enter your name to begin the adventure!";
        g2.drawString(sub,(W-g2.getFontMetrics().stringWidth(sub))/2,200);

        // name box
        int bx=W/2-180,by=250,bw=360,bh=58;
        // shadow
        g2.setColor(new Color(0,0,0,100));
        g2.fillRoundRect(bx+4,by+4,bw,bh,18,18);
        // box bg
        g2.setPaint(new GradientPaint(bx,by,new Color(30,40,80),bx,by+bh,new Color(15,20,50)));
        g2.fillRoundRect(bx,by,bw,bh,18,18);
        // border glow
        g2.setColor(new Color(100,180,255,180));
        g2.setStroke(new BasicStroke(2.5f));
        g2.drawRoundRect(bx,by,bw,bh,18,18);
        // text
        String display=playerName+(nameCursorBlink?"|":"");
        g2.setColor(Color.WHITE);
        g2.setFont(new Font("Consolas",Font.BOLD,26));
        g2.drawString(display,bx+16,by+37);

        // placeholder
        if(playerName.isEmpty()){
            g2.setColor(new Color(150,170,220,120));
            g2.setFont(new Font("Segoe UI",Font.ITALIC,22));
            g2.drawString("Type your name here...",bx+16,by+37);
        }

        // label
        g2.setColor(new Color(180,210,255));
        g2.setFont(new Font("Segoe UI",Font.BOLD,16));
        g2.drawString("PLAYER NAME",bx,by-12);

        // button
        boolean canGo=!playerName.trim().isEmpty();
        drawButton(g2,W/2-140,330,280,54,canGo?"🚀  LET'S GOOOO!":"(type your name first)",
                   canGo?new Color(50,220,90):new Color(80,80,100),
                   canGo?new Color(20,160,60):new Color(60,60,80));

        // hint
        g2.setColor(new Color(160,180,220,160));
        g2.setFont(new Font("Segoe UI",Font.PLAIN,14));
        String hint="Press ENTER to continue  •  Type letters to enter name";
        g2.drawString(hint,(W-g2.getFontMetrics().stringWidth(hint))/2,420);

        // decorative vehicle icons
        drawMiniCar(g2,80,480);
        drawMiniScooty(g2,W/2-25,490);
        drawMiniCycle(g2,W-120,480);
    }

    // ══════════════════════════════════════════════════════════════
    //  SCREEN 1 — VEHICLE SELECTOR
    // ══════════════════════════════════════════════════════════════
    void drawVehicleScreen(Graphics2D g2){
        // bg
        g2.setPaint(new GradientPaint(0,0,new Color(8,12,35),W,H,new Color(25,45,90)));
        g2.fillRect(0,0,W,H);
        for(StarBG s:stars) s.draw(g2,0);
        drawHillSilhouette(g2);

        // title
        g2.setFont(new Font("Arial Black",Font.BOLD,38));
        String t="👤  "+playerName.toUpperCase()+"'S GARAGE";
        FontMetrics fm=g2.getFontMetrics();
        // glow
        g2.setColor(new Color(255,200,0,40)); g2.drawString(t,(W-fm.stringWidth(t))/2+3,85);
        g2.setPaint(new GradientPaint(0,50,new Color(255,230,80),0,90,new Color(255,120,20)));
        g2.drawString(t,(W-fm.stringWidth(t))/2,82);

        g2.setColor(new Color(180,210,255,200));
        g2.setFont(new Font("Segoe UI",Font.ITALIC,18));
        String sub2="Choose your ride and conquer the hills!";
        g2.drawString(sub2,(W-g2.getFontMetrics().stringWidth(sub2))/2,110);

        // 3 vehicle cards
        String[] names={"CAR","SCOOTY","CYCLE"};
        String[] descs={"Top Speed ★★★★★","Speed ★★★★☆  Agile","Light ★★★☆☆  Eco"};
        String[] emojis={"🚗","🛵","🚲"};
        int[] cardX={60,W/2-130,W-320};

        for(int i=0;i<3;i++){
            boolean sel=(vehicle==i);
            float bob=(float)Math.sin(vehicleSelectAnim+i*2.1)*4f;
            drawVehicleCard(g2,cardX[i],(int)(140+bob),250,280,names[i],descs[i],emojis[i],i,sel);
        }

        // controls hint under cards
        g2.setColor(new Color(180,210,255,180));
        g2.setFont(new Font("Segoe UI",Font.PLAIN,15));
        String h="← → Arrow keys to select  •  ENTER to confirm  •  A/D in-game: Gas/Brake  •  W / SPACE: Jump";
        g2.drawString(h,(W-g2.getFontMetrics().stringWidth(h))/2,465);

        // big confirm button
        drawButton(g2,W/2-160,490,320,58,"⚡  START RACING!",new Color(255,160,0),new Color(200,100,0));
    }

    void drawVehicleCard(Graphics2D g2,int x,int y,int w,int h,String name,String desc,String emoji,int idx,boolean sel){
        // shadow
        g2.setColor(new Color(0,0,0,sel?140:80));
        g2.fillRoundRect(x+6,y+6,w,h,22,22);
        // bg gradient
        Color bg1=sel?new Color(50,110,220):new Color(25,35,80);
        Color bg2=sel?new Color(20,60,150):new Color(15,22,55);
        g2.setPaint(new GradientPaint(x,y,bg1,x,y+h,bg2));
        g2.fillRoundRect(x,y,w,h,22,22);
        // border
        g2.setColor(sel?new Color(100,180,255):new Color(60,80,140));
        g2.setStroke(new BasicStroke(sel?3f:1.5f));
        g2.drawRoundRect(x,y,w,h,22,22);

        if(sel){
            // glow border
            for(int r=1;r<=4;r++){
                g2.setColor(new Color(100,180,255,(int)(40/(r))));
                g2.drawRoundRect(x-r,y-r,w+r*2,h+r*2,22,22);
            }
            // SELECTED badge
            g2.setColor(new Color(50,220,90));
            g2.fillRoundRect(x+w/2-40,y-14,80,26,12,12);
            g2.setColor(Color.WHITE);
            g2.setFont(new Font("Arial Black",Font.BOLD,11));
            g2.drawString("SELECTED",x+w/2-36,y+2);
        }

        // emoji icon big
        g2.setFont(new Font("Segoe UI Emoji",Font.PLAIN,54));
        g2.drawString(emoji,x+w/2-28,y+82);

        // vehicle drawing
        AffineTransform at=g2.getTransform();
        g2.translate(x+w/2,y+148);
        double sc=1.25;
        g2.scale(sc,sc);
        if(idx==0) drawCarShape(g2);
        else if(idx==1) drawScootyShape(g2);
        else drawCycleShape(g2);
        g2.setTransform(at);

        // name
        g2.setFont(new Font("Arial Black",Font.BOLD,20));
        g2.setColor(sel?new Color(255,230,80):Color.WHITE);
        FontMetrics fm=g2.getFontMetrics();
        g2.drawString(name,x+(w-fm.stringWidth(name))/2,y+220);

        // desc
        g2.setFont(new Font("Segoe UI",Font.PLAIN,13));
        g2.setColor(new Color(180,210,255,200));
        fm=g2.getFontMetrics();
        g2.drawString(desc,x+(w-fm.stringWidth(desc))/2,y+242);
    }

    // ══════════════════════════════════════════════════════════════
    //  SCREEN 2 — GAME
    // ══════════════════════════════════════════════════════════════
    void drawGame(Graphics2D g2){
        g2.translate(shakeX,shakeY);

        // sky gradient based on progress
        float prog=(float)(vX/((TPTS-25)*TSTEP));
        Color skyT=blend(new Color(10,20,55),new Color(180,100,30),prog);
        Color skyB=blend(new Color(50,100,200),new Color(220,150,60),prog);
        g2.setPaint(new GradientPaint(0,0,skyT,0,H,skyB));
        g2.fillRect(0,0,W,H);

        // parallax mountains bg
        drawBGMountains(g2);

        // sun/moon
        drawSunMoon(g2,prog);

        // camera
        g2.translate(-camX,0);

        drawTerrain(g2);
        drawPickups(g2);
        drawParticles(g2);

        // finish flag
        int fx=(TPTS-22)*TSTEP;
        drawFinishFlag(g2,fx,(int)getTY(fx)-5);

        drawVehicle(g2);

        g2.translate(camX,0);

        drawHUD(g2);

        // jump button (bottom right)
        drawJumpButton(g2);

        g2.translate(-shakeX,-shakeY);
    }

    void drawBGMountains(Graphics2D g2){
        // layer 1 far — moves slower
        double ox1=camX*0.15;
        g2.setColor(new Color(30,55,100,180));
        int[] xs1=new int[12],ys1=new int[12]; xs1[0]=0; ys1[0]=H; xs1[11]=W; ys1[11]=H;
        for(int i=1;i<=10;i++){ xs1[i]=(int)((i-1)*(W/9.0)-(ox1%(W/9.0+1))); ys1[i]=200+(int)(80*Math.sin(i*1.2)); }
        g2.fillPolygon(xs1,ys1,12);

        // layer 2
        double ox2=camX*0.30;
        g2.setColor(new Color(20,80,40,160));
        int[] xs2=new int[10],ys2=new int[10]; xs2[0]=0;ys2[0]=H;xs2[9]=W;ys2[9]=H;
        for(int i=1;i<=8;i++){ xs2[i]=(int)((i-1)*(W/7.0)-(ox2%(W/7.0+1))); ys2[i]=290+(int)(70*Math.sin(i*0.9+2)); }
        g2.fillPolygon(xs2,ys2,10);
    }

    void drawSunMoon(Graphics2D g2,float prog){
        int sx=(int)(W*0.80),sy=(int)(80+prog*40);
        // glow
        for(int r=40;r>0;r-=8){
            g2.setColor(new Color(255,200,50,(int)(8*(41-r)/4)));
            g2.fillOval(sx-r,sy-r,r*2,r*2);
        }
        g2.setColor(new Color(255,230,100));
        g2.fillOval(sx-22,sy-22,44,44);
        g2.setColor(new Color(255,255,200,200));
        g2.fillOval(sx-16,sy-16,32,32);
    }

    void drawTerrain(Graphics2D g2){
        int lo=Math.max(0,(int)(camX/TSTEP)-1);
        int hi=Math.min(TPTS-1,lo+W/TSTEP+4);

        // dirt fill
        Polygon poly=new Polygon();
        poly.addPoint(tX[lo],H+30);
        for(int i=lo;i<=hi;i++) poly.addPoint(tX[i],tY[i]);
        poly.addPoint(tX[hi],H+30);
        // 3D effect: layered fills
        g2.setColor(new Color(60,160,60));  g2.fill(poly);
        Polygon dirt=new Polygon();
        dirt.addPoint(tX[lo],H+30);
        for(int i=lo;i<=hi;i++) dirt.addPoint(tX[i],tY[i]+10);
        dirt.addPoint(tX[hi],H+30);
        g2.setColor(new Color(130,80,30));  g2.fill(dirt);
        Polygon dark=new Polygon();
        dark.addPoint(tX[lo],H+30);
        for(int i=lo;i<=hi;i++) dark.addPoint(tX[i],tY[i]+28);
        dark.addPoint(tX[hi],H+30);
        g2.setColor(new Color(90,55,20));   g2.fill(dark);

        // grass line
        g2.setStroke(new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.setColor(new Color(40,200,60));
        for(int i=lo;i<hi;i++) g2.drawLine(tX[i],tY[i],tX[i+1],tY[i+1]);

        // grass tufts
        g2.setColor(new Color(30,180,50));
        g2.setStroke(new BasicStroke(2));
        for(int i=lo;i<hi;i+=3){
            double ang=Math.atan2(tY[i+1]-tY[i],TSTEP);
            double nx=-Math.sin(ang),ny=Math.cos(ang);
            int gx=tX[i],gy=tY[i];
            g2.drawLine(gx,gy,gx+(int)(nx*6),gy+(int)(ny*6)-5);
            g2.drawLine(gx+3,gy,gx+3+(int)(nx*5),gy+(int)(ny*5)-6);
        }
    }

    void drawPickups(Graphics2D g2){
        // coins
        for(double[] c:coinList){
            if(c[2]!=1||c[0]<camX-60||c[0]>camX+W+60) continue;
            int cx=(int)c[0],cy=(int)c[1];
            float bob=(float)(Math.sin(tick*0.08+cx)*4);
            cy+=(int)bob;
            // glow
            g2.setColor(new Color(255,215,0,40)); g2.fillOval(cx-16,cy-16,32,32);
            // coin 3D
            g2.setPaint(new GradientPaint(cx-10,cy-10,new Color(255,240,100),cx+10,cy+10,new Color(200,140,0)));
            g2.fillOval(cx-11,cy-11,22,22);
            g2.setColor(new Color(255,255,180,200)); g2.fillOval(cx-7,cy-9,8,6);
            g2.setColor(new Color(180,120,0));
            g2.setStroke(new BasicStroke(1.5f)); g2.drawOval(cx-11,cy-11,22,22);
            g2.setColor(Color.WHITE); g2.setFont(new Font("Arial",Font.BOLD,9)); g2.drawString("$",cx-4,cy+4);
        }
        // fuel cans
        for(double[] f:fuelCans){
            if(f[2]!=1||f[0]<camX-60||f[0]>camX+W+60) continue;
            int fx=(int)f[0],fy=(int)f[1];
            float bob=(float)(Math.sin(tick*0.07+fx)*3);
            fy+=(int)bob;
            // can body
            g2.setPaint(new GradientPaint(fx-10,fy-18,new Color(220,60,60),fx+10,fy+18,new Color(140,20,20)));
            g2.fillRoundRect(fx-12,fy-18,24,36,6,6);
            g2.setColor(new Color(255,120,120)); g2.fillRect(fx-8,fy-16,8,5);
            // nozzle
            g2.setColor(new Color(80,80,80)); g2.fillRect(fx+6,fy-22,8,10);
            // label
            g2.setColor(Color.WHITE); g2.setFont(new Font("Arial",Font.BOLD,9)); g2.drawString("⛽",fx-7,fy+6);
            // glow
            g2.setColor(new Color(255,100,50,30)); g2.fillOval(fx-18,fy-22,36,44);
        }
    }

    void drawFinishFlag(Graphics2D g2,int fx,int fy){
        g2.setColor(new Color(200,200,200));
        g2.setStroke(new BasicStroke(3));
        g2.drawLine(fx,fy,fx,fy-80);
        // checker flag
        for(int r=0;r<4;r++) for(int c2=0;c2<4;c2++){
            g2.setColor((r+c2)%2==0?Color.BLACK:Color.WHITE);
            g2.fillRect(fx+c2*10,fy-80+r*10,10,10);
        }
        g2.setColor(new Color(255,215,0));
        g2.setFont(new Font("Arial",Font.BOLD,14));
        g2.drawString("FINISH",fx-10,fy-90);
    }

    void drawVehicle(Graphics2D g2){
        // draw wheels
        drawWheelFull(g2,fwX,fwY,fwA,WRAD[vehicle]);
        drawWheelFull(g2,bwX,bwY,bwA,WRAD[vehicle]);

        AffineTransform old=g2.getTransform();
        g2.translate(vX,vY);
        g2.rotate(vAngle);

        if(vehicle==0)      drawCarShape(g2);
        else if(vehicle==1) drawScootyShape(g2);
        else                drawCycleShape(g2);

        g2.setTransform(old);
    }

    // ── CAR (detailed) ─────────────────────────────────────────
    void drawCarShape(Graphics2D g2){
        // shadow
        g2.setColor(new Color(0,0,0,50)); g2.fillOval(-40,20,80,14);
        // body
        g2.setPaint(new GradientPaint(-42,-4,new Color(240,60,60),42,10,new Color(160,20,20)));
        int[] bx={-44,-44,-32,32,44,44}; int[] by={10,-4,-15,-15,-4,10};
        g2.fillPolygon(bx,by,6);
        // roof
        g2.setPaint(new GradientPaint(-22,-4,new Color(200,30,30),22,10,new Color(130,10,10)));
        int[] rx={-22,-20,20,22}; int[] ry={-4,-22,-22,-4};
        g2.fillPolygon(rx,ry,4);
        // windows
        g2.setColor(new Color(160,220,255,200));
        g2.fillRect(-18,-21,14,14); g2.fillRect(4,-21,14,14);
        g2.setColor(new Color(255,255,255,80));
        g2.fillRect(-16,-20,5,6); g2.fillRect(6,-20,5,6);
        // divider
        g2.setColor(new Color(140,10,10)); g2.setStroke(new BasicStroke(2)); g2.drawLine(1,-22,1,-8);
        // headlight
        g2.setColor(new Color(255,255,180)); g2.fillOval(38,-3,9,6);
        g2.setColor(new Color(255,255,100,100)); g2.fillOval(35,-5,14,10);
        // bumpers
        g2.setPaint(new GradientPaint(-45,5,new Color(180,180,180),45,12,new Color(120,120,120)));
        g2.fillRoundRect(-46,3,8,8,4,4); g2.fillRoundRect(38,3,8,8,4,4);
        // exhaust
        g2.setColor(new Color(70,70,70)); g2.fillRect(-44,5,7,4);
        // door lines
        g2.setColor(new Color(120,10,10,160)); g2.setStroke(new BasicStroke(1.2f));
        g2.drawLine(-10,-15,-10,8); g2.drawLine(-36,-4,-10,-4);
        // wheel arches
        g2.setColor(new Color(120,10,10,120)); g2.setStroke(new BasicStroke(2));
        g2.drawArc(-18,2,20,14,0,180); g2.drawArc(-50,2,20,14,0,180);
    }

    // ── SCOOTY ──────────────────────────────────────────────────
    void drawScootyShape(Graphics2D g2){
        g2.setColor(new Color(0,0,0,40)); g2.fillOval(-28,16,56,10);
        // body
        g2.setPaint(new GradientPaint(-26,-2,new Color(60,180,255),-26,14,new Color(20,80,180)));
        int[] bx={-28,-26,-10,10,26,28,20,-20}; int[] by={14,0,-10,-10,0,14,14,14};
        g2.fillPolygon(bx,by,8);
        // seat
        g2.setPaint(new GradientPaint(-16,-14,new Color(40,40,40),10,-14,new Color(20,20,20)));
        g2.fillRoundRect(-16,-18,28,10,8,8);
        // handlebar
        g2.setColor(new Color(50,50,50)); g2.setStroke(new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawLine(18,-8,24,-16); g2.drawLine(24,-16,28,-16); g2.drawLine(24,-16,20,-16);
        // windshield
        g2.setColor(new Color(150,230,255,140));
        g2.fillPolygon(new int[]{10,20,26,16},new int[]{-10,-10,-2,-2},4);
        // headlight
        g2.setColor(new Color(255,255,180)); g2.fillOval(22,0,10,7);
        g2.setColor(new Color(255,255,100,80)); g2.fillOval(20,-2,14,11);
        // exhaust
        g2.setColor(new Color(80,80,80)); g2.setStroke(new BasicStroke(3)); g2.drawLine(-24,10,-30,12);
        g2.setColor(new Color(100,60,30)); g2.setStroke(new BasicStroke(2));
        g2.drawArc(-30,4,14,10,200,160);
    }

    // ── CYCLE ───────────────────────────────────────────────────
    void drawCycleShape(Graphics2D g2){
        g2.setColor(new Color(0,0,0,30)); g2.fillOval(-22,16,44,8);
        // frame
        g2.setColor(new Color(220,120,0)); g2.setStroke(new BasicStroke(4,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawLine(-18,14,0,-2); g2.drawLine(0,-2,18,14);
        g2.drawLine(0,-2,0,-16); g2.drawLine(-18,14,18,14);
        // seat
        g2.setColor(new Color(40,40,40)); g2.fillRoundRect(-10,-20,20,7,5,5);
        // handlebar
        g2.setColor(new Color(60,60,60)); g2.setStroke(new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawLine(14,-16,20,-14); g2.drawLine(20,-14,22,-10); g2.drawLine(20,-14,18,-14);
        // seat post
        g2.setColor(new Color(180,100,0)); g2.setStroke(new BasicStroke(3)); g2.drawLine(0,-2,0,-16);
        // pedals
        g2.setColor(new Color(60,60,60)); g2.fillOval(-5,10,10,10);
        g2.setStroke(new BasicStroke(2)); g2.drawLine(-5,15,-12,18); g2.drawLine(5,15,12,18);
        g2.fillRect(-14,17,6,4); g2.fillRect(10,17,6,4);
    }

    void drawWheelFull(Graphics2D g2,double wx,double wy,double ang,double wr){
        AffineTransform old=g2.getTransform();
        g2.translate(wx,wy);
        int r=(int)wr;
        // tyre
        g2.setPaint(new GradientPaint(-r,-r,new Color(50,50,50),r,r,new Color(20,20,20)));
        g2.fillOval(-r,-r,r*2,r*2);
        // tread
        g2.setColor(new Color(70,70,70)); g2.setStroke(new BasicStroke(2.5f));
        for(int i=0;i<8;i++){
            double a=ang+i*Math.PI/4;
            int x1=(int)(Math.cos(a)*(r-4)),y1=(int)(Math.sin(a)*(r-4));
            int x2=(int)(Math.cos(a)*r),y2=(int)(Math.sin(a)*r);
            g2.drawLine(x1,y1,x2,y2);
        }
        // rim
        g2.setColor(new Color(160,160,160)); g2.setStroke(new BasicStroke(1.5f));
        g2.drawOval(-r+3,-r+3,(r-3)*2,(r-3)*2);
        // spokes
        g2.setColor(new Color(140,140,140)); g2.setStroke(new BasicStroke(1.2f));
        for(int i=0;i<6;i++){
            double a=ang+i*Math.PI/3;
            g2.drawLine(0,0,(int)(Math.cos(a)*(r-4)),(int)(Math.sin(a)*(r-4)));
        }
        // hub
        g2.setPaint(new GradientPaint(-5,-5,new Color(230,230,230),5,5,new Color(150,150,150)));
        g2.fillOval(-6,-6,12,12);
        g2.setColor(new Color(100,100,100)); g2.drawOval(-6,-6,12,12);
        // center
        g2.setColor(new Color(60,60,60)); g2.fillOval(-3,-3,6,6);
        g2.setTransform(old);
    }

    // ══════════════════════════════════════════════════════════════
    //  HUD
    // ══════════════════════════════════════════════════════════════
    void drawHUD(Graphics2D g2){
        // top bar bg
        g2.setColor(new Color(0,0,0,150));
        g2.fillRoundRect(8,8,300,96,16,16);

        // player name tag
        g2.setPaint(new GradientPaint(14,14,new Color(50,100,200),14,40,new Color(20,50,140)));
        g2.fillRoundRect(14,14,110,28,10,10);
        g2.setColor(Color.WHITE); g2.setFont(new Font("Arial Black",Font.BOLD,13));
        g2.drawString("👤 "+playerName,20,33);

        // vehicle tag
        String[] vnames={"🚗 CAR","🛵 SCOOTY","🚲 CYCLE"};
        g2.setPaint(new GradientPaint(130,14,new Color(60,160,60),130,40,new Color(20,100,20)));
        g2.fillRoundRect(130,14,90,28,10,10);
        g2.setColor(Color.WHITE); g2.setFont(new Font("Arial Black",Font.BOLD,12));
        g2.drawString(vnames[vehicle],136,33);

        // distance
        g2.setFont(new Font("Arial Black",Font.BOLD,17));
        g2.setColor(new Color(255,230,60));
        g2.drawString(String.format("📏 %.0f m",score),18,62);

        // coins
        g2.setColor(new Color(255,215,0));
        g2.setFont(new Font("Arial Black",Font.BOLD,16));
        g2.drawString("💰 "+coins,18,84);

        // fuel bar
        g2.setColor(Color.LIGHT_GRAY); g2.setFont(new Font("Consolas",Font.BOLD,12)); g2.drawString("⛽",140,84);
        g2.setColor(new Color(50,50,50)); g2.fillRoundRect(160,71,140,16,8,8);
        Color fc=fuel>50?new Color(50,220,80):fuel>20?new Color(240,190,30):new Color(220,40,40);
        g2.setColor(fc); g2.fillRoundRect(160,71,(int)(fuel*1.4),16,8,8);
        g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(1.5f)); g2.drawRoundRect(160,71,140,16,8,8);
        if(fuel<20){ // warning pulse
            g2.setColor(new Color(255,50,50,(int)(120+80*Math.sin(tick*0.2))));
            g2.setFont(new Font("Arial Black",Font.BOLD,13));
            g2.drawString("LOW FUEL!",165,84);
        }

        // speed meter (right)
        drawSpeedMeter(g2,W-115,10,100,90);

        // controls hint bottom
        g2.setColor(new Color(255,255,255,90));
        g2.setFont(new Font("Consolas",Font.PLAIN,12));
        g2.drawString("D/→: Gas   A/←: Brake   W/SPACE/↑: Jump   R: Restart",10,H-12);
    }

    void drawSpeedMeter(Graphics2D g2,int x,int y,int w,int h){
        g2.setColor(new Color(0,0,0,150)); g2.fillRoundRect(x,y,w,h,14,14);
        double sp=Math.abs(vx)*10;
        // arc meter
        int cx=x+w/2,cy=y+h-18;
        g2.setColor(new Color(40,40,40)); g2.setStroke(new BasicStroke(8,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawArc(x+12,y+10,w-24,w-24,210,-240);
        // colored arc
        float spNorm=(float)Math.min(sp/(MXSPD[vehicle]*10),1.0);
        Color arc=spNorm<0.5f?new Color(50,220,80):spNorm<0.8f?new Color(240,190,30):new Color(220,40,40);
        g2.setColor(arc); g2.setStroke(new BasicStroke(6,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawArc(x+12,y+10,w-24,w-24,210,(int)(-240*spNorm));
        // needle
        double needleAng=Math.toRadians(210-240*spNorm);
        int nx2=(int)(cx+Math.cos(needleAng)*28),ny2=(int)(cy-Math.sin(needleAng)*28);
        g2.setColor(Color.WHITE); g2.setStroke(new BasicStroke(2)); g2.drawLine(cx,cy,nx2,ny2);
        g2.setColor(new Color(255,80,80)); g2.fillOval(cx-4,cy-4,8,8);
        // label
        g2.setColor(new Color(180,220,255)); g2.setFont(new Font("Consolas",Font.BOLD,10)); g2.drawString("km/h",x+w/2-14,y+h-4);
        g2.setColor(Color.WHITE); g2.setFont(new Font("Arial Black",Font.BOLD,15));
        String spStr=String.format("%.0f",sp);
        g2.drawString(spStr,x+(w-g2.getFontMetrics().stringWidth(spStr))/2,cy+4);
    }

    void drawJumpButton(Graphics2D g2){
        int bx=W-110,by=H-120,bw=90,bh=70;
        // shadow
        g2.setColor(new Color(0,0,0,100)); g2.fillRoundRect(bx+4,by+4,bw,bh,20,20);
        // button
        boolean lit=onGround&&jumpCooldown==0;
        Color btn1=lit?new Color(80,200,255):new Color(40,80,140);
        Color btn2=lit?new Color(20,100,200):new Color(20,40,100);
        g2.setPaint(new GradientPaint(bx,by,btn1,bx,by+bh,btn2));
        g2.fillRoundRect(bx,by,bw,bh,20,20);
        // glow when ready
        if(lit){ g2.setColor(new Color(100,220,255,60)); g2.fillRoundRect(bx-3,by-3,bw+6,bh+6,22,22); }
        g2.setColor(lit?new Color(150,230,255):new Color(60,100,180));
        g2.setStroke(new BasicStroke(2.5f)); g2.drawRoundRect(bx,by,bw,bh,20,20);
        // arrow up
        g2.setColor(Color.WHITE); g2.setFont(new Font("Segoe UI Emoji",Font.PLAIN,28));
        g2.drawString("⬆",bx+26,by+35);
        g2.setFont(new Font("Arial Black",Font.BOLD,11));
        g2.drawString("JUMP",bx+20,by+56);
        // cooldown arc
        if(jumpCooldown>0){
            g2.setColor(new Color(255,100,100,180)); g2.setStroke(new BasicStroke(3));
            g2.drawArc(bx+10,by+5,bw-20,bh-10,90,(int)(360*(float)jumpCooldown/25));
        }
    }

    void drawParticles(Graphics2D g2){
        for(Particle p:particles){ g2.setColor(p.color); g2.fillOval((int)p.x-3,(int)p.y-3,7,7); }
    }

    void drawOverlay(Graphics2D g2,String title,String sub,Color bg){
        g2.setColor(new Color(0,0,0,170)); g2.fillRect(0,0,W,H);
        // card
        g2.setColor(bg); g2.fillRoundRect(W/2-230,H/2-110,460,190,28,28);
        g2.setColor(new Color(255,255,255,80)); g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(W/2-230,H/2-110,460,190,28,28);
        // title
        g2.setFont(new Font("Arial Black",Font.BOLD,42));
        FontMetrics fm=g2.getFontMetrics();
        g2.setColor(new Color(0,0,0,80)); g2.drawString(title,(W-fm.stringWidth(title))/2+3,H/2-38);
        g2.setColor(Color.WHITE); g2.drawString(title,(W-fm.stringWidth(title))/2,H/2-40);
        // sub
        g2.setFont(new Font("Consolas",Font.PLAIN,18));
        fm=g2.getFontMetrics();
        g2.setColor(new Color(220,240,255)); g2.drawString(sub,(W-fm.stringWidth(sub))/2,H/2+2);
        // stats
        g2.setFont(new Font("Arial Black",Font.BOLD,16));
        String st=String.format("Distance: %.0f m   💰 Coins: %d",score,coins);
        fm=g2.getFontMetrics();
        g2.setColor(new Color(255,230,80)); g2.drawString(st,(W-fm.stringWidth(st))/2,H/2+40);
        // press r
        g2.setColor(new Color(200,230,255,180));
        g2.setFont(new Font("Consolas",Font.PLAIN,14));
        String r2="Press R to try again  •  Press ESC for menu";
        fm=g2.getFontMetrics(); g2.drawString(r2,(W-fm.stringWidth(r2))/2,H/2+70);
    }

    // ══════════════════════════════════════════════════════════════
    //  HELPERS / SHARED DRAWING
    // ══════════════════════════════════════════════════════════════
    void drawHillSilhouette(Graphics2D g2){
        g2.setColor(new Color(15,50,15,200));
        int[] hx={0,0,120,240,380,500,640,760,900,960,960};
        int[] hy={H,H-120,H-200,H-140,H-260,H-180,H-230,H-160,H-210,H-130,H};
        g2.fillPolygon(hx,hy,hx.length);
        g2.setColor(new Color(10,35,10,160));
        int[] hx2={0,0,80,180,300,420,540,660,780,870,960,960};
        int[] hy2={H,H-60,H-130,H-90,H-170,H-110,H-150,H-100,H-140,H-90,H-110,H};
        g2.fillPolygon(hx2,hy2,hx2.length);
    }

    void drawButton(Graphics2D g2,int x,int y,int w,int h,String label,Color c1,Color c2){
        g2.setColor(new Color(0,0,0,100)); g2.fillRoundRect(x+3,y+3,w,h,18,18);
        g2.setPaint(new GradientPaint(x,y,c1,x,y+h,c2));
        g2.fillRoundRect(x,y,w,h,18,18);
        g2.setColor(new Color(255,255,255,80)); g2.setStroke(new BasicStroke(2));
        g2.drawRoundRect(x,y,w,h,18,18);
        // hover shimmer
        g2.setColor(new Color(255,255,255,30)); g2.fillRoundRect(x+2,y+2,w-4,h/2-2,16,16);
        g2.setColor(Color.WHITE); g2.setFont(new Font("Arial Black",Font.BOLD,18));
        FontMetrics fm=g2.getFontMetrics();
        g2.drawString(label,x+(w-fm.stringWidth(label))/2,y+h/2+7);
    }

    void drawMiniCar(Graphics2D g2,int x,int y){
        AffineTransform at=g2.getTransform(); g2.translate(x,y);
        g2.setColor(new Color(220,60,60)); g2.fillPolygon(new int[]{-30,-30,-18,18,30,30},new int[]{8,-2,-12,-12,-2,8},6);
        g2.setColor(new Color(160,215,255,200)); g2.fillRect(-12,-11,10,8); g2.fillRect(4,-11,10,8);
        g2.setColor(new Color(30,30,30)); g2.fillOval(-26,5,16,16); g2.fillOval(10,5,16,16);
        g2.setColor(new Color(180,180,180)); g2.fillOval(-20,9,8,8); g2.fillOval(16,9,8,8);
        g2.setTransform(at);
    }
    void drawMiniScooty(Graphics2D g2,int x,int y){
        AffineTransform at=g2.getTransform(); g2.translate(x,y);
        g2.setColor(new Color(60,180,255)); g2.fillPolygon(new int[]{-22,-20,-6,6,22,22,14,-14},new int[]{12,0,-8,-8,0,12,12,12},8);
        g2.setColor(new Color(40,40,40)); g2.fillRoundRect(-12,-14,20,8,5,5);
        g2.setColor(new Color(30,30,30)); g2.fillOval(-24,7,16,16); g2.fillOval(8,7,16,16);
        g2.setColor(new Color(180,180,180)); g2.fillOval(-18,11,8,8); g2.fillOval(14,11,8,8);
        g2.setTransform(at);
    }
    void drawMiniCycle(Graphics2D g2,int x,int y){
        AffineTransform at=g2.getTransform(); g2.translate(x,y);
        g2.setColor(new Color(220,120,0)); g2.setStroke(new BasicStroke(3,BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        g2.drawLine(-16,12,0,0); g2.drawLine(0,0,16,12); g2.drawLine(-16,12,16,12); g2.drawLine(0,0,0,-12);
        g2.setColor(new Color(30,30,30)); g2.setStroke(new BasicStroke(2)); g2.drawOval(-22,4,20,20); g2.drawOval(2,4,20,20);
        g2.setTransform(at);
    }

    Color blend(Color a,Color b,float t){
        t=Math.max(0,Math.min(1,t));
        return new Color((int)(a.getRed()+(b.getRed()-a.getRed())*t),(int)(a.getGreen()+(b.getGreen()-a.getGreen())*t),(int)(a.getBlue()+(b.getBlue()-a.getBlue())*t));
    }

    // ══════════════════════════════════════════════════════════════
    //  PARTICLE
    // ══════════════════════════════════════════════════════════════
    static class Particle {
        double x,y,vx,vy; Color color; int life,maxLife;
        Particle(double x,double y,double vx,double vy,Color c,int life){ this.x=x;this.y=y;this.vx=vx;this.vy=vy;this.color=c;this.life=this.maxLife=life; }
        void update(){x+=vx;y+=vy;vy+=0.12;vx*=0.94;life--;int a=(int)(255*(double)life/maxLife);color=new Color(color.getRed(),color.getGreen(),color.getBlue(),Math.max(0,a));}
    }

    // ══════════════════════════════════════════════════════════════
    //  STAR BG
    // ══════════════════════════════════════════════════════════════
    static class StarBG {
        int x,y; float brightness,twinkle;
        StarBG(int x,int y,float b){ this.x=x;this.y=y;this.brightness=b;this.twinkle=(float)Math.random()*6.28f; }
        void draw(Graphics2D g2,int tick){ float b=brightness*(0.6f+0.4f*(float)Math.sin(twinkle+tick*0.04f)); int alpha=(int)(b*200); g2.setColor(new Color(255,255,255,Math.min(255,alpha))); g2.fillOval(x-1,y-1,2+(b>0.8f?1:0),2+(b>0.8f?1:0)); }
    }

    // ══════════════════════════════════════════════════════════════
    //  KEY INPUT
    // ══════════════════════════════════════════════════════════════
    @Override public void keyTyped(KeyEvent e){
        if(screen==0){
            char c=e.getKeyChar();
            if(Character.isLetterOrDigit(c)||c==' '){ if(playerName.length()<16) playerName+=c; }
        }
    }
    @Override public void keyPressed(KeyEvent e){
        int k=e.getKeyCode();
        // name screen
        if(screen==0){
            if(k==KeyEvent.VK_BACK_SPACE && playerName.length()>0) playerName=playerName.substring(0,playerName.length()-1);
            if(k==KeyEvent.VK_ENTER && !playerName.trim().isEmpty()) screen=1;
        }
        // vehicle screen
        else if(screen==1){
            if(k==KeyEvent.VK_LEFT||k==KeyEvent.VK_A)  vehicle=Math.max(0,vehicle-1);
            if(k==KeyEvent.VK_RIGHT||k==KeyEvent.VK_D) vehicle=Math.min(2,vehicle+1);
            if(k==KeyEvent.VK_ENTER||k==KeyEvent.VK_SPACE) startGame();
        }
        // game
        else if(screen==2){
            if(k==KeyEvent.VK_RIGHT||k==KeyEvent.VK_D) gasOn=true;
            if(k==KeyEvent.VK_LEFT||k==KeyEvent.VK_A) brakeOn=true;
            if(k==KeyEvent.VK_UP||k==KeyEvent.VK_W||k==KeyEvent.VK_SPACE) jumpReq=true;
        }
        // restart from game over / win
        if((screen==3||screen==4)&&k==KeyEvent.VK_R){ generateTerrain(); startGame(); }
        if((screen==3||screen==4)&&k==KeyEvent.VK_ESCAPE){ stopClip(engineClip); enginePlaying=false; screen=1; }
        if(screen==2&&k==KeyEvent.VK_R){ generateTerrain(); startGame(); }
    }
    @Override public void keyReleased(KeyEvent e){
        int k=e.getKeyCode();
        if(k==KeyEvent.VK_RIGHT||k==KeyEvent.VK_D) gasOn=false;
        if(k==KeyEvent.VK_LEFT||k==KeyEvent.VK_A) brakeOn=false;
    }

    // ══════════════════════════════════════════════════════════════
    //  MAIN
    // ══════════════════════════════════════════════════════════════
    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            JFrame f=new JFrame("Hill Climber – Racing Game");
            f.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            f.setResizable(false);
            HillRacingGame g=new HillRacingGame();
            f.add(g); f.pack();
            f.setLocationRelativeTo(null);
            f.setVisible(true);
            g.requestFocusInWindow();
        });
    }
}