package app.silk.usbprint;

final class DiagStore {
    static volatile String text = "لا يوجد تقرير بعد. اضغط «اختبار الطباعة المباشرة» أو «تشخيص USB».";
    static volatile UsbPrintTransport.Report last;

    private DiagStore() {}
}
