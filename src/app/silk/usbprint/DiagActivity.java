package app.silk.usbprint;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

public class DiagActivity extends Activity {
    private TextView reportView;
    private boolean alive = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diag);
        reportView = findViewById(R.id.reportText);
        reportView.setText(DiagStore.text);
        findViewById(R.id.diagBack).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        findViewById(R.id.diagRefresh).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        findViewById(R.id.diagCopy).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard == null) return;
                clipboard.setPrimaryClip(ClipData.newPlainText("silk-diag", reportView.getText()));
                reportView.append("\nنُسخ التقرير.");
            }
        });
        refresh();
    }

    @Override
    protected void onDestroy() {
        alive = false;
        super.onDestroy();
    }

    private void refresh() {
        final UsbManager manager = (UsbManager) getSystemService(USB_SERVICE);
        final UsbDevice device = firstPrinter(manager);
        if (device == null) {
            reportView.setText("لا توجد طابعة ظاهرة. ارجع ووصّل الكابل ثم اضغط اتصال.\n\n" + DiagStore.text);
            return;
        }
        if (manager == null || !manager.hasPermission(device)) {
            reportView.setText("الطابعة ظاهرة لكن إذن USB غير ممنوح. ارجع واضغط اتصال.\n\n"
                    + UsbPrintTransport.describeInterfaces(device));
            return;
        }
        reportView.setText("جارٍ قراءة الواجهات وحالة المنفذ…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                final UsbPrintTransport.Report report = UsbPrintTransport.probe(manager, device);
                DiagStore.last = report;
                DiagStore.text = report.text();
                if (!alive) return;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (alive) reportView.setText(report.text());
                    }
                });
            }
        }).start();
    }

    private static UsbDevice firstPrinter(UsbManager manager) {
        if (manager == null) return null;
        UsbDevice found = null;
        for (UsbDevice device : manager.getDeviceList().values()) {
            if (!UsbPrintTransport.isCandidate(device)) continue;
            if (UsbPrintTransport.choose(device) != null) return device;
            if (found == null) found = device;
        }
        return found;
    }
}
