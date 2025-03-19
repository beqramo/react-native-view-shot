package fr.greweb.reactnativeviewshot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.StringDef;

import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.uimanager.NativeViewHierarchyManager;
import com.facebook.react.uimanager.UIBlock;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.Deflater;

import javax.annotation.Nullable;

import static android.view.View.VISIBLE;

import android.graphics.drawable.Drawable;
import android.graphics.PorterDuff;

/**
 * Snapshot utility class allow to screenshot a view.
 */
public class ViewShot implements UIBlock {
    //region Constants
    /**
     * Tag fort Class logs.
     */
    private static final String TAG = ViewShot.class.getSimpleName();
    /**
     * Error code that we return to RN.
     */
    public static final String ERROR_UNABLE_TO_SNAPSHOT = "E_UNABLE_TO_SNAPSHOT";
    /**
     * pre-allocated output stream size for screenshot. In real life example it will eb around 7Mb.
     */
    private static final int PREALLOCATE_SIZE = 64 * 1024;
    /**
     * ARGB size in bytes.
     */
    private static final int ARGB_SIZE = 4;
    /**
     * Wait timeout for surface view capture.
     */
    private static final int SURFACE_VIEW_READ_PIXELS_TIMEOUT = 5;

    @SuppressWarnings("WeakerAccess")
    @IntDef({Formats.JPEG, Formats.PNG, Formats.WEBP, Formats.RAW})
    public @interface Formats {
        int JPEG = 0; // Bitmap.CompressFormat.JPEG.ordinal();
        int PNG = 1;  // Bitmap.CompressFormat.PNG.ordinal();
        int WEBP = 2; // Bitmap.CompressFormat.WEBP.ordinal();
        int RAW = -1;

        Bitmap.CompressFormat[] mapping = {
                Bitmap.CompressFormat.JPEG,
                Bitmap.CompressFormat.PNG,
                Bitmap.CompressFormat.WEBP
        };
    }

    /**
     * Supported Output results.
     */
    @StringDef({Results.BASE_64, Results.DATA_URI, Results.TEMP_FILE, Results.ZIP_BASE_64})
    public @interface Results {
        /**
         * Save screenshot as temp file on device.
         */
        String TEMP_FILE = "tmpfile";
        /**
         * Base 64 encoded image.
         */
        String BASE_64 = "base64";
        /**
         * Zipped RAW image in base 64 encoding.
         */
        String ZIP_BASE_64 = "zip-base64";
        /**
         * Base64 data uri.
         */
        String DATA_URI = "data-uri";
    }
    //endregion

    //region Static members
    /**
     * Image output buffer used as a source for base64 encoding
     */
    private static byte[] outputBuffer = new byte[PREALLOCATE_SIZE];
    //endregion

    //region Class members
    private final int tag;
    private final String extension;
    @Formats
    private final int format;
    private final double quality;
    private final Integer width;
    private final Integer height;
    private final File output;
    @Results
    private final String result;
    private final Promise promise;
    private final Boolean snapshotContentContainer;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final ReactApplicationContext reactContext;
    private final boolean handleGLSurfaceView;
    private final Activity currentActivity;
    private final Executor executor;
    //endregion

    //region Constructors
    @SuppressWarnings("WeakerAccess")
    public ViewShot(
            final int tag,
            final String extension,
            @Formats final int format,
            final double quality,
            @Nullable Integer width,
            @Nullable Integer height,
            final File output,
            @Results final String result,
            final Boolean snapshotContentContainer,
            final ReactApplicationContext reactContext,
            final Activity currentActivity,
            final boolean handleGLSurfaceView,
            final Promise promise,
            final Executor executor) {
        this.tag = tag;
        this.extension = extension;
        this.format = format;
        this.quality = quality;
        this.width = width;
        this.height = height;
        this.output = output;
        this.result = result;
        this.snapshotContentContainer = snapshotContentContainer;
        this.reactContext = reactContext;
        this.currentActivity = currentActivity;
        this.handleGLSurfaceView = handleGLSurfaceView;
        this.promise = promise;
        this.executor = executor;
    }
    //endregion

    //region Overrides
    @Override
    public void execute(final NativeViewHierarchyManager nativeViewHierarchyManager) {
        executor.execute(new Runnable () {
            @Override
            public void run() {
                try {
                    final View view;

                    if (tag == -1) {
                        // Full screen capture requires special handling
                        view = currentActivity.getWindow().getDecorView().findViewById(android.R.id.content);

                        // For full screen captures, don't use our complex logic, use
                        // simpler and more direct methods
                        if (view != null) {
                            captureScreenDirectly(view);
                            return;
                        } else {
                            Log.e(TAG, "No root view found for full screen capture");
                            promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "No root view found for full screen capture");
                            return;
                        }
                    } else {
                        // Component/specific view capture
                        view = nativeViewHierarchyManager.resolveView(tag);
                    }

                    if (view == null) {
                        Log.e(TAG, "No view found with reactTag: " + tag, new AssertionError());
                        promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "No view found with reactTag: " + tag);
                        return;
                    }

                    final ReusableByteArrayOutputStream stream = new ReusableByteArrayOutputStream(outputBuffer);
                    stream.setSize(proposeSize(view));
                    outputBuffer = stream.innerBuffer();

                    if (Results.TEMP_FILE.equals(result) && Formats.RAW == format) {
                        saveToRawFileOnDevice(view);
                    } else if (Results.TEMP_FILE.equals(result) && Formats.RAW != format) {
                        saveToTempFileOnDevice(view);
                    } else if (Results.BASE_64.equals(result) || Results.ZIP_BASE_64.equals(result)) {
                        saveToBase64String(view);
                    } else if (Results.DATA_URI.equals(result)) {
                        saveToDataUriString(view);
                    }
                } catch (final Throwable ex) {
                    Log.e(TAG, "Failed to capture view snapshot", ex);
                    promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Failed to capture view snapshot");
                }
            }
        });
    }
    //endregion

    //region Implementation
    private void saveToTempFileOnDevice(@NonNull final View view) throws IOException {
        final FileOutputStream fos = new FileOutputStream(output);
        captureView(view, fos);

        promise.resolve(Uri.fromFile(output).toString());
    }

    private void saveToRawFileOnDevice(@NonNull final View view) throws IOException {
        final String uri = Uri.fromFile(output).toString();

        final FileOutputStream fos = new FileOutputStream(output);
        final ReusableByteArrayOutputStream os = new ReusableByteArrayOutputStream(outputBuffer);
        final Point size = captureView(view, os);

        // in case of buffer grow that will be a new array with bigger size
        outputBuffer = os.innerBuffer();
        final int length = os.size();
        final String resolution = String.format(Locale.US, "%d:%d|", size.x, size.y);

        fos.write(resolution.getBytes(Charset.forName("US-ASCII")));
        fos.write(outputBuffer, 0, length);
        fos.close();

        promise.resolve(uri);
    }

    private void saveToDataUriString(@NonNull final View view) throws IOException {
        final ReusableByteArrayOutputStream os = new ReusableByteArrayOutputStream(outputBuffer);
        captureView(view, os);

        outputBuffer = os.innerBuffer();
        final int length = os.size();

        final String data = Base64.encodeToString(outputBuffer, 0, length, Base64.NO_WRAP);

        // correct the extension if JPG
        final String imageFormat = "jpg".equals(extension) ? "jpeg" : extension;

        promise.resolve("data:image/" + imageFormat + ";base64," + data);
    }

    private void saveToBase64String(@NonNull final View view) throws IOException {
        final boolean isRaw = Formats.RAW == this.format;
        final boolean isZippedBase64 = Results.ZIP_BASE_64.equals(this.result);

        final ReusableByteArrayOutputStream os = new ReusableByteArrayOutputStream(outputBuffer);
        final Point size = captureView(view, os);

        // in case of buffer grow that will be a new array with bigger size
        outputBuffer = os.innerBuffer();
        final int length = os.size();
        final String resolution = String.format(Locale.US, "%d:%d|", size.x, size.y);
        final String header = (isRaw ? resolution : "");
        final String data;

        if (isZippedBase64) {
            final Deflater deflater = new Deflater();
            deflater.setInput(outputBuffer, 0, length);
            deflater.finish();

            final ReusableByteArrayOutputStream zipped = new ReusableByteArrayOutputStream(new byte[32]);
            byte[] buffer = new byte[1024];
            while (!deflater.finished()) {
                int count = deflater.deflate(buffer); // returns the generated code... index
                zipped.write(buffer, 0, count);
            }

            data = header + Base64.encodeToString(zipped.innerBuffer(), 0, zipped.size(), Base64.NO_WRAP);
        } else {
            data = header + Base64.encodeToString(outputBuffer, 0, length, Base64.NO_WRAP);
        }

        promise.resolve(data);
    }

    @NonNull
    private List<View> getAllChildren(@NonNull final View v) {
        try {
        if (!(v instanceof ViewGroup)) {
            final ArrayList<View> viewArrayList = new ArrayList<>();
            viewArrayList.add(v);
            return viewArrayList;
        }

        final ArrayList<View> result = new ArrayList<>();

            // First add the parent itself - this ensures TextureView and SurfaceView
            // at the root level are processed correctly
            result.add(v);

            // Then only add special views (TextureView, SurfaceView) from children
            // to avoid duplicating content that's already captured by the parent draw()
        ViewGroup viewGroup = (ViewGroup) v;
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
                try {
            View child = viewGroup.getChildAt(i);
                    if (child == null || child.getVisibility() != View.VISIBLE) continue;

                    // Only collect TextureView and SurfaceView as they need special handling
                    if (child instanceof TextureView || child instanceof SurfaceView) {
                        result.add(child);
                    } else if (child instanceof ViewGroup) {
                        // For other ViewGroups, only look for TextureView and SurfaceView children
                        for (View specialView : findSpecialViews((ViewGroup) child)) {
                            result.add(specialView);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error getting child at index " + i + ": " + e.getMessage(), e);
                }
        }

        return result;
        } catch (Exception e) {
            Log.e(TAG, "Error in getAllChildren: " + e.getMessage(), e);
            // Return an empty list to prevent null pointer exceptions
            return new ArrayList<>();
        }
    }

    /**
     * Find only TextureView and SurfaceView instances in a ViewGroup hierarchy
     */
    private List<View> findSpecialViews(ViewGroup viewGroup) {
        List<View> specialViews = new ArrayList<>();

        try {
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                View child = viewGroup.getChildAt(i);
                if (child == null || child.getVisibility() != View.VISIBLE) continue;

                if (child instanceof TextureView || child instanceof SurfaceView) {
                    specialViews.add(child);
                } else if (child instanceof ViewGroup) {
                    specialViews.addAll(findSpecialViews((ViewGroup) child));
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Error finding special views: " + e.getMessage(), e);
        }

        return specialViews;
    }

    /**
     * Wrap {@link #captureViewImpl(View, OutputStream)} call and on end close output stream.
     */
    private Point captureView(@NonNull final View view, @NonNull final OutputStream os) throws IOException {
        try {
            return captureViewImpl(view, os);
        } finally {
            os.close();
        }
    }

    /**
     * Screenshot a view and return the captured bitmap.
     *
     * @param view the view to capture
     * @return screenshot resolution, Width * Height
     */
    private Point captureViewImpl(@NonNull final View view, @NonNull final OutputStream os) {
        try {
        int w = view.getWidth();
        int h = view.getHeight();

        if (w <= 0 || h <= 0) {
            throw new RuntimeException("Impossible to snapshot the view: view is invalid");
        }

            Log.d(TAG, "Initial view dimensions: " + w + "x" + h);

            // Calculate heights and dimensions first
            if (snapshotContentContainer && view instanceof ScrollView) {
                try {
            ScrollView scrollView = (ScrollView) view;
                    if (scrollView.getChildCount() > 0) {
                        scrollView.measure(
                            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                        );
                        View content = scrollView.getChildAt(0);
                        int contentHeight = content.getMeasuredHeight();
                        h = contentHeight + scrollView.getPaddingTop() + scrollView.getPaddingBottom();
                        Log.d(TAG, "Adjusted ScrollView height to: " + h);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error measuring ScrollView content: " + e.getMessage());
                    // Continue with original height if measurement fails
                }
            }
            // Special handling for ViewGroups to capture entire content height (especially for React Native refs)
            else if (view instanceof ViewGroup) {
                try {
                    ViewGroup viewGroup = (ViewGroup) view;

                    // Calculate total content height for the ViewGroup
                    int totalHeight = calculateTotalHeight(viewGroup);

                    // Only use the calculated height if it's greater than the current view height
                    // This prevents cutting off content in cases where the ref contains more content than what's visible
                    if (totalHeight > h) {
                        Log.d(TAG, "Adjusted ViewGroup height from " + h + " to: " + totalHeight);
                        h = totalHeight;
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error calculating ViewGroup content height: " + e.getMessage());
                    // Continue with original height if measurement fails
                }
            }

            Point resolution = new Point(w, h);
            Bitmap bitmap = null;
            Canvas canvas = null;

            try {
                bitmap = getBitmapForScreenshot(w, h);
                canvas = new Canvas(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error creating bitmap or canvas: " + e.getMessage(), e);
                return resolution; // Return early with just the resolution
            }

            // Use a flag to track if we've already rendered the view
            boolean viewAlreadyRendered = false;

            // NOW handle the ScrollView rendering after we have a valid canvas
            if (snapshotContentContainer && view instanceof ScrollView) {
                try {
                    ScrollView scrollView = (ScrollView) view;
                    if (scrollView.getChildCount() > 0) {
                        // Save original scroll position
                        int originalScrollY = scrollView.getScrollY();
                        View content = scrollView.getChildAt(0);

                        try {
                            // Use a solid white background instead of transparent
                            try {
                                canvas.drawColor(Color.WHITE);
                            } catch (Exception e) {
                                Log.e(TAG, "Error drawing background color: " + e.getMessage(), e);
                            }

                            // Reset scroll position for capture
                            scrollView.setScrollY(0);

                            // Better approach: Draw just the content (child) directly
                            // This prevents recursive rendering of the parent ScrollView
                            int saveCount = 0;
                            try {
                                saveCount = canvas.save();
                            } catch (Exception e) {
                                Log.e(TAG, "Error saving canvas state: " + e.getMessage(), e);
                            }

                            try {
                                // Apply padding offset
                                canvas.translate(scrollView.getPaddingLeft(), scrollView.getPaddingTop());

                                // Draw only the content, not the ScrollView itself
                                if (content instanceof ViewGroup) {
                                    // For ViewGroups, use draw instead of protected dispatchDraw
                                    // Clear the background first to avoid double-drawing
                                    Drawable bg = content.getBackground();
                                    content.setBackground(null);
                                    content.draw(canvas);
                                    content.setBackground(bg);
                                } else {
                                    // For regular views
                                    content.draw(canvas);
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error drawing ScrollView content: " + e.getMessage(), e);
                                // Fallback: try direct draw of content if dispatch fails
                                try {
                                    content.draw(canvas);
                                } catch (Exception ce) {
                                    Log.e(TAG, "Error in fallback content draw: " + ce.getMessage(), ce);
                                }
                            }

                            try {
                                canvas.restoreToCount(saveCount);
                            } catch (Exception e) {
                                Log.e(TAG, "Error restoring canvas state: " + e.getMessage(), e);
                            }

                            // Mark that we've already rendered the view
                            viewAlreadyRendered = true;

                            // Log for debugging
                            Log.d(TAG, "ScrollView capture complete using simplified approach");
                        } finally {
                            // Always restore original scroll position
                            try {
                                scrollView.setScrollY(originalScrollY);
                            } catch (Exception e) {
                                Log.e(TAG, "Error restoring scroll position: " + e.getMessage(), e);
                            }
                        }
                    } else {
                        // If no children, use regular capture method
                        try {
                            captureViewOld(view, bitmap);
                            viewAlreadyRendered = true;
                        } catch (Exception e) {
                            Log.e(TAG, "Error in captureViewOld for ScrollView: " + e.getMessage(), e);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error capturing ScrollView: " + e.getMessage(), e);
                    // Fall back to regular capture if ScrollView handling fails
                    try {
                        captureViewOld(view, bitmap);
                        viewAlreadyRendered = true;
                    } catch (Exception inner) {
                        Log.e(TAG, "Error in fallback captureViewOld: " + inner.getMessage(), inner);
                    }
                }
            }

            // Only proceed with other rendering methods if we haven't already rendered the view
            if (!viewAlreadyRendered) {
                // Special handling for ViewGroups (especially React Native refs)
                if (view instanceof ViewGroup && h > view.getHeight()) {
                    try {
                        ViewGroup viewGroup = (ViewGroup) view;

                        // Draw background for the entire height
                        try {
                            Drawable background = view.getBackground();
                            if (background != null) {
                                background.setBounds(0, 0, w, h);
                                background.draw(canvas);
                            } else {
                                canvas.drawColor(Color.TRANSPARENT);
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error drawing ViewGroup background: " + e.getMessage(), e);
                        }

                        // Draw the ViewGroup content with proper positioning
                        try {
                            captureViewGroupContent(viewGroup, canvas, w, h);
                        } catch (Exception e) {
                            Log.e(TAG, "Error in captureViewGroupContent: " + e.getMessage(), e);
                        }

                    } catch (Exception e) {
                        Log.e(TAG, "Error capturing ViewGroup: " + e.getMessage(), e);
                        // Fall back to regular capture
                        try {
                            captureViewOld(view, bitmap);
                        } catch (Exception inner) {
                            Log.e(TAG, "Error in fallback captureViewOld for ViewGroup: " + inner.getMessage(), inner);
                        }
                    }
                }
                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !(view instanceof SurfaceView)) {
                    // For Android O and above, use PixelCopy for better quality captures
                    try {
                        final Activity activity = currentActivity;
                        if (activity != null) {
                            final CountDownLatch latch = new CountDownLatch(1);
                            final int[] result = new int[1];

                            try {
                                final int[] viewLocation = new int[2];
                                view.getLocationInWindow(viewLocation);
                                final Rect rect = new Rect(
                                    viewLocation[0],
                                    viewLocation[1],
                                    viewLocation[0] + view.getWidth(),
                                    viewLocation[1] + view.getHeight()
                                );

                                PixelCopy.request(
                                    activity.getWindow(),
                                    rect,
                                    bitmap,
                                    copyResult -> {
                                        result[0] = copyResult;
                                        latch.countDown();
                                    },
                                    new Handler(Looper.getMainLooper())
                                );

                                // Wait for the pixel copy to complete
                                latch.await(SURFACE_VIEW_READ_PIXELS_TIMEOUT, TimeUnit.SECONDS);

                                if (result[0] != PixelCopy.SUCCESS) {
                                    Log.e(TAG, "PixelCopy failed with error: " + result[0]);
                                    try {
                                        captureViewOld(view, bitmap);
                                    } catch (Exception e) {
                                        Log.e(TAG, "Error in fallback captureViewOld after PixelCopy failure: " + e.getMessage(), e);
                                    }
                                }
                            } catch (Exception e) {
                                Log.e(TAG, "Error using PixelCopy: " + e.getMessage(), e);
                                try {
                                    captureViewOld(view, bitmap);
                                } catch (Exception inner) {
                                    Log.e(TAG, "Error in fallback captureViewOld after PixelCopy error: " + inner.getMessage(), inner);
                                }
                            }
                        } else {
                            try {
                                captureViewOld(view, bitmap);
                            } catch (Exception e) {
                                Log.e(TAG, "Error in captureViewOld when activity is null: " + e.getMessage(), e);
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error with PixelCopy: " + e.getMessage(), e);
                        try {
                            captureViewOld(view, bitmap);
                        } catch (Exception inner) {
                            Log.e(TAG, "Error in fallback captureViewOld: " + inner.getMessage(), inner);
                        }
                    }
                } else {
                    // For older Android versions or SurfaceView, use the old method
                    try {
                        captureViewOld(view, bitmap);
                    } catch (Exception e) {
                        Log.e(TAG, "Error in old device captureViewOld: " + e.getMessage(), e);
                    }
                }
            }

            // Process special children (TextureView, SurfaceView)
            try {
        final Paint paint = new Paint();
        paint.setAntiAlias(true);
        paint.setFilterBitmap(true);
        paint.setDither(true);

                // Process children that need special handling
        final List<View> childrenList = getAllChildren(view);

        for (final View child : childrenList) {
                    // Skip the root view as it's already been drawn
                    if (child == view) continue;

                    // Only process TextureView and SurfaceView - the rest are handled by captureView
            if (child instanceof TextureView) {
                        try {
                // skip all invisible to user child views
                if (child.getVisibility() != VISIBLE) continue;

                final TextureView tvChild = (TextureView) child;
                tvChild.setOpaque(false); // <-- switch off background fill

                            Bitmap childBitmapBuffer = null;
                            try {
                                childBitmapBuffer = tvChild.getBitmap(getExactBitmapForScreenshot(child.getWidth(), child.getHeight()));
                            } catch (Exception e) {
                                Log.e(TAG, "Error getting TextureView bitmap: " + e.getMessage(), e);
                                continue;
                            }

                            if (childBitmapBuffer != null) {
                                try {
                                    final int countCanvasSave = canvas.save();
                                    applyTransformations(canvas, view, child);
                                    canvas.drawBitmap(childBitmapBuffer, 0, 0, paint);
                                    canvas.restoreToCount(countCanvasSave);
                recycleBitmap(childBitmapBuffer);
                                } catch (Exception e) {
                                    Log.e(TAG, "Error drawing TextureView bitmap: " + e.getMessage(), e);
                                    recycleBitmap(childBitmapBuffer);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing TextureView: " + e.getMessage(), e);
                        }
            } else if (child instanceof SurfaceView && handleGLSurfaceView) {
                        try {
                final SurfaceView svChild = (SurfaceView)child;
                final CountDownLatch latch = new CountDownLatch(1);
                            final View finalView = view;
                            final View finalChild = child;
                            final Paint finalPaint = paint;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                                Bitmap childBitmapBuffer = null;
                                try {
                                    childBitmapBuffer = getExactBitmapForScreenshot(child.getWidth(), child.getHeight());
                                    final Bitmap finalChildBitmapBuffer = childBitmapBuffer;
                                    final Canvas finalCanvas = canvas;

                                    PixelCopy.request(svChild, childBitmapBuffer, result -> {
                                        try {
                                            final int countCanvasSave = finalCanvas.save();
                                            applyTransformations(finalCanvas, finalView, finalChild);
                                            finalCanvas.drawBitmap(finalChildBitmapBuffer, 0, 0, finalPaint);
                                            finalCanvas.restoreToCount(countCanvasSave);
                                        } catch (Exception e) {
                                            Log.e(TAG, "Error drawing SurfaceView bitmap: " + e.getMessage(), e);
                                        } finally {
                                            recycleBitmap(finalChildBitmapBuffer);
                                latch.countDown();
                            }
                        }, new Handler(Looper.getMainLooper()));

                                    try {
                        latch.await(SURFACE_VIEW_READ_PIXELS_TIMEOUT, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Log.e(TAG, "Interrupted waiting for SurfaceView PixelCopy: " + e.getMessage(), e);
                                    }
                    } catch (Exception e) {
                                    Log.e(TAG, "Cannot PixelCopy for " + svChild + ": " + e.getMessage(), e);
                                    if (childBitmapBuffer != null) {
                                        recycleBitmap(childBitmapBuffer);
                                    }
                    }
                } else {
                                try {
                    Bitmap cache = svChild.getDrawingCache();
                    if (cache != null) {
                                        canvas.drawBitmap(svChild.getDrawingCache(), 0, 0, paint);
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error drawing SurfaceView drawing cache: " + e.getMessage(), e);
                                }
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing SurfaceView: " + e.getMessage(), e);
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing special views: " + e.getMessage(), e);
            }

            // Handle scaling if needed
            try {
        if (width != null && height != null && (width != w || height != h)) {
                    try {
            final Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
            recycleBitmap(bitmap);
                        bitmap = null; // Prevent duplicate recycling

                        // Use the scaled bitmap for output
                        if (Formats.RAW == this.format && os instanceof ReusableByteArrayOutputStream) {
                            try {
                                final int total = width * height * ARGB_SIZE;
                                final ReusableByteArrayOutputStream rbaos = cast(os);
                                scaledBitmap.copyPixelsToBuffer(rbaos.asBuffer(total));
                                rbaos.setSize(total);
                            } catch (Exception e) {
                                Log.e(TAG, "Error copying scaled bitmap pixels to buffer: " + e.getMessage(), e);
                                // Try compression as fallback
                                try {
                                    final Bitmap.CompressFormat cf = Formats.mapping[this.format];
                                    scaledBitmap.compress(cf, (int) (100.0 * quality), os);
                                } catch (Exception inner) {
                                    Log.e(TAG, "Error compressing scaled bitmap: " + inner.getMessage(), inner);
                                }
                            }
                        } else {
                            try {
                                final Bitmap.CompressFormat cf = Formats.mapping[this.format];
                                scaledBitmap.compress(cf, (int) (100.0 * quality), os);
                            } catch (Exception e) {
                                Log.e(TAG, "Error compressing scaled bitmap: " + e.getMessage(), e);
                            }
                        }

                        recycleBitmap(scaledBitmap);
                    } catch (Exception e) {
                        Log.e(TAG, "Error scaling bitmap: " + e.getMessage(), e);
                        // Use original bitmap if scaling fails
                        if (bitmap != null) {
                            try {
                                final Bitmap.CompressFormat cf = Formats.mapping[this.format];
                                bitmap.compress(cf, (int) (100.0 * quality), os);
                            } catch (Exception inner) {
                                Log.e(TAG, "Error compressing original bitmap after scaling failure: " + inner.getMessage(), inner);
                            }
                            recycleBitmap(bitmap);
                            bitmap = null;
                        }
                    }
                } else {
                    // Use the original bitmap for output
                    if (bitmap != null) {
        if (Formats.RAW == this.format && os instanceof ReusableByteArrayOutputStream) {
                            try {
            final int total = w * h * ARGB_SIZE;
            final ReusableByteArrayOutputStream rbaos = cast(os);
            bitmap.copyPixelsToBuffer(rbaos.asBuffer(total));
            rbaos.setSize(total);
                            } catch (Exception e) {
                                Log.e(TAG, "Error copying bitmap pixels to buffer: " + e.getMessage(), e);
                                // Try compression as fallback
                                try {
                                    final Bitmap.CompressFormat cf = Formats.mapping[this.format];
                                    bitmap.compress(cf, (int) (100.0 * quality), os);
                                } catch (Exception inner) {
                                    Log.e(TAG, "Error compressing bitmap in fallback: " + inner.getMessage(), inner);
                                }
                            }
        } else {
                            try {
            final Bitmap.CompressFormat cf = Formats.mapping[this.format];
            bitmap.compress(cf, (int) (100.0 * quality), os);
                            } catch (Exception e) {
                                Log.e(TAG, "Error compressing bitmap: " + e.getMessage(), e);
                            }
        }

        recycleBitmap(bitmap);
                        bitmap = null;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in bitmap output handling: " + e.getMessage(), e);
                // Try to clean up resources
                if (bitmap != null) {
                    recycleBitmap(bitmap);
                }
            }

        return resolution; // return image width and height
        } catch (Exception e) {
            Log.e(TAG, "Fatal error taking screenshot: " + e.getMessage(), e);
            // Return a default resolution in case of complete failure
            return new Point(0, 0);
        }
    }

    /**
     * Calculate total height of a ViewGroup considering all its children
     */
    private int calculateTotalHeight(ViewGroup viewGroup) {
        try {
            int totalHeight = 0;

            // First ensure all children are measured
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                try {
                    View child = viewGroup.getChildAt(i);
                    if (child == null || child.getVisibility() != View.VISIBLE) continue;

                    // Ensure child is measured
                    try {
                        if (child.getMeasuredWidth() <= 0 || child.getMeasuredHeight() <= 0) {
                            child.measure(
                                View.MeasureSpec.makeMeasureSpec(viewGroup.getWidth(), View.MeasureSpec.AT_MOST),
                                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                            );
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error measuring child view: " + e.getMessage(), e);
                        continue;
                    }

                    // Get child's bottom position
                    int childBottom = child.getTop() + child.getMeasuredHeight();

                    // Add margins if any
                    try {
                        if (child.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                            ViewGroup.MarginLayoutParams params = (ViewGroup.MarginLayoutParams) child.getLayoutParams();
                            childBottom += params.bottomMargin;
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error calculating margins: " + e.getMessage(), e);
                    }

                    totalHeight = Math.max(totalHeight, childBottom);

                    // Recursively check children of ViewGroups
                    try {
                        if (child instanceof ViewGroup) {
                            int childGroupHeight = calculateTotalHeight((ViewGroup) child);
                            totalHeight = Math.max(totalHeight, child.getTop() + childGroupHeight);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error in recursive height calculation: " + e.getMessage(), e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing child at index " + i + ": " + e.getMessage(), e);
                }
            }

            // Add padding
            totalHeight += viewGroup.getPaddingTop() + viewGroup.getPaddingBottom();

            // Ensure we don't return something smaller than the current height
            return Math.max(totalHeight, viewGroup.getHeight());
        } catch (Exception e) {
            Log.e(TAG, "Error calculating ViewGroup height: " + e.getMessage(), e);
            return viewGroup.getHeight();
        }
    }

    /**
     * Capture content of a ViewGroup, handling the case when the content is taller than the visible area
     */
    private void captureViewGroupContent(ViewGroup viewGroup, Canvas canvas, int width, int height) {
        try {
            // First try direct draw
            int saveCount = canvas.save();
            try {
                viewGroup.draw(canvas);
            } catch (Exception e) {
                Log.e(TAG, "Error in direct ViewGroup draw: " + e.getMessage(), e);

                // If direct draw fails, draw each child individually
                for (int i = 0; i < viewGroup.getChildCount(); i++) {
                    try {
                        View child = viewGroup.getChildAt(i);
                        if (child == null || child.getVisibility() != View.VISIBLE) continue;

                        int childSaveCount = 0;
                        try {
                            childSaveCount = canvas.save();
                            canvas.translate(child.getLeft(), child.getTop());
                        } catch (Exception ce) {
                            Log.e(TAG, "Error saving canvas state for child: " + ce.getMessage(), ce);
                            continue;
                        }

                        try {
                            // Try to draw the child
                            child.draw(canvas);

                            // If the child is a ViewGroup and extends beyond the visible area
                            if (child instanceof ViewGroup &&
                                (child.getTop() + child.getHeight() > viewGroup.getHeight())) {
                                captureViewGroupContent((ViewGroup) child, canvas, child.getWidth(), height - child.getTop());
                            }
                        } catch (Exception ce) {
                            Log.e(TAG, "Error drawing child: " + ce.getMessage(), ce);
                        }

                        try {
                            canvas.restoreToCount(childSaveCount);
                        } catch (Exception ce) {
                            Log.e(TAG, "Error restoring canvas state: " + ce.getMessage(), ce);
                        }
                    } catch (Exception ce) {
                        Log.e(TAG, "Error processing child at index " + i + ": " + ce.getMessage(), ce);
                    }
                }
            }

            try {
                canvas.restoreToCount(saveCount);
            } catch (Exception e) {
                Log.e(TAG, "Error restoring canvas state after ViewGroup draw: " + e.getMessage(), e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error capturing ViewGroup content: " + e.getMessage(), e);
        }
    }

    /**
     * Concat all the transformation matrix's from parent to child.
     */
    @NonNull
    @SuppressWarnings("UnusedReturnValue")
    private Matrix applyTransformations(final Canvas c, @NonNull final View root, @NonNull final View child) {
        final Matrix transform = new Matrix();
        try {
        final LinkedList<View> ms = new LinkedList<>();

        // find all parents of the child view
            try {
        View iterator = child;
        do {
            ms.add(iterator);

                    ViewParent parent = iterator.getParent();
                    if (!(parent instanceof View)) {
                        break;
                    }
                    iterator = (View) parent;
                } while (iterator != root && iterator != null);
            } catch (Exception e) {
                Log.e(TAG, "Error finding parent hierarchy: " + e.getMessage(), e);
                // If we can't find parents, at least try to transform for just the child
                ms.add(child);
            }

        // apply transformations from parent --> child order
            try {
        Collections.reverse(ms);
            } catch (Exception e) {
                Log.e(TAG, "Error reversing transformation order: " + e.getMessage(), e);
            }

        for (final View v : ms) {
                try {
                    if (v == null) continue;

                    try {
            c.save();
                    } catch (Exception e) {
                        Log.e(TAG, "Error saving canvas state: " + e.getMessage(), e);
                        continue;
                    }

            // apply each view transformations, so each child will be affected by them
                    try {
            final float dx = v.getLeft() + ((v != child) ? v.getPaddingLeft() : 0) + v.getTranslationX();
            final float dy = v.getTop() + ((v != child) ? v.getPaddingTop() : 0) + v.getTranslationY();
            c.translate(dx, dy);
            c.rotate(v.getRotation(), v.getPivotX(), v.getPivotY());
            c.scale(v.getScaleX(), v.getScaleY());

            // compute the matrix just for any future use
            transform.postTranslate(dx, dy);
            transform.postRotate(v.getRotation(), v.getPivotX(), v.getPivotY());
            transform.postScale(v.getScaleX(), v.getScaleY());
                    } catch (Exception e) {
                        Log.e(TAG, "Error applying transformations: " + e.getMessage(), e);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error processing view in transformation chain: " + e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in applyTransformations: " + e.getMessage(), e);
        }

        return transform;
    }

    @SuppressWarnings("unchecked")
    private static <T extends A, A> T cast(final A instance) {
        return (T) instance;
    }
    //endregion

    //region Cache re-usable bitmaps
    /**
     * Synchronization guard.
     */
    private static final Object guardBitmaps = new Object();
    /**
     * Reusable bitmaps for screenshots.
     */
    private static final Set<Bitmap> weakBitmaps = Collections.newSetFromMap(new WeakHashMap<Bitmap, Boolean>());

    /**
     * Propose allocation size of the array output stream.
     */
    private static int proposeSize(@NonNull final View view) {
        final int w = view.getWidth();
        final int h = view.getHeight();

        return Math.min(w * h * ARGB_SIZE, 32);
    }

    /**
     * Return bitmap to set of available.
     */
    private static void recycleBitmap(@Nullable final Bitmap bitmap) {
        if (bitmap == null) return;

        synchronized (guardBitmaps) {
            weakBitmaps.add(bitmap);
        }
    }

    /**
     * Try to find a bitmap for screenshot in reusable set and if not found create a new one.
     */
    @NonNull
    private static Bitmap getBitmapForScreenshot(final int width, final int height) {
        synchronized (guardBitmaps) {
            for (final Bitmap bmp : weakBitmaps) {
                if (bmp.getWidth() == width && bmp.getHeight() == height) {
                    weakBitmaps.remove(bmp);
                    bmp.eraseColor(Color.TRANSPARENT);
                    return bmp;
                }
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }

    /**
     * Try to find a bitmap with exact width and height for screenshot in reusable set and if
     * not found create a new one.
     */
    @NonNull
    private static Bitmap getExactBitmapForScreenshot(final int width, final int height) {
        synchronized (guardBitmaps) {
            for (final Bitmap bmp : weakBitmaps) {
                if (bmp.getWidth() == width && bmp.getHeight() == height) {
                    weakBitmaps.remove(bmp);
                    bmp.eraseColor(Color.TRANSPARENT);
                    return bmp;
                }
            }
        }

        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    }
    //endregion

    //region Nested declarations

    /**
     * Stream that can re-use pre-allocated buffer.
     */
    @SuppressWarnings("WeakerAccess")
    public static class ReusableByteArrayOutputStream extends ByteArrayOutputStream {
        private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;

        public ReusableByteArrayOutputStream(@NonNull final byte[] buffer) {
            super(0);

            this.buf = buffer;
        }

        /**
         * Get access to inner buffer without any memory copy operations.
         */
        public byte[] innerBuffer() {
            return this.buf;
        }

        @NonNull
        public ByteBuffer asBuffer(final int size) {
            if (this.buf.length < size) {
                grow(size);
            }

            return ByteBuffer.wrap(this.buf);
        }

        public void setSize(final int size) {
            this.count = size;
        }

        /**
         * Increases the capacity to ensure that it can hold at least the
         * number of elements specified by the minimum capacity argument.
         *
         * @param minCapacity the desired minimum capacity
         */
        protected void grow(int minCapacity) {
            // overflow-conscious code
            int oldCapacity = buf.length;
            int newCapacity = oldCapacity << 1;
            if (newCapacity - minCapacity < 0)
                newCapacity = minCapacity;
            if (newCapacity - MAX_ARRAY_SIZE > 0)
                newCapacity = hugeCapacity(minCapacity);
            buf = Arrays.copyOf(buf, newCapacity);
        }

        protected static int hugeCapacity(int minCapacity) {
            if (minCapacity < 0) // overflow
                throw new OutOfMemoryError();

            return (minCapacity > MAX_ARRAY_SIZE) ?
                    Integer.MAX_VALUE :
                    MAX_ARRAY_SIZE;
        }

    }
    //endregion

    private void captureViewOld(final View view, final Bitmap bitmap) {
        try {
            // Original capture method
            Canvas canvas = null;
            try {
                canvas = new Canvas(bitmap);
            } catch (Exception e) {
                Log.e(TAG, "Error creating canvas: " + e.getMessage(), e);
                return;
            }

            // First clear the bitmap to prevent any artifacts
            try {
                bitmap.eraseColor(Color.TRANSPARENT);
            } catch (Exception e) {
                Log.e(TAG, "Error clearing bitmap: " + e.getMessage(), e);
            }

            // Handle background
            try {
                final Drawable background = view.getBackground();
                if (background != null) {
                    try {
                        background.draw(canvas);
                    } catch (Exception e) {
                        Log.e(TAG, "Error drawing background: " + e.getMessage(), e);
                        canvas.drawColor(Color.WHITE); // Fallback to white background
                    }
                } else {
                    canvas.drawColor(Color.TRANSPARENT);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error handling background: " + e.getMessage(), e);
                try {
                    canvas.drawColor(Color.WHITE); // Fallback to white background
                } catch (Exception inner) {
                    Log.e(TAG, "Error drawing fallback background: " + inner.getMessage(), inner);
                }
            }

            try {
                // Use alternative approach for ViewGroups to prevent duplicate content
                if (view instanceof ViewGroup) {
                    // Draw view directly but be careful with background
                    ViewGroup vg = (ViewGroup) view;

                    // Save original background
                    Drawable originalBg = vg.getBackground();

                    // Temporarily clear background to prevent double-drawing
                    vg.setBackground(null);

                    // Draw the view (which calls dispatchDraw internally)
                    vg.draw(canvas);

                    // Restore background
                    vg.setBackground(originalBg);
                } else {
                    // For non-ViewGroups, use the normal draw method
                    view.draw(canvas);
                }
            } catch (Exception e) {
                Log.e(TAG, "Error drawing view: " + e.getMessage(), e);
                try {
                    // Last resort - try the simplest draw call
                    view.draw(canvas);
                } catch (Exception inner) {
                    Log.e(TAG, "Fatal error drawing view: " + inner.getMessage(), inner);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Fatal error in captureViewOld: " + e.getMessage(), e);
            // At least try to draw something
            try {
                Canvas canvas = new Canvas(bitmap);
                canvas.drawColor(Color.WHITE);
            } catch (Exception ignored) {
                Log.e(TAG, "Failed to create even a blank canvas", ignored);
                // Nothing more we can do
            }
        }
    }

    /**
     * Helper method to force layout of all children in a ViewGroup
     */
    private void forceLayoutAllChildren(ViewGroup viewGroup) {
        for (int i = 0; i < viewGroup.getChildCount(); i++) {
            View child = viewGroup.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;

            // Get the child's measured dimensions
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();

            // Skip invalid measurements
            if (childWidth <= 0 || childHeight <= 0) continue;

            // Force layout at the child's proper position
            child.layout(child.getLeft(), child.getTop(),
                         child.getLeft() + childWidth,
                         child.getTop() + childHeight);

            // Recursively layout child ViewGroups
            if (child instanceof ViewGroup) {
                forceLayoutAllChildren((ViewGroup) child);
            }
        }
    }

    /**
     * Specialized method for full-screen captures that uses a simpler and more direct approach
     */
    private void captureScreenDirectly(View rootView) {
        try {
            // Get the root dimensions
            int w = rootView.getWidth();
            int h = rootView.getHeight();

            if (w <= 0 || h <= 0) {
                promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Root view has invalid dimensions");
                return;
            }

            // Create bitmap with original dimensions
            Bitmap bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);

            // For Android Oreo (8.0) and above, use PixelCopy for better quality
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    // Use PixelCopy API for full screen capture
                    final CountDownLatch latch = new CountDownLatch(1);
                    final Activity activity = currentActivity;
                    final boolean[] success = {false};

                    if (activity != null) {
                        PixelCopy.request(
                            activity.getWindow(),
                            bitmap,
                            copyResult -> {
                                success[0] = (copyResult == PixelCopy.SUCCESS);
                                latch.countDown();
                            },
                            new Handler(Looper.getMainLooper())
                        );

                        // Wait for completion
                        latch.await(SURFACE_VIEW_READ_PIXELS_TIMEOUT, TimeUnit.SECONDS);

                        if (!success[0]) {
                            // If PixelCopy failed, fall back to regular capture
                            rootView.draw(new Canvas(bitmap));
                        }
                    } else {
                        // No activity, use normal drawing
                        rootView.draw(new Canvas(bitmap));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error using PixelCopy for screen capture: " + e.getMessage(), e);
                    // Fall back to regular drawing
                    rootView.draw(new Canvas(bitmap));
                }
            } else {
                // For older Android versions
                Canvas canvas = new Canvas(bitmap);
                rootView.draw(canvas);
            }

            // Handle output format
            try {
                // Resize if needed
                if (width != null && height != null && (width != w || height != h)) {
                    Bitmap scaledBitmap = Bitmap.createScaledBitmap(bitmap, width, height, true);
                    bitmap.recycle(); // Recycle original
                    bitmap = scaledBitmap;
                }

                // Output to requested format
                if (Results.TEMP_FILE.equals(result)) {
                    FileOutputStream fos = new FileOutputStream(output);
                    try {
                        // Correctly get the CompressFormat from format integer
                        Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.PNG; // Default
                        if (this.format >= 0 && this.format < Formats.mapping.length) {
                            compressFormat = Formats.mapping[this.format];
                        }
                        bitmap.compress(compressFormat, (int) (100.0 * quality), fos);
                        promise.resolve(Uri.fromFile(output).toString());
                    } finally {
                        fos.close();
                        bitmap.recycle();
                    }
                } else if (Results.BASE_64.equals(result) || Results.DATA_URI.equals(result) || Results.ZIP_BASE_64.equals(result)) {
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    // Correctly get the CompressFormat from format integer
                    Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.PNG; // Default
                    if (this.format >= 0 && this.format < Formats.mapping.length) {
                        compressFormat = Formats.mapping[this.format];
                    }
                    bitmap.compress(compressFormat, (int) (100.0 * quality), bos);
                    bitmap.recycle();

                    String data = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);

                    if (Results.DATA_URI.equals(result)) {
                        String imageFormat = "jpg".equals(extension) ? "jpeg" : extension;
                        promise.resolve("data:image/" + imageFormat + ";base64," + data);
                    } else {
                        promise.resolve(data);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error processing bitmap for output: " + e.getMessage());
                if (bitmap != null && !bitmap.isRecycled()) {
                    bitmap.recycle();
                }
                promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Failed to process screen capture: " + e.getMessage());
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in direct screen capture: " + e.getMessage());
            promise.reject(ERROR_UNABLE_TO_SNAPSHOT, "Failed to capture screen: " + e.getMessage());
        }
    }

}

