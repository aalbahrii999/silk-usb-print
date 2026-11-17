package app.silk.usbprint;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.media.ExifInterface;
import android.os.ParcelFileDescriptor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class PrintComposer {
    static final int MODE_PDF = 0;
    static final int MODE_PCL = 1;
    static final int MODE_THERMAL_80 = 2;
    static final int MODE_THERMAL_58 = 3;
    static final int MODE_SYSTEM = 4;

    static final class Request {
        Context context;
        byte[] bytes;
        String mime;
        int mode;
        int paper;
        int copies;
        boolean sharp;
    }

    private PrintComposer() {}

    static byte[] compose(Request req) throws IOException {
        if (req.bytes == null || req.bytes.length == 0) {
            throw new IOException("اختر صورة أو مستنداً، أو اطبع الصفحة التجريبية.");
        }
        int copies = req.copies < 1 ? 1 : Math.min(req.copies, 9);
        req.copies = copies;
        if (req.mode == MODE_SYSTEM) return rawPdf(req);
        if (req.mode == MODE_PDF) return wrapPjlPdf(rawPdf(req), copies);
        if (req.mode == MODE_PCL) {
            try {
                return buildPcl(req, 300);
            } catch (OutOfMemoryError oom) {
                return buildPcl(req, 150);
            }
        }
        return buildEscPos(req);
    }

    private static boolean isPdf(Request req) {
        if (req.mime != null && req.mime.toLowerCase().contains("pdf")) return true;
        return req.bytes.length >= 5
                && req.bytes[0] == '%'
                && req.bytes[1] == 'P'
                && req.bytes[2] == 'D'
                && req.bytes[3] == 'F';
    }

    private static byte[] rawPdf(Request req) throws IOException {
        if (isPdf(req)) return req.bytes;
        Bitmap src = decodeImage(req.bytes);
        try {
            int[] pt = pagePoints(req.paper);
            PdfDocument document = new PdfDocument();
            PdfDocument.PageInfo info = new PdfDocument.PageInfo.Builder(pt[0], pt[1], 1).create();
            PdfDocument.Page page = document.startPage(info);
            Canvas canvas = page.getCanvas();
            canvas.drawColor(Color.WHITE);
            Rect dest = fitRect(src.getWidth(), src.getHeight(), pt[0], pt[1], Math.round(pt[0] * 0.05f), req.paper == 2);
            Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            canvas.drawBitmap(src, null, dest, paint);
            document.finishPage(page);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            document.writeTo(bos);
            document.close();
            return bos.toByteArray();
        } finally {
            src.recycle();
        }
    }

    private static int[] pagePoints(int paper) {
        if (paper == 1) return new int[]{612, 792};
        return new int[]{595, 842};
    }

    private static int[] pagePixels(int paper, int dpi) {
        if (dpi <= 150) {
            if (paper == 1) return new int[]{1272, 1650, 2};
            return new int[]{1240, 1754, 26};
        }
        if (paper == 1) return new int[]{2544, 3300, 2};
        return new int[]{2480, 3508, 26};
    }

    private static byte[] wrapPjlPdf(byte[] pdf, int copies) throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream(pdf.length + 256);
        writeUel(o);
        ascii(o, "@PJL JOB NAME=\"silk\"\r\n");
        ascii(o, "@PJL SET COPIES=" + copies + "\r\n");
        ascii(o, "@PJL ENTER LANGUAGE=PDF\r\n");
        o.write(pdf);
        if (pdf.length == 0 || pdf[pdf.length - 1] != '\n') o.write('\n');
        writeUel(o);
        ascii(o, "@PJL EOJ NAME=\"silk\"\r\n");
        writeUel(o);
        return o.toByteArray();
    }

    private static byte[] buildPcl(Request req, int dpi) throws IOException {
        int[] geom = pagePixels(req.paper, dpi);
        int maxPages = 20;
        List<Bitmap> pages = rasterPages(req, geom[0], geom[1], maxPages);
        try {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            writeUel(o);
            ascii(o, "@PJL JOB NAME=\"silk\"\r\n");
            ascii(o, "@PJL SET COPIES=" + req.copies + "\r\n");
            ascii(o, "@PJL ENTER LANGUAGE=PCL\r\n");
            esc(o, "E");
            for (int i = 0; i < pages.size(); i++) {
                writePclPage(o, pages.get(i), geom[2], dpi, req.sharp);
            }
            esc(o, "E");
            writeUel(o);
            ascii(o, "@PJL EOJ NAME=\"silk\"\r\n");
            writeUel(o);
            return o.toByteArray();
        } finally {
            recycleAll(pages);
        }
    }

    private static void writePclPage(ByteArrayOutputStream o, Bitmap page, int paperCode, int dpi, boolean sharp) throws IOException {
        int w = page.getWidth() & ~7;
        int h = page.getHeight();
        byte[] raster = toMono(page, w, sharp);
        int rowBytes = w / 8;
        esc(o, "&u" + dpi + "D");
        esc(o, "&l" + paperCode + "A");
        esc(o, "&l0O");
        esc(o, "&l0E");
        esc(o, "&a0L");
        esc(o, "*t" + dpi + "R");
        esc(o, "*r0F");
        esc(o, "*p0X");
        esc(o, "*p0Y");
        esc(o, "*r1A");
        for (int y = 0; y < h; y++) {
            esc(o, "*b" + rowBytes + "W");
            o.write(raster, y * rowBytes, rowBytes);
        }
        esc(o, "*rB");
        o.write(0x0C);
    }

    private static byte[] buildEscPos(Request req) throws IOException {
        int targetW = req.mode == MODE_THERMAL_58 ? 384 : 576;
        List<Bitmap> pages = thermalPages(req, targetW);
        try {
            ByteArrayOutputStream once = new ByteArrayOutputStream();
            once.write(0x1B);
            once.write('@');
            once.write(0x1B);
            once.write('a');
            once.write(1);
            for (int p = 0; p < pages.size(); p++) {
                writeEscPosRaster(once, pages.get(p), req.sharp);
                once.write(0x1B);
                once.write('d');
                once.write(3);
            }
            once.write(0x1B);
            once.write('d');
            once.write(4);
            byte[] job = once.toByteArray();
            if (req.copies <= 1) return job;
            ByteArrayOutputStream all = new ByteArrayOutputStream(job.length * req.copies);
            for (int i = 0; i < req.copies; i++) all.write(job);
            return all.toByteArray();
        } finally {
            recycleAll(pages);
        }
    }

    private static void writeEscPosRaster(ByteArrayOutputStream o, Bitmap bmp, boolean sharp) throws IOException {
        int w = bmp.getWidth() & ~7;
        int h = bmp.getHeight();
        byte[] bits = toMono(bmp, w, sharp);
        int rowBytes = w / 8;
        int y = 0;
        while (y < h) {
            int band = Math.min(128, h - y);
            o.write(0x1D);
            o.write('v');
            o.write('0');
            o.write(0);
            o.write(rowBytes & 0xFF);
            o.write((rowBytes >> 8) & 0xFF);
            o.write(band & 0xFF);
            o.write((band >> 8) & 0xFF);
            o.write(bits, y * rowBytes, band * rowBytes);
            y += band;
        }
    }

    private static List<Bitmap> thermalPages(Request req, int targetW) throws IOException {
        if (!isPdf(req)) {
            Bitmap src = decodeImage(req.bytes);
            try {
                List<Bitmap> one = new ArrayList<>();
                one.add(scaleToBox(src, targetW, 2000));
                return one;
            } finally {
                src.recycle();
            }
        }
        return rasterPdfFitWidth(req, targetW, 3);
    }

    private static Bitmap scaleToBox(Bitmap src, int maxW, int maxH) {
        float scale = Math.min(maxW / (float) src.getWidth(), maxH / (float) src.getHeight());
        int w = Math.max(8, Math.round(src.getWidth() * scale)) & ~7;
        int h = Math.max(8, Math.round(src.getHeight() * scale));
        if (w < 8) w = 8;
        return Bitmap.createScaledBitmap(src, w, h, true);
    }

    private static List<Bitmap> rasterPdfFitWidth(Request req, int targetW, int maxPages) throws IOException {
        File file = File.createTempFile("silk-th", ".pdf", req.context.getCacheDir());
        List<Bitmap> out = new ArrayList<>();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(req.bytes);
            fos.close();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                PdfRenderer renderer = new PdfRenderer(pfd);
                try {
                    if (renderer.getPageCount() > maxPages) {
                        throw new IOException("الحرارية تطبع حتى " + maxPages + " صفحات. قسّم المستند أو اختر PDF مباشر.");
                    }
                    for (int i = 0; i < renderer.getPageCount(); i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            int h = Math.round(targetW * (page.getHeight() / (float) page.getWidth()));
                            if (h > 2000) h = 2000;
                            if (h < 8) h = 8;
                            Bitmap bmp = Bitmap.createBitmap(targetW, h, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            out.add(bmp);
                        } finally {
                            page.close();
                        }
                    }
                } finally {
                    renderer.close();
                }
            } finally {
                pfd.close();
            }
        } catch (IOException e) {
            recycleAll(out);
            throw e;
        } catch (RuntimeException e) {
            recycleAll(out);
            throw e;
        } finally {
            file.delete();
        }
        if (out.isEmpty()) throw new IOException("ملف PDF فارغ.");
        return out;
    }

    private static List<Bitmap> rasterPages(Request req, int pageW, int pageH, int maxPages) throws IOException {
        if (!isPdf(req)) {
            Bitmap src = decodeImage(req.bytes);
            try {
                Bitmap page = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(page);
                canvas.drawColor(Color.WHITE);
                Rect dest = fitRect(src.getWidth(), src.getHeight(), pageW, pageH, Math.round(pageW * 0.05f), req.paper == 2);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                canvas.drawBitmap(src, null, dest, paint);
                List<Bitmap> one = new ArrayList<>();
                one.add(page);
                return one;
            } finally {
                src.recycle();
            }
        }
        File file = File.createTempFile("silk-pcl", ".pdf", req.context.getCacheDir());
        List<Bitmap> out = new ArrayList<>();
        try {
            FileOutputStream fos = new FileOutputStream(file);
            fos.write(req.bytes);
            fos.close();
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
            try {
                PdfRenderer renderer = new PdfRenderer(pfd);
                try {
                    if (renderer.getPageCount() > maxPages) {
                        throw new IOException("المستند فيه أكثر من " + maxPages + " صفحة. قسّمه ثم أعد المحاولة.");
                    }
                    for (int i = 0; i < renderer.getPageCount(); i++) {
                        PdfRenderer.Page page = renderer.openPage(i);
                        try {
                            Bitmap bmp = Bitmap.createBitmap(pageW, pageH, Bitmap.Config.ARGB_8888);
                            bmp.eraseColor(Color.WHITE);
                            Matrix matrix = new Matrix();
                            float s = Math.min(pageW / (float) page.getWidth(), pageH / (float) page.getHeight());
                            if (req.paper == 2) {
                                float pxPerMm = pageW / 210f;
                                s = Math.min(s, Math.min(100f * pxPerMm / page.getWidth(), 150f * pxPerMm / page.getHeight()));
                            }
                            float dx = (pageW - page.getWidth() * s) / 2f;
                            float dy = (pageH - page.getHeight() * s) / 2f;
                            matrix.postScale(s, s);
                            matrix.postTranslate(dx, dy);
                            page.render(bmp, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                            out.add(bmp);
                        } finally {
                            page.close();
                        }
                    }
                } finally {
                    renderer.close();
                }
            } finally {
                pfd.close();
            }
        } catch (IOException e) {
            recycleAll(out);
            throw e;
        } catch (RuntimeException e) {
            recycleAll(out);
            throw e;
        } finally {
            file.delete();
        }
        if (out.isEmpty()) throw new IOException("ملف PDF فارغ.");
        return out;
    }

    static Bitmap decodeImage(byte[] bytes) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, bounds);
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw new IOException("تعذّر قراءة الصورة.");
        int longEdge = Math.max(bounds.outWidth, bounds.outHeight);
        int sample = 1;
        while (longEdge / sample > 3600) sample *= 2;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opts);
        if (bmp == null) throw new IOException("تعذّر قراءة الصورة.");
        int rotation = 0;
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
            int ori = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            if (ori == ExifInterface.ORIENTATION_ROTATE_90) rotation = 90;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rotation = 180;
            else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rotation = 270;
        } catch (Exception ignored) {
        }
        if (rotation == 0) return bmp;
        Matrix matrix = new Matrix();
        matrix.postRotate(rotation);
        Bitmap rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), matrix, true);
        if (rotated != bmp) bmp.recycle();
        return rotated;
    }

    private static Rect fitRect(int sw, int sh, int pageW, int pageH, int margin, boolean photo) {
        int maxW = pageW - margin * 2;
        int maxH = pageH - margin * 2;
        if (photo) {
            float pxPerMm = pageW / 210f;
            maxW = Math.min(maxW, Math.round(100f * pxPerMm));
            maxH = Math.min(maxH, Math.round(150f * pxPerMm));
        }
        float scale = Math.min(maxW / (float) sw, maxH / (float) sh);
        int dw = Math.max(1, Math.round(sw * scale));
        int dh = Math.max(1, Math.round(sh * scale));
        int left = (pageW - dw) / 2;
        int top = (pageH - dh) / 2;
        return new Rect(left, top, left + dw, top + dh);
    }

    private static byte[] toMono(Bitmap bmp, int width, boolean sharp) {
        int h = bmp.getHeight();
        int srcW = bmp.getWidth();
        int rowBytes = width / 8;
        int[] px = new int[srcW * h];
        bmp.getPixels(px, 0, srcW, 0, 0, srcW, h);
        float[] lum = new float[width * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < width; x++) {
                int c = x < srcW ? px[y * srcW + x] : Color.WHITE;
                int r = (c >> 16) & 255;
                int g = (c >> 8) & 255;
                int b = c & 255;
                lum[y * width + x] = 0.299f * r + 0.587f * g + 0.114f * b;
            }
        }
        byte[] out = new byte[h * rowBytes];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < width; x++) {
                int i = y * width + x;
                float old = lum[i];
                boolean black;
                if (sharp) {
                    black = old < 176f;
                } else {
                    float nv = old < 128f ? 0f : 255f;
                    black = nv == 0f;
                    float err = old - nv;
                    addErr(lum, width, h, x + 1, y, err * (7f / 16f));
                    addErr(lum, width, h, x - 1, y + 1, err * (3f / 16f));
                    addErr(lum, width, h, x, y + 1, err * (5f / 16f));
                    addErr(lum, width, h, x + 1, y + 1, err * (1f / 16f));
                }
                if (black) out[y * rowBytes + (x >> 3)] |= (byte) (0x80 >> (x & 7));
            }
        }
        return out;
    }

    private static void addErr(float[] lum, int w, int h, int x, int y, float err) {
        if (x < 0 || y < 0 || x >= w || y >= h) return;
        int i = y * w + x;
        float v = lum[i] + err;
        if (v < 0f) v = 0f;
        if (v > 255f) v = 255f;
        lum[i] = v;
    }

    private static void recycleAll(List<Bitmap> pages) {
        if (pages == null) return;
        for (int i = 0; i < pages.size(); i++) {
            Bitmap b = pages.get(i);
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    private static void writeUel(ByteArrayOutputStream o) throws IOException {
        o.write(0x1B);
        ascii(o, "%-12345X");
    }

    private static void esc(ByteArrayOutputStream o, String cmd) throws IOException {
        o.write(0x1B);
        ascii(o, cmd);
    }

    private static void ascii(ByteArrayOutputStream o, String s) throws IOException {
        o.write(s.getBytes(StandardCharsets.US_ASCII));
    }
}
