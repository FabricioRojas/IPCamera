package com.pograming.pintaera.ipcamera;

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CamcorderProfile;
import android.media.MediaRecorder;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StrictMode;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;

import com.github.nkzawa.socketio.client.IO;
import com.github.nkzawa.socketio.client.Socket;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.Date;

public class MainActivity extends Activity {

    private Camera mCamera;
    private CameraPreview mPreview;
    private MediaRecorder mediaRecorder;

    public static final int MEDIA_TYPE_IMAGE = 1;
    public static final int MEDIA_TYPE_VIDEO = 2;
    public static final int BUFFER_SIZE = 20;

    private boolean isRecording = false;

    private Socket mSocket;
    {
        try {
            mSocket = IO.socket("http://localhost:8080");
        } catch (URISyntaxException e) {}
    }



    private void resetCamera(){
        releaseCameraAndPreview();

        mCamera = getCameraInstance();
//        Camera.Parameters params = mCamera.getParameters();
//        params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
//        mCamera.setParameters(params);
        mCamera.setPreviewCallback(pcallback);

        mPreview = new CameraPreview(this, mCamera);
        FrameLayout preview = findViewById(R.id.camera_preview);
        preview.addView(mPreview);
    }

    public byte[] convertYuvToJpeg(byte[] data, Camera camera) {

        YuvImage image = new YuvImage(data, ImageFormat.NV21,
                camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height, null);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int quality = 20;
        image.compressToJpeg(new Rect(0, 0, camera.getParameters().getPreviewSize().width, camera.getParameters().getPreviewSize().height), quality, baos);//this line you can crop the image quality

        return baos.toByteArray();
    }

    @Override
    protected void onPause() {
        super.onPause();
        releaseMediaRecorder();
        releaseCamera();
    }

    private void releaseMediaRecorder(){
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
            mCamera.lock();
        }
    }

    private void releaseCamera(){
        if (mCamera != null){
            mCamera.release();
            mCamera = null;
        }
    }


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        Log.d("TEST", "checkCameraHardware: " + checkCameraHardware(getApplicationContext()));
        if(checkCameraHardware(getApplicationContext())){
            resetCamera();
        }

        final Button captureButton = findViewById(R.id.button_capture);
        captureButton.setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
//                        mCamera.takePicture(null, null, mPicture);

                        if (isRecording) {
                            mediaRecorder.stop();
                            releaseMediaRecorder();
                            mCamera.lock();

                            captureButton.setText("Capture");
                            isRecording = false;
                        } else {
                            if (prepareVideoRecorder()) {
                                mediaRecorder.start();

                                captureButton.setText("Stop");
                                isRecording = true;
                            } else {
                                releaseMediaRecorder();
                            }
                        }
                    }
                }
        );
    }


    private boolean checkCameraHardware(Context context) {
        return context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA);
    }

    public static Camera getCameraInstance(){
        Camera c = null;
        try {
            c = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            Log.d("TEST", "getCameraInstance c " + c);
        }
        catch (Exception e){
            Log.d("TEST", "getCameraInstance catch " + e.getMessage());
        }
        return c;
    }

    private void releaseCameraAndPreview() {
        if (mCamera != null) {
            mCamera.release();
            mCamera = null;
        }
    }


    private Camera.PreviewCallback pcallback = new Camera.PreviewCallback(){
        public void onPreviewFrame(byte[] data, Camera camera){
            Log.d("TEST", "onPreviewFrame");
            try {
                byte[] baos = convertYuvToJpeg(data, camera);
                StringBuilder dataBuilder = new StringBuilder();
                dataBuilder.append("data:image/jpeg;base64,").append(Base64.encodeToString(baos, Base64.DEFAULT));
                Log.d("TEST", "onPreviewFrame emit");
                mSocket.emit("newFrame", dataBuilder.toString());
            } catch (Exception e) {
                Log.d("TEST", "onPreviewFrame ERROR");
            }
        }
    };

    private Camera.PictureCallback mPicture = new Camera.PictureCallback() {
        @Override
        public void onPictureTaken(byte[] data, Camera camera) {
            Log.d("TEST", "onPictureTaken");
            File pictureFile = getOutputMediaFile(MEDIA_TYPE_IMAGE);
            if (pictureFile == null){
                Log.d("TEST", "Error creating media file, check storage permissions");
                return;
            }
            try {
                Log.d("TEST", "FileOutputStream write");
                FileOutputStream fos = new FileOutputStream(pictureFile);
                fos.write(data);
                fos.close();
                resetCamera();
            } catch (FileNotFoundException e) {
                Log.d("TEST", "File not found: " + e.getMessage());
            } catch (IOException e) {
                Log.d("TEST", "Error accessing file: " + e.getMessage());
            }
        }
    };

    private static Uri getOutputMediaFileUri(int type){
        Log.d("TEST", "getOutputMediaFileUri");
        return Uri.fromFile(getOutputMediaFile(type));
    }

    private static File getOutputMediaFile(int type){

        Log.d("TEST", "getOutputMediaFile");
        File mediaStorageDir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), "MyCameraApp");


        if (! mediaStorageDir.exists()){
            if (! mediaStorageDir.mkdirs()){
                Log.d("TEST", "failed to create directory");
                return null;
            }
        }

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File mediaFile;
        Log.d("TEST", "getOutputMediaFile type: " + type);
        if (type == MEDIA_TYPE_IMAGE){
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "IMG_"+ timeStamp + ".jpg");
        } else if(type == MEDIA_TYPE_VIDEO) {
            mediaFile = new File(mediaStorageDir.getPath() + File.separator +
                    "VID_"+ timeStamp + ".mp4");
        } else {
            return null;
        }

        return mediaFile;
    }

    public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {
        private SurfaceHolder mHolder;
        private Camera mCamera;

        public CameraPreview(Context context, Camera camera) {
            super(context);

            Log.d("TEST", "CameraPreview");
            mCamera = camera;
            mCamera.setPreviewCallback(pcallback);
            mHolder = getHolder();
            mHolder.addCallback(this);
            mHolder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
        }

        public void surfaceCreated(SurfaceHolder holder) {
            try {
                mCamera.setPreviewDisplay(holder);
                mCamera.startPreview();

                mCamera.setPreviewCallback(pcallback);
            } catch (IOException e) {
                Log.d("TEST", "Error setting camera preview: " + e.getMessage());
            }
        }

        public void surfaceDestroyed(SurfaceHolder holder) {
            // empty. Take care of releasing the Camera preview in your activity.
        }

        public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
            if (mHolder.getSurface() == null){
                return;
            }

            try {
                mCamera.stopPreview();
            } catch (Exception e){

            }
            try {
                mCamera.setPreviewDisplay(mHolder);
                mCamera.startPreview();

            } catch (Exception e){
                Log.d("TEST", "Error starting camera preview: " + e.getMessage());
            }
        }
    }

    private boolean prepareVideoRecorder(){

        mCamera = getCameraInstance();
        mediaRecorder = new MediaRecorder();

        mCamera.unlock();
        mediaRecorder.setCamera(mCamera);

        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.CAMERA);

//        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
//        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H263);

        mediaRecorder.setProfile(CamcorderProfile.get(CamcorderProfile.QUALITY_HIGH));


//        try {
//            LocalServerSocket server = new LocalServerSocket("some name");
//            LocalSocket receiver = new LocalSocket();
//            receiver.connect(server.getLocalSocketAddress());
//            receiver.setReceiveBufferSize(BUFFER_SIZE);
//
//            LocalSocket sender = server.accept();
//            sender.setSendBufferSize(BUFFER_SIZE);
//
//            mediaRecorder.setOutputFile(sender.getFileDescriptor());
//        } catch (IOException e) {
//            e.printStackTrace();
            mediaRecorder.setOutputFile(getOutputMediaFile(MEDIA_TYPE_VIDEO).toString());
//        }



        mediaRecorder.setPreviewDisplay(mPreview.getHolder().getSurface());

        mCamera.setPreviewCallback(pcallback);
        try {
            mediaRecorder.prepare();
        } catch (IllegalStateException e) {
            Log.d("TEST", "IllegalStateException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        } catch (IOException e) {
            Log.d("TEST", "IOException preparing MediaRecorder: " + e.getMessage());
            releaseMediaRecorder();
            return false;
        }
        return true;
    }
}
