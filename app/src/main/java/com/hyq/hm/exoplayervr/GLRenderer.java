package com.hyq.hm.exoplayervr;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;

/**
 * Created by 海米 on 2018/10/26.
 */

public class GLRenderer {

    private Context context;
    private int aPositionHandle;
    private int programId;

    private final float[] projectionMatrix= new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] modelViewMatrix = new float[16];
    private final float[] mMVPMatrix = new float[16];



    private final float[] mSTMatrix = new float[16];
    private int uMatrixHandle;
    private int uSTMatrixHandle;

    private int uTextureSamplerHandle;
    private int aTextureCoordHandle;

    private int screenWidth,screenHeight;

    private int[] textures;
    private int textureId;

    private Sphere sphere;


    private SurfaceTexture surfaceTexture;

    public GLRenderer(Context context) {
        this.context = context;
        sphere = new Sphere(18,100,200);
    }
    public void onSurfaceCreated(){
        String vertexShader = ShaderUtils.readRawTextFile(context, R.raw.vertext_shader);
        String fragmentShader= ShaderUtils.readRawTextFile(context, R.raw.fragment_sharder);
        programId=ShaderUtils.createProgram(vertexShader,fragmentShader);
        aPositionHandle= GLES20.glGetAttribLocation(programId,"aPosition");
        uMatrixHandle=GLES20.glGetUniformLocation(programId,"uMatrix");
        uSTMatrixHandle=GLES20.glGetUniformLocation(programId,"uSTMatrix");
        uTextureSamplerHandle=GLES20.glGetUniformLocation(programId,"sTexture");
        aTextureCoordHandle=GLES20.glGetAttribLocation(programId,"aTexCoord");

        textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textures[0]);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameterf(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        textureId = textures[0];
        if(surfaceTexture != null){
            surfaceTexture.release();
        }
        surfaceTexture = new SurfaceTexture(textureId);
    }

    public SurfaceTexture getSurfaceTexture() {
        return surfaceTexture;
    }
    public void onSurfaceDestroyed(){
        GLES20.glDeleteProgram(programId);
        GLES20.glDeleteTextures(1,textures,0);
    }
    public void release(){
        if(surfaceTexture != null){
            surfaceTexture.release();
            surfaceTexture = null;
        }
    }
    public void onSurfaceChanged(int width, int height) {
        if(surfaceTexture != null){
            surfaceTexture.setDefaultBufferSize(width,height);
        }
        screenWidth=width; screenHeight=height;
        float ratio=(float)width/height;
        Matrix.perspectiveM(projectionMatrix, 0, 90f, ratio,  1, 50);
        Matrix.setLookAtM(viewMatrix, 0,
                0.0f, 0.0f, 0.0f,
                0.0f, 0.0f,-1.0f,
                0.0f, 1.0f, 0.0f);
    }

    public float xAngle=0f;
    public float yAngle=90f;
    public float zAngle;

    public void onDrawFrame(){
        if(surfaceTexture == null){
            return;
        }
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(mSTMatrix);

        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT | GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glViewport(0,0,screenWidth,screenHeight);
        GLES20.glUseProgram(programId);
        Matrix.setIdentityM(modelMatrix,0);

        Matrix.rotateM(modelMatrix, 0, -xAngle, 1, 0, 0);
        Matrix.rotateM(modelMatrix, 0, -yAngle, 0, 1, 0);
        Matrix.rotateM(modelMatrix, 0, -zAngle, 0, 0, 1);

        Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        Matrix.multiplyMM(mMVPMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);


        GLES20.glUniformMatrix4fv(uMatrixHandle,1,false,mMVPMatrix,0);
        GLES20.glUniformMatrix4fv(uSTMatrixHandle,1,false,mSTMatrix,0);

        sphere.uploadVerticesBuffer(aPositionHandle);
        sphere.uploadTexCoordinateBuffer(aTextureCoordHandle);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(uTextureSamplerHandle,0);

        sphere.draw();

    }
}
