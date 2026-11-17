package app.silk.usbprint;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.util.SparseArray;

import java.io.IOException;

final class UsbPrintTransport {
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

    private static final SparseArray<String> VENDORS = new SparseArray<>();

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
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_HUB) return false;
        Choice choice = choose(device);
        if (choice == null) return false;
        if (device.getDeviceClass() == UsbConstants.USB_CLASS_PRINTER) return true;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            int cls = device.getInterface(i).getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_PRINTER || cls == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                return true;
            }
        }
        return VENDORS.get(device.getVendorId()) != null;
    }

    static Choice choose(UsbDevice device) {
        Choice fallback = null;
        for (int i = 0; i < device.getInterfaceCount(); i++) {
            UsbInterface iface = device.getInterface(i);
            UsbEndpoint out = bulkOut(iface);
            if (out == null) continue;
            int cls = iface.getInterfaceClass();
            if (cls == UsbConstants.USB_CLASS_PRINTER) return new Choice(iface, out);
            if (fallback == null
                    && cls != UsbConstants.USB_CLASS_HID
                    && cls != UsbConstants.USB_CLASS_HUB
                    && cls != UsbConstants.USB_CLASS_MASS_STORAGE) {
                fallback = new Choice(iface, out);
            }
        }
        return fallback;
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

    static void write(UsbManager manager, UsbDevice device, byte[] data, Progress progress) throws IOException {
        if (manager == null) throw new IOException("هذا الجوال لا يدعم USB.");
        if (device == null) throw new IOException("صل الطابعة بكابل USB أولاً.");
        if (!manager.hasPermission(device)) throw new IOException("امنح إذن USB من زر الاتصال.");
        Choice choice = choose(device);
        if (choice == null) throw new IOException("ما لقيت منفذ إرسال في هذا الجهاز.");
        UsbDeviceConnection conn = manager.openDevice(device);
        if (conn == null) throw new IOException("تعذّر فتح الطابعة. افصل الكابل وأعد توصيله.");
        try {
            if (!conn.claimInterface(choice.iface, true)) {
                throw new IOException("تعذّر حجز الطابعة. أغلق أي تطبيق يستخدمها ثم أعد المحاولة.");
            }
            try {
                Thread.sleep(120);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            int offset = 0;
            int chunk = 16 * 1024;
            while (offset < data.length) {
                int len = Math.min(chunk, data.length - offset);
                int n = conn.bulkTransfer(choice.out, data, offset, len, 20000);
                if (n <= 0) {
                    throw new IOException("توقفت الطابعة أثناء الإرسال (" + offset + " / " + data.length + ").");
                }
                offset += n;
                if (progress != null) progress.onProgress(offset, data.length);
            }
        } finally {
            try {
                conn.releaseInterface(choice.iface);
            } catch (Exception ignored) {
            }
            conn.close();
        }
    }
}
