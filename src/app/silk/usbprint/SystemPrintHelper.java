package app.silk.usbprint;

import android.content.Context;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;

import java.io.FileOutputStream;
import java.io.OutputStream;

final class SystemPrintHelper {
    private SystemPrintHelper() {}

    static void print(Context context, final String jobName, final byte[] pdf) {
        PrintManager pm = (PrintManager) context.getSystemService(Context.PRINT_SERVICE);
        if (pm == null) throw new IllegalStateException("خدمة الطباعة غير متاحة على هذا الجهاز.");
        pm.print(jobName, new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                 CancellationSignal cancellationSignal, LayoutResultCallback callback,
                                 Bundle extras) {
                if (cancellationSignal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                PrintDocumentInfo info = new PrintDocumentInfo.Builder(jobName)
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                        .build();
                callback.onLayoutFinished(info, true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                CancellationSignal cancellationSignal, WriteResultCallback callback) {
                OutputStream os = null;
                try {
                    os = new FileOutputStream(destination.getFileDescriptor());
                    os.write(pdf);
                    os.flush();
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    callback.onWriteFailed(e.getMessage() == null ? "تعذّر تجهيز ملف الطباعة." : e.getMessage());
                } finally {
                    if (os != null) {
                        try {
                            os.close();
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        }, null);
    }
}
