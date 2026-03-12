package vision.capture.windows;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.GDI32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinGDI;
import com.sun.jna.platform.win32.WinNT;

import vision.capture.WindowCapture;

public class WindowsWindowCapture implements WindowCapture {

    @Override
    public List<WindowInfo> listWindows() {
        List<WindowInfo> out = new ArrayList<>();
        User32.INSTANCE.EnumWindows((hWnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hWnd)) return true;

            char[] buffer = new char[512];
            User32.INSTANCE.GetWindowText(hWnd, buffer, 512);
            String title = Native.toString(buffer).trim();
            if (title.isEmpty()) return true;

            WinDef.RECT rect = new WinDef.RECT();
            User32.INSTANCE.GetWindowRect(hWnd, rect);

            long id = Pointer.nativeValue(hWnd.getPointer());
            out.add(new WindowInfo(id, title, rect.left, rect.top, rect.right - rect.left, rect.bottom - rect.top));
            return true;
        }, null);
        return out;
    }

    @Override
    public BufferedImage captureWindow(long windowId) {
        HWND hWnd = new HWND(new Pointer(windowId));

        WinDef.RECT rect = new WinDef.RECT();
        if (!User32.INSTANCE.GetWindowRect(hWnd, rect)) {
            throw new RuntimeException("GetWindowRect failed for windowId=" + windowId);
        }

        int width = rect.right - rect.left;
        int height = rect.bottom - rect.top;

        // Capture window using PrintWindow (works for most apps)
        WinDef.HDC windowDC = User32.INSTANCE.GetDC(hWnd);
        if (windowDC == null) throw new RuntimeException("GetDC failed for windowId=" + windowId);

        WinDef.HDC memDC = GDI32.INSTANCE.CreateCompatibleDC(windowDC);
        WinDef.HBITMAP hBitmap = GDI32.INSTANCE.CreateCompatibleBitmap(windowDC, width, height);
        WinNT.HANDLE old = GDI32.INSTANCE.SelectObject(memDC, hBitmap);

        boolean ok = User32.INSTANCE.PrintWindow(hWnd, memDC, 0);

        // Convert bitmap to BufferedImage
        BufferedImage img = hbitmapToBufferedImage(memDC, hBitmap, width, height);

        // cleanup
        GDI32.INSTANCE.SelectObject(memDC, old);
        GDI32.INSTANCE.DeleteObject(hBitmap);
        GDI32.INSTANCE.DeleteDC(memDC);
        User32.INSTANCE.ReleaseDC(hWnd, windowDC);

        // even if PrintWindow returns false, img may still contain something useful
        return img;
    }

    private static BufferedImage hbitmapToBufferedImage(WinDef.HDC hdc, WinDef.HBITMAP hBitmap, int width, int height) {
        // 32-bit BGRA
        WinGDI.BITMAPINFO bmi = new WinGDI.BITMAPINFO();
        bmi.bmiHeader.biSize = bmi.bmiHeader.size();
        bmi.bmiHeader.biWidth = width;
        bmi.bmiHeader.biHeight = -height; // top-down
        bmi.bmiHeader.biPlanes = 1;
        bmi.bmiHeader.biBitCount = 32;
        bmi.bmiHeader.biCompression = WinGDI.BI_RGB;

        int imageSize = width * height * 4;
        Memory buffer = new Memory(imageSize);

        int lines = GDI32.INSTANCE.GetDIBits(
                hdc,
                hBitmap,
                0,
                height,
                buffer,
                bmi,
                WinGDI.DIB_RGB_COLORS
        );

        if (lines == 0) {
            throw new RuntimeException("GetDIBits failed; could not read bitmap pixels.");
        }

        // BGRA -> ARGB
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            int b = buffer.getByte(i * 4) & 0xFF;
            int g = buffer.getByte(i * 4 + 1) & 0xFF;
            int r = buffer.getByte(i * 4 + 2) & 0xFF;
            int a = buffer.getByte(i * 4 + 3) & 0xFF;
            pixels[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] dst = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        System.arraycopy(pixels, 0, dst, 0, pixels.length);
        return img;
    }
}