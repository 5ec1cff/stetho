package com.facebook.stetho.inspector.screencast;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Base64;
import android.util.Base64OutputStream;
import android.util.Log;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewHidden;
import android.view.ViewRootImpl;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import com.facebook.stetho.common.LogUtil;
import com.facebook.stetho.inspector.jsonrpc.JsonRpcPeer;
import com.facebook.stetho.inspector.jsonrpc.PeerService;
import com.facebook.stetho.inspector.protocol.module.Page;

import java.io.ByteArrayOutputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

// https://cs.android.com/android-studio/platform/tools/base/+/mirror-goog-studio-main:dynamic-layout-inspector/agent/appinspection/src/main/com/android/tools/agent/appinspection/ViewLayoutInspector.kt;drc=5ccf71cb71a5d2de925c85a27e8ec0f40b03d7aa

@SuppressLint("NewApi")
public class ScreenDispatcher extends PeerService implements InspectingObject.OnInspectingRootChangedListener {

    private boolean mIsRunning = false;
    private boolean mDestroyed = false;
    private final Page.ScreencastFrameEvent mEvent = new Page.ScreencastFrameEvent();
    private final Page.ScreencastFrameEventMetadata mMetadata = new Page.ScreencastFrameEventMetadata();

    private Page.StartScreencastRequest mRequest;
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private ByteArrayOutputStream mStream;
    private final InspectingObject mInspecting;

    private AtomicReference<CastingView> mCastingView = new AtomicReference<>(null);

    private static class CastingData {
        CastingView source;
        Bitmap bitmap;
    }

    private class CastingView implements PixelCopy.OnPixelCopyFinishedListener, ViewTreeObserver.OnDrawListener {
        float scaleX = 1;
        float scaleY = 1;
        View target;
        Bitmap bitmap;

        Page.StartScreencastRequest request;

        @Override
        public void onPixelCopyFinished(int i) {
            if (i == 0) {
                CastingData data = new CastingData();
                data.bitmap = bitmap;
                data.source = this;
                Message.obtain(mHandler, MSG_CAST, data).sendToTarget();
            } else {
                Log.e("ScreenDispatcher", "request pixel copy failed: " + i);
            }
        }

        @Override
        public void onDraw() {
            try {
                ViewRootImpl vri = ((ViewHidden) (Object) target).getViewRootImpl();
                if (vri == null || vri.mSurface == null) return;
                if (!vri.mSurface.isValid()) return;
                int viewWidth = target.getWidth();
                int viewHeight = target.getHeight();
                int[] location = new int[2];
                target.getLocationInSurface(location);
                Rect bounds = new Rect(location[0], location[1], viewWidth + location[0], viewHeight + location[1]);
                float scale = Math.min((float) request.maxWidth / (float) viewWidth,
                        (float) request.maxHeight / (float) viewHeight);
                int destWidth = (int) (viewWidth * scale);
                int destHeight = (int) (viewHeight * scale);
                if (destWidth == 0 || destHeight == 0) {
                    return;
                }
                bitmap = Bitmap.createBitmap(destWidth, destHeight, Bitmap.Config.RGB_565);
                PixelCopy.request(vri.mSurface, bounds, bitmap, this, mHandler);
                scaleX = scaleY = scale;
            } catch (OutOfMemoryError e) {
                LogUtil.w("Out of memory trying to allocate screencast Bitmap.");
            }
        }

        void start() {
            target.post(() -> {
                ViewTreeObserver vto = target.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.addOnDrawListener(this);
                    target.invalidate();
                }
            });
        }

        void stop() {
            target.post(() -> {
                ViewTreeObserver vto = target.getViewTreeObserver();
                if (vto.isAlive()) {
                    vto.removeOnDrawListener(this);
                }
            });
        }
    }
    private static final int MSG_CAST = 1;
    private static final int MSG_START = 2;
    private static final int MSG_STOP = 3;
    private static final int MSG_CASTING_VIEW_CHANGED = 4;
    private static final int MSG_DESTROY = 5;


    public ScreenDispatcher(JsonRpcPeer peer) {
        super(peer);
        mInspecting = peer.getService(InspectingObject.class);
        mHandlerThread = new HandlerThread("Screencast Dispatcher");
        mHandlerThread.start();
        mStream = new ByteArrayOutputStream();
        mHandler = new Handler(mHandlerThread.getLooper()) {
            @Override
            public void handleMessage(@NonNull Message msg) {
                if (mDestroyed) return;
                switch (msg.what) {
                    case MSG_START: {
                        if (!mIsRunning) {
                            mIsRunning = true;
                            mRequest = (Page.StartScreencastRequest) msg.obj;
                            updateCasting();
                            mInspecting.registerInspectingRootChangedListener(ScreenDispatcher.this);
                        }
                        break;
                    }
                    case MSG_DESTROY:
                        // fallthrough
                    case MSG_STOP:
                        if (mIsRunning) {
                            mIsRunning = false;
                            updateCasting();
                            mInspecting.unregisterInspectingRootChangedListener(ScreenDispatcher.this);
                        }
                        if (msg.what == MSG_DESTROY) {
                            Log.d("ScreenDispatcher", "ScreenDispatcher destroy");
                            Objects.requireNonNull(Looper.myLooper()).quitSafely();
                            mDestroyed = true;
                            mHandler = null;
                            mHandlerThread = null;
                            mStream = null;
                        }
                        break;
                    case MSG_CASTING_VIEW_CHANGED: {
                        if (mIsRunning) updateCasting();
                        break;
                    }
                    case MSG_CAST: {
                        if (mIsRunning) {
                            CastingData data = (CastingData) msg.obj;
                            CastingView current = mCastingView.get();
                            if (current != data.source) break;
                            cast(data.bitmap);
                        }
                        break;
                    }
                }
            }
        };
    }

    private void cast(Bitmap bitmap) {
        mStream.reset();
        Base64OutputStream base64Stream = new Base64OutputStream(mStream, Base64.NO_WRAP);
        // request format is either "jpeg" or "png"
        Bitmap.CompressFormat format = Bitmap.CompressFormat.valueOf(mRequest.format.toUpperCase());
        bitmap.compress(format, mRequest.quality, base64Stream);
        mEvent.data = mStream.toString();
        mMetadata.pageScaleFactor = 1;
        mMetadata.deviceWidth = bitmap.getWidth();
        mMetadata.deviceHeight = bitmap.getHeight();
        mMetadata.timestamp = System.currentTimeMillis() / 1000.;
        mEvent.metadata = mMetadata;
        getPeer().invokeMethod("Page.screencastFrame", mEvent, null);
    }

    private void updateCasting() {
        CastingView old = mCastingView.get();
        View oldTarget = null;
        if (old != null) {
            oldTarget = old.target;
            old.stop();
        }
        View newRoot;
        if (mIsRunning) newRoot = mInspecting.inspectingRoot();
        else newRoot = null;
        Log.d("stetho", "update casting " + oldTarget + " -> " + newRoot);
        if (newRoot != null) {
            CastingView newCastingView = new CastingView();
            newCastingView.target = newRoot;
            newCastingView.request = mRequest;
            newCastingView.start();
            mCastingView.set(newCastingView);
        } else {
            mCastingView.set(null);
        }
    }

    @Override
    protected void onDisconnect() {
        if (mDestroyed) return;
        Log.d("stetho", "Destroying screencast");
        mHandler.sendEmptyMessage(MSG_DESTROY);
    }

    public void getScale(float[] out) {
        CastingView v = mCastingView.get();
        if (v != null) {
            out[0] = v.scaleX;
            out[1] = v.scaleY;
        } else {
            out[0] = 1;
            out[1] = 1;
        }
    }

    public void startScreencast(Page.StartScreencastRequest request) {
        if (mDestroyed) return;
        Log.d("stetho", "Starting screencast");
        mHandler.removeMessages(MSG_START);
        mHandler.removeMessages(MSG_STOP);
        Message.obtain(mHandler, MSG_START, request).sendToTarget();
    }

    public void stopScreencast() {
        if (mDestroyed) return;
        Log.d("stetho", "Stopping screencast");
        Message.obtain(mHandler, MSG_STOP).sendToTarget();
    }

    @Override
    public void onInspectingRootChanged() {
        if (mDestroyed) return;
        mHandler.removeMessages(MSG_CASTING_VIEW_CHANGED);
        mHandler.sendEmptyMessage(MSG_CASTING_VIEW_CHANGED);
    }
}
