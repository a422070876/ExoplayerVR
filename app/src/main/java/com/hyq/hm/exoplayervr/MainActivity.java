package com.hyq.hm.exoplayervr;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.PermissionChecker;
import android.support.v7.app.AppCompatActivity;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoTimeListener;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {
    private String[] denied;
    private String[] permissions = {Manifest.permission.WRITE_EXTERNAL_STORAGE};

    private Handler mainHandler;

    private SeekBar seekBar;


    private RotationImageView guideView;

    private Surface mSurface;

    private Handler playHandler;
    private HandlerThread playThread;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mainHandler = new Handler();

        playThread = new HandlerThread("PlayThread");
        playThread.start();
        playHandler = new Handler(playThread.getLooper());


        mSensorManager=(SensorManager)getSystemService(Context.SENSOR_SERVICE);
        mSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        mSensorManager.registerListener(mSensorEventListener,mSensor,SensorManager.SENSOR_DELAY_GAME);



        eglUtils = new EGLUtils();
        renderer = new GLRenderer(this);
        surfaceView = findViewById(R.id.surface_view);

        seekBar = findViewById(R.id.seek_bar);
        playView = findViewById(R.id.play_view);


        guideView = findViewById(R.id.guide_view);
        guideView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                zero();
            }
        });


        surfaceView.getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {

            }

            @Override
            public void surfaceChanged(final SurfaceHolder holder, int format, final int width, final int height) {
                playHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if(player != null){
                            eglUtils.initEGL(holder.getSurface());
                            renderer.onSurfaceCreated();
                            renderer.onSurfaceChanged(width,height);
                            SurfaceTexture surfaceTexture = renderer.getSurfaceTexture();
                            surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                                @Override
                                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                                    renderer.onDrawFrame();
                                    eglUtils.swap();
                                }
                            });
                            mSurface = new Surface(surfaceTexture);
                            player.setVideoSurface(mSurface);
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    player.setPlayWhenReady(isPlayer);
                                    if(player.getPlayWhenReady()){
                                        videoTime();
                                    }
                                }
                            });
                        }
                    }
                });
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                playHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if(renderer != null){
                            renderer.release();
                        }
                        if(eglUtils != null){
                            eglUtils.release();
                        }
                    }
                });
                if(mSurface != null){
                    mSurface.release();
                    mSurface = null;
                }
            }
        });

        findViewById(R.id.video_view).setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mSensorManager.unregisterListener(mSensorEventListener);

                float y = event.getY();

                float x = event.getX();

                switch (event.getAction()) {

                    case MotionEvent.ACTION_MOVE:
                        float dy = y - mPreviousYs;
                        float dx = x - mPreviousXs;
                        renderer.yAngle += dx*0.1f;
                        renderer.xAngle += dy*0.1f;
                        if(renderer.yAngle > 360){
                            renderer.yAngle -= 360;
                        }else if(renderer.yAngle < 360){
                            renderer.yAngle += 360;
                        }
                        if (renderer.xAngle < -60f) {
                            renderer.xAngle = -60f;
                        } else if (renderer.xAngle > 30f) {
                            renderer.xAngle = 30f;
                        }
                        rotate();
                        break;
                    case MotionEvent.ACTION_UP:
                        mSensorManager.registerListener(mSensorEventListener,mSensor,SensorManager.SENSOR_DELAY_GAME);
                        break;
                }
                mPreviousYs = y;
                mPreviousXs = x;
                return true;
            }
        });


        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {

            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                isTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                player.seekTo(seekBar.getProgress()*player.getDuration()/100);
                isTracking = false;
            }
        });

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ArrayList<String> list = new ArrayList<>();
            for (int i = 0; i < permissions.length; i++) {
                if (PermissionChecker.checkSelfPermission(this, permissions[i]) == PackageManager.PERMISSION_DENIED) {
                    list.add(permissions[i]);
                }
            }
            if (list.size() != 0) {
                denied = new String[list.size()];
                for (int i = 0; i < list.size(); i++) {
                    denied[i] = list.get(i);
                }
                ActivityCompat.requestPermissions(this, denied, 5);
            } else {
                init();
            }
        } else {
            init();
        }
    }
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == 5) {
            boolean isDenied = false;
            for (int i = 0; i < denied.length; i++) {
                String permission = denied[i];
                for (int j = 0; j < permissions.length; j++) {
                    if (permissions[j].equals(permission)) {
                        if (grantResults[j] != PackageManager.PERMISSION_GRANTED) {
                            isDenied = true;
                            break;
                        }
                    }
                }
            }
            if (isDenied) {
                Toast.makeText(this, "请开启权限", Toast.LENGTH_SHORT).show();
            } else {
                init();

            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }
    private SimpleExoPlayer player;
    private SurfaceView surfaceView;

    private EGLUtils eglUtils;
    private GLRenderer renderer;

    private ImageView playView;


    private void init(){
        Uri url = Uri.parse(Environment.getExternalStorageDirectory().getAbsolutePath() +"/HMSDK/testvr.mp4");
        DefaultBandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();


        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);
        TrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

        player = ExoPlayerFactory.newSimpleInstance(this, trackSelector);


        DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(this,
                Util.getUserAgent(this, "ExoPlayerTime"), bandwidthMeter);


        MediaSource videoSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(url, mainHandler,null);
//        LoopingMediaSource loopingSource = new LoopingMediaSource(videoSource);
        player.prepare(videoSource);
        if(mSurface == null && surfaceView.getWidth() != 0 && surfaceView.getHeight() != 0){
            playHandler.post(new Runnable() {
                @Override
                public void run() {
                    if(player != null){
                        eglUtils.initEGL(surfaceView.getHolder().getSurface());
                        renderer.onSurfaceCreated();
                        renderer.onSurfaceChanged(surfaceView.getWidth(),surfaceView.getHeight());
                        SurfaceTexture surfaceTexture = renderer.getSurfaceTexture();
                        surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                            @Override
                            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                                renderer.onDrawFrame();
                                eglUtils.swap();
                            }
                        });
                        mSurface = new Surface(surfaceTexture);
                        player.setVideoSurface(mSurface);
                    }
                }
            });
        }
    }

    private boolean isTracking = false;
    private void videoTime(){
        seekBar.postDelayed(new Runnable() {
            @Override
            public void run() {
                if(!isTracking){
                    int progress = (int) (player.getContentPosition()*100/player.getDuration());
                    seekBar.setProgress(progress);
                }
                if(player.getContentPosition() >= player.getDuration()){
                    player.setPlayWhenReady(false);
                    playView.setImageResource(R.drawable.ic_play);
                }
                if(isResume && player.getPlayWhenReady()){
                    videoTime();
                }
            }
        },100);
    }

    private SensorManager mSensorManager;
    private Sensor mSensor;
    private float timestamp;
    private float angle[] = new float[3];

    private float mPreviousY, mPreviousYs;
    private float mPreviousX, mPreviousXs;

    private SensorEventListener mSensorEventListener =  new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent sensorEvent) {

            if (sensorEvent.accuracy != 0){
                int type = sensorEvent.sensor.getType();
                switch (type){
                    case Sensor.TYPE_GYROSCOPE:
                        if (timestamp != 0) {

                            angle[0] += sensorEvent.values[0];
                            angle[1] += sensorEvent.values[1];
                            angle[2] += sensorEvent.values[2];


                            float anglex = (float) Math.toDegrees(angle[0]);
                            float angley = (float) Math.toDegrees(angle[1]);
                            float anglez = (float) Math.toDegrees(angle[2]);

                            float dy = angley - mPreviousY;// 计算触控笔Y位移

                            float dx = anglex - mPreviousX;

                            renderer.yAngle += dx*0.025f;
                            renderer.xAngle -= dy*0.025f;
                            if(renderer.yAngle > 360){
                                renderer.yAngle -= 360;
                            }else if(renderer.yAngle < 360){
                                renderer.yAngle += 360;
                            }
                            if (renderer.xAngle < -60f) {
                                renderer.xAngle = -60f;
                            } else if (renderer.xAngle > 30f) {
                                renderer.xAngle = 30f;
                            }
                            mPreviousY = angley;
                            mPreviousX = anglex;
                            rotate();
                        }

                        timestamp = sensorEvent.timestamp;
                        break;
                }
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int i) {

        }
    };



    private boolean isResume = false;
    private boolean isPlayer = false;
    @Override
    protected void onResume() {
        super.onResume();
        mSensorManager.registerListener(mSensorEventListener,mSensor,SensorManager.SENSOR_DELAY_GAME);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M){
            View decorView = getWindow().getDecorView();
            int mHideFlags =
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decorView.setSystemUiVisibility(mHideFlags);
        }else{
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        }
        isResume = true;

    }
    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(mSensorEventListener);
        isResume = false;
        if(player != null){
            isPlayer = player.getPlayWhenReady();
            if(isPlayer){
                player.setPlayWhenReady(false);
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(player != null){
            player.setPlayWhenReady(false);
            player.stop();
            player.release();
        }

    }

    public void onPlayer(View view){
        if(player.getPlayWhenReady()){
            player.setPlayWhenReady(false);
            playView.setImageResource(R.drawable.ic_play);
        }else{
            if(player.getContentPosition() >= player.getDuration()){
                player.seekTo(0);
            }
            player.setPlayWhenReady(true);
            playView.setImageResource(R.drawable.ic_stop);
            videoTime();
        }
    }
    private void rotate() {

        guideView.setRotate( - renderer.yAngle);

    }

    int yy = 0;
    private void zero() {
        mSensorManager.unregisterListener(mSensorEventListener);


        float yAngle = renderer.yAngle;
        int a = (int) (yAngle/360);
        yy = (int) ((yAngle - 90) / 10f);
        yy -= a*36;

        guideView.post(new Runnable() {

            @Override

            public void run() {
                if (yy != 0) {
                    if (yy > 0) {
                        renderer.yAngle = renderer.yAngle - 10f;
                        guideView.postDelayed(this, 16);
                        yy--;
                    }
                    if (yy < 0) {
                        renderer.yAngle = renderer.yAngle + 10f;
                        guideView.postDelayed(this, 16);
                        yy++;
                    }
                } else {
                    renderer.yAngle = 90f;
                    mSensorManager.registerListener(mSensorEventListener,mSensor,SensorManager.SENSOR_DELAY_GAME);
                }
                renderer.xAngle = 0f;
            }

        });

    }
}
