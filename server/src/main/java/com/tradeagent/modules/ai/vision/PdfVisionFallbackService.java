package com.tradeagent.modules.ai.vision;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.List;

/**
 * 无字/贝塞尔图表 PDF 多模态视觉降级提取服务 (Vision PDF Fallback Service)
 * 
 * 核心技术痛点：
 * 海外客户发来的海运提单、光伏功率衰减曲线、产品工程图纸通常是矢量图（贝塞尔曲线 Path）
 * 或图片扫描件。普通文本提取器 (PDFBox/Tika) 提取出的字符长度为 0。
 * 
 * 解决方案：
 * 1. 【字符密度探针】：快速统计单页可提取文本长度，低于阈值（如 50 字符）立即触发视觉降级链；
 * 2. 【高保真渲染】：调用 PDFBox PDFRenderer 以 300 DPI 离线渲染高质量光栅图像；
 * 3. 【多模态视觉大模型提取】：将图像通过 Spring AI Media 封装送入 Vision 模型，
 *    通过结构化提示词强行提炼坐标曲线极值、印章单号与规格表格数据。
 */
@Slf4j
@Service
public class PdfVisionFallbackService {

    /**
     * 判定为无字/纯图表 PDF 的最小字符阈值
     */
    private static final int TEXT_LENGTH_THRESHOLD = 50;

    /**
     * 300 DPI 打印级渲染精度 (保证图表微小数字与印章清晰可辨)
     */
    private static final float RENDER_DPI = 300.0f;

    /**
     * 解析 PDF 文件 (双轨路由：纯文本优先，图表自动视觉降级)
     *
     * @param pdfFile 本地待解析的 PDF 文件
     * @param visionChatModel 多模态视觉大模型实例 (如 豆包视觉版 / GPT-4o)
     * @return 结构化提取出的文本与表格数据
     */
    public String extractPdfWithVisionFallback(File pdfFile, ChatModel visionChatModel) throws IOException {
        log.info("【PDF 智能解析】开始处理文件: {}", pdfFile.getName());

        try (PDDocument document = Loader.loadPDF(pdfFile)) {
            // 步骤 1: 先尝试极速纯文本提取
            PDFTextStripper textStripper = new PDFTextStripper();
            String rawText = textStripper.getText(document).trim();

            // 检查字符密度：如果包含了充沛的文本，直接返回，零视觉调用成本！
            if (rawText.length() >= TEXT_LENGTH_THRESHOLD) {
                log.info("【PDF 智能解析】文档字符密度充足 ({} 字符)，走快速纯文本解析链路", rawText.length());
                return rawText;
            }

            // 步骤 2: 字符过少，判定为矢量曲线图表或扫描件，触发视觉降级！
            log.warn("【PDF 智能解析】文档字符数仅为 {}，触发 300 DPI 多模态视觉降级链...", rawText.length());

            PDFRenderer pdfRenderer = new PDFRenderer(document);
            StringBuilder visionExtractedResults = new StringBuilder();

            // 针对每一页图表进行高保真渲染与多模态结构化提取 (默认处理前 3 页核心数据)
            int totalPages = Math.min(document.getNumberOfPages(), 3);
            for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
                log.info("【PDF 视觉降级】正在以 300 DPI 离线渲染第 {} 页...", pageIndex + 1);
                BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, RENDER_DPI, ImageType.RGB);

                // 转为 JPEG 字节流
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "jpeg", baos);
                byte[] imageBytes = baos.toByteArray();

                // 步骤 3: 构造多模态 Media 请求送入视觉大模型
                String pageResult = callVisionModelForChartExtraction(imageBytes, pageIndex + 1, visionChatModel);
                visionExtractedResults.append(pageResult).append("\n\n");
            }

            log.info("【PDF 智能解析】视觉降级结构化提取完成，捕获内容长度: {}", visionExtractedResults.length());
            return visionExtractedResults.toString();
        }
    }

    /**
     * 投递给视觉模型提取图表数据与曲线坐标
     */
    private String callVisionModelForChartExtraction(byte[] imageBytes, int pageNum, ChatModel visionChatModel) {
        String visionPrompt = String.format(
                "请专业分析此 PDF 第 %d 页的图像内容（包含矢量图表、曲线、单据或工程图）：\n" +
                "1. 提取所有可见的表格数据，转化为 Markdown 表格形式；\n" +
                "2. 若有坐标曲线图（如效率/功率/温度衰减曲线），请抽取横纵坐标物理量、关键拐点数值与趋势结论；\n" +
                "3. 提取所有印章、单据编号（PI/提单号）、型号规格及重要商业条款。\n" +
                "请严格按客观事实提取，禁止无端臆造。", pageNum
        );

        Media imageMedia = new Media(MimeTypeUtils.IMAGE_JPEG, imageBytes);
        UserMessage userMessage = new UserMessage(visionPrompt, List.of(imageMedia));

        Prompt prompt = new Prompt(userMessage);
        return visionChatModel.call(prompt).getResult().getOutput().getContent();
    }
}
