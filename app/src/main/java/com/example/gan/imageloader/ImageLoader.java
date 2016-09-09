package com.example.gan.imageloader;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.StatFs;
import android.support.v4.util.LruCache;
import android.widget.ImageView;

import com.jakewharton.disklrucache.DiskLruCache;
import com.orhanobut.logger.Logger;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;


/**
 * Created by gan on 2016/9/9.
 */
public class ImageLoader {
    public static final int MESSAGE_POST_RESULT = 1;
    // 核心线程数
    private static final int CPU_COUNT = Runtime.getRuntime().availableProcessors();
    // 线程池大小
    private static final int CORE_POOL_SIZE = CPU_COUNT + 1;
    // 线程池容纳的最大线程数
    private static final int MAXIMUM_POOL_SIZE = CPU_COUNT * 2 + 1;
    // 非核心线程闲置的超时时长
    private static final long KEEP_ALIVE = 10L;
    // URI的TAG
    private static final int TAG_KEY_URI = 0;

    // 工厂模式创造线程
    private static final ThreadFactory sThreadFactory = new ThreadFactory() {
        private final AtomicInteger mCount = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "ImageLoader---->" + mCount.getAndIncrement());
        }
    };

    // 创建线程池
    public static final Executor THREAD_POOL_EXECUTOR = new ThreadPoolExecutor(
            CORE_POOL_SIZE, MAXIMUM_POOL_SIZE, KEEP_ALIVE, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(), sThreadFactory);
    private static final long DISK_CACHE_SIZE = 1024 * 1024 * 50;
    private static final int DISK_CACHE_INDEX = 0;

    private boolean mIsDiskLruCacheCreater = false;

    // 创建在主线程的Handler
    private Handler mMainHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            LoaderResult result = (LoaderResult) msg.obj;
            ImageView imageView = result.imageView;

//            imageView.setImageBitmap(result.bitmap);

            String uri = (String) imageView.getTag(TAG_KEY_URI);
            if (uri.equals(result.uri)) {
                imageView.setImageBitmap(result.bitmap);
            } else {
                Logger.d("uri has changed,ignored");
            }
        }
    };

    private Context mContext;
    private LruCache<String, Bitmap> mMemoryCache;
    private DiskLruCache mDiskLruCache;
    private ImageResizer mImageResizer = new ImageResizer();

    /**
     * 构造函数,设置内存缓存和磁盘缓存
     */
    private ImageLoader(Context context) {
        mContext = context.getApplicationContext();
        // 内存缓存
        int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        int cacheSize = maxMemory / 8;
        mMemoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getRowBytes() * value.getHeight() / 1024;
            }
        };

        // 磁盘缓存
        File diskCacheDir = getDiskCacheDir(mContext, "bitmap");
        if (!diskCacheDir.exists()) {
            diskCacheDir.mkdirs();
        }
        if (getUsableSpace(diskCacheDir) > DISK_CACHE_SIZE) {
            try {
                mDiskLruCache = DiskLruCache.open(diskCacheDir, 1, 1, DISK_CACHE_SIZE);
                mIsDiskLruCacheCreater = true;
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 得到磁盘缓存目录
     */
    public File getDiskCacheDir(Context context, String uniqueName) {
        //判断SD卡是否存在，并且有读写权限
        boolean externalStorageAvailable = Environment.getExternalStorageState()
                .equals(Environment.MEDIA_MOUNTED);
        final String cachePath;
        if (externalStorageAvailable) {
            cachePath = context.getExternalCacheDir().getPath();
        } else {
            cachePath = context.getCacheDir().getPath();
        }
        return new File(cachePath + File.separator + uniqueName);
    }

    /**
     * 得到可用空间
     */
    @TargetApi(Build.VERSION_CODES.GINGERBREAD)
    private long getUsableSpace(File dir) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
            return dir.getUsableSpace();
        }
        final StatFs statFs = new StatFs(dir.getPath());
        return statFs.getBlockSize() * statFs.getAvailableBlocks();
    }

    /**
     * 返回一个ImageLoader实例
     */
    public static ImageLoader build(Context context) {
        return new ImageLoader(context);
    }

    /**
     * 将bitmap添加到内存缓存
     */
    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            mMemoryCache.put(key, bitmap);
        }
    }

    /**
     * 从内存缓存中读取bitmap
     */
    private Bitmap getBitmapFromMemCache(String key) {
        return mMemoryCache.get(key);
    }

    /**
     * 将bitmap和ImageView绑定
     */
    public void bindBitmap(final String uri, final ImageView imageView) {
        bindBitmap(uri, imageView, 0, 0);
    }

    private void bindBitmap(final String uri, final ImageView imageView, final int reqWidth, final int reqHeight) {
        imageView.setTag(TAG_KEY_URI, uri);
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            imageView.setImageBitmap(bitmap);
            return;
        }

        Runnable loadBitmapTask = new Runnable() {
            @Override
            public void run() {
                Bitmap bitmap = loadBitmap(uri, reqWidth, reqHeight);
                if (bitmap != null) {
                    LoaderResult result = new LoaderResult(imageView, uri, bitmap);
                    mMainHandler.obtainMessage(MESSAGE_POST_RESULT, result).sendToTarget();
                }
            }
        };
        THREAD_POOL_EXECUTOR.execute(loadBitmapTask);
    }

    /**
     * 加载图片
     * 内存--->磁盘--->网络
     */
    public Bitmap loadBitmap(String uri, int reqWidth, int reqHeight) {
        Bitmap bitmap = loadBitmapFromMemCache(uri);
        if (bitmap != null) {
            Logger.d("loadBitmapFromMemCache,url: " + uri);
            return bitmap;
        }
        try {
            bitmap = loadBitmapFromDiskCache(uri, reqWidth, reqHeight);
            if (bitmap != null) {
                Logger.d("loadBitmapFromDiskCache,url: " + uri);
                return bitmap;
            }
            bitmap = loadBitmapFromHttp(uri, reqWidth, reqHeight);
            Logger.d("loadBitmapFromHttp,url: " + uri);

        } catch (IOException e) {
            e.printStackTrace();
        }

        if (bitmap == null && !mIsDiskLruCacheCreater) {
            Logger.d("encounter error, DiskLruCache is not created.");
            bitmap = downloadBitmapFromUrl(uri);
        }
        return bitmap;
    }

    /**
     * 通过uri读取内存缓存
     */
    private Bitmap loadBitmapFromMemCache(String url) {
        final String key = hashKeyFormUrl(url);
        Bitmap bitmap = getBitmapFromMemCache(key);
        return bitmap;
    }

    /**
     * 通过uri读取磁盘缓存
     */
    private Bitmap loadBitmapFromDiskCache(String uri, int width, int height) throws IOException {
        //不允许在UI线程读取磁盘
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Logger.d("load bitmap from UI Thread,it's not recommended");
        }
        if (mDiskLruCache == null) {
            return null;
        }
        Bitmap bitmap = null;
        String key = hashKeyFormUrl(uri);
        DiskLruCache.Snapshot snapshot = mDiskLruCache.get(key);
        if (snapshot != null) {
            FileInputStream fileInputStream = (FileInputStream) snapshot.getInputStream(DISK_CACHE_INDEX);
            FileDescriptor fileDescriptor = fileInputStream.getFD();
            bitmap = mImageResizer
        }
        return bitmap;
    }


    /**
     * 将URL转换成key
     */
    private String hashKeyFormUrl(String url) {
        String cacheKey;
        try {
            final MessageDigest mDigest = MessageDigest.getInstance("MD5");
            mDigest.update(url.getBytes());
            cacheKey = bytesToHexString(mDigest.digest());
        } catch (NoSuchAlgorithmException e) {
            cacheKey = String.valueOf(url.hashCode());
        }
        return cacheKey;
    }

    /**
     * 将Url的字节数组转换成哈希字符串
     */
    private String bytesToHexString(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytes.length; i++) {
            String hex = Integer.toHexString(0xFF & bytes[i]);
            if (hex.length() == 1) {
                sb.append('0');
            }
            sb.append(hex);
        }
        return sb.toString();
    }

    public static class LoaderResult {
        private ImageView imageView;
        private Bitmap bitmap;
        private String uri;

        public LoaderResult(ImageView imageView, String uri, Bitmap bitmap) {
            this.imageView = imageView;
            this.bitmap = bitmap;
            this.uri = uri;
        }
    }
}
