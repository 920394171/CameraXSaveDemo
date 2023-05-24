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
其他位置的修改
1. utils中的四个文件为工具类，直接使用即可
2. 需要在src/main下新建assets文件夹，并将训练好并导出为torchscript格式的模型文件和一个每行为一个类别名称的classes.txt文件放进去
3. 需要修改xml文件，详见activity_main.xml
4. 需要修改app下的build.gradle，加入torch依赖
5. 注意android studio和sdk版本问题
 */

public final class MainActivity extends AppCompatActivity {
    private long mLastAnalysisResultTime = 0;
    private ImageCapture imageCapture; // 拍照的用例
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

        // 创建后台线程池
        mBackgroundExecutor = Executors.newSingleThreadExecutor();

        // 判断权限是否已经获得
        if (allPermissionsGranted()) {
            // 如果权限已获得，则启动相机
            startCamera();
        } else {
            // 如果权限未获得，则向用户请求所需权限
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 10);
        }

        try {
            // 加载模型权重文件ptl
            mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "epoch40facebest.torchscript.pt"));

            // 读取类别文件classes.txt，并存储到类别列表中
            List<String> classes = new ArrayList<>();
            InputStream inputStream = getAssets().open("classes.txt");
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                classes.add(line);
            }

            // 将类别列表转换为字符串数组
            PrePostProcessor.mClasses = new String[classes.size()];
            for (int i = 0; i < classes.size(); i++) {
                PrePostProcessor.mClasses[i] = classes.get(i);
            }
        } catch (IOException e) {
            Log.e("ImageSegmentation", "Error reading assets", e);
        }

        // 获取预处理和后处理器的实例
        prePostProcessor = PrePostProcessor.INSTANCE;
    }


    /**
     * 获取位于 Assets 目录下的文件的绝对路径，如果文件已存在于应用的内部存储中则直接返回该路径。
     *
     * @param context   上下文对象
     * @param assetName 要获取的文件名
     * @return 文件的绝对路径
     * @throws IOException 读取或写入文件时可能抛出的异常
     */
    public static String assetFilePath(Context context, String assetName) throws IOException {
        // 创建一个File对象，表示存储在应用内部存储中的目标文件
        File file = new File(context.getFilesDir(), assetName);

        // 如果文件已存在且大小大于0，则表示文件已经被复制到应用的内部存储中，直接返回该文件的绝对路径
        if (file.exists() && file.length() > 0) {
            return file.getAbsolutePath();
        }

        // 如果文件不存在或大小为0，则从Assets中读取文件，并将其复制到应用的内部存储中
        try (InputStream is = context.getAssets().open(assetName);
             FileOutputStream os = new FileOutputStream(file)) {
            byte[] buffer = new byte[4 * 1024];
            int read;
            // 循环读取输入流中的数据，并写入输出流，直到读取完整个文件
            while ((read = is.read(buffer)) != -1) {
                os.write(buffer, 0, read);
            }
            os.flush();
            // 返回复制后的文件的绝对路径
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

                        // 设置图像分析器，需要用到一个线程池，并分析得到的image
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

    /**
     * 对分析结果进行UI显示
     *
     * @param result
     */
    private void applyToUiAnalyzeImageResult(AnalysisResult result) {
        mResultView.setResults(result.getResults());
        // 用于标记一个视图（View）无效，并请求重新绘制
        mResultView.invalidate();
    }

    /**
     * 获取预览的TextureView
     *
     * @return
     */
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

    /**
     * 对图像进行分析和检测
     *
     * @param image
     * @param rotationDegrees 旋转的角度
     * @param changeToBack    是否将图像切换到前面
     * @return
     */
    private AnalysisResult analyzeImage(ImageProxy image, int rotationDegrees, boolean changeToBack) {
        try {
            if (mModule == null) {
                // 初始化模型
                mModule = LiteModuleLoader.load(assetFilePath(getApplicationContext(), "epoch40facebest.torchscript.pt"));
            }
        } catch (IOException e) {
            Log.e(TAG, "Error reading assets", e);
            return null;
        }

        @SuppressLint("UnsafeOptInUsageError") Bitmap bitmap = imgToBitmap(image.getImage());

        // 需要将图片统一顺时针旋转rotationDegrees，让图片恢复成竖直
        Matrix matrix = new Matrix();
        matrix.postRotate(rotationDegrees);
        bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

        // 如果是前摄，则需要镜像翻转
        if (!changeToBack) {
            Matrix matrixMirror = new Matrix();
            matrixMirror.preScale(-1f, 1f);
            matrixMirror.postTranslate(bitmap.getWidth(), 0f);
            bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrixMirror, true);
        }

        // 将bitmap变成规定的输入尺寸
        Bitmap resizedBitmap = Bitmap.createScaledBitmap(bitmap, prePostProcessor.getMInputWidth(), prePostProcessor.getMInputWidth(), true);
        // bitmap转换为tensor格式
        Tensor inputTensor = TensorImageUtils.bitmapToFloat32Tensor(resizedBitmap, prePostProcessor.getNO_MEAN_RGB(), prePostProcessor.getNO_STD_RGB());
        // 推理并得到结果
        IValue[] outputTuple = mModule.forward(IValue.from(inputTensor)).toTuple();
        Tensor outputTensor = outputTuple[0].toTensor();
        float[] outputs = outputTensor.getDataAsFloatArray();
        // 得到bitmap和模型要求的输入的长宽对应的比例、bitmap和模型输出的长款对应比例，用来恢复output中box的位置
        float imgScaleX = bitmap.getWidth() / (float) prePostProcessor.getMInputWidth();
        float imgScaleY = bitmap.getHeight() / (float) prePostProcessor.getMInputHeight();
        float ivScaleX = mResultView.getWidth() / (float) bitmap.getWidth();
        float ivScaleY = mResultView.getHeight() / (float) bitmap.getHeight();
        // 将模型的输出outputs转换成结果列表，每个结果包括矩形的位置，得分和类别
        ArrayList<Result> results = prePostProcessor.outputsToNMSPredictions(outputs, imgScaleX, imgScaleY, ivScaleX, ivScaleY, 0f, 0f);
        return new AnalysisResult(results);
    }


    /**
     * 将图片转化为bitmap，工具方法
     *
     * @param image
     * @return Bitmap
     */
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
     * mBackgroundExecutor是一个线程池，当不再需要使用相机捕获图片时，应该及时关闭mBackgroundExecutor线程池，以释放资源并避免内存泄漏
     */
    @Override
    protected void onDestroy() {
        super.onDestroy();
        mBackgroundExecutor.shutdown();
    }

    /**
     * @param requestCode  请求码
     * @param permissions
     * @param grantResults
     */
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

    /**
     * 保存图片所有分析结果的类
     */
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
