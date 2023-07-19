package com.facebook.stetho.inspector.screencast;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.view.View;
import android.view.ViewTreeObserver;

import androidx.annotation.MainThread;

import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.inspector.DomainContext;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.protocol.module.Page;

import java.io.ByteArrayOutputStream;
import java.lang.ref.WeakReference;

public class ScreencastDispatcher2 implements DomainContext.OnInspectingRootChangedListener {
    private final DomainContext mDomainContext;
    private boolean mIsRunning = false;
    private JsonRpcPeer mPeer;
    private WeakReference<View> mCastingView = null;
    private final Page.ScreencastFrameEvent mEvent = new Page.ScreencastFrameEvent();
    private final Page.ScreencastFrameEventMetadata mMetadata = new Page.ScreencastFrameEventMetadata();

    private Page.StartScreencastRequest mRequest;
    private Bitmap mBitmap;
    private final Canvas mCanvas = new Canvas();
    private HandlerThread mHandlerThread;
    private Handler mBackgroundHandler;
    private ByteArrayOutputStream mStream;
    private Handler mMainHandler;

    private final ViewTreeObserver.OnPreDrawListener mPreDrawListener = () -> {
        drawAndCast();
        return true;
    };
    private final Runnable mBackgroundRunnable = () -> {
        if (!mIsRunning || mBitmap == null) {
            return;
        }
        int width = mBitmap.getWidth();
        int height = mBitmap.getHeight();
        mStream.reset();
        Base64OutputStream base64Stream = new Base64OutputStream(mStream, Base64.DEFAULT);
        // request format is either "jpeg" or "png"
        Bitmap.CompressFormat format = Bitmap.CompressFormat.valueOf(mRequest.format.toUpperCase());
        mBitmap.compress(format, mRequest.quality, base64Stream);
        mEvent.data = mStream.toString();
        mMetadata.pageScaleFactor = 1;
        mMetadata.deviceWidth = width;
        mMetadata.deviceHeight = height;
        mEvent.metadata = mMetadata;
        mPeer.invokeMethod("Page.screencastFrame", mEvent, null);
    };

    @Override
    public void onInspectingRootChanged() {
        updateCastingView(mDomainContext.inspectingRoot());
    }

    private View getCastingView() {
        if (mCastingView == null) return null;
        return mCastingView.get();
    }

    @MainThread
    private void updateCastingView(View newView) {
        View old = getCastingView();
        if (old != null) {
            ViewTreeObserver observer = old.getViewTreeObserver();
            if (observer.isAlive()) {
                observer.removeOnPreDrawListener(mPreDrawListener);
            }
        }
        if (!mIsRunning) return;
        if (newView != null) {
            ViewTreeObserver observer = newView.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(mPreDrawListener);
            mCastingView = new WeakReference<>(newView);
            newView.invalidate();
        } else {
            mCastingView = null;
        }
    };

    public ScreencastDispatcher2(DomainContext domainContext) {
        mDomainContext = domainContext;
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    public void startScreencast(JsonRpcPeer peer, Page.StartScreencastRequest request) {
        LogUtil.d("Starting screencast2");
        mRequest = request;
        mPeer = peer;
        mHandlerThread = new HandlerThread("Screencast Dispatcher");
        mHandlerThread.start();
        mBackgroundHandler = new Handler(mHandlerThread.getLooper());
        mStream = new ByteArrayOutputStream();
        mIsRunning = true;
        mMainHandler.post(() -> {
            updateCastingView(mDomainContext.inspectingRoot());
        });
        mDomainContext.registerInspectingRootChangedListener(this);
    }

    public void stopScreencast() {
        LogUtil.d("Stopping screencast2");
        mBackgroundHandler.post(() -> {
            mBackgroundHandler.removeCallbacks(mBackgroundRunnable);
            mIsRunning = false;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
                mHandlerThread.quitSafely();
            } else {
                mHandlerThread.interrupt();
            }
            mHandlerThread = null;
            mBitmap = null;
            mStream = null;
        });
        mMainHandler.post(() -> {
            updateCastingView(null);
        });
        mDomainContext.unregisterInspectingRootChangedListener(this);
    }

    @MainThread
    private void drawAndCast() {
        if (!mIsRunning) return;
        updateCastingView(mDomainContext.inspectingRoot());
        View rootView = getCastingView();
        if (rootView == null) return;
        try {
            int viewWidth = rootView.getWidth();
            int viewHeight = rootView.getHeight();
            float scale = Math.min((float) mRequest.maxWidth / (float) viewWidth,
                    (float) mRequest.maxHeight / (float) viewHeight);
            int destWidth = (int) (viewWidth * scale);
            int destHeight = (int) (viewHeight * scale);
            if (destWidth == 0 || destHeight == 0) {
                return;
            }
            mDomainContext.scaleX = mDomainContext.scaleY = scale;
            mBitmap = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.RGB_565);
            Matrix matrix = new Matrix();
            mCanvas.setBitmap(mBitmap);
            matrix.postScale(scale, scale);
            mCanvas.setMatrix(matrix);
            rootView.draw(mCanvas);
            mBackgroundHandler.post(mBackgroundRunnable);
        } catch (OutOfMemoryError e) {
            LogUtil.w("Out of memory trying to allocate screencast Bitmap.");
        }
    }
}
