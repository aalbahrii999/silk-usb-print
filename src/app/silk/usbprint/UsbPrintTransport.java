package app.silk.usbprint;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbRequest;
import android.os.Build;
import android.util.Log;
import android.util.SparseArray;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class UsbPrintTransport {
    private static final String TAG = "Silk";
    private static final int PRINTER_GET_DEVICE_ID = 0;
    private static final int PRINTER_GET_PORT_STATUS = 1;
    private static final int PRINTER_SOFT_RESET = 2;
    private static final int REQ_IN = 0xA1;
    private static final int REQ_OUT = 0x21;

    interface Progress {
        void onProgress(int sent, int total);
    }

    static final class Choice {
        final UsbInterface iface;
        final UsbEndpoint out;

        Choice(UsbInterface iface, UsbEndpoint out) {
            this.iface = iface;
            this.out = out;
        }
    }

    static final class Report {
        final List<String> stages = new ArrayList<String>();
        String stage = "IDLE";
        int vendorId;
        int productId;
        int interfaceId = -1;
        int interfaceClass = -1;
        int interfaceSubclass = -1;
        int interfaceProtocol = -1;
        int endpointAddress = -1;
        int endpointMaxPacket;
        boolean permission;
        boolean opened;
        boolean claimed;
        boolean bulkOut;
        String deviceId = "";
        String portStatus = "";
        int jobBytes;
        int sentBytes;
        long durationMs;
        String error = "";
        boolean transferCompleted;
        String interfaces = "";

        void mark(String name) {
            stage = name;
            stages.add(name);
            Log.i(TAG, name);
        }

        String text() {
            StringBuilder o = new StringBuilder();
            o.append("آخر مرحلة ناجحة: ").append(stage).append('\n');
            o.append("المراحل:\n");
            for (int i = 0; i < stages.size(); i++) {
                o.append("- ").append(stages.get(i)).append('\n');
            }
            o.append('\n');
            o.append("VID:PID ").append(hex(vendorId)).append(':').append(hex(productId)).append('\n');
            o.append("إذن USB: ").append(permission ? "نعم" : "لا").append('\n');
            o.append("الاتصال مفتوح: ").append(opened ? "نعم" : "لا").append('\n');
            o.append("الواجهة محجوزة: ").append(claimed ? "نعم" : "لا").append('\n');
            o.append("Bulk OUT: ").append(bulkOut ? "نعم" : "لا").append('\n');
            o.append("Interface: ").append(interfaceId)
                    .append(" class=").append(interfaceClass)
                    .append(" subclass=").append(interfaceSubclass)
                    .append(" protocol=").append(interfaceProtocol).append('\n');
            o.append("Endpoint: ").append(endpointAddress >= 0 ? hex(endpointAddress) : "—")
                    .append(" type=BULK OUT")
                    .append(" maxPacket=").append(endpointMaxPacket).append('\n');
            o.append("IEEE 1284: ").append(deviceId.length() == 0 ? "—" : deviceId).append('\n');
            o.append("حالة المنفذ: ").append(portStatus.length() == 0 ? "—" : portStatus).append('\n');
            o.append("بايتات المهمة: ").append(jobBytes).append('\n');
            o.append("بايتات أكدها USB: ").append(sentBytes).append('\n');
            o.append("المدة: ").append(durationMs).append(" ms\n");
            o.append("اكتمل النقل: ").append(transferCompleted ? "نعم" : "لا").append('\n');
            o.append("اكتمال النقل لا يعني خروج الورقة.\n");
            if (error.length() > 0) o.append("الخطأ: ").append(error).append('\n');
            o.append("\nالواجهات:\n").append(interfaces);
            return o.toString();
        }

        private static String hex(int v) {
            return String.format(Locale.US, "%04X", v & 0xFFFF);
        }
    }

    private static final SparseArray<String> VENDORS = new SparseArray<String>();

    static {
        VENDORS.put(0x03F0, "HP");
        VENDORS.put(0x04B8, "Epson");
        VENDORS.put(0x04A9, "Canon");
        VENDORS.put(0x04F9, "Brother");
        VENDORS.put(0x04E8, "Samsung");
        VENDORS.put(0x043D, "Lexmark");
        VENDORS.put(0x0A5F, "Zebra");
        VENDORS.put(0x05CA, "Ricoh");
        VENDORS.put(0x0924, "Xerox");
        VENDORS.put(0x0482, "Kyocera");
        VENDORS.put(0x0519, "Star");
        VENDORS.put(0x154F, "SNBC");
        VENDORS.put(0x1203, "TSC");
        VENDORS.put(0x0416, "حرارية");
        VENDORS.put(0x1A86, "USB");
        VENDORS.put(0x067B, "USB");
        VENDORS.put(0x0483, "حرارية");
        VENDORS.put(0x04B3, "IBM");
    }

    private UsbPrintTransport() {}

    static String vendorName(int vendorId) {
        return VENDORS.get(vendorId);
    }

    static boolean isCandidate(UsbDevice device) {
        if (device == null) return false;
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_HUB) return false;
        if (!printerOuts(device).isEmpty()) return true;
        if (VENDORS.get(device.getVendorId()) != null && firstBulkOut(device) != null) return true;
        return false;
    }

    static Choice choose(UsbDevice device) {
        List<Choice> outs = printerOuts(device);
        if (!outs.isEmpty()) return outs.get(0);
        return null;
    }

    static Report probe(UsbManager manager, UsbDevice device) {
        Report report = new Report();
        fillIdentity(report, manager, device);
        report.interfaces = describeInterfaces(device);
        if (device != null) report.mark("USB_DEVICE_FOUND");
        if (!report.permission) {
            report.error = "لا يوجد إذن USB.";
            return report;
        }
        report.mark("USB_PERMISSION_GRANTED");
        Choice choice = choose(device);
        if (choice == null) {
            report.error = "لا توجد واجهة طابعة USB class 7 مع Bulk OUT. إرسال PCL إلى واجهة أخرى لا يطبع.";
            return report;
        }
        rememberChoice(report, choice);
        report.mark("USB_BULK_OUT_FOUND");
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) {
            report.error = "تعذّر فتح الطابعة.";
            return report;
        }
        report.opened = true;
        report.mark("USB_CONNECTION_OPENED");
        try {
            if (!conn.claimInterface(choice.iface, true)) {
                report.error = "فشل claimInterface على الواجهة " + choice.iface.getId() + ".";
                return report;
            }
            report.claimed = true;
            report.mark("USB_INTERFACE_CLAIMED");
            readPrinterClass(conn, choice.iface, report);
        } finally {
            release(conn, choice.iface, report.claimed);
        }
        return report;
    }

    static void write(UsbManager manager, UsbDevice device, byte[] data, Progress progress, Report report) throws IOException {
        if (report == null) report = new Report();
        long started = System.currentTimeMillis();
        fillIdentity(report, manager, device);
        report.interfaces = describeInterfaces(device);
        report.jobBytes = data == null ? 0 : data.length;
        try {
            if (device != null) report.mark("USB_DEVICE_FOUND");
            if (manager == null) throw fail(report, "هذا الجوال لا يدعم USB.");
            if (device == null) throw fail(report, "صل الطابعة بكابل USB أولاً.");
            if (!report.permission) throw fail(report, "امنح إذن USB من زر الاتصال.");
            report.mark("USB_PERMISSION_GRANTED");
            if (data == null || data.length == 0) throw fail(report, "بيانات الطباعة فارغة. لم يُرسل شيء.");
            Choice choice = choose(device);
            if (choice == null) {
                throw fail(report, "لا توجد واجهة طابعة (class 7) فيها Bulk OUT. لن يُرسل PCL إلى واجهة أخرى.");
            }
            rememberChoice(report, choice);
            report.mark("USB_BULK_OUT_FOUND");
            UsbDeviceConnection conn = manager.openDevice(device);
            if (conn == null) throw fail(report, "تعذّر فتح الطابعة. افصل الكابل وأعد توصيله.");
            report.opened = true;
            report.mark("USB_CONNECTION_OPENED");
            boolean claimed = false;
            try {
                if (!conn.claimInterface(choice.iface, true)) {
                    throw fail(report, "فشل claimInterface على الواجهة " + choice.iface.getId() + ".");
                }
                claimed = true;
                report.claimed = true;
                report.mark("USB_INTERFACE_CLAIMED");
                readPrinterClass(conn, choice.iface, report);
                if (report.portStatus.contains("الورق فارغ")) {
                    throw fail(report, "الطابعة تبلغ أن الورق فارغ. ضع ورقًا ثم أعد المحاولة.");
                }
                softReset(conn, choice.iface, report);
                try {
                    Thread.sleep(250);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                report.mark("USB_TRANSFER_STARTED");
                int mps = Math.max(1, choice.out.getMaxPacketSize());
                int chunk = 8192;
                if (chunk % mps != 0) chunk = mps * 16;
                int offset = 0;
                while (offset < data.length) {
                    int len = Math.min(chunk, data.length - offset);
                    int n = conn.bulkTransfer(choice.out, data, offset, len, 15000);
                    if (n < 0) {
                        throw fail(report, "فشل bulkTransfer عند " + offset + " / " + data.length + " (النتيجة " + n + ").");
                    }
                    if (n == 0) {
                        throw fail(report, "أعاد bulkTransfer صفرًا عند " + offset + " / " + data.length + ". لم يُحتسب نجاحًا.");
                    }
                    offset += n;
                    report.sentBytes = offset;
                    if (progress != null) progress.onProgress(offset, data.length);
                }
                if (offset != data.length) {
                    throw fail(report, "نقل جزئي: " + offset + " من " + data.length + ". لم تُعد المهمة من البداية.");
                }
                if (data.length % mps == 0) {
                    sendZeroLength(conn, choice.out, report);
                }
                try {
                    Thread.sleep(400);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                report.transferCompleted = true;
                report.mark("USB_TRANSFER_RESULT " + offset + "/" + data.length);
            } finally {
                release(conn, choice.iface, claimed);
            }
        } catch (IOException e) {
            if (report.error.length() == 0) report.error = e.getMessage() == null ? "خطأ USB" : e.getMessage();
            throw e;
        } finally {
            report.durationMs = System.currentTimeMillis() - started;
            DiagStore.text = report.text();
            DiagStore.last = report;
        }
    }

    private static void fillIdentity(Report report, UsbManager manager, UsbDevice device) {
        if (device == null) return;
        report.vendorId = device.getVendorId();
        report.productId = device.getProductId();
        report.permission = manager != null && manager.hasPermission(device);
    }

    private static void rememberChoice(Report report, Choice choice) {
        report.bulkOut = true;
        report.interfaceId = choice.iface.getId();
        report.interfaceClass = choice.iface.getInterfaceClass();
        report.interfaceSubclass = choice.iface.getInterfaceSubclass();
        report.interfaceProtocol = choice.iface.getInterfaceProtocol();
        report.endpointAddress = choice.out.getAddress();
        report.endpointMaxPacket = choice.out.getMaxPacketSize();
    }

    private static List<Choice> printerOuts(UsbDevice device) {
        List<Choice> proto2 = new ArrayList<Choice>();
        List<Choice> proto1 = new ArrayList<Choice>();
        List<Choice> other = new ArrayList<Choice>();
        if (device == null) return proto2;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() != UsbConstants.USB_CLASS_PRINTER) continue;
            if (iface.getInterfaceProtocol() == 4) continue;
            UsbEndpoint out = bulkOut(iface);
            if (out == null) continue;
            Choice choice = new Choice(iface, out);
            int protocol = iface.getInterfaceProtocol();
            if (protocol == 2) proto2.add(choice);
            else if (protocol == 1) proto1.add(choice);
            else other.add(choice);
        }
        proto2.addAll(proto1);
        proto2.addAll(other);
        return proto2;
    }

    private static Choice firstBulkOut(UsbDevice device) {
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint out = bulkOut(iface);
            if (out != null) return new Choice(iface, out);
        }
        return null;
    }

    private static UsbEndpoint bulkOut(UsbInterface iface) {
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    static String describeInterfaces(UsbDevice device) {
        if (device == null) return "لا يوجد جهاز.";
        StringBuilder o = new StringBuilder();
        o.append("deviceClass=").append(device.getDeviceClass())
                .append(" interfaces=").append(device.getInterfaceCount()).append('\n');
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            o.append("IF ").append(iface.getId())
                    .append(" alt=").append(iface.getAlternateSetting())
                    .append(" class=").append(iface.getInterfaceClass())
                    .append(" sub=").append(iface.getInterfaceSubclass())
                    .append(" proto=").append(iface.getInterfaceProtocol())
                    .append('\n');
            for (int e = 0; e < iface.getEndpointCount(); e++) {
                UsbEndpoint ep = iface.getEndpoint(e);
                o.append("  EP ").append(String.format(Locale.US, "%02X", ep.getAddress()))
                        .append(" type=").append(ep.getType())
                        .append(" dir=").append(ep.getDirection() == UsbConstants.USB_DIR_OUT ? "OUT" : "IN")
                        .append(" max=").append(ep.getMaxPacketSize())
                        .append('\n');
            }
        }
        return o.toString();
    }

    private static void readPrinterClass(UsbDeviceConnection conn, UsbInterface iface, Report report) {
        int index = iface.getId() | (iface.getAlternateSetting() << 8);
        byte[] idBuf = new byte[1024];
        int n = conn.controlTransfer(REQ_IN, PRINTER_GET_DEVICE_ID, 0, index, idBuf, idBuf.length, 2000);
        if (n >= 2) {
            int declared = ((idBuf[0] & 0xFF) << 8) | (idBuf[1] & 0xFF);
            int text = Math.min(n - 2, Math.max(0, declared - 2));
            if (text > 0) {
                report.deviceId = new String(idBuf, 2, text, StandardCharsets.US_ASCII).trim();
                report.mark("USB_DEVICE_ID");
            }
        }
        if (report.deviceId.length() == 0 && n < 0) {
            report.deviceId = "";
        }
        byte[] status = new byte[1];
        int s = conn.controlTransfer(REQ_IN, PRINTER_GET_PORT_STATUS, 0, index, status, 1, 2000);
        if (s == 1) {
            int bits = status[0] & 0xFF;
            boolean notError = (bits & 0x08) != 0;
            boolean selected = (bits & 0x10) != 0;
            boolean empty = (bits & 0x20) != 0;
            report.portStatus = "raw=0x" + Integer.toHexString(bits)
                    + (notError ? "، بلا خطأ" : "، خطأ")
                    + (selected ? "، محددة" : "، غير محددة")
                    + (empty ? "، الورق فارغ" : "، الورق موجود");
            report.mark("USB_PORT_STATUS");
        } else {
            report.portStatus = "الاستعلام غير مدعوم (" + s + ")";
        }
    }

    private static void softReset(UsbDeviceConnection conn, UsbInterface iface, Report report) {
        int index = iface.getId() | (iface.getAlternateSetting() << 8);
        int r = conn.controlTransfer(REQ_OUT, PRINTER_SOFT_RESET, 0, index, null, 0, 2000);
        report.mark(r >= 0 ? "USB_SOFT_RESET" : "USB_SOFT_RESET_SKIPPED");
    }

    private static void sendZeroLength(UsbDeviceConnection conn, UsbEndpoint ep, Report report) {
        UsbRequest request = new UsbRequest();
        try {
            if (!request.initialize(conn, ep)) {
                report.mark("USB_ZLP_FAILED");
                return;
            }
            ByteBuffer buffer = ByteBuffer.allocate(0);
            boolean queued;
            if (Build.VERSION.SDK_INT >= 26) queued = request.queue(buffer);
            else queued = request.queue(buffer, 0);
            if (!queued) {
                report.mark("USB_ZLP_FAILED");
                return;
            }
            if (conn.requestWait() == null) report.mark("USB_ZLP_FAILED");
            else report.mark("USB_ZLP_SENT");
        } catch (RuntimeException e) {
            report.mark("USB_ZLP_FAILED");
            Log.w(TAG, "zlp", e);
        } finally {
            try {
                request.close();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static void release(UsbDeviceConnection conn, UsbInterface iface, boolean claimed) {
        if (conn == null) return;
        if (claimed && iface != null) {
            try {
                conn.releaseInterface(iface);
            } catch (RuntimeException ignored) {
            }
        }
        try {
            conn.close();
        } catch (RuntimeException ignored) {
        }
    }

    private static IOException fail(Report report, String message) {
        report.error = message;
        report.mark("USB_TRANSFER_RESULT_FAIL");
        Log.e(TAG, report.stage + " " + message);
        return new IOException(message + "\nآخر مرحلة: " + report.stage);
    }

    static boolean wantsPcl(String deviceId, String label) {
        String id = deviceId == null ? "" : deviceId.toUpperCase(Locale.US);
        String name = label == null ? "" : label.toUpperCase(Locale.US);
        if (name.contains("P2035") || name.contains("2035")) return true;
        if (id.contains("P2035") || id.contains("LASERJET P2035")) return true;
        boolean pcl = id.contains("PCL");
        boolean pdf = id.contains("PDF");
        return pcl && !pdf;
    }
}
