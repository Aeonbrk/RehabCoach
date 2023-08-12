package com.example.myapplication;


import android.os.Bundle;

import android.util.Log;
import android.widget.Button;


import com.blankj.utilcode.util.PathUtils;
import com.blankj.utilcode.util.ResourceUtils;
import mmdeploy.Context;
import mmdeploy.Device;
import mmdeploy.Model;
import mmdeploy.PoseTracker;
import org.opencv.android.*;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.opencv.videoio.VideoCapture;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class CamActivity extends CameraActivity {

    // 静态代码块，在类加载时加载 OpenCV 库
    static {
        System.loadLibrary("opencv_java4");
    }

    // 常量，用于日志标签
    private static final String TAG = "OpencvCam";

    // 视频捕获对象
    public VideoCapture videoCapture;

    // 姿态跟踪器对象
    private PoseTracker poseTracker;

    // JavaCamera2View 视图对象
    private JavaCamera2View javaCameraView;

    // 切换摄像头按钮
    private Button switchCameraBtn;

    private Button backBtn;
    // 姿态跟踪器状态句柄`
    public long stateHandle;

    // 默认使用任意摄像头
    private int cameraId = JavaCamera2View.CAMERA_ID_ANY;

    // CvCameraViewListener2 对象，用于处理相机视图事件
    private CameraBridgeViewBase.CvCameraViewListener2 cvCameraViewListener2 = new CameraBridgeViewBase.CvCameraViewListener2() {
        @Override
        public void onCameraViewStarted(int width, int height) {
            try {
                stateHandle = initMMDeploy(); // 初始化姿态跟踪器
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            Log.i(TAG, "onCameraViewStarted width=" + width + ", height=" + height);
        }

        @Override
        public void onCameraViewStopped() {
            Log.i(TAG, "onCameraViewStopped");
        }

        @Override
        public Mat onCameraFrame(CameraBridgeViewBase.CvCameraViewFrame inputFrame) {
            // 获取相机帧
            org.opencv.core.Mat frame = inputFrame.rgba();

            // 定义缩小后的尺寸
            int newWidth = 512; // 新宽度
            int newHeight = 384; // 新高度

            // 缩小输入帧的大小
            Imgproc.resize(frame, frame, new org.opencv.core.Size(newWidth, newHeight));

            // 将缩小后的帧进行镜像处理
            if(cameraId == JavaCamera2View.CAMERA_ID_FRONT) {
                Core.flip(frame, frame, 0); // 参数1表示水平镜像，0表示垂直镜像
            }

            // 转换颜色空间：RGB 到 BGR
            org.opencv.core.Mat cvMat = new org.opencv.core.Mat();
            Imgproc.cvtColor(frame, cvMat, Imgproc.COLOR_RGB2BGR);

            // 将 OpenCV 的 Mat 转换为 mmdeploy 的 Mat
            mmdeploy.Mat mat = Utils.cvMatToMat(cvMat);


            // 使用姿态跟踪器处理帧
            PoseTracker.Result[] results = new PoseTracker.Result[0];
            try {
                results = poseTracker.apply(stateHandle, mat, -1);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }

            // 绘制姿态跟踪结果并返回绘制后的帧
            return Draw.drawPoseTrackerResult(frame, results);
        }
    };

    // BaseLoaderCallback 对象，用于处理 OpenCV 初始化事件
    private BaseLoaderCallback baseLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            Log.i(TAG, "onManagerConnected status=" + status + ", javaCameraView=" + javaCameraView);
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    if (javaCameraView != null) {
                        // 设置相机视图监听器
                        javaCameraView.setCvCameraViewListener(cvCameraViewListener2);
                        // 启用帧率显示
                        javaCameraView.enableFpsMeter();
                        // 启用相机视图
                        javaCameraView.enableView();
                    }
                }
                break;
                default:
                    super.onManagerConnected(status);
                    break;
            }
        }
    };

    @Override
    protected List<? extends CameraBridgeViewBase> getCameraViewList() {
        Log.i(TAG, "getCameraViewList");
        List<CameraBridgeViewBase> list = new ArrayList<>();
        list.add(javaCameraView);
        return list;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);
        findView(); // 查找视图元素
        setListener(); // 设置监听器
    }

    // 初始化姿态跟踪器
    public long initPoseTracker(String workDir) throws Exception {
        // 定义模型路径
        String detModelPath = workDir + "/rtmdet-nano-ncnn-fp16";
        String poseModelPath = workDir + "/rtmpose-tiny-ncnn-fp16";

        // 定义设备信息
        String deviceName = "cpu";
        int deviceID = 0;

        // 创建模型对象
        Model detModel = new Model(detModelPath);
        Model poseModel = new Model(poseModelPath);

        // 创建设备对象
        Device device = new Device(deviceName, deviceID);
        Context context = new Context();
        context.add(device);

        // 创建姿态跟踪器对象
        poseTracker = new mmdeploy.PoseTracker(detModel, poseModel, context);
        mmdeploy.PoseTracker.Params params = poseTracker.initParams();
        params.detInterval = 5;
        params.poseMaxNumBboxes = 6;

        // 创建姿态跟踪状态句柄
        long stateHandle = poseTracker.createState(params);
        return stateHandle;
    }

    // 初始化 MMDeploy
    public long initMMDeploy() throws Exception {
        String workDir = PathUtils.getExternalAppFilesPath() + File.separator + "file";

        // 从资产目录复制模型文件到工作目录
        if (ResourceUtils.copyFileFromAssets("models", workDir)) {
            return initPoseTracker(workDir);
        }
        return -1;
    }

    // 查找视图元素
    private void findView() {
        javaCameraView = findViewById(R.id.javaCameraView);
        switchCameraBtn = findViewById(R.id.switchCameraBtn);
        backBtn = findViewById(R.id.backBtn);
    }

    // 设置监听器
    private void setListener() {
        switchCameraBtn.setOnClickListener(view -> {
            switch (cameraId) {
                case JavaCamera2View.CAMERA_ID_ANY:
                case JavaCamera2View.CAMERA_ID_BACK:
                    cameraId = JavaCamera2View.CAMERA_ID_FRONT;
                    break;
                case JavaCamera2View.CAMERA_ID_FRONT:
                    cameraId = JavaCamera2View.CAMERA_ID_BACK;
                    break;
            }
            Log.i(TAG, "cameraId : " + cameraId);
            // 关闭并切换相机
            javaCameraView.disableView();
            javaCameraView.setCameraIndex(cameraId);
            javaCameraView.enableView();
        });

        backBtn.setOnClickListener(view -> {
            onPause();
            onBackPressed();
        });
    }

    @Override
    public void onPause() {
        Log.i(TAG, "onPause");
        super.onPause();
        if (javaCameraView != null) {
            // 暂停相机预览
            javaCameraView.disableView();
        }
    }

    @Override
    public void onResume() {
        Log.i(TAG, "onResume");
        super.onResume();
        if (OpenCVLoader.initDebug()) {
            Log.i(TAG, "initDebug true");
            // 初始化成功，连接到相机视图
            baseLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        } else {
            Log.i(TAG, "initDebug false");
            // 异步初始化 OpenCV
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, this, baseLoaderCallback);
        }
    }
}




