package com.bjfu.cameraxsavedemo;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.Image;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.bjfu.cameraxsavedemo.utils.PrePostProcessor;
import com.bjfu.cameraxsavedemo.utils.Result;
import com.bjfu.cameraxsavedemo.utils.ResultView;
import com.google.common.util.concurrent.ListenableFuture;

import org.pytorch.IValue;
import org.pytorch.LiteModuleLoader;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/*
1. 需要在app下的build.gradle中加入依赖：
    def camerax_version = "1.0.0-beta07"
    // CameraX core library using camera2 implementation
    implementation "androidx.camera:camera-camera2:$camerax_version"
    // CameraX Lifecycle Library
    implementation "androidx.camera:camera-lifecycle:$camerax_version"
    // CameraX View class
    implementation "androidx.camera:camera-view:1.0.0-alpha14"
2. 需要在app下的build.gradle中的 android 块末尾，紧跟 buildTypes 的位置添加：
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_1_8
        targetCompatibility JavaVersion.VERSION_1_8
    }
3. AndroidManifest.xml中添加权限：
    <uses-feature android:name="android.hardware.camera.any" />
    <uses-permission android:name="android.permission.CAMERA" />

4. 修改activity_main.xml文件:
    主要添加一个button以及一个androidx.camera.view.PreviewView
    PreviewView用于显示相机预览。layout_width和layout_height都设置为match_parent即可
        <Button
        android:id="@+id/camera_capture_button"
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:layout_marginBottom="50dp"
        android:elevation="2dp"
        android:scaleType="fitCenter"
        android:text="Take Photo"
        app:layout_constraintBottom_toBottomOf="parent"
        app:layout_constraintLeft_toLeftOf="parent"
        app:layout_constraintRight_toRightOf="parent" />

<!--    PreviewView用以显示相机预览-->
    <androidx.camera.view.PreviewView
        android:id="@+id/viewFinder"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />
 */


public final class MainActivity extends AppCompatActivity {
    private long mLastAnalysisResultTime = 0;
//    private androidx.camera.view.PreviewView viewFinder; // 自定义视图，显示相机预览用例
//    private Button camera_capture_button; // 拍照按钮
    private ImageCapture imageCapture; // 拍照的用例
    private File outputDirectory; // 输出路径
    private ExecutorService cameraExecutor; // 提供了一种用于管理线程池的方法和机制，高效地处理多线程任务
    private static final String FILENAME_FORMAT = "yyyy-MM-dd-HH-mm-ss-SSS"; // 定义图片名称格式
    private static final int REQUEST_CODE_PERMISSIONS = 10; // 定义权限的请求码
    private static final String[] REQUIRED_PERMISSIONS = new String[]{"android.permission.CAMERA"}; // 请求列表，只有一个相机使用权限
    private Bitmap mBitmap;
    private ResultView mResultView;
    private ExecutorService mBackgroundExecutor;
    private Module mModule;
    private String TAG = "TTZZ";
    private PrePostProcessor prePostProcessor;
    boolean firstIn = true;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
//        camera_capture_button = findViewById(R.id.camera_capture_button);
//        viewFinder = findViewById(R.id.object_detection_texture_view);

        mBackgroundExecutor = Executors.newSingleThreadExecutor();

        // 判断权限是否开启
        if (allPermissionsGranted()) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10);
        }

//        // 设置点击事件
//        camera_capture_button.setOnClickListener(view -> {
//            takePh2oto();
//        });

        // 得到输出目录
        outputDirectory = getOutputDirectory();

        // 得到线程池，用以相机的加载、拍摄等
        cameraExecutor = Executors.newSingleThreadExecutor();

        try {
            // 加载权重文件ptl
//            mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "deeplabv3_scripted_optimized.ptl"));
            mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "epoch40facebest.torchscript.pt"));

            List<String> classes = new ArrayList<>();
            InputStream inputStream = getAssets().open("classes.txt");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                classes.add(line);
            }
            PrePostProcessor.mClasses = new String[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                PrePostProcessor.mClasses[i] = classes.get(i);
            }

        } catch (IOException e) {
            Log.e("ImageSegmentation", "Error reading assets", e);
        }

        prePostProcessor = PrePostProcessor.INSTANCE;
    }

    public static String assetFilePath(Context context, String assetName) throws IOException {
        File file = new File(context.getFilesDir(), assetName);
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            os.flush();
            return file.getAbsolutePath();
        }
    }


    /**
     * 主要是进行相机拍照前相关参数的配置
     */
    private void startCamera() {
        // ProcessCameraProvider实例可以用来配置相机的用例（使用方式），例如预览、照片捕获以及视频捕获等
        // 利用getInstance在后台线程中执行provider的实例化，返回值为一个ListenableFuture
        // ListenableFuture<T>表示一个异步计算的结果，它会在T异步计算完成后调用监听器，addListener后可以通过get方法拿出完成的实例化对象
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture = ProcessCameraProvider.getInstance(this);
        cameraProviderFuture.addListener(() -> {
                    try {
                        // 此时拿到cameraProvider实例化
                        ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                        // preview用于接收相机捕获的图像预览
                        // Preview preview = new Preview.Builder()
                        //        .setTargetResolution(new Size(1920, 1080)) // 设置预览的分辨率
                        //        .setTargetRotation(Surface.ROTATION_90) // 设置预览的方向
                        //        .setTargetAspectRatio(new Rational(16, 9)) // 设置预览的宽高比
                        //        .setTargetName("my_preview") // 设置预览的名称
                        //        .build();
                        Preview preview = new Preview.Builder().build();

                        PreviewView textureView = getCameraPreviewTextureView();

                        // textureView提供了Surface对象来呈现相机预览
                        // preview将相机预览输出连接到textureView提供的Surface上
                        // Preview preview = new Preview.Builder()
                        //        .setTargetResolution(new Size(1920, 1080)) // 设置预览的分辨率
                        //        .setTargetRotation(Surface.ROTATION_90) // 设置预览的方向
                        //        .setTargetAspectRatio(new Rational(16, 9)) // 设置预览的宽高比
                        //        .setTargetName("my_preview") // 设置预览的名称
                        //        .build();
                        preview.setSurfaceProvider(textureView.createSurfaceProvider());

                        // imageCapture用于拍摄静态图像，提供了如拍摄尺寸、文件格式、闪光灯等配置选项
                        // ImageCapture imageCapture = new ImageCapture.Builder()
                        //        .setFlashMode(ImageCapture.FLASH_MODE_AUTO) // 设置闪光灯模式，包括自动、开启和关闭等模式
                        //        .setTargetAspectRatio(new Rational(16, 9)) // 设置照片的宽高比
                        //        .setTargetRotation(Surface.ROTATION_90) // 设置照片的旋转角度
                        //        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY) // 设置照片的捕获模式
                        //        .setCropAspectRatio(new Rational(4, 3)) // 设置照片的裁剪宽高比
                        //        .build();
                        imageCapture = new ImageCapture.Builder().build();

                        // cameraSelector用于设置前摄与后摄
//                         CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                         CameraSelector cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA;

                        // 创建ImageAnalysis实例，用于分析图像
                        // 背压策略（Backpressure Strategy）是在处理数据流（例如图像或事件流）时，用于控制数据流速度和压力的一种策略。两种：
                        // 1. 当消费者处理速度较慢时，该策略会跳过中间的帧，只处理最新的帧 ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                        // 2， 保留所有的图像帧 ImageAnalysis.STRATEGY_KEEP_ALL
                        ImageAnalysis imageAnalyzer = new ImageAnalysis.Builder()
                                .setTargetResolution(new Size(480, 640)) // 设置目标分辨率
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST) // 设置背压策略
                                .build();

                        // 设置图像分析器
                        imageAnalyzer.setAnalyzer(mBackgroundExecutor, image -> {
                            int rotationDegrees = image.getImageInfo().getRotationDegrees(); // 获取图像的旋转角度
                            if (SystemClock.elapsedRealtime() - mLastAnalysisResultTime >= 300) { // 检查是否满足分析时间间隔要求
                                AnalysisResult result = analyzeImage(image,
                                        rotationDegrees,
                                        cameraSelector == CameraSelector.DEFAULT_BACK_CAMERA
                                ); // 调用分析图像方法
                                Log.e(TAG, "RESULT: " + result); // 打印分析结果
                                mLastAnalysisResultTime = SystemClock.elapsedRealtime(); // 更新上次分析结果时间
                                if (result != null) {
                                    runOnUiThread(() -> applyToUiAnalyzeImageResult(result)); // 将分析结果应用于UI线程
                                } else {
                                    Log.e(TAG, "RESULT: NULL!!!!!!");
                                }
                            }
                            image.close(); // 关闭图像，释放资源
                        });



                        // 在使用CameraX库进行相机开发时，应用程序通常会将不同的相机用例（如预览、拍照等）绑定到相机对象上，以便实现相应的功能
                        // 当不再需要使用相机或相机用例时，应用程序需要将它们与相机解除绑定关系，释放资源
                        cameraProvider.unbindAll();

                        // 将相机用例配置与相机对象绑定在一起，实现相机预览和拍照等功能
                        cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageAnalyzer, imageCapture);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                },
                ContextCompat.getMainExecutor(this) // 用于创建一个可用于在主线程中执行任务的Executor对象
        );
    }

    private void applyToUiAnalyzeImageResult(AnalysisResult result) {
        mResultView.setResults(result.getResults());
        mResultView.invalidate();
    }

    private PreviewView getCameraPreviewTextureView() {
        mResultView = findViewById(R.id.resultView);
        if (!firstIn) {
            return findViewById(R.id.object_detection_texture_view);
        } else {
            return ((ViewStub) findViewById(R.id.object_detection_texture_view_stub))
                    .inflate()
                    .findViewById(R.id.object_detection_texture_view);
        }
    }

    private AnalysisResult analyzeImage(ImageProxy image, int rotationDegrees, boolean changeToBack) {
        try {
            if (mModule == null) {
//                mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "yolov5s.torchscript.ptl"));
                mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "epoch40facebest.torchscript.pt"));
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading assets", e);
            return null;
        }

        @SuppressLint("UnsafeOptInUsageError") Bitmap bitmap = imgToBitmap(image.getImage());

        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        if(!changeToBack) {
            Matrix matrixMirror = new Matrix();
            matrixMirror.preScale(-1f, 1f);
            matrixMirror.postTranslate(bitmap.getWidth(), 0f);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrixMirror, true);
        }

        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, prePostProcessor.getMInputWidth(), prePostProcessor.getMInputWidth(), true);
        Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, prePostProcessor.getNO_MEAN_RGB(), prePostProcessor.getNO_STD_RGB());
        IValue[] outputTuple = mModule.forward(IValue.from(inputTensor)).toTuple();
        Tensor outputTensor = outputTuple[0].toTensor();
        float[] outputs = outputTensor.getDataAsFloatArray();
        float imgScaleX = bitmap.getWidth() / (float) prePostProcessor.getMInputWidth();
        float imgScaleY = bitmap.getHeight() / (float) prePostProcessor.getMInputHeight();
        float ivScaleX = mResultView.getWidth() / (float) bitmap.getWidth();
        float ivScaleY = mResultView.getHeight() / (float) bitmap.getHeight();
        ArrayList<Result> results = prePostProcessor.outputsToNMSPredictions(outputs, imgScaleX, imgScaleY, ivScaleX, ivScaleY, 0f, 0f);
        return new AnalysisResult(results);
    }


    /**
     * 当相机完成拍摄后的回调方法
     */
    private void takePhoto() {
        if (imageCapture == null)
            return;

        // 创建一个文件对象，使用当前时间作为文件名
        File photoFile = new File(outputDirectory, new SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(System.currentTimeMillis()) + ".jpg");

        // 通过outputOptions，应用程序可以指定保存文件路径，元数据信息，旋转角度等输出文件过程中的操作，如：
        // ImageCapture.Metadata metadata = new ImageCapture.Metadata();
        // metadata.setLocation(location);
        // ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile)
        //        .setMetadata(metadata)
        //        .setCompressFormat(Bitmap.CompressFormat.JPEG)
        //        .setRotation(90)
        //        .setTargetAspectRatio(new Rational(16, 9))
        //        .setTargetRotation(Surface.ROTATION_90)
        //        .build();
        ImageCapture.OutputFileOptions outputOptions = new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        // 重写回调函数
        imageCapture.takePicture(
//                outputOptions, ContextCompat.getMainExecutor(this), new ImageCapture.OnImageSavedCallback() {
//                    @Override
//                    public void onError(@NonNull ImageCaptureException exc) {
//                        Log.e("CameraX", "拍摄失败：" + exc.getMessage(), exc);
//                    }
//
//                    @Override
//                    public void onImageSaved(@NonNull ImageCapture.OutputFileResults output) {
//                        Uri savedUri = Uri.fromFile(photoFile);
//                        String msg = "拍摄完成：" + savedUri;
//                        Toast.makeText(MainActivity.this.getBaseContext(), msg, Toast.LENGTH_LONG).show();
//                        Log.d("CameraX", msg);
//
//                        // 使用MediaScanner通知系统图库更新
//                        // scanFile方法将图片文件的路径作为参数传递给它，系统会检测这个路径下的所有文件，并将它们添加到系统相册数据库中
//                        MediaScannerConnection.scanFile(getBaseContext(), new String[]{photoFile.getPath()}, null, null);
//                    }
//                });
                ContextCompat.getMainExecutor(this), new ImageCapture.OnImageCapturedCallback() {
                    @Override
                    public void onCaptureSuccess(@NonNull ImageProxy image) {
                        @SuppressLint("UnsafeOptInUsageError") Bitmap bitmap = imgToBitmap(image.getImage());
                        // 无论前后摄像头，统一旋转rotate
                        Matrix matrix = new Matrix();
                        matrix.postRotate(image.getImageInfo().getRotationDegrees());
                        mBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    }

                    @Override
                    public void onError(@NonNull ImageCaptureException exception) {
                        super.onError(exception);
                    }
                });
    }

    private Bitmap imgToBitmap(Image image) {
        Image.Plane[] planes = image.getPlanes();
        ByteBuffer yBuffer = planes[0].getBuffer();
        ByteBuffer uBuffer = planes[1].getBuffer();
        ByteBuffer vBuffer = planes[2].getBuffer();
        int ySize = yBuffer.remaining();
        int uSize = uBuffer.remaining();
        int vSize = vBuffer.remaining();
        byte[] nv21 = new byte[ySize + uSize + vSize];
        yBuffer.get(nv21, 0, ySize);
        vBuffer.get(nv21, ySize, vSize);
        uBuffer.get(nv21, ySize + vSize, uSize);
        YuvImage yuvImage = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuvImage.compressToJpeg(new Rect(0, 0, yuvImage.getWidth(), yuvImage.getHeight()), 75, out);
        byte[] imageBytes = out.toByteArray();
        return BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length);
    }


    /**
     * 检查所有请求的权限是否同意
     */
    private boolean allPermissionsGranted() {
        boolean allGranted = true;
        for (String request :
                REQUIRED_PERMISSIONS) {
            allGranted &= ContextCompat.checkSelfPermission(this, request) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    /**
     * 得到图片输出的目录，如果sdcard/Android/media/包名 存在则返回sdcard/Android/media/包名/CameraXSaveDemo/，否则返回data/data/包名/files/
     */
    private File getOutputDirectory() {
        // getExternalMediaDirs返回一个数组，其中包含多个存储设备的路径，每个路径都是File对象
        // 数组中的第一个路径表示应用程序的主要存储设备，而其他路径表示应用程序可以访问的其他存储设备
        File[] exMedias = getExternalMediaDirs();
        try {
            File mediaDir = new File(exMedias[0], getResources().getString(R.string.app_name));
            mediaDir.mkdirs();
            if (mediaDir.exists())
                return mediaDir;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return getFilesDir();
    }

    /**
     * cameraExecutor是一个线程池，当不再需要使用相机捕获图片时，应该及时关闭cameraExecutor线程池，以释放资源并避免内存泄漏
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        cameraExecutor.shutdown();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera();
            } else {
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    public static class AnalysisResult {
        private ArrayList<Result> mResults;

        public AnalysisResult(ArrayList<Result> results) {
            this.mResults = results;
        }

        public ArrayList<Result> getResults() {
            return mResults;
        }

        public void setResults(ArrayList<Result> results) {
            this.mResults = results;
        }
    }

}
