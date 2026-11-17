package app.silk.usbprint;

import android.hardware.usb.UsbConfiguration;
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
        String inquiry = "";
        String switchNote = "";
        boolean needsPermission;

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
            if (inquiry.length() > 0) o.append("هوية التخزين: ").append(inquiry).append('\n');
            if (switchNote.length() > 0) o.append("تبديل الوضع: ").append(switchNote).append('\n');
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
        return vendorBulk(device);
    }

    static int suggestMode(UsbDevice device) {
        if (preferThermal(device)) return PrintComposer.MODE_THERMAL_80;
        if (preferSystemDialog(device)) return PrintComposer.MODE_SYSTEM;
        return PrintComposer.MODE_PCL;
    }

    static byte[] directTestJob(UsbDevice device) throws IOException {
        if (device != null && device.getVendorId() == 0x0A5F) return PrintComposer.zplTextTest();
        if (preferThermal(device)) return PrintComposer.escPosTextTest();
        return PrintComposer.pcl5eAsciiTest();
    }

    static boolean preferSystemDialog(UsbDevice device) {
        if (device == null || preferThermal(device)) return false;
        int vid = device.getVendorId();
        return vid == 0x04B8 || vid == 0x04A9;
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
        try {
            device = activatePrinter(manager, device, report);
        } catch (IOException e) {
            return report;
        }
        fillIdentity(report, manager, device);
        report.interfaces = describeInterfaces(device);
        if (!report.permission) {
            report.needsPermission = true;
            report.error = "تغيّر تعريف الطابعة بعد إيقاف وضع القرص. اضغط «اتصال» ثم أعد التشخيص.";
            return report;
        }
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
            device = activatePrinter(manager, device, report);
            fillIdentity(report, manager, device);
            report.interfaces = describeInterfaces(device);
            if (!report.permission) {
                report.needsPermission = true;
                throw fail(report, "تغيّر تعريف الطابعة بعد إيقاف وضع القرص. اضغط «اتصال» ثم أعد المحاولة.");
            }
            Choice choice = choose(device);
            if (choice == null) {
                throw fail(report, "لا توجد واجهة طابعة (class 7) فيها Bulk OUT. لن يُرسل شيء إلى واجهة تخزين.");
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
                if (choice.iface.getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
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
                } else {
                    report.mark("USB_VENDOR_CLASS_BULK");
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

    private static UsbDevice activatePrinter(UsbManager manager, UsbDevice device, Report report) throws IOException {
        if (device == null) return null;
        boolean storage = massStorage(device) != null;
        Choice printer = null;
        List<Choice> outs = printerOuts(device);
        if (!outs.isEmpty()) printer = outs.get(0);
        if (printer != null && !storage) {
            report.mark("USB_PRINTER_INTERFACE_READY");
            return device;
        }
        UsbDevice switched = tryPrinterConfiguration(manager, device, report);
        if (switched != null && !printerOuts(switched).isEmpty()) {
            fillIdentity(report, manager, switched);
            report.mark("USB_CONFIGURATION_SWITCHED");
            return switched;
        }
        if (isHpMassStorage(device)) {
            String note = disableHpSmartInstall(manager, device, report);
            report.switchNote = note;
            if ("real-disk".equals(note)) {
                throw stop(report, "هذا الجهاز قرص تخزين حقيقي (" + report.inquiry + ") وليس طابعة. لن يُرسل إليه شيء.");
            }
            UsbDevice again = waitForPrinter(manager, 0x03F0, 5000);
            if (again != null && !printerOuts(again).isEmpty()) {
                fillIdentity(report, manager, again);
                report.interfaces = describeInterfaces(again);
                report.mark("USB_REENUMERATED");
                return again;
            }
            throw stop(report, "الطابعة ظاهرة كقرص تعريفات (USB class 8) لا كطابعة. أُرسل أمر إيقاف HP Smart Install"
                    + (note == null || note.length() == 0 ? "" : " (" + note + ")")
                    + ". افصل كهرباء الطابعة عشر ثوانٍ، ثم صِل الكابل من جديد، ثم اضغط «تشخيص USB». لا تُعد الطباعة قبل أن تظهر واجهة class=7.");
        }
        if (printer != null) return device;
        Choice vendor = vendorBulk(device);
        if (vendor != null) {
            report.mark("USB_VENDOR_INTERFACE_READY");
            return device;
        }
        return device;
    }

    private static UsbDevice tryPrinterConfiguration(UsbManager manager, UsbDevice device, Report report) {
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) return null;
        int target = -1;
        try {
            target = findPrinterConfigValue(device, conn);
            if (target <= 0) return null;
            int r = conn.controlTransfer(0x00, 0x09, target, 0, null, 0, 2000);
            report.mark(r >= 0 ? "USB_SET_CONFIGURATION" : "USB_SET_CONFIGURATION_FAILED");
            if (r < 0) return null;
        } finally {
            try {
                conn.close();
            } catch (RuntimeException ignored) {
            }
        }
        return waitForPrinter(manager, device.getVendorId(), 3000);
    }

    private static int findPrinterConfigValue(UsbDevice device, UsbDeviceConnection conn) {
        int count = device.getConfigurationCount();
        for (int i = 0; i < count; i++) {
            UsbConfiguration cfg = device.getConfiguration(i);
            for (int j = 0; j < cfg.getInterfaceCount(); j++) {
                if (cfg.getInterface(j).getInterfaceClass() == UsbConstants.USB_CLASS_PRINTER) {
                    return cfg.getId();
                }
            }
        }
        byte[] raw = conn.getRawDescriptors();
        if (raw == null || raw.length < 18) return -1;
        int i = 18;
        int found = -1;
        while (i + 1 < raw.length) {
            int len = raw[i] & 0xFF;
            int type = raw[i + 1] & 0xFF;
            if (len < 2) break;
            if (type == 0x02 && i + 8 < raw.length) {
                int total = (raw[i + 2] & 0xFF) | ((raw[i + 3] & 0xFF) << 8);
                int value = raw[i + 5] & 0xFF;
                int end = Math.min(raw.length, i + Math.max(len, total));
                for (int p = i; p + 5 < end; ) {
                    int l = raw[p] & 0xFF;
                    int t = raw[p + 1] & 0xFF;
                    if (l < 2) break;
                    if (t == 0x04 && (raw[p + 5] & 0xFF) == UsbConstants.USB_CLASS_PRINTER) found = value;
                    p += l;
                }
                i = end;
                continue;
            }
            i += len;
        }
        return found;
    }

    private static UsbDevice waitForPrinter(UsbManager manager, int vendorId, int ms) {
        long end = System.currentTimeMillis() + ms;
        do {
            for (UsbDevice candidate : manager.getDeviceList().values()) {
                if (vendorId != 0 && candidate.getVendorId() != vendorId) continue;
                if (!printerOuts(candidate).isEmpty()) return candidate;
            }
            try {
                Thread.sleep(400);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        } while (System.currentTimeMillis() < end);
        return null;
    }

    private static String disableHpSmartInstall(UsbManager manager, UsbDevice device, Report report) {
        UsbInterface iface = massStorage(device);
        if (iface == null) return "no-msc";
        UsbEndpoint out = bulkOut(iface);
        UsbEndpoint in = bulkIn(iface);
        if (out == null || in == null) return "no-ep";
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) return "open-failed";
        boolean claimed = false;
        try {
            if (!conn.claimInterface(iface, true)) return "claim-failed";
            claimed = true;
            report.mark("USB_STORAGE_CLAIMED");
            byte[] put = smartInstallPut();
            int n = conn.bulkTransfer(out, put, put.length, 4000);
            String note = "put=" + n;
            report.mark(n == put.length ? "HP_SMART_INSTALL_SENT" : "HP_SMART_INSTALL_SEND_FAILED");
            byte[] resp = new byte[512];
            int rn = conn.bulkTransfer(in, resp, resp.length, 2500);
            if (rn > 0) note += " resp=" + asciiSafe(resp, rn);
            else {
                clearHalt(conn, in.getAddress());
                clearHalt(conn, out.getAddress());
            }
            report.inquiry = scsiInquiry(conn, out, in);
            if (isRealDisk(report.inquiry)) {
                report.mark("USB_REAL_DISK");
                return "real-disk";
            }
            ejectCd(conn, out, in);
            report.mark("USB_STORAGE_EJECT_TRIED");
            return note;
        } finally {
            release(conn, iface, claimed);
        }
    }

    private static boolean isRealDisk(String inquiry) {
        return inquiry != null && inquiry.startsWith("type=0");
    }

    private static boolean isHpMassStorage(UsbDevice device) {
        return device != null && device.getVendorId() == 0x03F0 && massStorage(device) != null;
    }

    private static UsbInterface massStorage(UsbDevice device) {
        if (device == null) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            if (iface.getInterfaceClass() == UsbConstants.USB_CLASS_MASS_STORAGE) return iface;
        }
        return null;
    }

    private static String scsiInquiry(UsbDeviceConnection conn, UsbEndpoint out, UsbEndpoint in) {
        byte[] cbw = new byte[31];
        cbw[0] = 0x55;
        cbw[1] = 0x53;
        cbw[2] = 0x42;
        cbw[3] = 0x43;
        cbw[4] = 1;
        cbw[8] = 36;
        cbw[12] = (byte) 0x80;
        cbw[14] = 6;
        cbw[15] = 0x12;
        cbw[19] = 36;
        int sent = conn.bulkTransfer(out, cbw, cbw.length, 2000);
        if (sent != cbw.length) {
            clearHalt(conn, out.getAddress());
            clearHalt(conn, in.getAddress());
            return "";
        }
        byte[] data = new byte[36];
        int n = conn.bulkTransfer(in, data, data.length, 2000);
        byte[] csw = new byte[13];
        conn.bulkTransfer(in, csw, csw.length, 1000);
        if (n < 32) {
            clearHalt(conn, in.getAddress());
            clearHalt(conn, out.getAddress());
            return "";
        }
        int type = data[0] & 0x1F;
        return "type=" + type + " " + slice(data, 8, 8) + " " + slice(data, 16, 16);
    }

    private static void ejectCd(UsbDeviceConnection conn, UsbEndpoint out, UsbEndpoint in) {
        byte[] cbw = new byte[31];
        cbw[0] = 0x55;
        cbw[1] = 0x53;
        cbw[2] = 0x42;
        cbw[3] = 0x43;
        cbw[4] = 2;
        cbw[14] = 6;
        cbw[15] = 0x1B;
        cbw[19] = 0x02;
        int sent = conn.bulkTransfer(out, cbw, cbw.length, 2000);
        if (sent != cbw.length) {
            clearHalt(conn, out.getAddress());
            clearHalt(conn, in.getAddress());
            return;
        }
        byte[] csw = new byte[13];
        int n = conn.bulkTransfer(in, csw, csw.length, 2000);
        if (n < 0) {
            clearHalt(conn, in.getAddress());
            clearHalt(conn, out.getAddress());
        }
    }

    private static byte[] smartInstallPut() {
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\r\n"
                + "<featurestatus xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:noNamespaceSchemaLocation=\"featurestatus.xsd\">\r\n"
                + "  <smartInstall>disabled</smartInstall>\r\n"
                + "</featurestatus>\r\n\r\n";
        byte[] bodyBytes = body.getBytes(StandardCharsets.US_ASCII);
        String head = "PUT /dev/featurestatus.xml HTTP/1.1\r\n"
                + "CONTENT-LENGTH: " + bodyBytes.length + "\r\n"
                + "USER-AGENT:hp Proxy/3.0\r\n"
                + "HOST:localhost:3910\r\n\r\n";
        byte[] headBytes = head.getBytes(StandardCharsets.US_ASCII);
        byte[] all = new byte[headBytes.length + bodyBytes.length];
        System.arraycopy(headBytes, 0, all, 0, headBytes.length);
        System.arraycopy(bodyBytes, 0, all, headBytes.length, bodyBytes.length);
        return all;
    }

    private static void clearHalt(UsbDeviceConnection conn, int endpoint) {
        try {
            conn.controlTransfer(0x02, 0x01, 0, endpoint, null, 0, 1000);
        } catch (RuntimeException ignored) {
        }
    }

    private static String slice(byte[] data, int offset, int len) {
        String s = new String(data, offset, len, StandardCharsets.US_ASCII).trim();
        StringBuilder o = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 32 && c < 127) o.append(c);
        }
        return o.toString();
    }

    private static String asciiSafe(byte[] data, int len) {
        String s = slice(data, 0, Math.min(len, data.length));
        if (s.length() > 180) return s.substring(0, 180);
        return s;
    }

    private static boolean preferThermal(UsbDevice device) {
        if (device == null) return false;
        String name = "";
        try {
            String product = device.getProductName();
            if (product != null) name = product.toUpperCase(Locale.US);
        } catch (RuntimeException ignored) {
        }
        if (name.contains("TM-") || name.contains("POS") || name.contains("RECEIPT") || name.contains("THERMAL")) {
            return true;
        }
        int vid = device.getVendorId();
        return vid == 0x0519 || vid == 0x154F || vid == 0x0416 || vid == 0x1A86 || vid == 0x0483 || vid == 0x1203;
    }

    private static Choice vendorBulk(UsbDevice device) {
        if (device == null || VENDORS.get(device.getVendorId()) == null) return null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            int cls = iface.getInterfaceClass();
            if (cls != UsbConstants.USB_CLASS_VENDOR_SPEC && cls != 0) continue;
            UsbEndpoint out = bulkOut(iface);
            if (out != null) return new Choice(iface, out);
        }
        return null;
    }

    private static UsbEndpoint bulkIn(UsbInterface iface) {
        if (iface == null) return null;
        for (int e = 0; e < iface.getEndpointCount(); e++) {
            UsbEndpoint ep = iface.getEndpoint(e);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_IN) {
                return ep;
            }
        }
        return null;
    }

    private static IOException stop(Report report, String message) {
        report.error = message;
        report.mark("USB_NEEDS_POWER_CYCLE");
        Log.e(TAG, message);
        return new IOException(message + "\nآخر مرحلة: " + report.stage);
    }

    static String describeInterfaces(UsbDevice device) {
        if (device == null) return "لا يوجد جهاز.";
        StringBuilder o = new StringBuilder();
        o.append("deviceClass=").append(device.getDeviceClass())
                .append(" interfaces=").append(device.getInterfaceCount())
                .append(" configs=").append(device.getConfigurationCount()).append('\n');
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            o.append("IF ").append(iface.getId())
                    .append(" alt=").append(iface.getAlternateSetting())
                    .append(" class=").append(iface.getInterfaceClass())
                    .append(classLabel(iface.getInterfaceClass()))
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

    private static String classLabel(int cls) {
        if (cls == UsbConstants.USB_CLASS_PRINTER) return " طابعة";
        if (cls == UsbConstants.USB_CLASS_MASS_STORAGE) return " تخزين";
        if (cls == UsbConstants.USB_CLASS_VENDOR_SPEC) return " خاصة";
        return "";
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
