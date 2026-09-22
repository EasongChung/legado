package com.github.barteksc.pdfviewer;

import com.shockwave.pdfium.util.SizeF;

/**
 * AndroidPdfViewer 坐标换算桥接。
 *
 * <p>本类**必须**位于 {@code com.github.barteksc.pdfviewer} 包下: {@link PDFView#pdfFile}
 * 是包内可见字段, 外部包无法访问, 而页面偏移换算只能经由 {@link PdfFile} 取得。
 *
 * <h3>解决上游 bug：次轴偏移在竖向滚动时写死为 0</h3>
 * 上游 {@code PDFView.drawWithListener} 在竖向滚动时把次轴偏移写死为 0，
 * 而真正绘制页面的 {@code drawPart} 用的却是水平居中偏移 {@link PdfFile#getSecondaryPageOffset}。
 * 导致多页不等宽时画布原点水平错位。
 * 本类提供补偿计算，让点击命中与绘制图层严密对齐。
 */
public final class PdfViewGeometryBridge {

    private PdfViewGeometryBridge() {
    }

    /** 文档是否已加载完成; 未加载时其余方法均不可调用 */
    public static boolean isReady(PDFView pdfView) {
        return pdfView != null && pdfView.pdfFile != null;
    }

    /**
     * 次轴偏移(竖向滚动时为 X, 横向为 Y), **已含 zoom**。
     * 用于修正上游 drawWithListener 写死 0 的偏差。
     */
    public static float getSecondaryPageOffset(PDFView pdfView, int page) {
        if (!isReady(pdfView)) {
            return 0f;
        }
        return pdfView.pdfFile.getSecondaryPageOffset(page, pdfView.getZoom());
    }

    /** 主轴偏移(竖向滚动时为 Y, 横向为 X), **已含 zoom** */
    public static float getPageOffset(PDFView pdfView, int page) {
        if (!isReady(pdfView)) {
            return 0f;
        }
        return pdfView.pdfFile.getPageOffset(page, pdfView.getZoom());
    }

    /** 主轴偏移量落在第几页; 入参为已减去 currentOffset 的文档坐标 */
    public static int getPageAtOffset(PDFView pdfView, float offset) {
        if (!isReady(pdfView)) {
            return 0;
        }
        return pdfView.pdfFile.getPageAtOffset(offset, pdfView.getZoom());
    }

    /**
     * 指定页的适配尺寸(FitPolicy 缩放后的像素, 非 PDF 原始点)。
     */
    public static SizeF getPageSize(PDFView pdfView, int page) {
        if (!isReady(pdfView)) {
            return new SizeF(0f, 0f);
        }
        return pdfView.pdfFile.getPageSize(page);
    }
}
