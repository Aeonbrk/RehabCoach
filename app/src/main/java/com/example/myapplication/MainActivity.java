package com.example.myapplication;

import android.annotation.SuppressLint;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.Button;
import android.content.Intent;
import android.widget.ImageView;
import android.widget.Toast;
import android.database.Cursor;
import android.provider.MediaStore;


import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.*;
import com.blankj.utilcode.util.PathUtils;
import com.blankj.utilcode.util.ResourceUtils;

import mmdeploy.*;

import java.io.File;
import java.lang.*;

public class MainActivity extends AppCompatActivity {
    // 声明私有变量
    private Button addVideoButton; // 添加视频按钮
    private Button addCameraButton; // 添加相机按钮
    private ImageView videoFrameView; // 视频帧展示视图
    private PoseTracker poseTracker; // 姿态追踪器
    private long stateHandle; // 状态句柄
    private int frameID; // 帧ID

    private VideoCapture videoCapture; // 视频捕获

    // 加载 OpenCV 的库
    static {
        System.loadLibrary("opencv_java4");
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            stateHandle = initMMDeploy(); // 初始化 MMDeploy
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        setContentView(R.layout.activity_main); // 设置布局
        this.addVideoButton = (Button) findViewById(R.id.addVideoButton); // 获取添加视频按钮
        this.videoFrameView = (ImageView) findViewById(R.id.videoFrameView); // 获取视频帧展示视图

        // 设置添加视频按钮的点击监听
        this.addVideoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // 检查是否有写入外部存储的权限
                if (ContextCompat.checkSelfPermission(MainActivity.this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(MainActivity.this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
                } else {
                    openAlbum(); // 打开相册
                }
            }
        });

        // 获取并设置添加相机按钮的点击监听
        this.addCameraButton = findViewById(R.id.addCameraButton);
        this.addCameraButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent();
                intent.setClass(MainActivity.this, CamActivity.class); // 跳转到 CamActivity
                startActivity(intent);
            }
        });
    }

    // 打开相册方法
    private void openAlbum() {
        Intent intent = new Intent();
        intent.setAction(Intent.ACTION_PICK); // 设置动作为选择内容
        intent.setType("video/*"); // 设置类型为视频
        startActivityForResult(intent, 2); // 启动活动并等待结果
    }

    // 初始化姿态追踪器方法
    public long initPoseTracker(String workDir) throws Exception {
        // 定义模型路径和设备信息
        String detModelPath = workDir + "/rtmdet-nano-ncnn-fp16";
        String poseModelPath = workDir + "/rtmpose-tiny-ncnn-fp16";
        String deviceName = "cpu";
        int deviceID = 0;

        // 创建模型和设备实例
        Model detModel = new Model(detModelPath);
        Model poseModel = new Model(poseModelPath);
        Device device = new Device(deviceName, deviceID);
        Context context = new Context();
        context.add(device);

        // 创建姿态追踪器实例
        poseTracker = new mmdeploy.PoseTracker(detModel, poseModel, context);
        mmdeploy.PoseTracker.Params params = poseTracker.initParams();
        params.detInterval = 5;
        params.poseMaxNumBboxes = 6;
        long stateHandle = poseTracker.createState(params);
        return stateHandle;
    }

    // 初始化 MMDeploy 方法
    public long initMMDeploy() throws Exception {
        // 定义工作目录路径
        String workDir = PathUtils.getExternalAppFilesPath() + File.separator + "file";

        // 从 assets 中复制模型文件到工作目录
        if (ResourceUtils.copyFileFromAssets("models", workDir)) {
            return initPoseTracker(workDir); // 初始化姿态追踪器
        }
        return -1;
    }

    // 处理权限请求结果
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                openAlbum(); // 打开相册
            } else {
                Toast.makeText(MainActivity.this, "拒绝访问存储权限。", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // 处理活动返回结果
    @SuppressLint("Range")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 2) {
            String path = null;
            Uri uri = data.getData();
            System.out.printf("调试：URI 方案是 %s\n", uri.getScheme());

            // 查询媒体内容的数据
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                if (cursor.moveToFirst()) {
                    path = cursor.getString(cursor.getColumnIndex(MediaStore.Images.Media.DATA));
                }
                cursor.close();
            }

            // 使用路径创建视频捕获对象
            this.videoCapture = new VideoCapture(path, org.opencv.videoio.Videoio.CAP_ANDROID);
            if (!this.videoCapture.isOpened()) {
                System.out.printf("无法打开视频：%s", path);
            }
            Double fps = videoCapture.get(org.opencv.videoio.Videoio.CAP_PROP_FPS);
            frameID = 0;
            Handler handler = new Handler();
            Runnable drawThread = new Runnable() {
                org.opencv.core.Mat frame = new org.opencv.core.Mat();
                public void run() {
                    videoCapture.read(frame);
                    System.out.printf("处理帧 %d\n", frameID);
                    if (frame.empty()) {
                        return;
                    }
                    org.opencv.core.Mat cvMat = new org.opencv.core.Mat();
                    Imgproc.cvtColor(frame, cvMat, Imgproc.COLOR_RGB2BGR);
                    Mat mat = Utils.cvMatToMat(cvMat);
                    if (stateHandle == -1) {
                        System.out.println("状态创建失败！");
                        return;
                    }
                    PoseTracker.Result[] results = new PoseTracker.Result[0];
                    try {
                        results = poseTracker.apply(stateHandle, mat, -1);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    Draw.drawPoseTrackerResult(frame, results);
                    Bitmap bitmap = null;
                    bitmap = Bitmap.createBitmap(frame.width(), frame.height(), Bitmap.Config.ARGB_8888);
                    org.opencv.android.Utils.matToBitmap(frame, bitmap);
                    videoFrameView.setImageBitmap(bitmap);
                    videoFrameView.invalidate();
                    handler.postDelayed(this, (long) (1000 / fps));
                    frameID++;
                }
            };
            handler.post(drawThread);
        }
    }
}
