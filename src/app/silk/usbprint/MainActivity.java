package app.silk.usbprint;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.pdf.PdfRenderer;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String ACTION_USB_PERMISSION = "app.silk.usbprint.USB_PERMISSION";
    private static final int REQ_PICK = 41;
    private static final int MAX_BYTES = 40 * 1024 * 1024;

    private UsbManager usbManager;
    private SharedPreferences prefs;
    private TextView statusText;
    private TextView fileName;
    private TextView logText;
    private TextView copiesText;
    private ImageView preview;
    private Button connectButton;
    private Button printButton;
    private Spinner deviceSpinner;
    private Spinner paperSpinner;
    private Spinner langSpinner;
    private CheckBox sharpCheck;
    private Bitmap previewBitmap;
    private final List<UsbDevice> devices = new ArrayList<>();
    private Uri selectedUri;
    private byte[] selectedBytes;
    private String selectedMime = "";
    private String selectedName = "";
    private int copies = 1;
    private boolean printing;
    private boolean alive = true;
    private boolean receiverOn;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION_USB_PERMISSION.equals(intent.getAction())) return;
            UsbDevice device = readDevice(intent);
            boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);
            if (granted && device != null) {
                log("USB_PERMISSION_GRANTED");
                notePrinterLanguage(device);
            } else {
                log("رُفض إذن USB. لن يُرسل شيء إلى الطابعة.");
            }
            refreshDevices();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        prefs = getSharedPreferences("silk", MODE_PRIVATE);

        statusText = findViewById(R.id.statusText);
        fileName = findViewById(R.id.fileName);
        logText = findViewById(R.id.logText);
        copiesText = findViewById(R.id.copiesText);
        preview = findViewById(R.id.preview);
        connectButton = findViewById(R.id.connectButton);
        printButton = findViewById(R.id.printButton);
        deviceSpinner = findViewById(R.id.deviceSpinner);
        paperSpinner = findViewById(R.id.paperSpinner);
        langSpinner = findViewById(R.id.langSpinner);
        sharpCheck = findViewById(R.id.sharpCheck);

        ArrayAdapter<String> papers = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"A4", "Letter", "صورة ١٠×١٥ على A4"});
        papers.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        paperSpinner.setAdapter(papers);

        ArrayAdapter<String> langs = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{
                        "PDF مباشر — طابعات المكتب الحديثة",
                        "PCL 5e — ليزر HP وBrother",
                        "حرارية ٨٠ ملم",
                        "حرارية ٥٨ ملم",
                        "نافذة أندرويد — إبسون وكانون"
                });
        langs.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        langSpinner.setAdapter(langs);

        paperSpinner.setSelection(prefs.getInt("paper", 0));
        int lang = prefs.getInt("lang", PrintComposer.MODE_PCL);
        if (!prefs.getBoolean("v11_pcl", false)) {
            lang = PrintComposer.MODE_PCL;
            prefs.edit().putBoolean("v11_pcl", true).putInt("lang", lang).apply();
        }
        langSpinner.setSelection(lang);
        sharpCheck.setChecked(prefs.getBoolean("sharp", false));
        copies = prefs.getInt("copies", 1);
        if (copies < 1) copies = 1;
        if (copies > 9) copies = 9;
        copiesText.setText(String.valueOf(copies));

        findViewById(R.id.refreshButton).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshDevices();
            }
        });
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestUsb();
            }
        });
        findViewById(R.id.pickImage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(new String[]{"image/*"});
            }
        });
        findViewById(R.id.pickDoc).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pick(new String[]{"application/pdf", "image/*"});
            }
        });
        findViewById(R.id.testPage).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                loadTestPage();
            }
        });
        findViewById(R.id.copyMinus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (copies > 1) {
                    copies--;
                    copiesText.setText(String.valueOf(copies));
                    savePrefs();
                }
            }
        });
        findViewById(R.id.copyPlus).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (copies < 9) {
                    copies++;
                    copiesText.setText(String.valueOf(copies));
                    savePrefs();
                }
            }
        });
        printButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startPrint();
            }
        });
        findViewById(R.id.directTest).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startDirectTest();
            }
        });
        findViewById(R.id.openDiag).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(MainActivity.this, DiagActivity.class));
            }
        });
        AdapterView.OnItemSelectedListener save = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                savePrefs();
                refreshDevices();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        };
        paperSpinner.setOnItemSelectedListener(save);
        langSpinner.setOnItemSelectedListener(save);
        deviceSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateConnectionLabel();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        if (savedInstanceState != null) {
            copies = savedInstanceState.getInt("copies", copies);
            copiesText.setText(String.valueOf(copies));
            paperSpinner.setSelection(savedInstanceState.getInt("paper", paperSpinner.getSelectedItemPosition()));
            langSpinner.setSelection(savedInstanceState.getInt("lang", langSpinner.getSelectedItemPosition()));
            sharpCheck.setChecked(savedInstanceState.getBoolean("sharp", sharpCheck.isChecked()));
            String uri = savedInstanceState.getString("uri");
            if (uri != null) loadUri(Uri.parse(uri));
            else if (savedInstanceState.getBoolean("test")) loadTestPage();
        }

        boolean host = getPackageManager().hasSystemFeature("android.hardware.usb.host");
        if (!host) {
            statusText.setText("هذا الجوال لا يعلن دعم USB OTG. جرّب الكابل، أو استخدم «نافذة أندرويد».");
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            // النظام يرسل نتيجة إذن USB من خارج التطبيق، لذا المستقبل لازم يكون exported.
            registerReceiver(usbReceiver, filter, RECEIVER_EXPORTED);
        } else {
            registerReceiver(usbReceiver, filter);
        }
        receiverOn = true;
        refreshDevices();
        UsbDevice attached = readDevice(getIntent());
        if (attached != null && usbManager != null && !usbManager.hasPermission(attached)) {
            requestPermission(attached);
        }
    }

    @Override
    protected void onStop() {
        if (receiverOn) {
            unregisterReceiver(usbReceiver);
            receiverOn = false;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        if (previewBitmap != null && !previewBitmap.isRecycled()) previewBitmap.recycle();
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        refreshDevices();
        UsbDevice attached = readDevice(intent);
        if (attached != null && usbManager != null && !usbManager.hasPermission(attached)) {
            requestPermission(attached);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("copies", copies);
        out.putInt("paper", paperSpinner.getSelectedItemPosition());
        out.putInt("lang", langSpinner.getSelectedItemPosition());
        out.putBoolean("sharp", sharpCheck.isChecked());
        if (selectedUri != null) out.putString("uri", selectedUri.toString());
        out.putBoolean("test", selectedUri == null && selectedBytes != null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK && resultCode == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
            } catch (Exception ignored) {
            }
            loadUri(uri);
        }
    }

    private void pick(String[] mimes) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, mimes);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQ_PICK);
    }

    private void loadUri(final Uri uri) {
        fileName.setText("جارٍ قراءة الملف…");
        printButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String name = "ملف";
                    long size = -1;
                    Cursor cursor = getContentResolver().query(uri,
                            new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                            null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                if (!cursor.isNull(0)) name = cursor.getString(0);
                                if (!cursor.isNull(1)) size = cursor.getLong(1);
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                    if (size > MAX_BYTES) throw new IllegalStateException("الملف أكبر من ٤٠ م.ب. اختر ملفاً أصغر.");
                    String mime = getContentResolver().getType(uri);
                    if (mime == null) mime = "";
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    InputStream in = getContentResolver().openInputStream(uri);
                    if (in == null) throw new IllegalStateException("تعذّر فتح الملف.");
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = in.read(buf)) >= 0) {
                            if (bos.size() + n > MAX_BYTES) {
                                throw new IllegalStateException("الملف أكبر من ٤٠ م.ب. اختر ملفاً أصغر.");
                            }
                            bos.write(buf, 0, n);
                        }
                    } finally {
                        in.close();
                    }
                    final byte[] data = bos.toByteArray();
                    final String finalName = name;
                    final String finalMime = mime;
                    final Bitmap thumb = makePreview(data, mime);
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            selectedUri = uri;
                            selectedBytes = data;
                            selectedMime = finalMime;
                            selectedName = finalName;
                            fileName.setText(finalName);
                            setPreview(thumb);
                            printButton.setEnabled(!printing);
                            boolean pdf = finalMime.contains("pdf") || (data.length > 4 && data[0] == '%');
                            if (pdf && !sharpCheck.isChecked()) sharpCheck.setChecked(true);
                            log("تم تجهيز " + finalName + " (" + (data.length / 1024) + " ك.ب)");
                        }
                    });
                } catch (Exception e) {
                    final String msg = e.getMessage() == null ? "تعذّر قراءة الملف." : e.getMessage();
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            printButton.setEnabled(!printing);
                            fileName.setText(getString(R.string.empty_preview));
                            log(msg);
                        }
                    });
                }
            }
        }).start();
    }

    private void loadTestPage() {
        Bitmap bmp = Bitmap.createBitmap(1240, 1754, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawColor(Color.WHITE);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF1B5E56);
        canvas.drawRect(0, 0, 1240, 36, paint);
        paint.setColor(0xFF1A211F);
        paint.setTextAlign(Paint.Align.RIGHT);
        paint.setTextSize(78);
        canvas.drawText("سلك", 1160, 180, paint);
        paint.setTextSize(42);
        canvas.drawText("صفحة طباعة تجريبية", 1160, 260, paint);
        paint.setTextSize(34);
        paint.setColor(0xFF5E6A66);
        String[] lines = new String[]{
                "إذا ظهرت هذه الصفحة فالكابل يعمل.",
                "للألوان على HP وBrother جرّب PDF مباشر.",
                "لليزر الأقدم جرّب PCL رمادي.",
                "للإيصالات جرّب حرارية ٨٠ أو ٥٨.",
                "لطابعات إبسون وكانون جرّب نافذة أندرويد."
        };
        int y = 380;
        for (int i = 0; i < lines.length; i++) {
            canvas.drawText(lines[i], 1160, y, paint);
            y += 78;
        }
        paint.setColor(0xFF1B5E56);
        canvas.drawRect(180, 820, 1060, 1500, paint);
        paint.setColor(0xFFF3EEE6);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(64);
        canvas.drawText("عيّنة", 620, 1200, paint);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        selectedBytes = bos.toByteArray();
        selectedMime = "image/png";
        selectedName = "صفحة تجريبية";
        selectedUri = null;
        fileName.setText(selectedName);
        Bitmap thumb = Bitmap.createScaledBitmap(bmp, 360, 508, true);
        bmp.recycle();
        setPreview(thumb);
        log("الصفحة التجريبية جاهزة. اختر الطريقة ثم اطبع.");
    }

    private Bitmap makePreview(byte[] data, String mime) {
        try {
            boolean pdf = (mime != null && mime.contains("pdf")) || (data.length > 4 && data[0] == '%');
            if (!pdf) return PrintComposer.decodeImage(data);
            File file = File.createTempFile("silk-prev", ".pdf", getCacheDir());
            try {
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(data);
                fos.close();
                ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
                try {
                    PdfRenderer renderer = new PdfRenderer(pfd);
                    try {
                        PdfRenderer.Page page = renderer.openPage(0);
                        try {
                            int w = 480;
                            int h = Math.max(48, Math.round(w * (page.getHeight() / (float) page.getWidth())));
                            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            return bmp;
                        } finally {
                            page.close();
                        }
                    } finally {
                        renderer.close();
                    }
                } finally {
                    pfd.close();
                }
            } finally {
                file.delete();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private void setPreview(Bitmap bmp) {
        if (previewBitmap != null && previewBitmap != bmp && !previewBitmap.isRecycled()) {
            previewBitmap.recycle();
        }
        previewBitmap = bmp;
        preview.setImageBitmap(bmp);
    }

    private void refreshDevices() {
        devices.clear();
        List<String> labels = new ArrayList<>();
        if (usbManager != null) {
            for (UsbDevice device : usbManager.getDeviceList().values()) {
                if (!UsbPrintTransport.isCandidate(device)) continue;
                devices.add(device);
                labels.add(label(device));
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        deviceSpinner.setAdapter(adapter);
        deviceSpinner.setVisibility(devices.isEmpty() ? View.GONE : View.VISIBLE);
        updateConnectionLabel();
    }

    private void updateConnectionLabel() {
        int mode = langSpinner.getSelectedItemPosition();
        if (mode == PrintComposer.MODE_SYSTEM) {
            statusText.setText("نافذة أندرويد لا تحتاج كابل داخل التطبيق. الطابعة تُختار من نافذة النظام.");
            connectButton.setEnabled(false);
            connectButton.setText(getString(R.string.connect));
            return;
        }
        if (devices.isEmpty()) {
            statusText.setText("لا توجد طابعة على الكابل. وصل OTG ثم اضغط تحديث.");
            connectButton.setEnabled(false);
            connectButton.setText(getString(R.string.connect));
            return;
        }
        UsbDevice device = currentDevice();
        boolean ok = device != null && usbManager != null && usbManager.hasPermission(device);
        if (ok) {
            statusText.setText("جاهزة: " + label(device));
            connectButton.setEnabled(false);
            connectButton.setText(getString(R.string.connected));
        } else {
            statusText.setText("تم العثور على جهاز. اضغط «اتصال» لمنح الإذن.");
            connectButton.setEnabled(true);
            connectButton.setText(getString(R.string.connect));
        }
    }

    private UsbDevice currentDevice() {
        if (devices.isEmpty()) return null;
        int i = deviceSpinner.getSelectedItemPosition();
        if (i < 0 || i >= devices.size()) return devices.get(0);
        return devices.get(i);
    }

    private String label(UsbDevice device) {
        String vendor = UsbPrintTransport.vendorName(device.getVendorId());
        String product = null;
        try {
            product = device.getProductName();
        } catch (Exception ignored) {
        }
        StringBuilder sb = new StringBuilder();
        if (vendor != null) sb.append(vendor).append(' ');
        if (product != null && product.trim().length() > 0) sb.append(product).append(' ');
        sb.append(String.format(Locale.US, "%04X:%04X", device.getVendorId(), device.getProductId()));
        return sb.toString();
    }

    private void requestUsb() {
        UsbDevice device = currentDevice();
        if (device == null) {
            log("لا توجد طابعة موصولة.");
            return;
        }
        requestPermission(device);
    }

    private void requestPermission(UsbDevice device) {
        if (usbManager == null) return;
        if (usbManager.hasPermission(device)) {
            refreshDevices();
            return;
        }
        Intent explicit = new Intent(ACTION_USB_PERMISSION);
        explicit.setPackage(getPackageName());
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
        PendingIntent pending = PendingIntent.getBroadcast(this, 0, explicit, flags);
        usbManager.requestPermission(device, pending);
    }

    private void startPrint() {
        log("PRINT_BUTTON_CLICKED");
        if (printing) return;
        if (selectedBytes == null) {
            log("توقف: لا يوجد ملف. اختر صورة أو مستنداً، أو اضغط اختبار الطباعة المباشرة.");
            return;
        }
        savePrefs();
        final int mode = langSpinner.getSelectedItemPosition();
        final UsbDevice device = currentDevice();
        if (mode == PrintComposer.MODE_PDF && device != null
                && UsbPrintTransport.wantsPcl(DiagStore.last == null ? "" : DiagStore.last.deviceId, label(device))) {
            langSpinner.setSelection(PrintComposer.MODE_PCL);
            log("توقف: هذه الطابعة لا تطبع PDF. تم اختيار PCL 5e. ابدأ باختبار الطباعة المباشرة.");
            return;
        }
        if (mode != PrintComposer.MODE_SYSTEM && !readyUsb(device)) return;
        final byte[] bytes = selectedBytes;
        final String mime = selectedMime;
        final int paper = paperSpinner.getSelectedItemPosition();
        final int copyCount = copies;
        final boolean sharp = sharpCheck.isChecked();
        beginSend();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UsbPrintTransport.Report report = new UsbPrintTransport.Report();
                try {
                    if (device != null) report.mark("USB_DEVICE_FOUND");
                    PrintComposer.Request req = new PrintComposer.Request();
                    req.context = getApplicationContext();
                    req.bytes = bytes;
                    req.mime = mime;
                    req.mode = mode;
                    req.paper = paper;
                    req.copies = copyCount;
                    req.sharp = sharp;
                    final byte[] job = PrintComposer.compose(req);
                    report.mark("PRINT_JOB_CREATED");
                    report.jobBytes = job.length;
                    if (mode == PrintComposer.MODE_PCL) report.mark("PCL_DATA_READY");
                    else report.mark("JOB_DATA_READY");
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("PRINT_JOB_CREATED " + job.length + " بايت");
                        }
                    });
                    if (mode == PrintComposer.MODE_SYSTEM) {
                        ui(new Runnable() {
                            @Override
                            public void run() {
                                endSend();
                                SystemPrintHelper.print(MainActivity.this, "سلك", job);
                                log("فُتحت نافذة الطباعة في أندرويد. اختر الطابعة من هناك.");
                            }
                        });
                        return;
                    }
                    sendUsb(device, job, report);
                } catch (Throwable t) {
                    failSend(report, t);
                }
            }
        }).start();
    }

    private void startDirectTest() {
        log("PRINT_BUTTON_CLICKED");
        if (printing) return;
        final UsbDevice device = currentDevice();
        if (!readyUsb(device)) return;
        if (UsbPrintTransport.preferSystemDialog(device)) {
            log("هذه الطابعة (إبسون أو كانون) لا تقبل PCL الخام غالباً. اختر «نافذة أندرويد».");
            return;
        }
        beginSend();
        new Thread(new Runnable() {
            @Override
            public void run() {
                UsbPrintTransport.Report report = new UsbPrintTransport.Report();
                try {
                    report.mark("USB_DEVICE_FOUND");
                    final byte[] job = UsbPrintTransport.directTestJob(device);
                    report.mark("PRINT_JOB_CREATED");
                    report.mark("PCL_DATA_READY");
                    report.jobBytes = job.length;
                    ui(new Runnable() {
                        @Override
                        public void run() {
                            log("مهمة الاختبار جاهزة: " + job.length + " بايت");
                        }
                    });
                    sendUsb(device, job, report);
                } catch (Throwable t) {
                    failSend(report, t);
                }
            }
        }).start();
    }

    private boolean readyUsb(UsbDevice device) {
        if (device == null) {
            log("توقف: لا توجد طابعة. وصل الكابل ثم اضغط تحديث.");
            return false;
        }
        log("USB_DEVICE_FOUND");
        if (usbManager == null || !usbManager.hasPermission(device)) {
            log("توقف: إذن USB غير ممنوح.");
            requestPermission(device);
            return false;
        }
        log("USB_PERMISSION_GRANTED");
        return true;
    }

    private void beginSend() {
        printing = true;
        printButton.setEnabled(false);
        findViewById(R.id.directTest).setEnabled(false);
        printButton.setText(getString(R.string.printing));
    }

    private void endSend() {
        printing = false;
        printButton.setEnabled(true);
        findViewById(R.id.directTest).setEnabled(true);
        printButton.setText(getString(R.string.print_now));
        updateConnectionLabel();
    }

    private void sendUsb(final UsbDevice device, final byte[] job, final UsbPrintTransport.Report report) throws java.io.IOException {
        UsbPrintTransport.write(usbManager, device, job, new UsbPrintTransport.Progress() {
            @Override
            public void onProgress(final int sent, final int total) {
                ui(new Runnable() {
                    @Override
                    public void run() {
                        statusText.setText("تم إرسال " + sent + " من " + total + " بايت");
                    }
                });
            }
        }, report);
        ui(new Runnable() {
            @Override
            public void run() {
                endSend();
                log("USB_TRANSFER_RESULT " + report.sentBytes + " من " + job.length
                        + " بايت خلال " + report.durationMs + " ms");
                log("النقل اكتمل. هذا لا يؤكد خروج الورقة. إذا لم تتحرك الطابعة افتح تشخيص USB وانسخ التقرير.");
                statusText.setText("تم إرسال " + report.sentBytes + " من " + job.length + " بايت");
            }
        });
    }

    private void failSend(final UsbPrintTransport.Report report, final Throwable t) {
        final String msg = t.getMessage() == null ? "تعذّر الإرسال." : t.getMessage();
        if (report.error == null || report.error.length() == 0) report.error = msg;
        DiagStore.last = report;
        DiagStore.text = report.text();
        Log.e("Silk", msg, t);
        ui(new Runnable() {
            @Override
            public void run() {
                endSend();
                log("فشل عند " + report.stage);
                log(msg);
                if (report.needsPermission) {
                    refreshDevices();
                    requestUsb();
                }
            }
        });
    }

    private void notePrinterLanguage(final UsbDevice device) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UsbPrintTransport.Report report = UsbPrintTransport.probe(usbManager, device);
                DiagStore.last = report;
                DiagStore.text = report.text();
                final int suggested = UsbPrintTransport.suggestMode(device);
                ui(new Runnable() {
                    @Override
                    public void run() {
                        if (suggested == PrintComposer.MODE_THERMAL_80) {
                            langSpinner.setSelection(PrintComposer.MODE_THERMAL_80);
                            log("وُجدت طابعة إيصالات. اختيرت حرارية ٨٠ ملم.");
                            return;
                        }
                        if (suggested == PrintComposer.MODE_SYSTEM) {
                            langSpinner.setSelection(PrintComposer.MODE_SYSTEM);
                            log("إبسون وكانون المنزلية تعمل من نافذة أندرويد.");
                            return;
                        }
                        if (report.error != null && report.error.contains("class 8")) {
                            log(report.error);
                            return;
                        }
                        if (UsbPrintTransport.wantsPcl(report.deviceId, label(device))) {
                            langSpinner.setSelection(PrintComposer.MODE_PCL);
                            log("الطابعة تفهم PCL 5e. اضغط «اختبار الطباعة المباشرة».");
                        }
                    }
                });
            }
        }).start();
    }

    private void savePrefs() {
        if (prefs == null || paperSpinner == null) return;
        prefs.edit()
                .putInt("paper", paperSpinner.getSelectedItemPosition())
                .putInt("lang", langSpinner.getSelectedItemPosition())
                .putBoolean("sharp", sharpCheck.isChecked())
                .putInt("copies", copies)
                .apply();
    }

    private void log(String message) {
        Log.i("Silk", message);
        CharSequence prev = logText.getText();
        String next = prev == null || prev.length() == 0 ? message : prev + "\n" + message;
        if (next.length() > 3500) next = next.substring(next.length() - 3500);
        logText.setText(next);
    }

    private void ui(Runnable runnable) {
        if (!alive) return;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (alive) runnable.run();
            }
        });
    }

    private UsbDevice readDevice(Intent intent) {
        if (intent == null) return null;
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice.class);
        }
        return intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
    }
}
