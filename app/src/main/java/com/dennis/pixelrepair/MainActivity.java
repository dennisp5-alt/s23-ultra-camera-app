package com.dennis.pixelrepair;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Offline, non-destructive, single-image experimental photo repair lab. */
public class MainActivity extends Activity {
    private static final int PICK_PHOTO = 11;
    private static final int SAVE_PHOTO = 12;
    private static final long MAX_PIXELS = 24_000_000L;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private CompareView preview;
    private TextView status, strengthLabel;
    private Button saveButton, modeButton, profileButton;
    private Bitmap original, repaired, editMap;
    private int sensitivity = 45, revision = 0, viewMode = 0, repairProfile = 1;
    private boolean isWorking = false;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(12, 17, 23));
        getWindow().setNavigationBarColor(Color.rgb(12, 17, 23));
        buildUi();
    }

    private int dp(float value) {
        return (int) (getResources().getDisplayMetrics().density * value + .5f);
    }

    private TextView text(String s, int sp, int colour) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(colour);
        return t;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(label);
        b.setTextSize(13);
        b.setTextColor(Color.WHITE);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackgroundTintList(ColorStateList.valueOf(Color.rgb(37, 64, 77)));
        b.setPadding(dp(7), 0, dp(7), 0);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(12, 17, 23));
        root.setPadding(dp(12), dp(8), dp(12), dp(8));
        setContentView(root);

        TextView header = text("PIXEL REPAIR LAB", 20, Color.rgb(238, 245, 249));
        header.setLetterSpacing(.09f);
        root.addView(header);
        TextView tagline = text("Original-resolution experimental pixel repair · offline",
                12, Color.rgb(157, 180, 193));
        root.addView(tagline);

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(0, dp(8), 0, dp(8));
        root.addView(actions);
        Button openButton = button("Open photo");
        actions.addView(openButton, new LinearLayout.LayoutParams(0, dp(42), 1f));
        openButton.setOnClickListener(v -> openPhoto());
        modeButton = button("View: Split");
        LinearLayout.LayoutParams modeParams = new LinearLayout.LayoutParams(0, dp(42), 1f);
        modeParams.setMargins(dp(7), 0, 0, 0);
        actions.addView(modeButton, modeParams);
        modeButton.setOnClickListener(v -> {
            viewMode = (viewMode + 1) % 4;
            preview.setMode(viewMode);
            modeButton.setText("View: " + (viewMode == 0 ? "Split" :
                viewMode == 1 ? "Original" : viewMode == 2 ? "Repaired" : "Edits"));
        });

        profileButton = button("Repair: Balanced");
        LinearLayout.LayoutParams profileLp = new LinearLayout.LayoutParams(-1, dp(37));
        profileLp.bottomMargin = dp(7);
        root.addView(profileButton, profileLp);
        profileButton.setOnClickListener(v -> {
            repairProfile = (repairProfile + 1) % 3;
            profileButton.setText(repairProfile == 0 ? "Repair: Defects only"
                : repairProfile == 1 ? "Repair: Balanced" : "Repair: Shadow Lab");
            processPhoto();
        });
        preview = new CompareView();
        root.addView(preview, new LinearLayout.LayoutParams(-1, 0, 1f));

        strengthLabel = text("Sensitivity: 45 / 100", 14, Color.rgb(225, 235, 241));
        LinearLayout.LayoutParams strengthLp = new LinearLayout.LayoutParams(-1, -2);
        strengthLp.topMargin = dp(10);
        root.addView(strengthLabel, strengthLp);
        SeekBar sensitivityBar = new SeekBar(this);
        sensitivityBar.setMax(100); sensitivityBar.setProgress(sensitivity);
        sensitivityBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(113, 211, 219)));
        sensitivityBar.setThumbTintList(ColorStateList.valueOf(Color.rgb(113, 211, 219)));
        root.addView(sensitivityBar);
        sensitivityBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar b, int value, boolean fromUser) {
                sensitivity = value;
                strengthLabel.setText("Sensitivity: " + value + " / 100");
            }
            @Override public void onStartTrackingTouch(SeekBar b) {}
            @Override public void onStopTrackingTouch(SeekBar b) { processPhoto(); }
        });

        status = text("Choose a photo to inspect. Pinch to zoom for detail.",
                12, Color.rgb(161, 182, 194));
        status.setMinLines(2);
        root.addView(status);

        saveButton = button("Save repaired PNG");
        saveButton.setEnabled(false);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(-1, dp(44));
        saveLp.topMargin = dp(4);
        root.addView(saveButton, saveLp);
        saveButton.setOnClickListener(v -> savePhoto());
    }

    private void openPhoto() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, PICK_PHOTO);
    }

    private void savePhoto() {
        if (repaired == null || isWorking) return;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.setType("image/png");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_TITLE, "PixelRepair_" + System.currentTimeMillis() + ".png");
        startActivityForResult(intent, SAVE_PHOTO);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == PICK_PHOTO) loadPhoto(uri);
        else if (requestCode == SAVE_PHOTO) writePhoto(uri);
    }

    private void loadPhoto(Uri uri) {
        final int job = ++revision;
        isWorking = true; saveButton.setEnabled(false);
        status.setText("Opening original photograph…");
        worker.execute(() -> {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap loaded = ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
                    Size size = info.getSize();
                    if ((long) size.getWidth() * size.getHeight() > MAX_PIXELS) {
                        throw new IllegalArgumentException("This first test supports up to 24 MP. The file was NOT resized.");
                    }
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    decoder.setMutableRequired(false);
                });
                if (loaded.getConfig() == Bitmap.Config.HARDWARE) {
                    loaded = loaded.copy(Bitmap.Config.ARGB_8888, false);
                }
                final Bitmap loadedFinal = loaded;
                ui.post(() -> {
                    if (job != revision) return;
                    original = loadedFinal;
                    repaired = null;
                    editMap = null;
                    preview.setImages(original, null, null);
                    processPhoto();
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (job == revision) {
                        isWorking = false;
                        status.setText("Could not open image: " + e.getMessage());
                    }
                });
            }
        });
    }

    private void processPhoto() {
        if (original == null) return;
        final int job = ++revision;
        final int strength = sensitivity;
        final int profile = repairProfile;
        final Bitmap input = original;
        isWorking = true; saveButton.setEnabled(false);
        status.setText("Inspecting " + input.getWidth() + " × " +
                input.getHeight() + " pixels…");
        worker.execute(() -> {
            try {
                int w = input.getWidth(), h = input.getHeight();
                int[] source = new int[w * h];
                input.getPixels(source, 0, w, 0, 0, w, h);
                PixelRepairEngine.Result result = strength == 0
                    ? new PixelRepairEngine.Result(source.clone(), 0)
                    : PixelRepairEngine.repair(source, w, h, strength);
                int defectCount = result.changedPixels;
                int fineCount = (profile == 0) ? 0 :
                    ShadowRepairEngine.denoise(source, result.pixels, w, h, strength, profile == 2);
                int total = defectCount + fineCount;
                Bitmap output = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                output.setPixels(result.pixels, 0, w, 0, 0, w, h);
                Bitmap changeMap = buildEditMap(source, result.pixels, w, h);
                ui.post(() -> {
                    if (job != revision) return;
                    repaired = output;
                    editMap = changeMap;
                    preview.setImages(input, output, changeMap);
                    saveButton.setEnabled(true);
                    isWorking = false;
                    status.setText(String.format(Locale.US,
                        "Inspected %,d · edited %,d (%.4f%%).\\n" +
                        "Defects: %,d · fine noise: %,d. Original preserved.",
                        (long) w * h, total,
                        (100.0 * total) / ((long) w * h),
                        defectCount, fineCount));
                });
            } catch (Exception e) {
                ui.post(() -> {
                    if (job == revision) {
                        isWorking = false;
                        status.setText("Repair failed: " + e.getMessage());
                    }
                });
            }
        });
    }


    // Compact map of actual original-vs-output RGB differences.
    // Highlighting exaggerates edits for debugging; it is NOT photo content.
    private static Bitmap buildEditMap(int[] source, int[] result, int w, int h) {
        int step = Math.max(1, (int) Math.ceil(Math.max(w, h) / 768.0));
        int mw=(w+step-1)/step, mh=(h+step-1)/step;
        int[] sum = new int[mw*mh], count = new int[mw*mh];
        for (int y=0; y<h; y++) {
            int row=y*w, mapRow=(y/step)*mw;
            for (int x=0; x<w; x++) {
                int i=row+x;
                if (source[i] == result[i]) continue;
                int a=source[i], b=result[i];
                int diff=Math.max(Math.abs(((a>>>16)&255)-((b>>>16)&255)),
                    Math.max(Math.abs(((a>>>8)&255)-((b>>>8)&255)),
                    Math.abs((a&255)-(b&255))));
                int m=mapRow+x/step;
                sum[m]+=Math.min(100,diff);
                count[m]++;
            }
        }
        int[] pixels=new int[mw*mh];
        for(int i=0;i<pixels.length;i++){
            if(count[i]==0)continue;
            double diff=(double)sum[i]/count[i];
            double coverage=(double)count[i]/(step*step);
            int alpha=Math.min(218,(int)Math.round(43+diff*4+coverage*78));
            pixels[i]=(alpha<<24)|0x00ffb938;
        }
        Bitmap out=Bitmap.createBitmap(mw,mh,Bitmap.Config.ARGB_8888);
        out.setPixels(pixels,0,mw,0,0,mw,mh);
        return out;
    }

    private void writePhoto(Uri uri) {
        final Bitmap toSave = repaired;
        if (toSave == null) return;
        status.setText("Saving lossless repaired PNG…");
        worker.execute(() -> {
            try (OutputStream out = getContentResolver().openOutputStream(uri, "w")) {
                if (out == null || !toSave.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    throw new IOException("File could not be saved");
                }
                ui.post(() -> status.setText("Saved PNG. Original photo was not modified."));
            } catch (Exception e) {
                ui.post(() -> status.setText("Save failed: " + e.getMessage()));
            }
        });
    }

    @Override protected void onDestroy() {
        worker.shutdown();
        super.onDestroy();
    }

    private final class CompareView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint dividerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF imageRect = new RectF();
        private final ScaleGestureDetector scaleDetector;
        private Bitmap left, right, changedOverlay;
        private int mode = 0;
        private float zoom = 1f, dx = 0f, dy = 0f, lastX, lastY;

        CompareView() {
            super(MainActivity.this);
            setBackgroundColor(Color.rgb(21, 28, 36));
            scaleDetector = new ScaleGestureDetector(MainActivity.this,
                new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    @Override public boolean onScale(ScaleGestureDetector detector) {
                        zoom = Math.max(1f, Math.min(12f,
                                zoom * detector.getScaleFactor()));
                        if (zoom == 1f) { dx = 0; dy = 0; }
                        invalidate();
                        return true;
                    }
                });
        }

        void setImages(Bitmap originalBitmap, Bitmap repairedBitmap, Bitmap map) {
            boolean newPhoto = (left != originalBitmap);
            left = originalBitmap;
            right = repairedBitmap;
            changedOverlay = map;
            if (newPhoto) { zoom = 1f; dx = 0; dy = 0; }
            invalidate();
        }

        void setMode(int nextMode) { mode = nextMode; invalidate(); }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            canvas.drawColor(Color.rgb(21, 28, 36));
            Bitmap base = (mode == 2 && right != null) ? right : left;
            if (base == null) {
                Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
                p.setColor(Color.rgb(150, 174, 186));
                p.setTextSize(dp(15));
                p.setTextAlign(Paint.Align.CENTER);
                canvas.drawText("Open a photograph to begin", getWidth()/2f,
                        getHeight()/2f, p);
                return;
            }
            float fit = Math.min((float) getWidth() / base.getWidth(),
                    (float) getHeight() / base.getHeight());
            float imgWidth = base.getWidth() * fit * zoom;
            float imgHeight = base.getHeight() * fit * zoom;
            float cx = getWidth() / 2f + dx;
            float cy = getHeight() / 2f + dy;
            imageRect.set(cx-imgWidth/2, cy-imgHeight/2,
                    cx+imgWidth/2, cy+imgHeight/2);
            canvas.drawBitmap(base, null, imageRect, paint);

            if (mode == 3 && changedOverlay != null) {
                canvas.drawBitmap(changedOverlay, null, imageRect, paint);
            }
            if (mode == 0 && right != null) {
                canvas.save();
                canvas.clipRect(getWidth()/2f, 0, getWidth(), getHeight());
                canvas.drawBitmap(right, null, imageRect, paint);
                canvas.restore();
                dividerPaint.setColor(Color.WHITE);
                dividerPaint.setStrokeWidth(dp(2));
                canvas.drawLine(getWidth()/2f, 0, getWidth()/2f,
                        getHeight(), dividerPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            scaleDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastX = event.getX(); lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getPointerCount() == 1 && !scaleDetector.isInProgress()
                        && zoom > 1.001f) {
                        dx += event.getX() - lastX;
                        dy += event.getY() - lastY;
                        invalidate();
                    }
                    lastX = event.getX(); lastY = event.getY();
                    return true;
                case MotionEvent.ACTION_UP:
                    performClick(); return true;
                default: return true;
            }
        }

        @Override public boolean performClick() {
            super.performClick();
            return true;
        }
    }
}
