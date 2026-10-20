package com.blackholes.tracker;


import android.content.Context;
import android.hardware.Camera;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;

@SuppressWarnings("deprecation")
public class CameraPreview extends SurfaceView implements SurfaceHolder.Callback {

    private SurfaceHolder holder;
    private Camera camera;
    private int contentWidth;
    private int contentHeight;

    public CameraPreview(Context context, AttributeSet attrs) {
        super(context, attrs);
        holder = getHolder();
        holder.addCallback(this);
        // Deprecated setting, but required on Android versions prior to 3.0
        holder.setType(SurfaceHolder.SURFACE_TYPE_PUSH_BUFFERS);
    }

    public void setCamera(Camera camera) {
        this.camera = camera;
        if (this.camera != null) {
            requestLayout();
            try {
                this.camera.setPreviewDisplay(holder);
                this.camera.startPreview();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Size of the camera frames as they appear on screen (i.e. after the
     * display rotation), so the preview can keep their aspect ratio.
     */
    public void setContentSize(int width, int height) {
        if (width != contentWidth || height != contentHeight) {
            contentWidth = width;
            contentHeight = height;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (contentWidth <= 0 || contentHeight <= 0 || width <= 0 || height <= 0) {
            setMeasuredDimension(width, height);
            return;
        }
        // Scale the frames uniformly until they cover the whole screen instead
        // of stretching them to fit; the parent centers this view
        // (layout_gravity="center") and the overflow falls off the edges.
        // A stretched preview doesn't line up with the AR overlay.
        float scale = Math.max((float) width / contentWidth, (float) height / contentHeight);
        setMeasuredDimension(Math.round(contentWidth * scale), Math.round(contentHeight * scale));
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // The Surface has been created, now tell the camera where to draw the preview.
        try {
            if (camera != null) {
                camera.setPreviewDisplay(holder);
                camera.startPreview();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        // Empty. MainActivity takes care of releasing the Camera preview.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        // If your preview can change or rotate, take care of those events here.
        if (this.holder.getSurface() == null) {
            return;
        }

        try {
            if (camera != null) {
                camera.stopPreview();
            }
        } catch (Exception e) {
            // Ignore: tried to stop a non-existent preview
        }

        try {
            if (camera != null) {
                camera.setPreviewDisplay(this.holder);
                camera.startPreview();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

